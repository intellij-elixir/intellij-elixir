package org.elixir_lang.facet

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.SdkTableChanges
import org.elixir_lang.sdk.SdkTableChanges.Kind
import org.elixir_lang.sdk.erlang.PairingRepairs
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
class SdksService : Disposable {
    // Read by root-set-changed listeners, which run inside whatever write action committed an SDK.
    @Volatile
    private var model: ProjectSdksModel? = null

    /** The service's own [model] is being applied to the table. */
    @Volatile
    private var applying = false

    /** The SDK [register] is adding to the table for this service's own model. */
    @Volatile
    private var registering: Sdk? = null

    /**
     * SDKs another writer added, removed, renamed or committed since the last [pass]. The table's listeners run inside
     * its write action, maybe off the EDT, so they only record.
     */
    private val touched: MutableSet<Sdk> = ConcurrentHashMap.newKeySet()

    /** A page closed without applying, so the next [pass] refreshes every copy no open page has edited. */
    private val refreshAll = AtomicBoolean()

    private val passScheduled = AtomicBoolean()

    private val pages = mutableSetOf<Page>()

    /** An open SDKs page, which [pass] asks what it has edited and tells what changed. */
    internal interface Page {
        /** The copies whose editors hold changes not yet applied to the table. */
        fun editedCopies(): Collection<Sdk>

        /** [copy] left the model, so its editor closes and the page forgets what it recorded for it. */
        fun close(copy: Sdk)

        /** The model is in line with the table again. */
        fun modelChanged()
    }

    init {
        // A page's Apply writes every copy, whatever its type.
        SdkTableChanges.follow(this, { true }, ::tableChanged)
    }

    /**
     * Another writer, such as "Configure from mise" through SdkRegistrar, changes the table under open pages. A model
     * being applied adds and removes its own SDKs in the table, holding an added one as a key and having already dropped
     * a removed one, and commits each SDK it holds.
     */
    private fun tableChanged(jdk: Sdk, kind: Kind) {
        if (kind == Kind.ADDED && jdk === registering) return
        val model = this.model ?: return
        val copy = model.projectSdks[jdk]
        // No copy to bring in line: the pass adds an SDK the table gained as the table then holds it.
        if (kind == Kind.ROOTS_COMMITTED && copy == null) return
        if (applying) {
            val ownChange = when (kind) {
                // `ProjectSdksModel.doApply` adds the key and copies the editable clone's state in on the next line,
                // so an SDK it has just added still carries the name it was cloned with.
                Kind.ADDED -> copy != null
                Kind.REMOVED -> copy == null
                Kind.RENAMED -> copy != null && copy.name == jdk.name
                Kind.ROOTS_COMMITTED -> true
            }
            if (ownChange) return
        }
        touched += jdk
        schedule()
    }

    private fun schedule() {
        if (!passScheduled.compareAndSet(false, true)) return
        val application = ApplicationManager.getApplication()
        application.invokeLater({
            passScheduled.set(false)
            pass()
        }, ModalityState.nonModal(), application.disposed)
    }

    /**
     * Brings the model in line with the table: an SDK the table lost leaves it, even when edited; one it gained enters
     * it; and a touched copy no open page has edited is refreshed from the table.
     *
     * [PairingRepairs] repairs first what the commits left, so an edited path list is re-based onto the repaired copy
     * before an Apply writes it. A repair after the commit reconfigures the SDK, which drops a path the user added.
     */
    @RequiresEdt
    private fun pass() {
        PairingRepairs.getInstance().flush()
        val all = refreshAll.getAndSet(false)
        val sdks = touched.toList().also(touched::removeAll)
        val model = model ?: return
        if (!all && sdks.isEmpty()) return
        val copies = model.projectSdks
        val table = tableSdks()
        val gained = mutableListOf<Sdk>()
        for (sdk in sdks) {
            val copy = copies[sdk]
            val present = sdk in table
            if (copy != null && !present) {
                leave(model, copy)
            } else if (copy == null && present && !copies.containsValue(sdk)) {
                gained += sdk
            }
        }
        heldByAFreshModel(gained).forEach(model::addSdk)
        val edited = pages.flatMap(Page::editedCopies)
        val (kept, stale) = (if (all) copies.keys.toList() else sdks).filter { it in table }
            .mapNotNull { sdk -> copies[sdk]?.to(sdk) }
            .partition { (copy, _) -> edited.any { it === copy } }
        // Refreshed by a later pass or Apply, once no page has it edited: an edit undone leaves the copy stale.
        kept.forEach { (_, sdk) -> touched += sdk }
        if (stale.isNotEmpty()) WriteAction.run<Throwable> { stale.forEach { (copy, sdk) -> refresh(copy, sdk) } }
        pages.forEach(Page::modelChanged)
    }

