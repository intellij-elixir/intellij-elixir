package org.elixir_lang.mix.watcher

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager.getCachedValue
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.PackageManager
import org.elixir_lang.mix.Dep
import org.elixir_lang.mix.Project.MIX_EXS
import org.elixir_lang.package_manager.virtualFile
import java.util.*

class Resolution(
    val rootVirtualFileToDepSet: Map<VirtualFile, Set<Dep>>,
    val depToRootVirtualFile: Map<Dep, VirtualFile?>
) {
    companion object {
        /**
         * Resolves the transitive dep-set for all given [rootVirtualFiles].
         *
         * Uses [readAction] (WARA) for PSI access so the EDT is never blocked waiting for a read
         * lock. If a write action preempts a read, the lambda is transparently retried. On retry,
         * the [CachedValue] keyed on each `mix.exs` `PsiFile` avoids reparsing files that haven't
         * changed since the previous attempt, keeping restarts cheap.
         */
        suspend fun resolution(
            psiManager: PsiManager,
            progressIndicator: ProgressIndicator,
            vararg rootVirtualFiles: VirtualFile
        ): Resolution {
            val rootVirtualFileQueue: Queue<VirtualFile> = ArrayDeque<VirtualFile>()
            rootVirtualFileQueue.addAll(rootVirtualFiles)
            // Anything reached by following a Dep.Type.LIBRARY dep is a dependency, and Mix resolves
            // its deps as one. A MODULE dep names an app of the same umbrella, which is a project
            // Mix is building rather than one it fetched, so reaching a root that way joins this set
            // instead. Membership rather than dequeue order decides it, so a root reads the same
            // however the queue is drained.
            val projectRootVirtualFiles = rootVirtualFiles.toHashSet()
            val rootVirtualFileToDepSet = mutableMapOf<VirtualFile, Set<Dep>>()
            val depToRootVirtualFile = mutableMapOf<Dep, VirtualFile?>()
            // The deps directory each reached file resolves through. Deliberately not folded into
            // projectRootVirtualFiles: that set also decides isDependency, so adding a library dep's
            // root to it would stop Mix's only:/optional: filter applying to that dep's own deps.
            val rootVirtualFileToDepsDirectory = mutableMapOf<VirtualFile, VirtualFile>()

            while (rootVirtualFileQueue.isNotEmpty() && !progressIndicator.isCanceled) {
                ProgressManager.checkCanceled()
                val rootVirtualFile = rootVirtualFileQueue.remove()

                if (!rootVirtualFileToDepSet.containsKey(rootVirtualFile)) {
                    val (depSet, declaredDepsPath) = rootVirtualFileToPackageDeps(
                        psiManager,
                        progressIndicator,
                        rootVirtualFile,
                        isDependency = rootVirtualFile !in projectRootVirtualFiles,
                    )

                    val owningProjectRoot = owningProjectRoot(rootVirtualFile, projectRootVirtualFiles)

                    // `Mix.Project.in_project` applies the inherited `deps_path` as post-config, so
                    // it overrides whatever the file itself declares: a dependency uses the deps
                    // directory of the project that reached it, never one of its own.
                    val depsDirectory = rootVirtualFileToDepsDirectory[rootVirtualFile]
                        ?: declaredDepsPath?.let { rootVirtualFile.findFileByRelativePath(it) }
                        ?: owningProjectRoot?.findFileByRelativePath(MIX_DEPS_DIRECTORY_NAME)

                    for (dep in depSet) {
                        if (progressIndicator.isCanceled) {
                            break
                        }
                        ProgressManager.checkCanceled()

                        if (!depToRootVirtualFile.contains(dep)) {
                            val depRootVirtualFile =
                                depRootVirtualFile(dep, rootVirtualFile, depsDirectory, owningProjectRoot)

                            depToRootVirtualFile[dep] = depRootVirtualFile

                            if (depRootVirtualFile != null) {
                                if (dep.type == Dep.Type.MODULE) {
                                    // Registering it before it is dequeued is what settles its
                                    // isDependency, which is read off this set.
                                    projectRootVirtualFiles.add(depRootVirtualFile)
                                }

                                // Mix checks a dependency's own deps out beside it, so the
                                // directory that held this one holds everything it declares.
                                depsDirectory?.let {
                                    rootVirtualFileToDepsDirectory.putIfAbsent(depRootVirtualFile, it)
                                }

                                if (!rootVirtualFileToDepSet.contains(depRootVirtualFile) &&
                                    !rootVirtualFileQueue.contains(depRootVirtualFile)
                                ) {
                                    rootVirtualFileQueue.add(depRootVirtualFile)
                                }
                            }
                        }
                    }

                    rootVirtualFileToDepSet[rootVirtualFile] = depSet
                }
            }

            return Resolution(rootVirtualFileToDepSet, depToRootVirtualFile)
        }

        /**
         * The directory Mix would resolve [dep] to.
         *
         * `Mix.Project.deps_config/1` hands every dependency the *top-level* project's `deps_path`,
         * already expanded, so a hex or git dep declared by a dependency is checked out beside it
         * under the project - `deps/<parent>/deps/<child>` is a directory no Mix configuration
         * produces. Looking there ended the walk one level below every project root.
         *
         * An `in_umbrella:` dep is checked out nowhere - it is already an app of the umbrella that
         * owns the declaring app, so its `apps/<name>` is relative to the umbrella root and not to
         * the app that wrote it. `apps/<app>/apps/<sibling>` is a directory no umbrella produces.
         *
         * An explicit `path:` dep is the exception and keeps the old base: `Mix.SCM.Path` expands it
         * against the directory of whatever declared it, so `"../sibling"` inside a dependency
         * really is relative to that dependency.
         *
         * The search is confined to the one project that owns the declaring file, so two content
         * roots that both have `deps/<name>` cannot be confused for one another. The lookup stays on
         * [VirtualFile.findFileByRelativePath] because neither a `deps/` nor an `apps/` path needs
         * upward traversal, leaving [Dep.virtualFile]'s refresh-based resolution for the paths that
         * do.
         */
        private fun depRootVirtualFile(
            dep: Dep,
            declaringRootVirtualFile: VirtualFile,
            depsDirectory: VirtualFile?,
            owningProjectRoot: VirtualFile?
        ): VirtualFile? {
            if (dep.path.startsWith(MIX_DEPS_DIRECTORY_PREFIX)) {
                depsDirectory
                    ?.findFileByRelativePath(dep.path.removePrefix(MIX_DEPS_DIRECTORY_PREFIX))
                    ?.let { return it }
            }

            // A `path:` written after `in_umbrella:` wins the path while leaving the type, so the
            // prefix rather than the type is what says the umbrella owns this one.
            if (dep.type == Dep.Type.MODULE && dep.path.startsWith(UMBRELLA_APPS_DIRECTORY_PREFIX)) {
                owningProjectRoot?.findFileByRelativePath(dep.path)?.let { return it }
            }

            return dep.virtualFile(declaringRootVirtualFile)
        }

        /**
         * The content root of the Mix project whose `deps` directory Mix would install into.
         *
         * That is the innermost root containing [declaringRootVirtualFile], except for an umbrella
         * app: an app shares the umbrella's `deps`, `_build` and lock file and never has its own, so
         * a `deps` directory beside its `mix.exs` is debris from before it joined the umbrella.
         * Every other content root is a project in its own right and keeps its own `deps`, including
         * one that merely happens to sit inside another.
         */
        private fun owningProjectRoot(
            declaringRootVirtualFile: VirtualFile,
            projectRootVirtualFiles: Set<VirtualFile>
        ): VirtualFile? {
            var root = projectRootVirtualFiles
                .filter { VfsUtilCore.isAncestor(it, declaringRootVirtualFile, false) }
                .maxByOrNull { it.path.length }
                ?: return null

            while (true) {
                // An umbrella imported one module per app hands in only `apps/<app>`, so requiring
                // the umbrella root to be among them would strand every such app. A `mix.exs` above
                // an `apps` directory is an umbrella whether or not the caller named it.
                val umbrella = root.parent
                    ?.takeIf { it.name == UMBRELLA_APPS_DIRECTORY_NAME }
                    ?.parent
                    ?.takeIf { it in projectRootVirtualFiles || it.findChild(MIX_EXS) != null }
                    ?: return root

                root = umbrella
            }
        }

        private suspend fun rootVirtualFileToPackageDeps(
            psiManager: PsiManager,
            progressIndicator: ProgressIndicator,
            rootVirtualFile: VirtualFile,
            isDependency: Boolean
        ): PackageDeps {
            progressIndicator.text2 = "Finding package file under ${rootVirtualFile.path}"
            val packageManagerVirtualFile = virtualFile(rootVirtualFile)

            return if (packageManagerVirtualFile != null) {
                val (packageManager, packageVirtualFile) = packageManagerVirtualFile

                packageVirtualFileToPackageDeps(
                    psiManager,
                    progressIndicator,
                    packageManager,
                    packageVirtualFile,
                    isDependency
                )
            } else {
                PackageDeps.EMPTY
            }
        }

        private suspend fun packageVirtualFileToPackageDeps(
            psiManager: PsiManager,
            progressIndicator: ProgressIndicator,
            packageManager: PackageManager,
            packageVirtualFile: VirtualFile,
            isDependency: Boolean
        ): PackageDeps {
            if (progressIndicator.isCanceled) {
                return PackageDeps.EMPTY
            }

            progressIndicator.text2 = "Finding deps in ${packageVirtualFile.path}"

            // WARA: acquires the read lock without blocking the EDT. If a write action preempts,
            // readAction restarts the lambda. The file is found and read in the one action because a
            // write action between two can drop its provider and leave the PsiFile invalid.
            // IncorrectOperationException can occur when the VirtualFile is no longer valid (e.g.,
            // deleted while we waited for the lock).
            return readAction {
                val packagePsiFile = try {
                    psiManager.findFile(packageVirtualFile)
                } catch (@Suppress("unused") error: IncorrectOperationException) {
                    null
                }

                if (packagePsiFile != null) {
                    packagePsiFileToPackageDeps(packageManager, packagePsiFile, isDependency)
                } else {
                    PackageDeps.EMPTY
                }
            }
        }

        /**
         * Reads the dep-set from [packagePsiFile], whose caller holds the read lock.
         *
         * The result is cached via [CachedValueProvider] keyed on [packagePsiFile]'s modification
         * tracker. If a write action cancels the WARA and modifies a *different* file, the cached
         * value for this file remains valid - on restart the cache hit returns the previous result
         * without reparsing. This is correct: only a modification to [packagePsiFile] itself would
         * invalidate the cache and trigger a fresh parse.
         *
         * The [org.elixir_lang.package_manager.DepGatherer] is constructed freshly per WARA attempt (via [PackageManager.depGatherer])
         * so partial results from a cancelled attempt are never reused.
         */
        @RequiresReadLock
        private fun packagePsiFileToPackageDeps(
            packageManager: PackageManager,
            packagePsiFile: PsiFile,
            isDependency: Boolean
        ): PackageDeps =
            // Two keys, because the dep set a file yields now depends on how the file was
            // reached, while a CachedValue outlives the resolution that populated it: one
            // module's run can hand in a root that another's reaches as a dependency.
            getCachedValue(packagePsiFile, if (isDependency) DEPENDENCY_DEP_SET else PROJECT_DEP_SET) {
                packageManager
                    .depGatherer(isDependency)
                    .apply { packagePsiFile.accept(this) }
                    .let { PackageDeps(it.depSet.toSet(), it.depsPath) }
                    .let { CachedValueProvider.Result.create(it, packagePsiFile) }
            }
    }
}

private const val MIX_DEPS_DIRECTORY_NAME = "deps"
private const val MIX_DEPS_DIRECTORY_PREFIX = "deps/"
private const val UMBRELLA_APPS_DIRECTORY_NAME = "apps"
private const val UMBRELLA_APPS_DIRECTORY_PREFIX = "apps/"

/**
 * What one package file yields: the deps it declares, and the `deps_path:` it declares them to live
 * under, if any.
 */
private data class PackageDeps(val depSet: Set<Dep>, val depsPath: String?) {
    companion object {
        val EMPTY = PackageDeps(emptySet(), null)
    }
}

private val PROJECT_DEP_SET: Key<CachedValue<PackageDeps>> =
    Key.create<CachedValue<PackageDeps>>("PROJECT_DEP_SET")
private val DEPENDENCY_DEP_SET: Key<CachedValue<PackageDeps>> =
    Key.create<CachedValue<PackageDeps>>("DEPENDENCY_DEP_SET")
