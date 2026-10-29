package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.code_insight.completion.callDefinitionClauseVisible
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Candidate
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.impl.functionNameAtomValue
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.qualifierModulars
import org.elixir_lang.psi.putAncestorUnquote
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.psi.putVisitedElement
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.psi.scope.CallableTable
import org.elixir_lang.psi.scope.ReachedDeclaration
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.maxScope
import org.elixir_lang.reference.resolver.Callable
import org.elixir_lang.structure_view.element.Delegation
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The candidates today's call walk finds, read before `Resolver.preferred` narrows them for `multiResolve`. The walk
 * itself is private to this file.
 */
object LegacyWalkSource : CandidateSource {
    @RequiresReadLock
    override fun candidates(use: Use, incompleteCode: Boolean): List<Found> {
        ThreadingAssertions.assertReadAccess()

        val found = when (use) {
            is Use.OfCall -> walk(use.entrance, use.arity, incompleteCode).flatMap { found(it, use) }
            // A `@spec` is read in its module's body, so it names what the module declares wherever it declares it.
            is Use.Specified ->
                walk(use.modular, use.name, use.arity, incompleteCode)
                    .flatMap { found(it, use) }
                    .filter { it.candidate.reach == Reach.OWN }
            // A tag names what is reached from its entrance, imported or not.
            is Use.Tag -> walk(use.entrance, use.name, use.arity, incompleteCode).flatMap { found(it, use) }
            // A link names what is reached from its entrance, as a tag does.
            is Use.Link -> walk(use.entrance, use.name, use.arity, incompleteCode).flatMap { found(it, use) }
            is Use.Remote ->
                use.module
                    .maybeModularNameToModulars(use.atom.containingFile, useCall = null, incompleteCode = false)
                    .flatMap { modular -> walk(modular, use.name, use.arity, incompleteCode) }
                    .flatMap { found(it, use) }
        }

        return delegatedThrough(found)
    }

    /**
     * [found] with each delegation target's [Found.delegatedThrough]: the head of the delegation the use reached it
     * through, the first `defdelegate` in its `via`, as the walk found that delegation. The walk finds one candidate
     * for each declaring element, so the first found at that element is the delegation.
     */
    private fun delegatedThrough(found: List<Found>): List<Found> {
        val heads = found.filterNot { it.candidate.reach.delegationTarget }.distinctBy(Found::element).associate { it.element to it.head }

        return found.map { target ->
            if (target.candidate.reach.delegationTarget) {
                target.copy(delegatedThrough = target.via.firstOrNull { Delegation.`is`(it) }?.let { heads[it] })
            } else {
                target
            }
        }
    }

    private fun walk(modular: PsiElement, name: String, arity: Int?, incompleteCode: Boolean): List<VisitedElementSetResolveResult> =
        resolveResults(name, arity, incompleteCode, modular, atom = true)

    /** The variables [call] may name. A variable declares nothing, so the step never sees it. */
    @RequiresReadLock
    internal fun variables(call: Call, incompleteCode: Boolean): List<ResolveResult> =
        walk(call, Unquote.arity(call), incompleteCode)
            .filter { it.reached.isEmpty() }
            .map { PsiElementResolveResult(it.element, it.isValidResult) }

    private val WALK =
        Key<CachedValue<ConcurrentMap<Arity, List<VisitedElementSetResolveResult>>>>("${LegacyWalkSource::class.java.name}.WALK")
    private val INCOMPLETE_CODE_WALK =
        Key<CachedValue<ConcurrentMap<Arity, List<VisitedElementSetResolveResult>>>>("${LegacyWalkSource::class.java.name}.INCOMPLETE_CODE_WALK")
    private val walks = AtomicInteger()

    /** The calls whose walk is under way on this thread, for complete or incomplete code. */
    private val walking: ThreadLocal<MutableSet<Call>> = ThreadLocal.withInitial { mutableSetOf() }

    /** How many walks [walk] has run, rather than served from its cache. */
    @TestOnly
    fun walkCount(): Int = walks.get()

