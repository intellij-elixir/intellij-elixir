package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.annotations.TestOnly
import org.elixir_lang.util.ElixirAppCoroutineService
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

private val LOG = logger<SdkVersionWatchService>()

/** Keeps [SdkVersionFileWatcher] watching every installation [SdkVersionsStore] holds. */
internal object SdkVersionWatchService {
    /**
     * Only homes already read, or read without an answer: watching loads version files into the VFS, which boots a
     * stopped `\\wsl.localhost` distro, and a home that was read had its distro running at some point in this session.
     */
    fun homesToWatch(): Set<String> = SdkVersionsStore.getInstance().homes() + unanswered.values

    /**
     * A home whose version files answered nothing, such as an `OTP_VERSION` read while an install was still writing
     * it, is watched too, so the write that completes it is read.
     */
    fun watchUnanswered(canonicalHomePath: String, homePath: String) {
        val canonicalKey = installationKey(canonicalHomePath) ?: return
        val key = installationKey(homePath) ?: return
        val changed = synchronized(unanswered) {
            unansweredWrites++
            unanswered.put(key, canonicalKey) != canonicalKey
        }
        if (changed) requestRewatch()
    }

    /** Canonical home by configured home: an SDK that goes away names its home as configured, a symlink maybe. */
    private val unanswered = ConcurrentHashMap<String, String>()

    /** Guarded by [unanswered]. */
    private var unansweredWrites = 0L

    fun install(parentDisposable: Disposable) {
        val scope = service<ElixirAppCoroutineService>().supervisedChildScope("SdkVersionWatchService")
        Disposer.register(parentDisposable) { scope.cancel() }
        val installation = Installation(scope, parentDisposable)
        // The previous installation's subscription and watch are held by its own scope and handle, so it would go on
        // driving rewatch unless torn down here.
        val previous = installed
        installed = installation
        previous?.watching?.getAndSet(null)?.let(Disposer::dispose)
        previous?.scope?.cancel()
        // Cleared only while this installation is still the live one: otherwise disposing the first of two installs
        // disables the second, and every later rewatch returns silently.
        Disposer.register(parentDisposable) { if (installed === installation) installed = null }

        scope.launch { rewatch() }

        val connection = ApplicationManager.getApplication().messageBus.connect(scope)
        connection.subscribe(SdkVersionsListener.TOPIC, SdkVersionsListener { _, _ -> requestRewatch() })
        connection
            .subscribe(
                ProjectJdkTable.JDK_TABLE_TOPIC,
                object : ProjectJdkTable.Listener {
                    // Published from inside the table's write action, so the filesystem work is launched.
                    override fun jdkAdded(jdk: Sdk) {
                        val homePath = jdk.homePath?.takeIf { isOurs(jdk) } ?: return
                        scope.launch { SdkVersionsFiller.fillIfUnread(homePath) }
                    }

                    override fun jdkRemoved(jdk: Sdk) {
                        val homePath = jdk.homePath?.takeIf { isOurs(jdk) } ?: return
                        scope.launch { forgetUnlessRegistered(homePath) }
                    }
                },
            )
    }

    private fun isOurs(sdk: Sdk): Boolean =
        sdk.sdkType === ElixirSdkType.instance || sdk.sdkType === ErlangSdkType.instance

    /**
     * Two Elixir SDKs pairing one build with different Erlang SDKs share a home, so removing one leaves the
     * installation in use. Lexical throughout: the store records which installation each spelling belongs to.
     */
    internal suspend fun forgetUnlessRegistered(homePath: String) {
        val store = SdkVersionsStore.getInstance()
        val canonicalHome = store.canonicalHome(homePath) ?: return
        val stillRegistered = readAction {
            val table = ProjectJdkTable.getInstance()
            (table.getSdksOfType(ElixirSdkType.instance) + table.getSdksOfType(ErlangSdkType.instance))
                .any { store.canonicalHome(it.homePath) == canonicalHome }
        }
        if (!stillRegistered) {
            store.forgetInstallation(canonicalHome, homePath)
            requestRewatch()
        }
    }

    /**
     * Drops the homes the store has answered for since they were recorded, and those no SDK uses: a candidate home read
     * while an SDK was being chosen answers nothing, and no SDK removal would ever forget it.
     */
    private suspend fun pruneUnanswered() {
        if (unanswered.isEmpty()) return
        val stored = SdkVersionsStore.getInstance().homes()
        synchronized(unanswered) { unanswered.values.removeAll(stored) }
        val writes = synchronized(unanswered) { unansweredWrites }
        val registered = readAction {
            val table = ProjectJdkTable.getInstance()
            (table.getSdksOfType(ElixirSdkType.instance) + table.getSdksOfType(ErlangSdkType.instance))
                .mapNotNullTo(HashSet()) { installationKey(it.homePath) }
        }
        // A home recorded since the table was read may be an SDK added since, so the pass after this one prunes.
        val pruned = synchronized(unanswered) {
            (unansweredWrites == writes).also { if (it) unanswered.keys.retainAll(registered) }
        }
        if (!pruned) requestRewatch()
    }

    private fun requestRewatch() {
        val installation = installed ?: return
        // Collapsed: filling a project's homes records one after another. The flag is cleared before the pass, so a
        // change landing while it runs still schedules the next one.
        if (!installation.rewatchPending.compareAndSet(false, true)) return

        installation.scope.launch {
            installation.rewatchPending.set(false)
            rewatch()
        }
    }

