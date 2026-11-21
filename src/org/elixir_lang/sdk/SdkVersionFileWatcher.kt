package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.ThreadingAssertions
import org.elixir_lang.util.WslFlatWatchRefresh
import org.elixir_lang.util.loadForEvents
import org.jetbrains.annotations.VisibleForTesting

private val LOG = logger<SdkVersionFileWatcher>()

/**
 * Re-reads an installation replaced at the same path, which the SDK model does not report because the home and the SDK
 * are unchanged. Every version file is watched here: the platform watches an Elixir home's `ebin` only while a module
 * uses the SDK, and `<N>/OTP_VERSION` in each of [SdkHomePaths.erlangReleasesDirs] is under no root at all.
 */
internal object SdkVersionFileWatcher {
    fun elixirVersionFiles(homePath: String): List<String> {
        val ebin = "${normalize(homePath)}/${SdkHomePaths.ELIXIR_EBIN}"

        return listOf("$ebin/elixir.app", "$ebin/Elixir.System.beam")
    }

    /** Lists every releases directory, so never call it under a lock. */
    fun otpVersionFiles(homePath: String): List<String> =
        SdkHomePaths.erlangReleasesDirs(homePath)
            .flatMap(SdkHomePaths::otpReleaseDirs)
            .map { "${normalize(it.path)}/OTP_VERSION" }

    /**
     * @param homesByWatchedPath a set per path: an install prefix and the home nested in it, such as `/usr` and
     * `/usr/lib/erlang`, watch the same files.
     */
    @VisibleForTesting
    fun homesToRevalidate(events: List<VFileEvent>, homesByWatchedPath: Map<String, Set<String>>): Set<String> {
        if (homesByWatchedPath.isEmpty()) return emptySet()
        val byNormalizedPath = homesByWatchedPath.mapKeys { (path, _) -> normalize(path) }

        // A new release directory is matched through its watched parent releases directory. Only a numeric one:
        // `RELEASES` and `start_erl.data` live there too and `release_handler` rewrites them.
        return events.flatMapTo(mutableSetOf()) { event ->
            pathOf(event)?.let { path ->
                byNormalizedPath[path]
                    ?: path.substringAfterLast('/')
                        .takeIf(SdkHomePaths::isOtpReleaseName)
                        ?.let { byNormalizedPath[path.substringBeforeLast('/')] }
            }.orEmpty()
        }
    }

    /**
     * @param homePaths reachable homes only: watching one in an uninstalled WSL distribution stalls on its network path.
     * @return the paths actually watched, so a caller can tell whether the registration happened.
     */
    fun watch(
        homePaths: Collection<String>,
        parentDisposable: Disposable,
        revalidate: (String) -> Unit,
    ): Set<String> {
        // Lists every releases directory and refreshes from disk for every home, possibly on `\\wsl.localhost`; on the
        // EDT the platform runs that refresh inline.
        ThreadingAssertions.assertBackgroundThread()
        // A synchronous refresh must not be invoked from a thread holding a read action.
        ThreadingAssertions.assertNoReadAccess()
        // The SDK table publishes its add and remove events from inside a write action, and a background write
        // action reaches here without tripping either assertion above.
        check(!ApplicationManager.getApplication().isWriteAccessAllowed) {
            "Watching version files reads the filesystem and must not run under a write lock"
        }
        val homesByWatchedPath = mutableMapOf<String, MutableSet<String>>()
        for (homePath in homePaths) {
            // An upgrade adding `releases/28` beside `releases/27` rewrites no watched file; only the releases
            // directory sees it.
            val watchedForHome = buildList {
                addAll(elixirVersionFiles(homePath))
                addAll(otpVersionFiles(homePath))
                SdkHomePaths.erlangReleasesDirs(homePath).mapTo(this) { normalize(it.path) }
            }
            val home = normalize(homePath)
            for (path in watchedForHome) {
                homesByWatchedPath.getOrPut(path, ::mutableSetOf).add(home)
            }
        }
        if (homesByWatchedPath.isEmpty()) return emptySet()

        // Subscribed before loading: loading a file that changed since it was read is itself a change event. The parent
        // can be disposed while this runs, by a later rewatch or installation, and registering on it then throws.
        val connection = ApplicationManager.getApplication().messageBus.connect()
        if (!Disposer.tryRegister(parentDisposable, connection)) {
            connection.disconnect()
            return emptySet()
        }
        connection
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        val homes = homesToRevalidate(events, homesByWatchedPath)
                        SdkVersionWatchService.traceForTests {
                            val watchedHomes = homesByWatchedPath.values.flatten().toSet()
                            val underHomes = events.mapNotNull(::pathOf)
                                .filter { path -> watchedHomes.any { path.startsWith("$it/") } }
                            if (underHomes.isEmpty()) null else "VFS events under watched homes $underHomes matched $homes"
                        }
                        for (homePath in homes) {
                            LOG.debug("A version file under '$homePath' changed")
                            revalidate(homePath)
                        }
                    }
                },
            )

        val localFileSystem = LocalFileSystem.getInstance()
        val watched = mutableSetOf<String>()
        val loaded = mutableListOf<VirtualFile>()
        val watchRequests = homesByWatchedPath.keys.mapNotNull { path ->
            localFileSystem.loadForEvents(path)?.let(loaded::add)
            localFileSystem.addRootToWatch(path, false)?.also { watched.add(path) }
        }
        val unwatch = Disposable { localFileSystem.removeWatchedRoots(watchRequests) }
        if (!Disposer.tryRegister(parentDisposable, unwatch)) Disposer.dispose(unwatch)
        service<WslFlatWatchRefresh>().follow(homesByWatchedPath.keys, parentDisposable)

        // Finding a file the VFS already has compares neither timestamp nor length, so an installation replaced while
        // nothing was watching goes unnoticed unless it is marked dirty first.
        if (loaded.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, false, *loaded.toTypedArray())

        return watched
    }

    /** Declared non-null, but an event that cannot report a path must be skipped rather than throw. */
    private fun pathOf(event: VFileEvent): String? =
        (event.path as String?)?.takeIf { it.isNotEmpty() }?.let(::normalize)

    /** Keyed like the store, so a home and the event paths under it match whatever case each carries. */
    private fun normalize(path: String): String = installationKey(path) ?: path
}