    /**
     * Those of [sdks] a model reset now would hold: [freshModel] alone decides which table SDKs the model holds, such as
     * only those on the project's machine.
     */
    private fun heldByAFreshModel(sdks: List<Sdk>): List<Sdk> {
        if (sdks.isEmpty()) return sdks
        val fresh = freshModel()
        try {
            val held = fresh.projectSdks.keys
            return sdks.filter { sdk -> held.any { it === sdk } }
        } finally {
            // Each clone is a Disposable registered under the VirtualFilePointerManager.
            fresh.disposeUIResources()
        }
    }

    /** [copy] leaves [model], and its editor closes on every open page. */
    private fun leave(model: ProjectSdksModel, copy: Sdk) {
        pages.forEach { it.close(copy) }
        model.removeSdk(copy)
    }

    /** [copies], which the table never held, leave the model, and the open pages are told. */
    @RequiresEdt
    internal fun takeOut(copies: Collection<Sdk>) {
        if (copies.isEmpty()) return
        val model = model ?: return
        copies.forEach { leave(model, it) }
        pages.forEach(Page::modelChanged)
    }

    /** Runs inside the one write action [pass] takes for all the copies it refreshes. */
    private fun refresh(copy: Sdk, original: Sdk) {
        val modificator = copy.sdkModificator
        modificator.name = original.name
        modificator.homePath = original.homePath
        modificator.versionString = original.versionString
        modificator.sdkAdditionalData = original.sdkAdditionalData
        modificator.removeAllRoots()
        for (type in OrderRootType.getAllTypes()) {
            original.rootProvider.getUrls(type).forEach { modificator.addRoot(it, type) }
        }
        modificator.commitChanges()
    }

    @RequiresEdt
    internal fun addPage(page: Page) {
        pages += page
    }

