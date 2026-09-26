package org.elixir_lang.psi.scope.call_definition_clause

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
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.scope.maxScope

class MultiResolve
private constructor(
        /**
         * Already [NameMatch.query]-normalized. Can be `null` when `Qualifier.unquote(variable)(...)` is used
         * because although scope can be limited to `Qualifier`, no `name` can be inferred, so all public call
         * definition clauses in `Qualifier` should resolve, but as invalid.
         */
        private val name: String?,
        /**
         * If `name` is `null`, then `resolvedPrimaryArity` must be valid or `incompleteCode` `true` or no match will be
         * found at all.
         */
        private val resolvedPrimaryArity: Int,
        private val incompleteCode: Boolean) : org.elixir_lang.psi.scope.CallDefinitionClause() {
    override fun targetName(): String? = name

    override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.CLAUSE, state)

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean =
        addIfNameOrArityToResolveResults(element, element.nameArityInterval, state)

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean =
            addDeclarations(element, CallableDeclaration.Form.CALLBACK, state)

    override fun executeOnDelegation(element: Call, state: ResolveState): Boolean {
        // firstOrNull, not singleOrNull: delegationHead is single-head only today, but a second declaration
        // must not make this silently resolve to nothing once that widens (#4040).
        CallableDeclaration.declarations(element, CallableDeclaration.Form.DELEGATION, state).firstOrNull()
            ?.let { declaration -> declaration.arityInterval?.let { NameArityInterval(declaration.name, it) } }
            ?.let { headNameArityInterval ->
                val headName = headNameArityInterval.name
                val nameMatch = nameMatch(headName, CallableDeclaration.delegationHead(element) ?: element)
                val validArity = resolvedPrimaryArity in headNameArityInterval.arityInterval

                if (isCandidate(nameMatch, validArity)) {
                    val headValidResult = validArity && nameMatch == NameMatch.EXACT

                    // the defdelegate is valid or invalid regardless of whether the `to:` (and `:as` resolves as
                    // `defdelegate` still defines a function in the module with the head's name and arity even if it
                    // will fail at runtime to call the delegated function
                    addToResolveResults(element, headNameArityInterval, nameMatch, headValidResult, state)

                    element.keywordArgument("to")?.let { definingModuleName ->
                        val modulars = definingModuleName.maybeModularNameToModulars(element.containingFile, useCall = null, incompleteCode = incompleteCode)

                        val nameInDefiningModule = nameInDefiningModule(element, headName)

                        if (modulars.isNotEmpty() && nameInDefiningModule != null) {
                            for (modular in modulars) {
                                // Call recursively to get all the proper `for` and `use` handling. The name is
                                // written in this module, so it is normalized at this module's language level.
                                val modularResolveResults = resolveResults(
                                    nameInDefiningModule,
                                    resolvedPrimaryArity,
                                    incompleteCode,
                                    modular,
                                    querySite = element
                                )

                                // A target is reachable only as the head declares it - under the head's name
                                // and arities, and valid only if the head is - so `as:` renames it, a prefix of
                                // the head reaches nothing, and a default argument in the target widens nothing.
                                for (modularResolveResult in modularResolveResults) {
                                    val validResult = headValidResult && modularResolveResult.isValidResult

                                    when (modularResolveResult) {
                                        is CallDefinitionResolveResult ->
                                            if (modularResolveResult.nameArityInterval.arityInterval.overlaps(headNameArityInterval.arityInterval)) {
                                                addToResolveResults(
                                                    modularResolveResult.element,
                                                    headNameArityInterval,
                                                    nameMatch,
                                                    validResult,
                                                    state,
                                                    nameInDefiningModule
                                                )
                                            }
                                        // an `EEx` function whose arity cannot be known
                                        else -> (modularResolveResult.element as? Call)?.let { call ->
                                            addToResolveResults(call, nameInDefiningModule, validResult, state)
                                        }
                                    }
                                }

                                if (!keepProcessing()) {
                                    break
                                }
                            }
                        }
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

    private fun addDeclarations(call: Call, form: CallableDeclaration.Form, state: ResolveState): Boolean =
            whileIn(CallableDeclaration.declarations(call, form, state)) { declaration ->
                val arityInterval = declaration.arityInterval

                if (arityInterval != null) {
                    addIfNameOrArityToResolveResults(call, NameArityInterval(declaration.name, arityInterval), state)
                } else if (isCandidate(nameMatch(declaration.name, call), validArity = false)) {
                    // The call does not say its arity (`EEx.function_from_string` given `@args`), so there is no
                    // interval to record, and a call can only ever be checked against it as invalid.
                    addToResolveResults(call, declaration.name, validResult = false, state)
                } else {
                    true
                }
            }

    /** [element] declares [nameArityInterval]: add it if its name, or with no name to match its arity, fits. */
    private fun addIfNameOrArityToResolveResults(element: PsiElement,
                                                 nameArityInterval: NameArityInterval,
                                                 state: ResolveState): Boolean {
        val nameMatch = nameMatch(nameArityInterval.name, element)
        val validArity = resolvedPrimaryArity in nameArityInterval.arityInterval

        return if (isCandidate(nameMatch, validArity)) {
            val validResult = validArity && nameMatch == NameMatch.EXACT

            addToResolveResults(element, nameArityInterval, nameMatch, validResult, state)
        } else {
            true
        }
    }

    /** How [candidate], declared at [element], matches this walk's name; [NameMatch.NONE] when it has no name. */
    private fun nameMatch(candidate: String, element: PsiElement): NameMatch =
        name?.let { NameMatch.of(it, candidate, element) } ?: NameMatch.NONE

    private fun isCandidate(nameMatch: NameMatch, validArity: Boolean): Boolean =
        if (name == null) incompleteCode || validArity else admits(nameMatch)

    /**
     * A definition the name only starts is a completion candidate, never a declaration of the name, so complete
     * resolution keeps exact names only and nothing downstream has to filter them out again.
     */
    private fun admits(nameMatch: NameMatch): Boolean =
        nameMatch == NameMatch.EXACT || (incompleteCode && nameMatch == NameMatch.PREFIX)

    override fun keepProcessing(): Boolean = resolveResultOrderedSet.keepProcessing(incompleteCode)
    fun resolveResults(): List<VisitedElementSetResolveResult> = resolveResultOrderedSet.toList()

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    private fun addToResolveResults(element: PsiElement,
                                    nameArityInterval: NameArityInterval,
                                    nameMatch: NameMatch,
                                    validResult: Boolean,
                                    state: ResolveState,
                                    name: String = nameArityInterval.name): Boolean =
        visitedElementSet(element, state)?.let { visitedElementSet ->
            resolveResultOrderedSet.add(
                CallDefinitionResolveResult(element, validResult, visitedElementSet, nameArityInterval, nameMatch),
                name
            )

            keepProcessing()
        } ?: true

    /** For an `EEx` function whose arity cannot be known, so there is no interval to record, directly or delegated. */
    private fun addToResolveResults(call: Call, name: String, validResult: Boolean, state: ResolveState): Boolean =
        visitedElementSet(call, state)?.let { visitedElementSet ->
            resolveResultOrderedSet.add(call, name, validResult, visitedElementSet)

            keepProcessing()
        } ?: true

    /** `null` for a source [element] with no name identifier, which cannot be a result. */
    private fun visitedElementSet(element: PsiElement, state: ResolveState): Set<PsiElement>? =
        when (element) {
            is BeamCallDefinition -> state.visitedElementSet()
            else -> (element as? Named)?.nameIdentifier?.let { nameIdentifier ->
                if (PsiTreeUtil.isAncestor(state.get(ENTRANCE), nameIdentifier, false)) {
                    emptySet()
                } else {
                    state.visitedElementSet()
                }
            }
        }

    companion object {
        /**
         * @param name normalized here, once, so every entry into the walk compares the same way
         * @param querySite where [name] is written, whose language level decides how it normalizes; a qualified
         * call enters at the module it names, which may be compiled under a different one
         */
        @JvmOverloads
        @JvmStatic
        fun resolveResults(name: String?,
                           resolvedFinalArity: Int,
                           incompleteCode: Boolean,
                           entrance: PsiElement,
                           resolveState: ResolveState = ResolveState.initial(),
                           querySite: PsiElement = entrance): List<VisitedElementSetResolveResult> {
            val multiResolve = MultiResolve(name?.let { NameMatch.query(it, querySite) }, resolvedFinalArity, incompleteCode)
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

/** The `as:` name, the head's when there is no `as:`, or `null` when `as:` names nothing fixed - which targets nothing. */
private fun nameInDefiningModule(delegation: Call, headName: String): String? =
    when (val asArgument = delegation.keywordArgument("as")) {
        null -> headName
        else -> (asArgument as? ElixirAtom)?.literalName()
    }
