package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.impl.call.stabBodyChildExpressions
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.util.AccumulatorContinue

object Using {
    @RequiresReadLock
    fun treeWalkUp(
        using: PsiElement,
        use: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        when (using) {
            is Call -> treeWalkUp(using, use, resolveState, keepProcessing)
            /* Unreachable while `isDefiner` compares a decompiled definition's always-null `name`, which leaves
               `definers` empty for a beam module. Giving `CallDefinitionImpl` a `getName()` arms this, so the two
               have to change together. */
            is BeamCallDefinition -> TODO()
            else -> true
        }

    private fun treeWalkUp(
        using: Call,
        use: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        // `forward = false`: the first element is the last statement of the `do` block.
        val statements = using.stabBodyChildExpressions(forward = false)?.toList() ?: return true

        return when (val last = statements.firstOrNull()?.stripAccessExpression()) {
            // `[conditional, imports]` - the fragments a `__using__` splices (Phoenix.Component.__using__/1). Read
            // via `childExpressions`, not `unmatchedExpressionList`, which omits a nested container's accessExpression.
            is ElixirList ->
                treeWalkUpFragments(last, last.childExpressions().toList(), use, resolveState, keepProcessing)
            // `{conditional, imports}` returned bare; without its own arm the `else` reads an earlier statement
            is ElixirTuple -> treeWalkUpValue(last, use, resolveState, keepProcessing)
            // a `__using__` returns its last statement's value, so a match that raises defines nothing in the caller
            is Match ->
                !Destructure.matches(last.leftOperand(), last.rightOperand()) ||
                        treeWalkUpValue(last.rightOperand(), use, resolveState, keepProcessing)
            else ->
                statements
                    .filterIsInstance<Call>()
                    .firstOrNull()
                    ?.let { treeWalkUpValue(it, use, resolveState, keepProcessing) }
                    ?: true
        }
    }

    /** A variable element resolves to its `pattern = value` bindings; anything else is walked as is. */
    private fun treeWalkUpListElement(
        element: PsiElement,
        use: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        val boundValues = ((element as? Call)?.reference as? PsiPolyVariantReference)
            ?.multiResolve(false)
            ?.filter { it.isValidResult }
            ?.mapNotNull { it.element }
            ?.flatMap { declaration -> boundValues(declaration) }
            .orEmpty()

        return if (boundValues.isEmpty()) {
            treeWalkUpValue(element, use, resolveState, keepProcessing)
        } else {
            whileIn(boundValues) { value -> treeWalkUpValue(value, use, resolveState, keepProcessing) }
        }
    }

    /**
     * The values the enclosing `pattern = value` binds [declaration] to, however deeply the pattern destructures it.
     */
    private fun boundValues(declaration: PsiElement): List<PsiElement> =
        PsiTreeUtil
            .getParentOfType(declaration, Match::class.java)
            ?.let { match -> Destructure.valuesAt(match.leftOperand(), match.rightOperand(), declaration) }
            .orEmpty()

    private fun treeWalkUpValue(
        value: PsiElement?,
        use: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        when (val stripped = value?.stripAccessExpression()) {
            is Call ->
                stripped
                    .takeUnlessHasBeenVisited(resolveState)
                    ?.let { call -> treeWalkUpFromLastChildCall(call, use, resolveState, keepProcessing) }
                    ?: true
            is ElixirList ->
                treeWalkUpFragments(stripped, stripped.childExpressions().toList(), use, resolveState, keepProcessing)
            is ElixirTuple -> {
                val fragments = stripped.childExpressions().toList()

                // Only a two-element tuple is a quoted literal. `{:__block__, [], [fragment]}` splices too, but
                // that needs the node's shape read rather than its size, and is not modelled.
                if (fragments.size == 2) {
                    treeWalkUpFragments(stripped, fragments, use, resolveState, keepProcessing)
                } else {
                    true
                }
            }
            else -> true
        }

    /**
     * Each element of [container] read as a fragment. Marked visited first: [Destructure] can answer a container
     * again, so only the resolver's earlier-binding rule would otherwise stop that arriving back here.
     */
    private fun treeWalkUpFragments(
        container: PsiElement,
        fragments: List<PsiElement>,
        use: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        container
            .takeUnlessHasBeenVisited(resolveState)
            ?.let { entered ->
                val enteredResolveState = resolveState.putVisitedElement(entered)

                whileIn(fragments) { fragment ->
                    treeWalkUpListElement(fragment, use, enteredResolveState, keepProcessing)
                }
            }
            ?: true

    private fun treeWalkUpFromLastChildCall(
        lastChildCall: Call,
        useCall: Call?,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        val resolvedModuleName = lastChildCall.resolvedModuleName()
        val functionName = lastChildCall.functionName()

        return if (resolvedModuleName != null && functionName != null) {
            when {
                resolvedModuleName == KERNEL && functionName == QUOTE -> {
                    QuoteMacro.treeWalkUp(lastChildCall, resolveState, keepProcessing)
                }

                resolvedModuleName == KERNEL && functionName == APPLY -> {
                    lastChildCall.finalArguments()?.let { arguments ->
                        // TODO pipelines to apply/3
                        if (arguments.size == 3) {
                            arguments[0].let { maybeModularName ->
                                val modulars = maybeModularName.maybeModularNameToModulars(
                                    maxScope = maybeModularName.containingFile,
                                    useCall = useCall,
                                    incompleteCode = false
                                )

                                var accumlatedKeepProcessing = true

                                for (modular in modulars) {
                                    val modularResolveState = resolveState.putVisitedElement(modular)

                                    val name = useCall?.finalArguments()?.let { arguments ->
                                        if (arguments.size == 2) {
                                            when (val which = arguments[1].stripAccessExpression()) {
                                                is ElixirAtom -> if (which.line == null) {
                                                    which.lastChild.text
                                                } else {
                                                    null
                                                }
                                                else -> null
                                            }
                                        } else {
                                            null
                                        }
                                    }

                                    accumlatedKeepProcessing = if (name != null) {
                                        Modular.callDefinitionClauseCallFoldWhile(
                                            modular,
                                            name,
                                            modularResolveState
                                        ) { callDefinitionClauseCall, _, _, accResolveState ->
                                            val finalContinue = treeWalkUp(
                                                callDefinitionClauseCall,
                                                useCall,
                                                accResolveState,
                                                keepProcessing
                                            )
                                            AccumulatorContinue(accResolveState, finalContinue)
                                        }.`continue`
                                    } else {
                                        Modular.callDefinitionClauseCallWhile(
                                            modular,
                                            modularResolveState
                                        ) { callDefinitionClauseCall, accResolveState ->
                                            if (CallableDeclaration.definerOf(callDefinitionClauseCall)?.capabilities?.remoteCallable == true) {
                                                treeWalkUp(
                                                    callDefinitionClauseCall,
                                                    useCall,
                                                    accResolveState,
                                                    keepProcessing
                                                )
                                            } else {
                                                true
                                            }
                                        }
                                    }
                                }

                                accumlatedKeepProcessing
                            }
                        } else {
                            true
                        }
                    } ?: true
                }
                resolvedModuleName == KERNEL && functionName == CASE -> {
                    val lastChildCallResolveState = resolveState.putVisitedElement(lastChildCall)

                    Case.treeWalkUp(lastChildCall, lastChildCallResolveState, keepProcessing)
                }
                else -> {
                    var accumulatedKeepProcessing = true

                    for (reference in lastChildCall.references) {
                        val resolvedList: List<PsiElement> = if (reference is PsiPolyVariantReference) {
                            reference
                                .multiResolve(false)
                                .mapNotNull { it.element }
                        } else {
                            reference.resolve()?.let { listOf(it) } ?: emptyList()
                        }.filter { !resolveState.hasBeenVisited(it) }

                        for (resolved in resolvedList) {
                            accumulatedKeepProcessing = if (resolved is Call && CallDefinitionClause.`is`(resolved)) {
                                val resolvedResolveSet = resolveState.putVisitedElement(resolved)

                                treeWalkUp(
                                    using = resolved,
                                    use = useCall,
                                    resolveState = resolvedResolveSet,
                                    keepProcessing = keepProcessing
                                )
                            } else {
                                true
                            }

                            if (!accumulatedKeepProcessing) {
                                break
                            }
                        }

                        if (!accumulatedKeepProcessing) {
                            break
                        }
                    }

                    accumulatedKeepProcessing
                }
            }
        } else {
            true
        }
    }

    @RequiresReadLock
    fun definers(modular: PsiElement): Sequence<PsiElement> =
        when (modular) {
            is Call -> definers(modular)
            is BeamModule -> definers(modular)
            else -> emptySequence()
        }

    @RequiresReadLock
    fun definers(modularCall: Call): Sequence<Call> =
        modularCall
            .macroChildCallSequence()
            .filter { isDefiner(it) }

    fun definers(moduleImpl: BeamModule): Sequence<BeamCallDefinition> =
        moduleImpl
            .callDefinitions()
            .asSequence()
            .filter { isDefiner(it) }

    /**
     * Returns true if [modular] is `ExUnit.CaseTemplate` itself or a module that (directly or
     * transitively) uses `ExUnit.CaseTemplate`.
     *
     * This is needed because `ExUnit.CaseTemplate.__using__/1` generates a `defmacro __using__`
     * inside the target module at *compile time*.  That generated macro never appears in the
     * module's source `.ex` file, so [definers] returns an empty sequence for such modules and the
     * scope walker gives up without importing `describe`, `test`, etc.  Detecting the CaseTemplate
     * pattern lets [Use.treeWalkUp] substitute `ExUnit.Case.__using__/1` directly instead.
     *
     * Cycle-safety is provided by [resolveState]'s visited-element set.
     */
    @RequiresReadLock
    fun isCaseTemplate(modular: PsiElement, resolveState: ResolveState): Boolean {
        if (resolveState.hasBeenVisited(modular)) return false
        return when (modular) {
            is BeamModule -> modular.name == EXUNIT_CASE_TEMPLATE
            is Call -> {
                val updatedState = resolveState.putVisitedElement(modular)
                modular.name == EXUNIT_CASE_TEMPLATE ||
                modular.macroChildCallSequence()
                    .filter { Use.`is`(it) }
                    .any { useCall ->
                        Use.modulars(useCall).any { inner ->
                            isCaseTemplate(inner, updatedState)
                        }
                    }
            }
            else -> false
        }
    }

    /**
     * Locates the `__using__/1` definers of `ExUnit.Case` via the stub index.
     *
     * Used as a substitute in [Use.treeWalkUp] when the target module is a CaseTemplate: because the
     * generated `__using__` is not in the source PSI and the `unquote(__MODULE__).__proxy__` call inside
     * it cannot be statically resolved, we skip the opaque generated layer and go straight to
     * `ExUnit.Case.__using__/1`, which is what every CaseTemplate chain ultimately delegates to.
     */
    @RequiresReadLock
    fun exUnitCaseDefiners(context: PsiElement): Sequence<PsiElement> =
        StubIndex
            .getElements(
                ModularName.KEY,
                EXUNIT_CASE,
                context.project,
                GlobalSearchScope.allScope(context.project),
                NamedElement::class.java
            )
            .asSequence()
            .flatMap { definers(it) }

    private const val EXUNIT_CASE = "ExUnit.Case"
    private const val EXUNIT_CASE_TEMPLATE = "ExUnit.CaseTemplate"
    private const val ARITY = 1
    private const val USING = "__using__"

    private fun isDefiner(call: Call): Boolean =
        CallableDeclaration.definerOf(call) == CallableDeclaration.Definer.DEFMACRO &&
                nameArityInterval(call, ResolveState.initial())?.let { nameArityRange ->
                    nameArityRange.name == USING && nameArityRange.arityInterval.contains(ARITY)
                }
                ?: false

    private fun isDefiner(callDefinitionImpl: BeamCallDefinition): Boolean =
        callDefinitionImpl.name == USING &&
                callDefinitionImpl.exportedArity(ResolveState.initial()) == ARITY &&
                CallableDeclaration.isCompileTime(callDefinitionImpl)
}
