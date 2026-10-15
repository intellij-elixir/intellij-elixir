package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.functionNameAtomValue
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.scope.CallableTable
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.structure_view.element.Delegation
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger

object Callable : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Callable> {
    override fun resolve(callable: org.elixir_lang.reference.Callable, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val element = callable.element

        return expand(org.elixir_lang.reference.Resolver.preferred(element, incompleteCode, walk(element, incompleteCode)))
            .toTypedArray()
    }

    private val WALK = Key<CachedValue<List<VisitedElementSetResolveResult>>>("org.elixir_lang.reference.resolver.Callable.WALK")
    private val INCOMPLETE_CODE_WALK =
        Key<CachedValue<List<VisitedElementSetResolveResult>>>("org.elixir_lang.reference.resolver.Callable.INCOMPLETE_CODE_WALK")
    private val walks = AtomicInteger()

    /** The calls whose walk is under way on this thread, for complete or incomplete code. */
    private val walking: ThreadLocal<MutableSet<Call>> = ThreadLocal.withInitial { mutableSetOf() }

    /** What the walk finds for [call] before `Resolver.preferred` narrows it, which `multiResolve` and the candidates share. */
    @RequiresReadLock
    fun walk(call: Call, incompleteCode: Boolean): List<VisitedElementSetResolveResult> {
        ThreadingAssertions.assertReadAccess()

        val walking = walking.get()
        val started = walking.add(call)

        if (!started) {
            // This call is already being resolved here, so a table built now would record it unresolved.
            CallableTable.abandonBuild()
        }

        try {
            return CachedValuesManager.getManager(call.project).getCachedValue(
                call,
                if (incompleteCode) INCOMPLETE_CODE_WALK else WALK,
                {
                    walks.incrementAndGet()

                    // The file as well: a non-physical file's edits do not move MODIFICATION_COUNT.
                    CachedValueProvider.Result.create(
                        resolveAll(call, Unquote.arity(call), incompleteCode),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        call.containingFile
                    )
                },
                false
            )
        } finally {
            if (started) {
                walking.remove(call)
            }
        }
    }

    /** How many walks [walk] has run, rather than served from its cache. */
    @TestOnly
    fun walkCount(): Int = walks.get()

    fun resolve(call: Call, resolvedPrimaryArity: Arity, incompleteCode: Boolean): Array<ResolveResult> {
        val preferred = resolvePreferred(call, resolvedPrimaryArity, incompleteCode)
        val expanded = expand(preferred)

        return expanded.toTypedArray()
    }

    /** A call the walk passes through on its way to a declaration, which [expand] adds to the results. */
    internal fun isPath(call: Call): Boolean = Delegation.`is`(call) || Import.`is`(call) || Use.`is`(call)

    internal fun expand(visitedElementSetResolveResultList: List<VisitedElementSetResolveResult>): List<PsiElementResolveResult> =
        visitedElementSetResolveResultList
            .flatMap { visitedElementSetResolveResult ->
                val visitedElementSet = visitedElementSetResolveResult.visitedElementSet
                val validResult = visitedElementSetResolveResult.isValidResult

                val pathResolveResultList =
                    visitedElementSet
                        .filter { visitedElement -> (visitedElement as? Call)?.let(::isPath) ?: false }
                        .map { PsiElementResolveResult(it, validResult) }

                val terminalResolveResult = PsiElementResolveResult(
                    visitedElementSetResolveResult.element,
                    visitedElementSetResolveResult.isValidResult
                )

                listOf(terminalResolveResult) + pathResolveResultList
            }
            // deduplicate shared `defdelegate`, `import`, or `use`
            .groupBy { resolveResultKey(it) }
            .map { (_, resolveResults) ->
                resolveResults.maxByOrNull { if (it.isValidResult) 1 else 0 } ?: resolveResults.first()
            }
            .let(::deduplicateEquivalentResults)