    /** @return whether the watch was rebuilt. */
    suspend fun rewatch(): Boolean {
        val installation = installed ?: return false

        return installation.rewatches.withLock {
            pruneUnanswered()
            val homes = homesToWatch()
            // Checked on every rewatch, so a home skipped because its distro was not installed is retried then.
            val unreachable = withContext(Dispatchers.IO) { homes.filterNotTo(HashSet(), wslCompat::isReachable) }
            // A value re-read changes the store without changing its homes, and every rebuild does I/O per home.
            if (homes == installation.lastWatched.get() && unreachable == installation.lastUnreachable) {
                traceForTests { "rewatch skipped: already watching $homes" }
                return@withLock false
            }
            val lifetime = homes.takeIf { it.isNotEmpty() }?.let { Disposer.newDisposable("SdkVersionFileWatcher") }
            // `tryRegister`: the parent may already be disposed, and parenting on a disposed parent throws.
            if (lifetime != null && !Disposer.tryRegister(installation.parentDisposable, lifetime)) {
                Disposer.dispose(lifetime)
                return@withLock false
            }
            // Cleared first because `lastUnreachable` is overwritten below: a throw must not leave the two describing
            // different watches.
            val previouslyWatched = installation.lastWatched.getAndSet(null).orEmpty()
            installation.lastUnreachable = unreachable
            if (lifetime == null) {
                installation.watching.getAndSet(null)?.let(Disposer::dispose)
                traceForTests { "rewatch disposed the previous watch; nothing to watch" }
                installation.lastWatched.set(homes)
                return@withLock true
            }

            LOG.debug("Watching the version files of ${homes.size} installation(s)")
            traceForTests { "rewatch building a watch for $homes" }
            // The previous watch stays subscribed until this one is: a version file changed through the VFS in between
            // is published once, and a refresh afterwards finds nothing changed.
            val watched = try {
                withContext(Dispatchers.IO) {
                    beforeWatchRebuiltForTests?.invoke()
                    // Only the reachable homes, so what is recorded as unreachable is what this watch left out.
                    SdkVersionFileWatcher.watch(homes - unreachable, lifetime) { homePath ->
                        traceForTests { "a version file under $homePath changed" }
                        installation.scope.launch {
                            val changed = SdkVersionsFiller.fill(homePath, clearWhenUnreadable = true)
                            traceForTests {
                                val otp = SdkVersionsStore.getInstance().otpVersion(homePath)
                                "re-read $homePath: changed $changed, OTP $otp"
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Disposer.dispose(lifetime)
                throw e
            }
            installation.watching.getAndSet(lifetime)?.let(Disposer::dispose)
            traceForTests { "rewatch built: ${watched.size} path(s) watched, and disposed the previous watch" }
            // A write that completed a blank version file before this watch loaded it fires no event, so an unanswered
            // home it has just started watching is read once.
            val unansweredHomes = unanswered.values.toSet()
            homes
                .filter { it in unansweredHomes && it !in unreachable && it !in previouslyWatched }
                .forEach { home ->
                    installation.scope.launch { SdkVersionsFiller.fill(home, clearWhenUnreadable = true) }
                }
            // Only after `watch` returns: it throws for a distro that stopped answering, and a recorded set is skipped.
            installation.lastWatched.set(homes)
            true
        }
    }

    @Volatile
    private var installed: Installation? = null

    /** Runs as a rebuild starts, for a test that changes a version file while the watch is being replaced. */
    @Volatile
    @set:TestOnly
    var beforeWatchRebuiltForTests: (() -> Unit)? = null

    /**
     * Whether the fills and rewatches the live installation launched have finished, for a test that must see the store
     * settled. A replaced installation's fills are not counted, nor fills started outside the installation's scope, such
     * as `fillUsedBy`'s.
     */
    @TestOnly
    fun isIdleForTests(): Boolean = installed?.scope?.coroutineContext?.job?.children?.none() ?: true

    /** Stops watching what [SdkVersionsStore.clearForTests] emptied, which it does without telling anyone. */
    @TestOnly
    fun stopWatchingForTests() {
        unanswered.clear()
        trace.clear()
        val installation = installed ?: return
        installation.watching.getAndSet(null)?.let(Disposer::dispose)
        installation.lastWatched.set(emptySet())
    }

    /** Where the watch stands, for a test that timed out waiting on it. */
    @TestOnly
    fun describeForTests(): String {
        val installation = installed ?: return "not installed"

        return "installed, scope active: ${installation.scope.isActive}; watching: ${installation.lastWatched.get()}; " +
            "rewatch pending: ${installation.rewatchPending.get()}, running: ${installation.rewatches.isLocked}; " +
            "store homes: ${SdkVersionsStore.getInstance().homes()}; unanswered: ${unanswered.values}; " +
            "recent: ${trace.joinToString(" | ")}"
    }

    // Exists only to track down flaky SDK-watch tests, which see a changed version file go unread; remove it if those
    // flakes have not shown up in a while.
    private val trace = ConcurrentLinkedDeque<String>()

    /** [event] is only built in unit-test mode, and a null one records nothing. */
    internal fun traceForTests(event: () -> String?) {
        if (!ApplicationManager.getApplication().isUnitTestMode) return
        trace.addLast("${LocalTime.now()} ${event() ?: return}")
        while (trace.size > TRACE_LIMIT) trace.pollFirst()
    }

    private const val TRACE_LIMIT = 40

    /**
     * [rewatches] serialises rewatches: interleaved, the one that read the older homes could finish last and install
     * them over the newer set, leaving a home unwatched.
     */
    private class Installation(val scope: CoroutineScope, val parentDisposable: Disposable) {
        val watching = AtomicReference<Disposable?>()
        val lastWatched = AtomicReference<Set<String>?>()
        val rewatches = Mutex()
        val rewatchPending = AtomicBoolean(false)

        /** The homes the last rebuild skipped as unreachable, guarded by [rewatches]. */
        var lastUnreachable: Set<String> = emptySet()
    }
}
