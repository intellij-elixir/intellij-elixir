package org.elixir_lang.psi.scope.call_definition_clause

import org.elixir_lang.psi.scope.Reach.Companion.reachedAsDelegationTarget
import org.elixir_lang.psi.scope.Reach
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.scope.maxScope

class MultiResolve
private constructor(
        /**
         * Can be `null` when `Qualifier.unquote(variable)(...)` is used because although scope can be limited to
         * `Qualifier`, no `name` can be inferred, so all public call definition clauses in `Qualifier` should resolve,
         * but as invalid.
         */
        private val name: String?,
        /**
         * If `name` is `null`, then `resolvedPrimaryArity` must be valid or `incompleteCode` `true` or no match will be
         * found at all.
         */
        private val resolvedPrimaryArity: Int,
        private val incompleteCode: Boolean) : org.elixir_lang.psi.scope.CallDefinitionClause() {
    override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.CLAUSE, state)

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean {
        val compileTime = CallableDeclaration.capabilitiesOf(element, state)?.compileTime
        val declaration = CallableDeclaration.Declaration(element.nameArityInterval.name, element.nameArityInterval.arityInterval)

        return if (admitted(declaration, compileTime, state)) {
            addIfNameOrArityToResolveResults(element, declaration.name, accepted(declaration, compileTime, state), state)
        } else {
            true
        }
    }

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.CALLBACK, state)

    override fun executeOnDelegation(element: Call, state: ResolveState): Boolean {
        val compileTime = CallableDeclaration.Declared.Source(element, CallableDeclaration.Form.DELEGATION).capabilities?.compileTime

        // `delegationHead` reads a single head until #4040.
        CallableDeclaration.declarations(element, CallableDeclaration.Form.DELEGATION, state).firstOrNull()
            ?.takeIf { admitted(it, compileTime, state) }
            ?.let { declaration ->
                val headName = declaration.name
                val validArity = accepted(declaration, compileTime, state)

                reached(this.name, headName, validArity, incompleteCode)?.let { headValidResult ->
                    // the defdelegate is valid or invalid regardless of whether the `to:` (and `:as` resolves as
                    // `defdelegate` still defines a function in the module with the head's name and arity even if it
                    // will fail at runtime to call the delegated function
                    addToResolveResults(element, headName, headValidResult, state)

                    // A delegation reaches its target at the arity it declares, by the name it declares: `defdelegate
                    // snoc(a, b)` passes `snoc(a, b)` on, and not `sno(a, b)`, which only starts its name. One of another
                    // arity - `defdelegate snoc(a)` for a `snoc/2` call - does not pass the call on, so what it delegates
                    // to is only a candidate, added once the walk is over and found nothing valid: a valid `snoc/2`
                    // reached through it would end the walk before the delegation that does declare `snoc/2`.
                    val named = this.name == null || headName == this.name

                    if (incompleteCode || (named && validArity)) {
                        // Incomplete code lists every target, but only one the call names is a valid result.
                        addTargets(element, headName, state, candidatesOnly = !(named && validArity))
                    } else if (named) {
                        otherArityDelegations += OtherArityDelegation(element, headName, state)
                    }
                }
            }

        return keepProcessing()
    }

    override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.EEX_FUNCTION_FROM, state)

    override fun executeOnException(element: Call, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.EXCEPTION, state)

    override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.GENERATOR_EMBED, state)

    private fun addDeclarations(call: Call, form: CallableDeclaration.Form, state: ResolveState): Boolean {
        val compileTime = CallableDeclaration.Declared.Source(call, form).capabilities?.compileTime

        return whileIn(CallableDeclaration.declarations(call, form, state).filter { admitted(it, compileTime, state) }) { declaration ->
            addIfNameOrArityToResolveResults(call, declaration.name, accepted(declaration, compileTime, state), state)
        }
    }

    /** Whether an `import` this was reached through brings in [declaration] at any arity. */
    private fun admitted(declaration: CallableDeclaration.Declaration, compileTime: Boolean?, state: ResolveState): Boolean =
        Import.admits(state, declaration.name, declaration.nameArityInterval().arityInterval, compileTime)

    /** Whether [declaration] is defined, and imported if reached through an `import`, at the resolved arity. */
    private fun accepted(declaration: CallableDeclaration.Declaration, compileTime: Boolean?, state: ResolveState): Boolean =
        declaration.accepts(resolvedPrimaryArity) &&
            Import.admits(state, declaration.name, ArityInterval(resolvedPrimaryArity, resolvedPrimaryArity), compileTime)

    private fun addIfNameOrArityToResolveResults(call: Call, name: String, validArity: Boolean, state: ResolveState): Boolean =
        reached(this.name, name, validArity, incompleteCode)?.let { addToResolveResults(call, name, it, state) } ?: true

    private fun addIfNameOrArityToResolveResults(callDefinition: BeamCallDefinition,
                                                 name: String,
                                                 validArity: Boolean,
                                                 state: ResolveState) : Boolean =
        reached(this.name, name, validArity, incompleteCode)?.let { addToResolveResults(callDefinition, name, it, state) } ?: true

    private fun addTargets(delegation: Call, headName: String, delegationState: ResolveState, candidatesOnly: Boolean = false) {
        val state = delegationState.reachedAsDelegationTarget()

        for (targets in delegatedTargets(delegation, headName, resolvedPrimaryArity, incompleteCode)) {
            for ((definition, targetName, valid) in targets) {
                when (definition) {
                    is Call -> addToResolveResults(definition, targetName, valid && !candidatesOnly, state)
                    is BeamCallDefinition -> addToResolveResults(definition, targetName, valid && !candidatesOnly, state)
                }
            }

            if (!keepProcessing()) {
                break
            }
        }
    }

    private class OtherArityDelegation(val delegation: Call, val headName: String, val state: ResolveState)

    private val otherArityDelegations = mutableListOf<OtherArityDelegation>()

    override fun keepProcessing(): Boolean = resolveResultOrderedSet.keepProcessing(incompleteCode)

    fun resolveResults(): List<VisitedElementSetResolveResult> {
        for (other in otherArityDelegations) {
            if (!keepProcessing()) break

            addTargets(other.delegation, other.headName, other.state, candidatesOnly = true)
        }
        otherArityDelegations.clear()

        return resolveResultOrderedSet.toList()
    }

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    private fun addToResolveResults(call: Call, name: String, validResult: Boolean, state: ResolveState): Boolean =
            (call as? Named)?.nameIdentifier?.let { nameIdentifier ->
                if (PsiTreeUtil.isAncestor(state.get(ENTRANCE), nameIdentifier, false)) {
                    resolveResultOrderedSet.add(call, name, validResult, emptySet(), Reach.of(call, state))
                } else {
                    resolveResultOrderedSet.add(call, name, validResult, state.visitedElementSet(), Reach.of(call, state))
                }

                keepProcessing()
            } ?: true

    private fun addToResolveResults(callDefinition: BeamCallDefinition,
                                    name: String,
                                    validResult: Boolean,
                                    state: ResolveState): Boolean {
        resolveResultOrderedSet.add(callDefinition, name, validResult, state.visitedElementSet(), Reach.of(callDefinition, state))

        return keepProcessing()
    }

    companion object {
        /**
         * Whether a use of [name] reaches a declaration of [declared], and if so as a valid result: `null` when it does not
         * reach it; a name the use only starts is a candidate, never valid; and a use naming nothing - `Mod.unquote(f)()`,
         * or completion with a `null` [name] - reaches what fits its arity, or with [incompleteCode] everything, as
         * candidates.
         */
        fun reached(name: String?, declared: String, validArity: Boolean, incompleteCode: Boolean): Boolean? =
            if (if (name == null) incompleteCode || validArity else declared.startsWith(name)) {
                validArity && declared == name
            } else {
                null
            }

        /** A definition a `defdelegate` delegates to: what it is, the name it has there, and whether it fits the arity. */
        data class DelegatedTarget(val definition: PsiElement, val name: String, val isValid: Boolean)

        /**
         * What [delegation] delegates to at [arity], one list per module its `to:` names: [headName], or its `as:`
         * name, defined there. Lazy, so a caller can stop at the first module that resolves. The walk starts at that
         * module, so it does not depend on where the delegation is written.
         */
        @JvmStatic
        fun delegatedTargets(delegation: Call, headName: String, arity: Int, incompleteCode: Boolean): Sequence<List<DelegatedTarget>> {
            val definingModuleName = delegation.keywordArgument("to") ?: return emptySequence()
            // An `as:` that names nothing fixed targets nothing.
            val nameInDefiningModule = CallableDeclaration.delegatedName(delegation, headName) ?: return emptySequence()

            return definingModuleName
                .maybeModularNameToModulars(delegation.containingFile, useCall = null, incompleteCode = incompleteCode)
                .asSequence()
                .map { modular ->
                    // Call recursively to get all the proper `for` and `use` handling.
                    resolveResults(nameInDefiningModule, arity, incompleteCode, modular).mapNotNull { result ->
                        // Anything but a source or compiled definition is not something a delegation can target.
                        result.element
                            .takeIf { it is Call || it is BeamCallDefinition }
                            ?.let { DelegatedTarget(it, nameInDefiningModule, result.isValidResult) }
                    }
                }
        }

        @JvmOverloads
        @JvmStatic
        fun resolveResults(name: String?,
                           resolvedFinalArity: Int,
                           incompleteCode: Boolean,
                           entrance: PsiElement,
                           resolveState: ResolveState = ResolveState.initial()): List<VisitedElementSetResolveResult> {
            val multiResolve = MultiResolve(name, resolvedFinalArity, incompleteCode)
            val maxScope = maxScope(entrance)

            val entranceResolveState = resolveState
                    .put(ENTRANCE, entrance)
                    .putInitialVisitedElement(entrance)
                    .putAncestorUnquote(entrance)

            PsiTreeUtil.treeWalkUp(
                    multiResolve,
                    entrance,
                    maxScope,
                    entranceResolveState
            )

            return multiResolve.resolveResults()
        }
    }
}
