package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.PopupMenuListenerAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.VersionComparatorUtil
import com.intellij.util.ui.JBUI
import com.intellij.xml.util.XmlStringUtil
import org.elixir_lang.facet.SdksService
import org.elixir_lang.isElixirModule
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment.Target
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.tool_manager.BuiltInToolManagers
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.PinsModel
import org.elixir_lang.tool_manager.PinsPlan
import org.elixir_lang.tool_manager.ToolManagerOptIns
import org.elixir_lang.tool_manager.ToolManagerPins
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.tool_manager.agreedPins
import org.elixir_lang.tool_manager.providesEnvironment
import org.elixir_lang.tool_manager.resolveFirst
import org.elixir_lang.tool_manager.toolManagerFolder
import org.elixir_lang.util.ElixirAppCoroutineService
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.nio.file.Path
import java.util.function.Predicate
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The chooser as one SDK field per row: on IntelliJ IDEA a Project SDK row, then a row for each Elixir module of
 * [project] and each of [modules], [focus]'s first, else in module order. The fields read one [ProjectSdksModel], whose
 * project SDK is the Project SDK row's Elixir pick, so a module row on "Project SDK" shows that row's unsaved value. A
 * tool manager's pins for a row's folder are offered in the row, and made into an SDK at OK. OK answers the rows that
 * differ from what they opened on.
 */