    /** What the walk finds for [call] used at [arity], as a capture's name is used at the capture's arity. */
    @RequiresReadLock
    private fun walk(call: Call, arity: Arity, incompleteCode: Boolean): List<VisitedElementSetResolveResult> {
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

    /** A call the walk passes through on its way to a declaration. */
    private fun isPath(call: Call): Boolean =
        Delegation.`is`(call) || Import.`is`(call) || org.elixir_lang.psi.Use.`is`(call)

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
            resolveResults(atom ?: name, resolvedPrimaryArity, incompleteCode, element, atom != null)

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
                resolveResults(atom ?: resolvableName, arity, incompleteCode, modular, atom != null)
            }
        } else {
            emptyList()
        }
    }

    /**
     * What a walk up from [entrance] finds that declares [name], `null` being every name. A delegation starts a walk
     * again at its target's module through this function.
     */
    private fun resolveResults(
        name: String?,
        resolvedFinalArity: Int?,
        incompleteCode: Boolean,
        entrance: PsiElement,
        atom: Boolean
    ): List<VisitedElementSetResolveResult> {
        val multiResolve = MultiResolve(
            name,
            resolvedFinalArity,
            incompleteCode,
            atom && name != null,
            LegacyWalkSource::resolveResults
        )

        PsiTreeUtil.treeWalkUp(
            multiResolve,
            entrance,
            maxScope(entrance),
            ResolveState.initial()
                .put(ENTRANCE, entrance)
                .putInitialVisitedElement(entrance)
                .putAncestorUnquote(entrance)
        )

        return multiResolve.resolveResults()
    }

    @RequiresReadLock
    override fun visible(position: CompletionPosition): List<Visible> {
        ThreadingAssertions.assertReadAccess()

        return when (position) {
            // Completion offers the walk's lookups only at an unqualified call; after a qualifier it offers that module's.
            is CompletionPosition.OfCall ->
                when (position.call) {
                    is Unqualified -> visibleByWalk(position.call)
                    is Qualified -> visibleAfterQualifier(position)
                    else -> emptyList()
                }
            is CompletionPosition.AfterDot -> visibleAfterQualifier(position)
            is CompletionPosition.Capture -> visibleAfterModule(position, position.arity)
            is CompletionPosition.Mfa -> visibleAfterModule(position, null)
        }
    }

    /** What the walk finds visible at [position]. */
    private fun visibleByWalk(position: Call): List<Visible> {
        val variants = Variants()
        val entranceCallDefinitionClause = entranceCallDefinitionClause(position)
        val resolveState = ResolveState
            .initial()
            .put(ENTRANCE, position)
            .put(Variants.ENTRANCE_CALL_DEFINITION_CLAUSE, entranceCallDefinitionClause)
            .putInitialVisitedElement(position)

        if (entranceCallDefinitionClause != null) {
            resolveState.putVisitedElement(entranceCallDefinitionClause)
        }

        PsiTreeUtil.treeWalkUp(variants, position, position.containingFile, resolveState)

        return variants.visible()
    }

    private fun entranceCallDefinitionClause(entrance: Call): Call? {
        val parameter = Parameter.putParameterized(Parameter(entrance))

        return if (parameter.isCallDefinitionClauseName) {
            parameter.parameterized as Call?
        } else {
            null
        }
    }

    private fun visibleAfterQualifier(position: CompletionPosition): List<Visible> =
        position.module?.qualifierModulars()?.let(::callDefinitionClauseVisible).orEmpty()

    /** The modulars [position]'s module names while being typed, with a clause of [arity] where it is not `null`. */
    private fun visibleAfterModule(position: CompletionPosition, arity: Int?): List<Visible> =
        position.module
            ?.maybeModularNameToModulars(maxScope = position.element.containingFile, useCall = null, incompleteCode = true)
            ?.let { callDefinitionClauseVisible(it, arity) }
            .orEmpty()

    private fun found(result: VisitedElementSetResolveResult, use: Use): List<Found> =
        result.reached.mapNotNull { reached ->
            val reach = reached.reach ?: return@mapNotNull null

            applicability(reached, use)?.let { applicability ->
                val paths = reached.visitedElementSet.filterIsInstance<Call>().filter(::isPath)

                val via = paths + reached.via

                Found(
                    Candidate(reached.declaration, reach, applicability),
                    result.element,
                    via,
                    DeclaringForm.writtenHead(
                        result.element,
                        reached.declaration,
                        declaringModule(reached, reach, result.element, via),
                    ),
                    delegatedThrough = null,
                )
            }
        }

    /**
     * The name of the module that declares [reached]: a compiled one's own, and for a source one the module enclosing
     * its [element], or, for what a `use` injects, the module that holds the `use`, the first call [via] crossed.
     */
    private fun declaringModule(reached: ReachedDeclaration, reach: Reach, element: PsiElement, via: List<Call>): String? =
        when (val declared = reached.declaration.declared) {
            is Declared.Compiled -> declared.origin.module
            is Declared.Source ->
                (via.firstOrNull().takeIf { reach == Reach.USE } ?: element as? Call)
                    ?.let { CallDefinitionClause.enclosingModularMacroCall(it) }
                    ?.let { SyntacticCall.of(it).canonicalName() }
        }

    private fun applicability(reached: ReachedDeclaration, use: Use): Applicability? =
        when (use) {
            is Use.AnyName ->
                Applicability.ofAnyName(
                    reached.declaration, reached.searchedAtom, reached.admitted, use.arity, reached.via.map(::headArity)
                )
            is Use.Named -> named(reached, use.arity)
            is Use.Specified -> named(reached, use.arity)
            is Use.Tag -> named(reached, use.arity)
            is Use.Link -> named(reached, use.arity)
            is Use.Remote -> named(reached, use.arity)
        }

    /** A `null` [arity] is any arity: every declaration of the name it reaches is a candidate. */
    private fun named(reached: ReachedDeclaration, arity: Int?): Applicability? =
        if (reached.headNamed) {
            reached.searchedAtom?.let {
                val named = reached.declaration.name == it && reached.admitted
                val crossed = reached.via.map(::headArity)

                if (arity == null) {
                    Applicability.ofAnyArity(reached.declaration, named, crossed)
                } else {
                    Applicability.of(reached.declaration, it, named, arity, crossed)
                }
            }
        } else {
            null
        }

    /** The arity [delegation]'s head declares. */
    private fun headArity(delegation: Call): ArityKnowledge =
        Delegation.declaration(delegation, ResolveState.initial())?.arity ?: ArityKnowledge.Unknown
}
