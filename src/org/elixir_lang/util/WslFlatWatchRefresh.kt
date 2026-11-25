package org.elixir_lang.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Refreshes the non-recursive watch roots on WSL every [INTERVAL], so they raise the events the platform's watcher
 * fails to: before 2026.2, `EelFileWatcher.setWatchRoots` passes the recursive roots where it means the flat ones, so
 * a flat root on `\\wsl.localhost` is never watched. Fixed in 2026.2 by intellij-community `d5bde6ac4cd0` (IJPL-223816,
 * IJPL-223563) and not backported to 2026.1.
 *
 * Delete this file and its calls in `SdkVersionFileWatcher` and `MiseRefreshTrigger` once `pluginSinceBuild` is 262
 * or later.
 */
@Service
internal class WslFlatWatchRefresh(private val scope: CoroutineScope) {
    private val owners = ConcurrentHashMap<String, Int>()
    private val started = AtomicBoolean()

    /** Refreshes each of [paths] that the platform's watcher drops, until [parent] is disposed. */
    fun follow(paths: Collection<String>, parent: Disposable) {
        val baseline = ApplicationInfo.getInstance().build.baselineVersion
        val eelWatcher = Registry.`is`(EEL_WATCHER_KEY, false)
        val affected = paths.filter { isAffected(it, baseline, eelWatcher) }
        if (affected.isEmpty()) return

        track(affected, parent)
        if (started.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(INTERVAL)
                    followed().takeIf { it.isNotEmpty() }?.let(::refresh)
                }
            }
        }
    }

    @VisibleForTesting
    internal fun track(paths: Collection<String>, parent: Disposable) {
        for (path in paths) owners.merge(path, 1, Int::plus)
        val release = Disposable {
            for (path in paths) owners.computeIfPresent(path) { _, count -> (count - 1).takeIf { it > 0 } }
        }
        if (!Disposer.tryRegister(parent, release)) Disposer.dispose(release)
    }

    @VisibleForTesting
    internal fun followed(): Set<String> = HashSet(owners.keys)

    companion object {
        private const val EEL_WATCHER_KEY = "use.eel.file.watcher"
        private const val FIXED_IN_BASELINE = 262
        private val INTERVAL = 10.seconds
        private val WSL_PREFIXES = listOf("//wsl.localhost/", "//wsl$/")

        @VisibleForTesting
        internal fun isAffected(path: String, baseline: Int, eelWatcher: Boolean): Boolean =
            baseline < FIXED_IN_BASELINE && eelWatcher &&
                FileUtil.toSystemIndependentName(path).let { normalized ->
                    WSL_PREFIXES.any { normalized.startsWith(it, ignoreCase = true) }
                }

        /** Only paths the VFS has loaded, which are the only ones a refresh reports; reads the filesystem, so never under a lock. */
        @VisibleForTesting
        internal fun refresh(paths: Collection<String>) {
            val lfs = LocalFileSystem.getInstance()
            val files = paths.filter(wslCompat::isReachable).mapNotNull { lfs.findFileByPathIfCached(it) }
            if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
        }
    }
}