class SdkRowsDialog(
    private val project: Project,
    parent: Component?,
    private val modules: List<Module>,
    private val focus: Module?,
) : DialogWrapper(project, parent, true, IdeModalityType.IDE) {
    private val model = RowsModel().apply { reset(project) }

    /** Why a pick of each table SDK is refused, or null when it isn't, worked out once per SDK as the table holds it. */
    private val refusals = HashMap<Sdk, String?>()

    init {
        model.addListener(object : SdkModel.Listener {
            override fun sdkAdded(sdk: Sdk) = added(sdk)
        })
    }

    /**
     * A row: [module]'s, or the Project SDK row's when null, its field, and the item it opened on, which follows its SDK's
     * fresh copy as the rows follow the table. [facetOnly] when only the Project SDK row's pick reaches [module], and
     * [ownEntry] when [module] holds its own entry, which the row doesn't list. [folder] is the one the tool managers
     * are asked about, again by Check again.
     */
    internal inner class Row(
        val module: Module?,
        val combo: SdkComboBox,
        var initial: SdkListItem,
        val facetOnly: Boolean = false,
        private val ownEntry: Boolean = false,
        val folder: Path? = null,
        val reopen: () -> Unit,
    ) {
        /** The tool manager's pins the row offers, and their entry in its list. */
        private var pins: ToolManagerVersions? = null
        private var entry: SdkListItem? = null

        /** Whether the user has changed the row, which a late tool manager answer then leaves alone. */
        var touched = false

        /** Whether an offer picks in the row: the user hasn't changed it, and it opened on no SDK of its own. */
        private val offerPicks: Boolean
            get() = !touched && initial !is SdkListItem.SdkItem && initial !is SdkListItem.InvalidSdkItem

        /** The SDK OK made from the row's entry. */
        var made: Sdk? = null

        val holdsEntry: Boolean get() = entry != null && combo.selectedItem == entry

        /** The pins of the entry the row holds. */
        val heldPins: ToolManagerVersions? get() = pins?.takeIf { holdsEntry }

        /** The table SDK the row holds, or null for "Project SDK", "Keep <JDK>", an entry OK didn't make, or none. */
        val sdk: Sdk? get() = if (holdsEntry) made else tableSdkOf(combo.selectedItem)

        /** Whether OK sets the module's own entry to inherit: only while the Project SDK row gives an SDK to inherit. */
        private val clearsOwnEntry: Boolean get() = ownEntry && projectRow?.sdk != null

        val changed: Boolean get() = clearsOwnEntry || combo.selectedItem != initial

        /** Whether the row opened on "Project SDK" and shows it, or none without one, so it reads the project SDK. */
        val followsProjectSdk: Boolean
            get() = initial is SdkListItem.ProjectSdkItem &&
                (combo.selectedItem is SdkListItem.ProjectSdkItem || showsNone)

        /** Whether the row shows its "<None>" placeholder. */
        val showsNone: Boolean get() = combo.selectedItem == combo.model.listModel.findNoneSdkItem()

        /**
         * Whether OK sets the row's module to inherit: its own entry is cleared, or the user changed it to "Project SDK"
         * or to the same pins as the changed Project SDK row's, so the module inherits the SDK they make.
         */
        val inherits: Boolean
            get() = clearsOwnEntry || changed && (combo.selectedItem is SdkListItem.ProjectSdkItem ||
                made != null && made == projectRow?.takeIf { it.changed }?.made)

        /**
         * The table SDK OK gives the row's module: the row's own, or, for a module row OK sets to inherit, the Project
         * SDK row's.
         */
        val given: Sdk? get() = if (module != null && inherits) projectRow?.sdk else sdk

        val name: String get() = module?.name ?: "Project SDK"

        /** The row's latest check of its folder, which Check again cancels for a new one. */
        var check: Job? = null

        private val lineText = JBLabel()
        private val checkAgain = ActionLink("Check again") { ask(this@Row) }

        /** Why the row's folder offers nothing, under the rows, with Check again after a tool manager's error. */
        val line = JPanel(HorizontalLayout(JBUI.scale(8))).apply {
            isVisible = false
            add(lineText)
            add(checkAgain)
        }

        /**
         * Lists [pins]' entry, once, picked unless the user changed the row or it opened on an SDK of its own. A
         * facet-only row lists none: its pins count only toward the Project SDK row's.
         */
        fun offer(pins: ToolManagerVersions) {
            if (entry != null || facetOnly) return
            this.pins = pins
            bySelf {
                entry = combo.addSdkReferenceItem(Type.instance, entryName(pins), null, true)
                if (offerPicks) combo.selectedItem = entry
            }
        }

        /**
         * Takes out the entry, which OK made no working SDK of, picking the model's copy of [instead], else what the row
         * opens on now, and says [why] under the rows.
         */
        fun dropEntry(instead: Sdk?, why: String?) {
            val held = entry as? SdkListItem.SdkReferenceItem ?: return
            entry = null
            pins = null
            bySelf {
                combo.model.modelBuilder.removeSdkReferenceItem(held)
                val copy = instead?.let { model.projectSdks[it] }
                if (copy != null) combo.setSelectedSdk(copy) else reopen()
            }
            say(why)
        }

        /** Picks the table's [sdk], unless the user changed the row, it opened on an SDK of its own, or it is facet-only. */
        fun preselect(sdk: Sdk) {
            if (facetOnly) return
            val copy = model.projectSdks[sdk] ?: return
            if (offerPicks) bySelf { combo.setSelectedSdk(copy) }
        }

        /** Says [text] under the rows, or nothing when null, with Check again when [retry]. */
        fun say(text: String?, retry: Boolean = false) {
            lineText.text = text
            checkAgain.isVisible = retry
            line.isVisible = text != null
        }
    }

    internal val projectRow: Row? = if (isSmallIde) null else projectRow()
    internal val moduleRows: List<Row> = ModuleManager.getInstance(project).modules
        .filter { it.isElixirModule() || it in modules || it == focus }
        .sortedBy { it != focus }
        .map(::moduleRow)
    /** The module rows whose folder the tool managers are asked about: those with a folder. */
    private val askedRows: List<Row> = moduleRows.filter { it.folder != null }
    private val rows: List<Row> = listOfNotNull(projectRow) + moduleRows
    internal val manageSdksLink = manageSdksLink()

    private val inStepBox = JBCheckBox().apply { addActionListener { environmentBox.isEnabled = isSelected } }
    private val environmentBox = JBCheckBox().apply { border = JBUI.Borders.emptyLeft(20) }

    /** The tool manager's boxes, shown while a row holds its entry. */
    private val boxes = JPanel(VerticalLayout(0)).apply {
        isVisible = false
        add(inStepBox)
        add(environmentBox)
    }

    /** The tool manager the boxes name. */
    private var boxesManager: ElixirToolManager? = null

    /** The rows, scrolling past [SHOWN_ROWS], under them each row's line, the tool manager's boxes, and Manage SDKs... */
    internal val rowsPanel: JComponent = panel {
        row { cell(RowsScroll(fields(), rows.size)).align(AlignX.FILL) }
        row { cell(JPanel(VerticalLayout(0)).apply { for (row in rows) add(row.line) }) }
        row { cell(boxes) }
        row { cell(manageSdksLink) }
    }

    var answer: SdkRowsAnswer? = null
        private set

    /** Each row's field, labelled with its name. */
    private fun fields(): JComponent = panel {
        for (row in rows) {
            row("${row.name}:") { cell(row.combo).accessibleName(row.name) }
        }
    }

    private val scope = service<ElixirAppCoroutineService>().supervisedChildScope(javaClass.simpleName)
    private val toolManagers = BuiltInToolManagers.getInstance().all

    /** True while the chooser itself selects in a row, so the selection isn't taken for the user's. */
    private var selecting = false

    /** The pins each module row's folder offers, once the tool managers answered it. */
    private val answered = mutableMapOf<Row, ToolManagerVersions?>()

    /** The Project SDK row's entry as the module rows show it: an Elixir SDK of its name, never in the table. */
    private val heldEntry: Sdk by lazy {
        ProjectJdkTable.getInstance().createSdk(entryName(projectRow!!.heldPins!!), Type.instance)
            .also { sdk -> (sdk as? Disposable)?.let { Disposer.register(disposable, it) } }
    }

    init {
        title = "Choose Elixir SDK"
        for (row in rows) {
            row.combo.addItemListener {
                if (it.stateChange != ItemEvent.SELECTED) return@addItemListener
                if (row === projectRow) projectRowChanged()
                if (!selecting) row.touched = true
                // "<None>" stands in only while there is no project SDK; a module row picking it reads the project SDK.
                if (row.showsNone && row.module != null && model.projectSdk != null) bySelf(row.reopen)
                boxesFollow()
            }
        }
        // As Manage SDKs... changes them.
        followTableAt(disposable, rowsPanel, ::followTable)
        init()
        // Each folder on its own.
        askedRows.forEach(::ask)
    }

    private fun bySelf(select: () -> Unit) {
        val before = selecting
        selecting = true
        try {
            select()
        } finally {
            selecting = before
        }
    }

    /**
     * The Project SDK row, opening on the project SDK when it is Elixir, else the newest working Elixir SDK, which the
     * model takes as its project SDK. A JDK project SDK is listed first as "Keep <JDK>", which keeps it, and with no
     * Elixir SDK to offer the row opens on it; with no project SDK at all, on "<No Project SDK>".
     */
    private fun projectRow(): Row {
        val saved = model.saved
        // The table's SDK, whose copy in the model a table change replaces.
        val savedElixir = saved?.takeIf { it.sdkType === Type.instance }?.let(model::findSdk)
        val offer = { savedElixir?.let { model.projectSdks[it] } ?: newestWorkingElixir() }
        val combo = field()
        val first = offer()
        model.projectSdk = first
        val keep = saved?.takeIf { savedElixir == null }
            ?.let { combo.addSdkReferenceItem(it.sdkType as SdkType, "Keep ${it.name}", it.versionString, true) }
        val opened = keep ?: SdkListItem.ProjectSdkItem()
        // With no Elixir SDK to offer, "<No SDK>" is listed, so the row can leave a tool manager's entry it is offered.
        if (first == null) combo.showNoneSdkItem()
        openProjectRow(combo, first, keep)
        // The field's model takes "<No Project SDK>" without an event, so the module rows are told.
        return Row(null, combo, if (savedElixir != null) combo.selectedItem!! else opened) {
            openProjectRow(combo, offer(), keep)
            projectRowChanged()
        }
    }

    /** Selects in the Project SDK row's [combo] what it opens on: [offer], else [keep], else "<No Project SDK>". */
    private fun openProjectRow(combo: SdkComboBox, offer: Sdk?, keep: SdkListItem?) {
        when {
            offer != null -> combo.setSelectedSdk(offer)
            keep != null -> combo.selectedItem = keep
            // Set on the field's model, so its list doesn't carry the item: a field never drops a listed "Project SDK",
            // which would then name this row's own pick.
            else -> combo.model.selectedItem = SdkListItem.ProjectSdkItem()
        }
    }

    /**
     * A row's field over the rows' model, offering Elixir's Add, or when [facetOnly] over no SDK and offering nothing,
     * and listing no SDK found on disk.
     */
    private fun field(facetOnly: Boolean = false): SdkComboBox {
        val sdks = if (facetOnly) ProjectSdkOnly(model) else model
        val creates = if (facetOnly) noType else elixirOnly
        return SdkComboBox(SdkComboBoxModel.createSdkComboBoxModel(project, sdks, elixirOnly, creates)).apply { listNoneFound() }
    }

    /** [module]'s row, opening on what [openModuleRow] selects. */
    private fun moduleRow(module: Module): Row {
        val facetOnly = ElixirSdkAssignment.target(module) == Target.PROJECT_SDK
        // A facet-only row's field lists no SDK and offers no Add, only "Project SDK", so the user never changes it.
        val combo = field(facetOnly)
        val (entry, folder) = ReadAction.computeBlocking<Pair<ElixirSdkLookup.OwnSdk?, Path?>, Throwable> {
            ElixirSdkLookup.ownSdk(module) to module.toolManagerFolder
        }
        val own = entry.takeUnless { facetOnly }
        val open = { openModuleRow(combo, own) }
        open()
        // A module naming no SDK of its own reads the project SDK, which only IntelliJ IDEA has.
        val initial = if (own == null && projectRow != null) SdkListItem.ProjectSdkItem() else combo.selectedItem!!
        return Row(module, combo, initial, facetOnly, ownEntry = facetOnly && entry != null, folder = folder, reopen = open)
    }

    /**
     * Selects in a module row's [combo] what it opens on: the model's copy of its [own] Elixir SDK, shown invalid when
     * the table no longer has it, else "Project SDK" while the Project SDK row is Elixir, else none.
     */
    private fun openModuleRow(combo: SdkComboBox, own: ElixirSdkLookup.OwnSdk?) {
        val copy = own?.sdk?.let { model.projectSdks[it] }
        when {
            copy != null -> combo.setSelectedSdk(copy)
            own != null -> combo.selectedItem = combo.showInvalidSdkItem(own.name)
            model.projectSdk != null -> combo.selectedItem = combo.showProjectSdkItem()
            // An empty field has no selection, and the platform's renderer throws on a null value.
            else -> combo.selectedItem = combo.showNoneSdkItem()
        }
    }

    /**
     * The Project SDK row changed: the model's project SDK follows its Elixir pick, or the SDK standing for its entry,
     * and every module field re-reads it, listing "Project SDK" while there is one. A row that [Row.followsProjectSdk]
     * opens again on it.
     */
    private fun projectRowChanged() {
        val projectRow = projectRow!!
        val shown = if (projectRow.holdsEntry) heldEntry else (projectRow.combo.selectedItem as? SdkListItem.SdkItem)?.sdk
        model.projectSdk = shown
        bySelf {
            for (row in moduleRows) {
                if (shown != null) row.combo.showProjectSdkItem()
                // Reopening selects, and a field reloads as it selects.
                if (row.followsProjectSdk) row.reopen() else row.combo.reloadModel()
            }
        }
    }

    /** Asks the tool managers about [row]'s folder off the EDT, and acts on what [ToolManagerPins.plan] makes of the pins. */
    private fun ask(row: Row) {
        val folder = row.folder!!
        row.check?.cancel()
        row.check = scope.launch {
            val (answer, pins, plan) = withContext(Dispatchers.IO) {
                val answer = resolveFirst(toolManagers, listOf(folder))[folder]
                val pins = (answer as? ToolManagerResult.Success)?.versions
                Triple(answer, pins, pins?.elixir?.takeIf { it.installed }?.let { ToolManagerPins.plan(it.installPath, listOf(pins)) })
            }
            // The chooser's own modality, so an answer can't land while OK makes the SDKs.
            val modality = modalityOf(rowsPanel)
            // Selecting in a row validates it, which reads the SDK model under a read lock that `Dispatchers.UI` lacks.
            @Suppress("ObsoleteDispatchersEdt")
            withContext(Dispatchers.EDT + modality.asContextElement()) {
                row.say(null)
                when (plan) {
                    is PinsPlan.Paired -> row.offer(pins!!)
                    is PinsPlan.OnInstall -> row.preselect(plan.sdk)
                    PinsPlan.NeedsErlang -> row.say(needsErlang(row, pins!!))
                    is PinsPlan.Several -> {}
                    null -> (answer as? ToolManagerResult.Error)?.let { error ->
                        row.say(XmlStringUtil.wrapInHtml("${StringUtil.escapeXmlEntities(row.name)}: ${error.description}"), retry = true)
                    }
                }
                answered(row, pins.takeIf { plan is PinsPlan.Paired })
            }
        }
    }

    /**
     * [row] answered, offering [pins] or none. Once every row asked has answered, and each offers the same pins, the
     * Project SDK row offers them.
     */
    private fun answered(row: Row, pins: ToolManagerVersions?) {
        answered[row] = pins
        if (answered.size == askedRows.size) agreedPins(answered.values)?.let { projectRow?.offer(it) }
    }

    private fun needsErlang(row: Row, pins: ToolManagerVersions): String =
        "${row.name}: ${managerName(pins)} pins " +
            (pins.erlang?.let { "Erlang ${it.version}, which isn't installed" } ?: "Elixir ${pins.elixir?.version} but no Erlang")

    /** The boxes show while a row holds an entry, ticked as the project's settings have its tool manager. */
    private fun boxesFollow() {
        val manager = rows.firstNotNullOfOrNull { it.heldPins }?.let(::managerOf)
        boxes.isVisible = manager != null
        if (manager == null || manager == boxesManager) return
        boxesManager = manager
        val settings = ToolManagerSettings.getInstance(project)
        inStepBox.text = inStepText(manager.displayName)
        inStepBox.isSelected = settings.isEnabled(manager)
        environmentBox.text = environmentText(manager.displayName)
        environmentBox.isSelected = settings.isEnvironmentEnabled(manager)
        environmentBox.isVisible = providesEnvironment(manager)
        environmentBox.isEnabled = inStepBox.isSelected
    }

    /** The boxes as shown, when they are and differ from the project's settings: OK writes them, unticked turning the tool manager off. */
    private fun optIns(): ToolManagerOptIns? {
        val manager = boxesManager?.takeIf { boxes.isVisible } ?: return null
        val settings = ToolManagerSettings.getInstance(project)
        return ToolManagerOptIns(manager.name, environment = environmentBox.isSelected, enabled = inStepBox.isSelected)
            .takeIf { it.enabled != settings.isEnabled(manager) || it.environment != settings.isEnvironmentEnabled(manager) }
    }

    /** "mise: Elixir 1.20.4, Erlang 29.0.6": the tool manager and the versions [pins] names. */
    private fun entryName(pins: ToolManagerVersions): String =
        "${managerName(pins)}: Elixir ${pins.elixir?.version}" + (pins.erlang?.let { ", Erlang ${it.version}" } ?: "")

    private fun managerOf(pins: ToolManagerVersions): ElixirToolManager? = toolManagers.firstOrNull { it.name == pins.toolManagerName }

    private fun managerName(pins: ToolManagerVersions): String = managerOf(pins)?.displayName ?: pins.toolManagerName

    /** A row's Add made [copy], which that row has picked: it goes into the table at once, so Cancel keeps it, and every row lists it. */
    private fun added(copy: Sdk) {
        val sdk = checkNotNull(model.findSdk(copy))
        // The model also takes in the table's own SDKs, as the rows follow the table.
        if (copy.sdkType !== Type.instance || service<SdksService>().inTable(sdk)) return
        commitAdded(sdk)
        bySelf { for (row in rows) row.combo.reloadModel() }
    }

    /**
     * Gives each SDK the table [touched] a fresh copy in the model, or none once the table no longer has it. A row on a
     * copy that went picks the fresh one, or shows its SDK invalid, and a row that opened on one opened on the fresh one.
     */
    private fun followTable(touched: Collection<Sdk>) {
        refusals.clear()
        val picked = rows.associateWith { tableSdkOf(it.combo.selectedItem) }
        val opened = rows.associateWith { tableSdkOf(it.initial) }
        for (sdk in touched) {
            model.projectSdks[sdk]?.let { copy ->
                model.removeSdk(copy)
                (copy as? Disposable)?.let(Disposer::dispose)
            }
        }
        service<SdksService>().heldByAFreshModel(touched, project).forEach(model::addSdk)
        bySelf {
            for (row in rows) {
                row.combo.reloadModel()
                opened[row]?.takeIf { it in touched }?.let { model.projectSdks[it] }?.let(row.combo.model.listModel::findSdkItem)
                    ?.let { row.initial = it }
                val sdk = picked[row]?.takeIf { it in touched } ?: continue
                val copy = model.projectSdks[sdk]
                if (copy != null) row.combo.setSelectedSdk(copy) else row.combo.selectedItem = row.combo.showInvalidSdkItem(sdk.name)
            }
        }
    }

    /** The table's SDK [item] is the model's copy of, or null when it is no SDK. */
    private fun tableSdkOf(item: SdkListItem?): Sdk? = (item as? SdkListItem.SdkItem)?.let { model.findSdk(it.sdk) }

    override fun createCenterPanel(): JComponent = rowsPanel

    /** The row whose pick reaches [focus], else the first module row with a pick of its own, else the Project SDK row. */
    override fun getPreferredFocusedComponent(): JComponent? {
        val row = moduleRows.find { it.module == focus } ?: moduleRows.firstOrNull { !it.facetOnly }
        return (row?.takeUnless { it.facetOnly } ?: projectRow)?.combo
    }

    // A Kotlin UI DSL panel turns continuous validation off, and postponed validation would start only at the first OK.
    override fun continuousValidation(): Boolean = true

    override fun postponeValidation(): Boolean = false

    /**
     * Each row whose module OK would give an SDK a pick refuses, or set to inherit no Elixir SDK, saying why: refused at
     * OK once the user changed it, outlined but allowed while unchanged.
     */
    @VisibleForTesting
    public override fun doValidateAll(): List<ValidationInfo> = rows.mapNotNull { row ->
        val given = row.given
        val reason = when {
            given != null -> refusal(given)
            // "Project SDK" stays listed once shown, as the platform can't take it away, and draws red with none.
            row.module != null && row.inherits && model.projectSdk == null -> "No Elixir project SDK to inherit."
            else -> null
        } ?: return@mapNotNull null
        ValidationInfo(reason, row.combo).let { if (row.changed) it else it.withOKEnabled() }
    }

    /** Why a pick of [sdk] is refused, or null when it isn't. */
    private fun refusal(sdk: Sdk): String? = if (sdk in refusals) refusals[sdk] else pairingRefusal(sdk).also { refusals[sdk] = it }

    /** The newest Elixir SDK in the model that a pick would not refuse, as the model's copy. */
    private fun newestWorkingElixir(): Sdk? = model.projectSdks.entries
        .filter { (sdk, _) -> sdk.sdkType === Type.instance }
        .sortedWith { a, b -> VersionComparatorUtil.compare(b.key.versionString, a.key.versionString) }
        .firstOrNull { refusal(it.key) == null }
        ?.value

    /** Clicks OK, as the user does. */
    @VisibleForTesting
    internal fun clickOk() = okAction.actionPerformed(null)

    /** Shows the chooser and answers what OK picked, or null on Cancel. */
    fun showAndAnswer(): SdkRowsAnswer? = if (showAndGet()) answer else null

    override fun doOKAction() {
        if (!makeEntries()) return
        answer = SdkRowsAnswer(
            projectRow?.takeIf { it.changed }?.sdk,
            moduleRows.filter { it.changed }.mapNotNull { row ->
                (if (row.inherits) RowPick.Inherit else row.sdk?.let(RowPick::Own))?.let { row.module!! to it }
            }.toMap(),
            optIns(),
        )
        super.doOKAction()
    }

    /**
     * Makes an Elixir SDK from each distinct pins the rows' entries hold, as the tool manager does, under modal progress
     * over the chooser, and gives it to each row holding them. True when every one made an SDK a pick takes; otherwise
     * each row whose pins didn't says why, and OK writes nothing. A row whose pins made an SDK it can't take drops its
     * entry for that SDK, and one whose install has several drops it for what it opens on; one whose pins made nothing
     * keeps it and opens on what it opened on.
     */
    private fun makeEntries(): Boolean {
        val held = rows.filter { it.holdsEntry }
        var holding = held
        while (holding.isNotEmpty()) {
            val pins = holding.first().heldPins!!
            val (same, rest) = holding.partition { it.heldPins!!.samePins(pins) }
            val made = modelUnderProgress(project, rowsPanel, managerName(pins), pins)
            // Making it can pair an SDK the table had, so the model's copy and its refusal are read afresh.
            val found = (made as? PinsModel.Found)?.sdk
            found?.let { followTable(setOf(it)) }
            val sdk = found?.takeIf { refusal(it) == null }
            for (row in same) {
                row.made = sdk
                if (sdk != null) continue
                when (made) {
                    is PinsModel.Found -> row.dropEntry(found, null)
                    is PinsModel.Several -> row.dropEntry(null, severalSdks(row, pins))
                    // The entry stays, so picking it again makes its pins again.
                    PinsModel.None -> {
                        bySelf(row.reopen)
                        row.say(madeNoSdk(row, pins))
                    }
                }
            }
            holding = rest
        }
        return held.all { it.made != null }
    }

    private fun severalSdks(row: Row, pins: ToolManagerVersions): String =
        "${row.name}: ${managerName(pins)}'s Elixir ${pins.elixir?.version} install has several SDKs; pick one"

    private fun madeNoSdk(row: Row, pins: ToolManagerVersions): String =
        "${row.name}: no SDK could be made of ${pinsText(managerName(pins), pins)}"

    override fun dispose() {
        scope.cancel()
        super.dispose()
        model.disposeUIResources()
    }

    /** The rows' model. Its project SDK is what a module row shows as "Project SDK", which the table needn't hold. */
    private class RowsModel : ProjectSdksModel() {
        private var shown: Sdk? = null

        /** The project SDK [reset] read, as the model's copy. */
        val saved: Sdk? get() = super.getProjectSdk()

        override fun getProjectSdk(): Sdk? = shown

        override fun setProjectSdk(projectSdk: Sdk?) {
            shown = projectSdk
        }
    }

    /** A model with no SDKs of its own, whose project SDK is [rows]': a field over it lists only "Project SDK". */
    private class ProjectSdkOnly(private val rows: ProjectSdksModel) : ProjectSdksModel() {
        override fun getSdks(): Array<Sdk> = emptyArray()

        override fun getProjectSdk(): Sdk? = rows.projectSdk
    }
}

