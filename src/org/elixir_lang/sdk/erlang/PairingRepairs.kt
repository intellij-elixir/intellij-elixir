package org.elixir_lang.sdk.erlang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.SdkTableChanges
import org.elixir_lang.sdk.SdkTableChanges.Kind
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.staticIsValidDependency
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.TestOnly
import java.util.IdentityHashMap

/**
 * Pairs the Elixir SDKs that store an Erlang SDK's old name or home with it once a commit to the table renames or moves
 * it, in the table and in every open model's copies, and sets up their code paths again.
 *
 * It runs after the commit's write action: a model's Apply writes each copy over its SDK in table order in one write
 * action, so a repair made at the Erlang SDK's event would be written over by its Elixir SDKs' stale copies.
 */
@Service
internal class PairingRepairs : Disposable {
    /** An Erlang SDK's name and home, as its Elixir SDKs store them. */
    data class Pairing(val name: String, val home: String?) {
        // `pathsEqualWslAware` is false for a blank path, so a blank home left as it was would read as a move.
        constructor(sdk: Sdk) : this(sdk.name, sdk.homePath?.takeIf(String::isNotBlank))
    }

    private val lock = Any()

    /** The following of the table, once started. */
    private var following: Disposable? = null

    /** Each followed SDK's name and home as last seen. */
    private val known = IdentityHashMap<Sdk, Pairing>()

    /** The first name and home each SDK had since the last [flush], which its Elixir SDKs still store. */
    private val pending = IdentityHashMap<Sdk, Pairing>()

    /** Each open model, with how many holders opened it. */
    private val open = IdentityHashMap<ProjectSdksModel, Int>()

    /**
     * Follows the table, if not already. It must start before the first rename it repairs, as the follower hears no
     * previous name, so it only subscribes and snapshots the in-memory table: it may run inside the table's write
     * action, or on a thread whose first call another waits for.
     */
    fun ensureStarted() {
        synchronized(lock) {
            if (following != null) return
            following = Disposer.newDisposable(this, "PairingRepairs").also {
                SdkTableChanges.follow(it, ::staticIsValidDependency, ::touched)
            }
            ProjectJdkTable.getInstance().allJdks.filter(::staticIsValidDependency).forEach { known[it] = Pairing(it) }
        }
    }

    /** Stops following the table and forgets what it knew, as at load, before any [ensureStarted]. */
    @TestOnly
    fun resetForTests() = synchronized(lock) {
        following?.let(Disposer::dispose)
        following = null
        known.clear()
        pending.clear()
        open.clear()
    }

    /** [model]'s copies are repaired too, until as many [release]s. */
    fun open(model: ProjectSdksModel) {
        ensureStarted()
        synchronized(lock) { open.merge(model, 1, Int::plus) }
    }

    fun release(model: ProjectSdksModel) {
        synchronized(lock) { open.computeIfPresent(model) { _, holders -> (holders - 1).takeIf { it > 0 } } }
    }

    /** The pairings [erlangSdk]'s Elixir SDKs may store: its own, and the one before a rename or move not yet repaired. */
    fun pairingsOf(erlangSdk: Sdk): List<Pairing> =
        listOfNotNull(Pairing(erlangSdk), synchronized(lock) { pending[erlangSdk] })

    private fun touched(sdk: Sdk, kind: Kind) {
        synchronized(lock) {
            when (kind) {
                Kind.ADDED -> {
                    known[sdk] = Pairing(sdk)
                    return
                }

                Kind.REMOVED -> {
                    // Its pending pairing stays for the deletion ask until the pass, which skips an SDK the table lacks.
                    known.remove(sdk)
                    return
                }

                Kind.RENAMED, Kind.ROOTS_COMMITTED -> {
                    val now = Pairing(sdk)
                    val was = known.put(sdk, now)
                    if (was == null || was == now) return
                    pending.putIfAbsent(sdk, was)
                }
            }
        }
        schedule()
    }

    /**
     * Queues a pass in the modality of the commit, so that a commit from a modal dialog such as Project Structure is
     * repaired, with that dialog's model, while the dialog is still open. One pass per commit, since a pass queued
     * earlier in another modality may not run until the dialog closes; a pass that finds nothing left writes nothing.
     */
    private fun schedule() {
        val application = ApplicationManager.getApplication()
        val modality = if (application.isDispatchThread) ModalityState.current() else ModalityState.nonModal()
        application.invokeLater(::flush, modality, application.disposed)
    }

    /** Repairs now what the commits since the last pass left, before a reader shows a copy they leave stale. */
    @RequiresEdt
    fun flush() {
        val (changed, models) = synchronized(lock) {
            (IdentityHashMap(pending).also { pending.clear() }) to open.keys.toList()
        }
        if (changed.isEmpty()) return
        val table = ProjectJdkTable.getInstance().allJdks.asList()
        val rehomed = WriteAction.compute<List<Sdk>, Throwable> {
            changed.flatMap { (erlangSdk, was) -> repair(erlangSdk, was, table, models) }
        }
        // Against the table, which holds what was committed: a model's copy of the Erlang SDK may not yet.
        ElixirSdkPathConfigurator.followErlangHome(rehomed)
    }

    /** Pairs with [erlangSdk] the SDKs in [table] and [models] that store [was]; returns those paired at another home. */
    private fun repair(erlangSdk: Sdk, was: Pairing, table: List<Sdk>, models: List<ProjectSdksModel>): List<Sdk> {
        if (table.none { it === erlangSdk }) return emptyList()
        val renamedFrom = was.name.takeIf { it != erlangSdk.name }
        val movedFrom = was.home?.takeUnless { wslCompat.pathsEqualWslAware(it, erlangSdk.homePath) }
        if (renamedFrom == null && movedFrom == null) return emptyList()
        val copies = models.map { model ->
            // Its own copy of the Erlang SDK may still be stale, and would read as another SDK holding the old pairing.
            val own = model.projectSdks[erlangSdk]
            model.projectSdks.values.filter { it !== own }
        }
        return (listOf(table) + copies).flatMap { sdks ->
            ElixirSdkMutation.repairPairings(sdks, erlangSdk, renamedFrom, movedFrom)
        }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): PairingRepairs = service()
    }
}