    private fun deduplicateEquivalentResults(resolveResults: List<PsiElementResolveResult>): List<PsiElementResolveResult> {
        val deduplicated = mutableListOf<PsiElementResolveResult>()

        for (candidate in resolveResults) {
            val existingIndex = deduplicated.indexOfFirst { existing ->
                existing.element.manager.areElementsEquivalent(existing.element, candidate.element)
            }

            if (existingIndex == -1) {
                deduplicated.add(candidate)
            } else if (candidate.isValidResult && !deduplicated[existingIndex].isValidResult) {
                deduplicated[existingIndex] = candidate
            }
        }

        return deduplicated
    }

    // A compiled element's navigationElement is its mirror, and building that decompiles the whole module.
    private fun resolveResultKey(resolveResult: PsiElementResolveResult): Any {
        if (resolveResult.element is PsiCompiledElement) return resolveResult.element

        val element = resolveResult.element.navigationElement
        val filePath = element.containingFile?.virtualFile?.path ?: ""
        val range = element.textRange
        val startOffset = range?.startOffset ?: -1
        val endOffset = range?.endOffset ?: -1
        val elementType = PsiUtilCore.getElementType(element)?.toString() ?: ""

        return "$filePath#$startOffset:$endOffset:$elementType"
    }

    private fun resolvePreferred(
        element: Call,
        resolvedPrimaryArity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val all = resolveAll(element, resolvedPrimaryArity, incompleteCode)

        return org.elixir_lang.reference.Resolver.preferred(element, incompleteCode, all)
    }

    private fun resolveAll(element: Call, resolvedPrimaryArity: Arity, incompleteCode: Boolean) =
        /* DO NOT use `getName()` as it will return the NameIdentifier's text, which for `defmodule` is the Alias,
           not `defmodule` */
        element
            .functionName()
            ?.let { name -> resolve(element, name, resolvedPrimaryArity, incompleteCode) }
            ?: emptyList()

    private fun resolve(element: Call, name: String, resolvedPrimaryArity: Arity, incompleteCode: Boolean) =
        resolveInScope(element, name, resolvedPrimaryArity, incompleteCode)

    private fun resolveInScope(
        element: Call,
        name: String,
        resolvedPrimaryArity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        return RecursionManager.doPreventingRecursion(element, true) {
            try {
                if (element is Qualified) {
                    resolveQualified(element, name, resolvedPrimaryArity, incompleteCode)
                } else {
                    resolveUnqualified(element, name, resolvedPrimaryArity, incompleteCode)
                }
            } catch (stackOverflowError: StackOverflowError) {
                Logger.error(
                    Callable::class.java,
                    "StackOverflowError when annotating Call",
                    element,
                    stackOverflowError
                )

                emptyList()
            }
        } ?: emptyList()
    }

    private fun resolveUnqualified(
        element: Call,
        name: String,
        resolvedPrimaryArity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val resolveResultList = mutableListOf<VisitedElementSetResolveResult>()

        // UnqualifiedNoArgumentsCall prevents `foo()` from being treated as a variable.
        // resolvedFinalArity prevents `|> foo` from being counted as 0-arity
        if (element is UnqualifiedNoArgumentsCall<*> && resolvedPrimaryArity == 0) {
            val variableResolveList = org.elixir_lang.psi.scope.variable.MultiResolve.resolveResultList(
                name,
                incompleteCode,
                element
            )

            resolveResultList.addAll(variableResolveList)
        }

        val atom = functionNameAtomValue(element)
        val callDefinitionClauseResolveResultList =
            org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                atom ?: name,
                resolvedPrimaryArity,
                incompleteCode,
                element,
                ResolveState.initial(),
                atom != null
            )

        resolveResultList.addAll(callDefinitionClauseResolveResultList)

        return resolveResultList
    }

    private fun resolveQualified(
        element: Qualified,
        name: String,
        arity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val modulars = element.qualifiedToModulars()

        return if (modulars.isNotEmpty()) {
            val resolvableName = name.takeUnless { Unquote.isQualified(element, it) }
            val atom = resolvableName?.let { functionNameAtomValue(element) }

            modulars.flatMap { modular ->
                org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                    atom ?: resolvableName,
                    arity,
                    incompleteCode,
                    modular,
                    ResolveState.initial(),
                    atom != null
                )
            }
        } else {
            emptyList()
        }
    }
}