/** How many rows the chooser shows before they scroll. */
private const val SHOWN_ROWS = 10

/** [fields], [count] rows of them, shown whole up to [SHOWN_ROWS] and scrolling past them. */
private class RowsScroll(private val fields: JComponent, private val count: Int) :
    JBScrollPane(fields, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
    init {
        border = JBUI.Borders.empty()
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
        if (count <= SHOWN_ROWS) return@apply
        height = fields.preferredSize.height * SHOWN_ROWS / count
        width += verticalScrollBar.preferredSize.width
    }
}

internal val elixirOnly = Predicate<SdkTypeId> { it === Type.instance }

private val noType = Predicate<SdkTypeId> { false }

/**
 * Swaps the field's own popup listener, which reloads Add and lists the SDKs found on disk as the popup opens, for one
 * that only reloads Add.
 */
private fun SdkComboBox.listNoneFound() {
    // Gotcha: the platform's only filter for found SDKs is Add's, so this relies on SdkComboBox's private listener.
    val own = popupMenuListeners.filter { it.javaClass.enclosingClass == SdkComboBox::class.java }
    if (own.size != 1) logger<SdkRowsDialog>().warn("SdkComboBox has ${own.size} popup listeners of its own, not 1")
    own.forEach(::removePopupMenuListener)
    addPopupMenuListener(object : PopupMenuListenerAdapter() {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = model.modelBuilder.reloadActions()
    })
}
