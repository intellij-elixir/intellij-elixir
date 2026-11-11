package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.LibraryOrSdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.ControlFlowException
import kotlinx.coroutines.CancellationException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.facet.FacetModels
import org.elixir_lang.mix.buildEnv
import org.elixir_lang.mix.defaultBuildEnv
import org.elixir_lang.mix.sync.isConsolidatedLibraryName
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.Require
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.module.MultiResolve
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.sdk.elixir.ElixirSdkLookup


object Module : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Module> {
    override fun resolve(
        module: org.elixir_lang.reference.Module,
        incompleteCode: Boolean
    ): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val element = module.element
        val name = element.fullyQualifiedName().removeElixirPrefix()

        return resolve(element, name, incompleteCode)
    }

    /**
     * In Elixir, every module `Foo.Bar` is internally named `:Elixir.Foo.Bar` in the
     * Erlang VM.  Source code can reference modules with the explicit `Elixir.` prefix
     * (e.g. `Elixir.Enum.map/2`), but the [ModularName] stub index stores modules by
     * their Elixir-source name (`Enum`, not `Elixir.Enum`).  Strip the prefix so that
     * resolution, completion, go-to-definition, and all other code insight features work
     * uniformly for both `Enum.map()` and `Elixir.Enum.map()`.
     */
    private fun String.removeElixirPrefix(): String =
        if (startsWith("Elixir.")) removePrefix("Elixir.") else this

    fun resolve(element: PsiElement, name: String, incompleteCode: Boolean): Array<ResolveResult> {
        val preferred = resolvePreferred(element, name, incompleteCode, inScope = true)
        val expanded = expand(preferred)

        return expanded.toTypedArray()
    }

    private fun expand(visitedElementSetResolveResultList: List<VisitedElementSetResolveResult>): List<PsiElementResolveResult> =
        visitedElementSetResolveResultList
            .flatMap { visitedElementSetResolveResult ->
                val visitedElementSet = visitedElementSetResolveResult.visitedElementSet
                val validResult = visitedElementSetResolveResult.isValidResult

                val pathResolveResultList =
                    visitedElementSet
                        .filter { visitedElement ->
                            visitedElement.let { it as? Call }?.let { visitedCall ->
                                Alias.`is`(visitedCall) || Require.`is`(visitedCall) || Use.`is`(visitedCall)
                            } ?: false
                        }
                        .map { PsiElementResolveResult(it, validResult) }

                val terminalResolveResult = PsiElementResolveResult(
                    visitedElementSetResolveResult.element,
                    visitedElementSetResolveResult.isValidResult
                )

                listOf(terminalResolveResult) + pathResolveResultList
            }
            // deduplicate shared `defdelegate`, `import`, or `use`
            .groupBy { it.element }
            .map { (_, resolveResults) -> resolveResults.first() }

    /**
     * The modulars named [name], preferring valid ones, source over a compiled copy, then those under the caller's IDE
     * module. The one place a name becomes modulars, whichever way the module was written.
     *
     * @param name an index name verbatim: `Mod` for `Mod` and `:"Elixir.Mod"`, `:lists` for `:lists`.
     * @param inScope whether to look at the `alias`es in scope first, which a module written as an atom never is.
     */
    fun resolvePreferred(
        element: PsiElement,
        name: String,
        incompleteCode: Boolean,
        inScope: Boolean,
    ): List<VisitedElementSetResolveResult> =
        org.elixir_lang.reference.Resolver.preferred(element, incompleteCode, resolveAll(element, name, incompleteCode, inScope))

    /**
     * The modulars named [name], an index name as [resolvePreferred] takes it, in [element]'s view, and those of them
     * Elixir would load for [element] ([loadedCopies]), with no preference among them.
     */
    fun resolveLoadable(element: PsiElement, name: String): Loadable {
        val all = resolveAll(element, name, incompleteCode = false, inScope = false)

        return Loadable(element, loadedCopies(element, all), all)
    }

    /** @property loaded those of [all], the modulars named in [asker]'s view, Elixir would load */
    class Loadable(
        private val asker: PsiElement,
        val loaded: List<VisitedElementSetResolveResult>,
        private val all: List<VisitedElementSetResolveResult>,
    ) {
        /**
         * The CLASSES and SOURCES roots of each library or SDK entry of [asker]'s IDE module and of the SDK its facet
         * names, the roots [narrowedScope] searches.
         */
        private val entries: List<Pair<Set<VirtualFile>, Array<VirtualFile>>> by lazy(LazyThreadSafetyMode.NONE) {
            val module = moduleOf(asker) ?: return@lazy emptyList()

            val roots = ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrSdkOrderEntry>().map {
                it.getRootFiles(OrderRootType.CLASSES) to it.getRootFiles(OrderRootType.SOURCES)
            } + listOfNotNull(FacetModels.getInstance(module.project).roots(module))

            roots.map { (classes, sources) -> classes.toSet() to sources }
        }

        /** The files of [all], every copy [loaded] is ruled from. */
        val files: Set<VirtualFile> by lazy(LazyThreadSafetyMode.NONE) { all.mapNotNullTo(HashSet()) { it.file } }

        /** The files of [all]'s source copies. */
        private val sourceFiles: Set<VirtualFile> by lazy(LazyThreadSafetyMode.NONE) {
            all.filterNot { it.isBeam }.mapNotNullTo(HashSet()) { it.file }
        }

        /**
         * The one file of [all]'s copies the entry of [asker]'s IDE module that holds [beam] attaches as source; `null`
         * when it attaches none or several files. Counted by file, not modular: what this answers is held until a file
         * gains or drops the name, which a file dropping one of two definitions of it doesn't.
         */
        @RequiresReadLock
        fun sourceOf(beam: VirtualFile): VirtualFile? {
            if (sourceFiles.isEmpty()) return null

            val sources = entries.filter { (classes, _) -> VfsUtilCore.isUnder(beam, classes) }
                .flatMapTo(HashSet()) { (_, sources) -> sources.asIterable() }
            if (sources.isEmpty()) return null

            return sourceFiles.singleOrNull { VfsUtilCore.isUnder(it, sources) }
        }
    }

    /**
     * Those of [candidates] Elixir would load for [asker]:
     * - when a library or the SDK holds a `.beam`, no source outside the project's content;
     * - when [asker]'s SDK holds one of several `.beam`s, no copy outside the project's content that the SDK does not
     *   hold: a stale library's copy of the SDK's module;
     * - a module's own `.beam` over its consolidated copy, whose exports are the same;
     * - the `.beam`s built for [asker]'s Mix env ([defaultBuildEnv]), over other envs' builds.
     */
    private fun loadedCopies(
        asker: PsiElement,
        candidates: List<VisitedElementSetResolveResult>,
    ): List<VisitedElementSetResolveResult> {
        val index = ProjectFileIndex.getInstance(asker.project)

        val sourceRuled = if (candidates.any { it.isBeam && it.file?.let(index::isInLibraryClasses) == true }) {
            candidates.filter { it.isBeam || it.file?.let(index::isInContent) == true }
        } else {
            candidates
        }
        val loadable = sdkCopyOverStale(asker, index, sourceRuled)
        val consolidated = { candidate: VisitedElementSetResolveResult ->
            candidate.isBeam && isConsolidated(index, candidate.file)
        }
        val unconsolidated = if (loadable.any { it.isBeam && !consolidated(it) }) {
            loadable.filterNot(consolidated)
        } else {
            loadable
        }
        val env = defaultBuildEnv(asker.project, effectiveVirtualFile(asker))

        return if (unconsolidated.any { buildEnv(it.file) == env }) {
            unconsolidated.filter { buildEnv(it.file).let { built -> built == null || built == env } }
        } else {
            unconsolidated
        }
    }

    /**
     * [copies] without those outside the project's content that [asker]'s SDK does not hold, when several `.beam`s
     * are left and the SDK holds one. An exclusion is ignored ([ProjectFileIndex.getContentRootForFile]): Mix sync
     * excludes a dependency's build, which loads before the SDK's copy.
     */
    private fun sdkCopyOverStale(
        asker: PsiElement,
        index: ProjectFileIndex,
        copies: List<VisitedElementSetResolveResult>,
    ): List<VisitedElementSetResolveResult> {
        if (copies.count { it.isBeam } < 2) return copies

        val roots = ElixirSdkLookup.mostSpecificSdk(asker)?.rootProvider?.getFiles(OrderRootType.CLASSES)?.toSet()
            ?: return copies
        val inSdk = { copy: VisitedElementSetResolveResult -> copy.file?.let { VfsUtilCore.isUnder(it, roots) } == true }

        if (copies.none { it.isBeam && inSdk(it) }) return copies

        return copies.filterNot { !inSdk(it) && it.file?.let { f -> index.getContentRootForFile(f, false) } == null }
    }

    /** The file of this copy's modular. */
    private val VisitedElementSetResolveResult.file: VirtualFile? get() = element.containingFile?.originalFile?.virtualFile

    /** Whether this copy is a `.beam`'s. */
    private val VisitedElementSetResolveResult.isBeam: Boolean get() = element.isDecompiled()

    /** Whether [file] is in Mix sync's consolidated-protocols library. */
    private fun isConsolidated(index: ProjectFileIndex, file: VirtualFile?): Boolean =
        file != null && index.getOrderEntriesForFile(file).any { entry ->
            (entry as? LibraryOrderEntry)?.libraryName?.let(::isConsolidatedLibraryName) == true
        }

    private fun resolveAll(element: PsiElement, name: String, incompleteCode: Boolean, inScope: Boolean) =
        try {
            if (inScope) {
                resolveInScope(element, name, incompleteCode)
                    .takeIf { set -> set.any(ResolveResult::isValidResult) }
                    ?: multiResolveProject(element, name)
            } else {
                multiResolveProject(element, name)
            }
        } catch (stackOverflowError: StackOverflowError) {
            Logger.error(Module::class.java, "StackOverflowError when resolving Alias", element, stackOverflowError)

            emptyList()
        } catch (runtimeException: RuntimeException) {
            // `RecursionManager` rewraps anything raised in its own unwinding, so an overflow can
            // arrive with its cause nested. Cancellation is excluded per `tryCatchUtils.kt`.
            if (runtimeException is ControlFlowException ||
                runtimeException is CancellationException ||
                generateSequence<Throwable>(runtimeException) { it.cause }
                    .none { it is StackOverflowError }
            ) {
                throw runtimeException
            }

            Logger.error(
                Module::class.java,
                "StackOverflowError unwinding alias resolution",
                element,
                runtimeException
            )

            emptyList()
        }

    private fun resolveInScope(element: PsiElement, name: String, incompleteCode: Boolean) =
        MultiResolve.resolveResults(name, incompleteCode, element)

    @RequiresReadLock
    internal fun multiResolveProject(
        entrance: PsiElement,
        name: String
    ): List<VisitedElementSetResolveResult> {
        val resolveResultList = mutableListOf<VisitedElementSetResolveResult>()
        val project = entrance.project

        if (!DumbService.isDumb(project)) {
            val searchScope = narrowedScope(entrance, project)

            StubIndex
                .getInstance()
                .processElements(
                    ModularName.KEY,
                    name,
                    project,
                    searchScope,
                    null,
                    NamedElement::class.java
                ) { namedElement ->
                    /**
                     * Don't use `namedElement.navigationElement` as it triggers decompiling of the whole module,
                     * which will parse the whole Module text, which is overkill to get the module's name that is
                     * available in the `BeamModule` alone.
                     */
                    resolveResultList.add(VisitedElementSetResolveResult(namedElement))
                }
        }

        return resolveResultList
    }
}
