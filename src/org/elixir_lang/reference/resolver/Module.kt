package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.diagnostic.ControlFlowException
import kotlinx.coroutines.CancellationException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.Require
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.module.MultiResolve
import org.elixir_lang.psi.stub.index.ModularName


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
        val preferred = resolvePreferred(element, name, incompleteCode)
        val expanded = expand(preferred)

        return expanded.toTypedArray()
    }

    /**
     * Whether [element] is an `alias`, `require` or `use` a module was reached through: listed with the module, so Go To
     * offers it, but not what the name resolves to.
     */
    @RequiresReadLock
    fun isPath(element: PsiElement): Boolean =
        (element as? Call)?.let { Alias.`is`(it) || Require.`is`(it) || Use.`is`(it) } ?: false

    private fun expand(visitedElementSetResolveResultList: List<VisitedElementSetResolveResult>): List<PsiElementResolveResult> =
        visitedElementSetResolveResultList
            .flatMap { visitedElementSetResolveResult ->
                val visitedElementSet = visitedElementSetResolveResult.visitedElementSet
                val validResult = visitedElementSetResolveResult.isValidResult

                val pathResolveResultList =
                    visitedElementSet
                        .filter(::isPath)
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

    private fun resolvePreferred(
        element: PsiElement,
        name: String,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val all = resolveAll(element, name, incompleteCode)

        return org.elixir_lang.reference.Resolver.preferred(element, incompleteCode, all)
    }

    private fun resolveAll(element: PsiElement, name: String, incompleteCode: Boolean) =
        try {
            resolveInScope(element, name, incompleteCode)
                .takeIf { set -> set.any(ResolveResult::isValidResult) }
                ?: multiResolveProject(element, name)
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

    private fun multiResolveProject(
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