    /**
     * [page] closed; when [unapplied] changes were written to copies, the next pass refreshes them. It also refreshes
     * the copies an earlier pass left stale because [page] had them edited.
     */
    @RequiresEdt
    internal fun removePage(page: Page, unapplied: Boolean) {
        pages -= page
        if (unapplied) refreshAll.set(true)
        schedule()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): SdksService? = ApplicationManager.getApplication().getService(SdksService::class.java)
    }

    /**
     * Discards the cached model so the next [getModel] rebuilds it from the current JDK table.
     * The model is an application-level singleton that lives for the whole test JVM; teardown that
     * mutates the JDK table directly (bypassing the model) would otherwise leak SDK clones into the
     * next test. Not used in production - the model follows the table.
     */
    @TestOnly
    fun resetForTests() {
        model?.let(PairingRepairs.getInstance()::release)
        model?.disposeUIResources()
        model = null
        touched.clear()
        refreshAll.set(false)
    }

    /**
     * Runs a pending [pass] first, so nothing stale is applied, and then [applyEditors], which write a page's edits to
     * their copies. [names] gives the name each copy will be applied under, which a page's Name field may hold before
     * [applyEditors] writes it.
     *
     * Validating the model reads the SDK table. A modal Settings dialog's Apply holds write-intent, a Settings tab's
     * Save Changes holds no lock, so the lock is taken here.
     *
     * [removed] are the copies one page removed. They leave the table only once the rest applies, so they are taken
     * out of the model's map and left unvalidated while it does, and put back whatever happens: `removeSdk` would
     * queue them for every later Apply, from any page.
     *
     * The table SDKs whose copies another open page has edited, and [from] has not, are taken out of each commit too:
     * their copies carry only part of that page's edit, and that page applies its edit whole.
     *
     * Once applied, [PairingRepairs] repairs what the commit left, and then the open pages are told, as after a pass: an
     * editor reset first would show a copy whose Erlang SDK moved unrepaired, and add the new home's code paths itself.
     */
    @Suppress("UnstableApiUsage")
    @Throws(ConfigurationException::class)
    internal fun apply(
        from: Page? = null,
        removed: Collection<Sdk> = emptyList(),
        names: (Sdk) -> String = Sdk::getName,
        applyEditors: () -> Unit = {},
    ) = WriteIntentReadAction.run {
        pass()
        val model = getModel()
        val sdks = model.projectSdks
        val table = tableSdks()
        val removedEntries = sdks.filterValues { copy -> removed.any { it === copy } }
        val editedHere = from?.editedCopies().orEmpty()
        val editedElsewhere = pages.filter { it !== from }.flatMap(Page::editedCopies)
        val heldEntries = sdks.filter { (key, copy) ->
            editedElsewhere.any { it === copy } && editedHere.none { it === copy } && removed.none { it === copy } &&
                key in table
        }
        refuseNameClash(table, sdks.filterKeys { it !in heldEntries }, removedEntries.keys, names)
        applyEditors()
        applyModel(model, heldEntries + removedEntries)
        if (removedEntries.isNotEmpty()) {
            removedEntries.values.forEach { leave(model, it) }
            applyModel(model, heldEntries)
        }
        PairingRepairs.getInstance().flush()
        pages.forEach(Page::modelChanged)
    }

    /** [sdk] is a table SDK itself, not a copy that shares its name. */
    internal fun inTable(sdk: Sdk): Boolean = sdk in tableSdks()

    /** The table's SDKs themselves, by identity: a copy may share a table SDK's name. */
    private fun tableSdks(): Set<Sdk> =
        Collections.newSetFromMap(IdentityHashMap<Sdk, Boolean>()).apply { addAll(ProjectJdkTable.getInstance().allJdks) }

    /**
     * An SDK applied under a name another table SDK still holds is refused, even when that SDK is renamed away or
     * removed in the same Apply: the removal runs second. An empty name, or one two copies would be applied
     * under, is refused too. Refused before anything is written to the copies, which every page shares.
     */
    private fun refuseNameClash(
        table: Set<Sdk>,
        sdks: Map<Sdk, Sdk>,
        removedKeys: Collection<Sdk>,
        names: (Sdk) -> String,
    ) {
        val applied = mutableListOf<String>()
        for ((key, copy) in sdks) {
            if (removedKeys.any { it === key }) continue
            val name = names(copy)
            if (name.isEmpty()) throw ConfigurationException(ProjectBundle.message("sdk.list.name.required.error"))
            applied += name
            if (name == key.name && key in table) continue
            // SDKs of two types may share a name, so each holder counts, not the first found.
            val holders = table.filter { it.name == name }
            if (holders.isEmpty()) continue
            if (holders.all { holder -> removedKeys.any { it === holder } }) {
                throw ConfigurationException("Apply the removal first")
            }
            throw ConfigurationException("\"$name\" is still another SDK's name: rename that SDK and apply it first")
        }
        // Checked once every copy is, so a name a table SDK still holds is refused as that, whichever copy comes first.
        if (applied.size != applied.toSet().size) {
            throw ConfigurationException(ProjectBundle.message("sdk.list.unique.name.required.error"))
        }
    }

    /** Commits [model] with [held] out of its map for the commit alone, so the model's listeners never see it without them. */
    private fun applyModel(model: ProjectSdksModel, held: Map<Sdk, Sdk>) {
        val sdks = model.projectSdks
        held.keys.forEach(sdks::remove)
        applying = true
        try {
            model.apply()
        } finally {
            applying = false
            sdks.putAll(held)
        }
    }

    /**
     * Adds [sdk], set up, to the table and then to [sdkModel], keyed by [sdk] itself so applying the model updates it
     * rather than adding a second.
     */
    @RequiresEdt
    internal fun register(sdk: Sdk, sdkModel: SdkModel?) {
        registering = sdk.takeIf { sdkModel != null && sdkModel === model }
        try {
            SdkConfigurationUtil.addSdk(sdk)
        } finally {
            registering = null
        }
        sdkModel?.addSdk(sdk)
    }

    fun <T> projectJdkImplList(clazz: Class<T>) =
        getModel().sdks.filter { clazz.isInstance(it.sdkType) }.map { it as ProjectJdkImpl }

    fun getModel(): ProjectSdksModel {
        return model ?: freshModel().also {
            model = it
            // The pages build an Elixir SDK's editor only once they show it.
            PairingRepairs.getInstance().open(it)
        }
    }

    private fun freshModel(): ProjectSdksModel {
        // `reset` keeps the table SDKs on the project's machine. The cached model must NOT be reset again per view
        // (re-cloning would invalidate SDK references other open views hold and break removal - see ProjectSdksModel).
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        var model: ProjectSdksModel?

        do {
            model = try {
                ProjectSdksModel().apply { reset(project) }
            } catch (_: AssertionError) {
                null
            }
        } while (model == null)

        return model
    }
}
