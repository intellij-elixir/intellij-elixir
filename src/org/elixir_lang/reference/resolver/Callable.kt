package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

object Callable : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Callable> {
    override fun resolve(callable: org.elixir_lang.reference.Callable, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val answer = answer(callable.element, incompleteCode)

        return (answer.variables + Resolved.results(answer.projection)).toTypedArray()
    }

    /**
     * What a call resolves to before `multiResolve` gives one result per element; [bound] is whether a valid variable
     * binds its name.
     */
    class Answer(val variables: List<ResolveResult>, val bound: Boolean, val projection: List<Found>)

    @RequiresReadLock
    fun answer(call: Call, incompleteCode: Boolean): Answer {
        val variables = variables(call, incompleteCode)
        val bound = bound(variables)
        val projection = org.elixir_lang.declaration.Use.of(call)
            ?.let { Resolved.projection(it, incompleteCode, bound) }
            .orEmpty()

        return Answer(variables, bound, projection)
    }

    /** Whether a valid variable binds [call]'s name, which then names the variable and no function. */
    @RequiresReadLock
    fun bound(call: Call, incompleteCode: Boolean): Boolean = bound(variables(call, incompleteCode))

    private fun bound(variables: List<ResolveResult>): Boolean = variables.any { it.isValidResult }

    /** The variables [call] may name. A variable declares nothing, so the step never sees it. */
    @RequiresReadLock
    private fun variables(call: Call, incompleteCode: Boolean): List<ResolveResult> =
        walk(call, incompleteCode)
            .filter { it.reached.isEmpty() }
            .map { PsiElementResolveResult(it.element, it.isValidResult) }

    private val WALK =
        Key<CachedValue<ConcurrentMap<Arity, List<VisitedElementSetResolveResult>>>>("org.elixir_lang.reference.resolver.Callable.WALK")
    private val INCOMPLETE_CODE_WALK =
        Key<CachedValue<ConcurrentMap<Arity, List<VisitedElementSetResolveResult>>>>("org.elixir_lang.reference.resolver.Callable.INCOMPLETE_CODE_WALK")
    private val walks = AtomicInteger()

    /** The calls whose walk is under way on this thread, for complete or incomplete code. */
    private val walking: ThreadLocal<MutableSet<Call>> = ThreadLocal.withInitial { mutableSetOf() }

    /** What the walk finds for [call], which `multiResolve` and the candidates share. */
    @RequiresReadLock
    fun walk(call: Call, incompleteCode: Boolean): List<VisitedElementSetResolveResult> =
        walk(call, Unquote.arity(call), incompleteCode)

    /** What the walk finds for [call] used at [arity], as a capture's name is used at the capture's arity. */
    @RequiresReadLock
    fun walk(call: Call, arity: Arity, incompleteCode: Boolean): List<VisitedElementSetResolveResult> {
        ThreadingAssertions.assertReadAccess()

        val walking = walking.get()
        val started = walking.add(call)

        if (!started) {
            // This call is already being resolved here, so a table built now would record it unresolved.
            CallableTable.abandonBuild()
        }

        try {
            val byArity = CachedValuesManager.getManager(call.project).getCachedValue(
                call,
                if (incompleteCode) INCOMPLETE_CODE_WALK else WALK,
                {
                    // The file as well: a non-physical file's edits do not move MODIFICATION_COUNT.
                    CachedValueProvider.Result.create(
                        ConcurrentHashMap(),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        call.containingFile
                    )
                },
                false
            )

            return byArity[arity] ?: run {
                walks.incrementAndGet()
                // The walk runs outside the cached value's provider, so its recursion guard does not see a walk that
                // recursion prevention cut short.
                val stamp = RecursionManager.markStack()
                val walked = resolveAll(call, arity, incompleteCode)

                if (stamp.mayCacheNow()) byArity.putIfAbsent(arity, walked) ?: walked else walked
            }
        } finally {
            if (started) {
                walking.remove(call)
            }
        }
    }

    /** How many walks [walk] has run, rather than served from its cache. */
    @TestOnly
    fun walkCount(): Int = walks.get()

    /** A call the walk passes through on its way to a declaration. */
    internal fun isPath(call: Call): Boolean = Delegation.`is`(call) || Import.`is`(call) || Use.`is`(call)

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
