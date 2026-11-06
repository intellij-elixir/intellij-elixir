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
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.SdkTableChanges
import org.elixir_lang.sdk.SdkTableChanges.Kind
import org.jdom.Element
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

    /** What each page's refused Apply had its editors write, written back out of the copies until its next Apply. */
    private val refusedWrites = mutableMapOf<Page, List<Write>>()

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
     */
    @RequiresEdt
    private fun pass() {
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
        refusedWrites.replaceAll { _, writes -> writes.filter { it.copy !== copy } }
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
        refusedWrites -= page
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
        model?.disposeUIResources()
        model = null
        touched.clear()
        refreshAll.set(false)
        refusedWrites.clear()
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
     * their copies carry only part of that page's edit, and that page applies its edit whole. What [applyEditors]
     * changes in one, as an Erlang SDK's editor repairs its Elixir copies, is still committed, unless the table SDK no
     * longer holds what was written over, as when it was paired with another Erlang SDK since.
     *
     * When the Apply is refused, what [applyEditors] wrote to those copies and to the copies no page has edited is
     * written back out of them, and written in again by [from]'s next Apply before its editors run: an Erlang SDK's
     * editor repairs its Elixir copies once, against the home it had before. A copy that no longer holds what was
     * written over is left as it is.
     *
     * Once applied, the open pages are told, as after a pass: an Erlang SDK's editor also writes Elixir copies another
     * page may show.
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
        val unedited = sdks.filterValues { copy ->
            editedHere.none { it === copy } && removed.none { it === copy }
        }
        val heldEntries = unedited.filter { (key, copy) ->
            editedElsewhere.any { it === copy } && key in table
        }
        refuseNameClash(table, sdks.filterKeys { it !in heldEntries }, removedEntries.keys, names)
        val replays = from?.let(refusedWrites::remove).orEmpty()
            .filter { write -> unedited.values.any { it === write.copy } && write.fits(write.copy) }
        val beforeEditors = unedited.mapValues { (_, copy) -> SdkState(copy) }
        if (replays.isNotEmpty()) WriteAction.run<Throwable> { replays.forEach(Write::replay) }
        applyEditors()
        val writes = unedited.mapValues { (key, copy) -> Write(copy, beforeEditors.getValue(key), SdkState(copy)) }
            .filterValues { !it.isEmpty }
        val standIns = writes.filter { (key, write) -> key in heldEntries && write.fits(key) }
            .mapValues { (key, write) -> write.standIn(key) }
        try {
            applyModel(model, heldEntries + removedEntries, standIns)
        } catch (e: ConfigurationException) {
            if (writes.isNotEmpty()) WriteAction.run<Throwable> { writes.values.forEach(Write::undo) }
            if (from != null) refusedWrites[from] = writes.values.toList()
            throw e
        } finally {
            standIns.values.forEach { Disposer.dispose(it as Disposable) }
        }
        if (removedEntries.isNotEmpty()) {
            removedEntries.values.forEach { leave(model, it) }
            applyModel(model, heldEntries)
        }
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
     * under, is refused too. Refused before anything is written to the copies, which every page shares: an Erlang SDK's
     * editor writes its new name into its Elixir copies.
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

    /**
     * Commits [model] with [held] out of its map, or [standIns] in their place, for the commit alone, so the model's
     * listeners never see a map without them.
     */
    private fun applyModel(model: ProjectSdksModel, held: Map<Sdk, Sdk>, standIns: Map<Sdk, Sdk> = emptyMap()) {
        val sdks = model.projectSdks
        held.keys.forEach(sdks::remove)
        sdks.putAll(standIns)
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

/** What applying a page's editors may change in a copy, the additional data as its type saves it. */
private class SdkState(sdk: Sdk) {
    val name: String = sdk.name
    val home: String? = sdk.homePath
    val version: String? = sdk.versionString
    val data: Element = savedData(sdk)
    val roots: Map<OrderRootType, Set<String>> =
        OrderRootType.getAllTypes().associateWith { sdk.rootProvider.getUrls(it).toSet() }
}

/**
 * What an Apply's editors changed in [copy], from [before] to [after]: name, home and version whole, each attribute of
 * the additional data and its children, and each type's roots.
 */
private class Write(val copy: Sdk, private val before: SdkState, private val after: SdkState) {
    private val changedAttributes = (before.data.attributes + after.data.attributes).map { it.name }.distinct()
        .filter { before.data.getAttributeValue(it) != after.data.getAttributeValue(it) }
    private val childrenChanged = !sameChildren(before.data, after.data)
    private val dataChanged = changedAttributes.isNotEmpty() || childrenChanged
    private val changedRoots = OrderRootType.getAllTypes().filter { before.roots[it] != after.roots[it] }

    val isEmpty = before.name == after.name && before.home == after.home && before.version == after.version &&
        !dataChanged && changedRoots.isEmpty()

    /**
     * [sdk] still holds [before] in each part that changed, so writing the change over it writes over what the editors
     * wrote over: not a pairing to another Erlang SDK. Roots are left out, as [write] merges them root by root.
     */
    fun fits(sdk: Sdk): Boolean {
        val now = SdkState(sdk)
        fun <T> holds(part: SdkState.() -> T) = before.part() == after.part() || now.part() == before.part()
        return holds { name } && holds { home } && holds { version } &&
            changedAttributes.all { attribute -> holds { data.getAttributeValue(attribute) } } &&
            (!childrenChanged || sameChildren(now.data, before.data))
    }

    fun replay() = write(copy, before, after)

    fun undo() = write(copy, after, before)

    /**
     * A clone of [original] with what changed written over it. A held copy also carries another page's unapplied edit,
     * or misses an outside commit, so only the change is written.
     */
    fun standIn(original: Sdk): Sdk =
        original.clone().also { WriteAction.run<Throwable> { write(it, before, after, owner = original) } }

    /** Writes over [sdk] each part that changed, from [from] to [to]. The additional data is loaded for [owner]. */
    private fun write(sdk: Sdk, from: SdkState, to: SdkState, owner: Sdk = sdk) {
        val data = if (!dataChanged) null else savedData(sdk).also { element ->
            for (name in changedAttributes) {
                val value = to.data.getAttributeValue(name)
                if (value == null) element.removeAttribute(name) else element.setAttribute(name, value)
            }
            if (childrenChanged) element.setContent(to.data.cloneContent())
        }
        val roots = changedRoots.associateWith { type ->
            val was = from.roots.getValue(type)
            val now = to.roots.getValue(type)
            val kept = sdk.rootProvider.getUrls(type).filter { it in now || it !in was }
            kept + now.filter { it !in was && it !in kept }
        }
        val modificator = sdk.sdkModificator
        if (from.name != to.name) modificator.name = to.name
        if (from.home != to.home) modificator.homePath = to.home
        if (from.version != to.version) modificator.versionString = to.version
        if (data != null) modificator.sdkAdditionalData = (sdk.sdkType as SdkType).loadAdditionalData(owner, data)
        for ((type, urls) in roots) {
            modificator.removeRoots(type)
            urls.forEach { modificator.addRoot(it, type) }
        }
        modificator.commitChanges()
    }
}

private fun sameChildren(one: Element, other: Element): Boolean =
    JDOMUtil.areElementsEqual(Element("children").addContent(one.cloneContent()),
        Element("children").addContent(other.cloneContent()))

/** [sdk]'s additional data as its type saves it. */
private fun savedData(sdk: Sdk): Element = Element("additional").also { element ->
    val data = sdk.sdkAdditionalData ?: return@also
    (sdk.sdkType as? SdkType)?.saveAdditionalData(data, element)
}
