package org.elixir_lang.sdk.choice

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.VersionComparatorUtil
import com.intellij.util.ui.JBUI
import org.elixir_lang.isElixirModule
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment.Target
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.Type
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.util.function.Predicate
import javax.swing.JComponent

/**
 * The chooser as one SDK field per row: on IntelliJ IDEA a Project SDK row, then a row for each Elixir module of
 * [project]. The fields read one [ProjectSdksModel], whose project SDK is the Project SDK row's Elixir pick, so a module
 * row on "Project SDK" shows that row's unsaved value. OK answers the rows that differ from what they opened on.
 */
class SdkRowsDialog(private val project: Project, parent: Component?) :
    DialogWrapper(project, parent, true, IdeModalityType.IDE) {
    private val model = ProjectSdksModel().apply { reset(project) }

    /** Why a pick of each table SDK is refused, or null when it isn't, worked out once per SDK as the table holds it. */
    private val refusals = HashMap<Sdk, String?>()

    /** The project SDK the chooser opened on, as the model's copy; the model then holds the Project SDK row's Elixir pick. */
    private val saved: Sdk? = model.projectSdk

    init {
        // A field built over a model with a project SDK lists "Project SDK", which the Project SDK row must not.
        model.projectSdk = null
    }

    /**
     * A row: [module]'s, or the Project SDK row's when null, its field, and the item it opened on. [facetOnly] when only
     * the Project SDK row's pick reaches [module], and [ownEntry] when [module] holds its own entry, which the row
     * doesn't list.
     */
    internal inner class Row(
        val module: Module?,
        val combo: SdkComboBox,
        val initial: SdkListItem,
        val facetOnly: Boolean = false,
        private val ownEntry: Boolean = false,
        val reopen: () -> Unit,
    ) {
        /** The table SDK the row holds, or null for "Project SDK", "Keep <JDK>" or none. */
        val sdk: Sdk? get() = (combo.selectedItem as? SdkListItem.SdkItem)?.let { model.findSdk(it.sdk) }

        /** Whether OK sets the module's own entry to inherit: only while the Project SDK row gives an SDK to inherit. */
        private val clearsOwnEntry: Boolean get() = ownEntry && projectRow?.sdk != null

        val changed: Boolean get() = clearsOwnEntry || combo.selectedItem != initial

        /** Whether the row, reading the project SDK, still shows what it opened on: "Project SDK", or none without one. */
        val followsProjectSdk: Boolean
            get() = initial is SdkListItem.ProjectSdkItem &&
                (combo.selectedItem is SdkListItem.ProjectSdkItem || combo.selectedItem == combo.model.listModel.findNoneSdkItem())

        /** Whether OK sets the row's module to inherit: its own entry is cleared, or the user changed it to "Project SDK". */
        val inherits: Boolean get() = clearsOwnEntry || (changed && combo.selectedItem is SdkListItem.ProjectSdkItem)

        /**
         * The table SDK OK gives the row's module: the row's own, or, for a module row OK sets to inherit, the Project
         * SDK row's.
         */
        val given: Sdk? get() = if (module != null && inherits) projectRow?.sdk else sdk

        val name: String get() = module?.name ?: "Project SDK"
    }

    internal val projectRow: Row? = if (isSmallIde) null else projectRow()
    internal val moduleRows: List<Row> = ModuleManager.getInstance(project).modules.filter { it.isElixirModule() }.map(::moduleRow)
    private val rows: List<Row> = listOfNotNull(projectRow) + moduleRows

    var answer: SdkRowsAnswer? = null
        private set

    /** Each row's field, labelled with its name. */
    private fun fields(): JComponent = panel {
        for (row in rows) {
            row("${row.name}:") { cell(row.combo).accessibleName(row.name) }
        }
    }

    init {
        title = "Choose Elixir SDK"
        projectRow?.combo?.addItemListener { if (it.stateChange == ItemEvent.SELECTED) projectRowChanged() }
        init()
    }

    /**
     * The Project SDK row, opening on the project SDK when it is Elixir, else the newest working Elixir SDK, which the
     * model takes as its project SDK. A JDK project SDK is listed first as "Keep <JDK>", which keeps it, and with no
     * Elixir SDK to offer the row opens on it; with no project SDK at all, on "<No Project SDK>".
     */
    private fun projectRow(): Row {
        val savedElixir = saved?.takeIf { it.sdkType === Type.instance }
        val offer = savedElixir ?: newestWorkingElixir()
        val combo = SdkComboBox(SdkComboBoxModel.createSdkComboBoxModel(project, model, elixirOnly))
        model.projectSdk = offer
        val keep = saved?.takeIf { savedElixir == null }
            ?.let { combo.addSdkReferenceItem(it.sdkType as SdkType, "Keep ${it.name}", it.versionString, true) }
        val opened = keep ?: SdkListItem.ProjectSdkItem()
        val open = { openProjectRow(combo, offer, keep) }
        open()
        return Row(null, combo, if (savedElixir != null) combo.selectedItem!! else opened, reopen = open)
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

    /** [module]'s row, opening on what [openModuleRow] selects. */
    private fun moduleRow(module: Module): Row {
        val facetOnly = ElixirSdkAssignment.target(module) == Target.PROJECT_SDK
        // A facet-only row's field lists no SDK and offers no Add, only "Project SDK", so the user never changes it.
        val combo = SdkComboBox(
            if (facetOnly) {
                SdkComboBoxModel.createSdkComboBoxModel(project, ProjectSdkOnly(model), elixirOnly, { false })
            } else {
                SdkComboBoxModel.createSdkComboBoxModel(project, model, elixirOnly)
            },
        )
        val entry = ReadAction.computeBlocking<ElixirSdkLookup.OwnSdk?, Throwable> { ElixirSdkLookup.ownSdk(module) }
        val own = entry.takeUnless { facetOnly }
        val open = { openModuleRow(combo, own) }
        open()
        // A module naming no SDK of its own reads the project SDK, which only IntelliJ IDEA has.
        val initial = if (own == null && projectRow != null) SdkListItem.ProjectSdkItem() else combo.selectedItem!!
        return Row(module, combo, initial, facetOnly, ownEntry = facetOnly && entry != null, reopen = open)
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
     * The Project SDK row changed: the model's project SDK follows its Elixir pick, and every module field re-reads it,
     * listing "Project SDK" while it is Elixir. A row that [Row.followsProjectSdk] opens again on it.
     */
    private fun projectRowChanged() {
        val copy = (projectRow!!.combo.selectedItem as? SdkListItem.SdkItem)?.sdk
        model.projectSdk = copy
        for (row in moduleRows) {
            if (copy != null) row.combo.showProjectSdkItem()
            row.combo.reloadModel()
            if (row.followsProjectSdk) row.reopen()
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(RowsScroll(fields(), rows.size)).align(AlignX.FILL) }
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
        answer = SdkRowsAnswer(
            projectRow?.takeIf { it.changed }?.sdk,
            moduleRows.filter { it.changed }.mapNotNull { row ->
                when {
                    row.inherits -> RowPick.Inherit
                    else -> row.sdk?.let(RowPick::Own)
                }?.let { row.module!! to it }
            }.toMap(),
            null,
        )
        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
        model.disposeUIResources()
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
