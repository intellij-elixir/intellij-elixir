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
            ?.let { declaration ->
                val headName = declaration.name
                val validArity = declaration.arityInterval?.let { resolvedPrimaryArity in it } ?: false

                if ((this.name == null && (incompleteCode || validArity)) ||
                        (this.name != null && headName.startsWith(this.name))) {
                    val headValidResult = validArity && headName == this.name

                    // the defdelegate is valid or invalid regardless of whether the `to:` (and `:as` resolves as
                    // `defdelegate` still defines a function in the module with the head's name and arity even if it
                    // will fail at runtime to call the delegated function
                    addToResolveResults(element, headName, headValidResult, state)

                    element.keywordArgument("to")?.let { definingModuleName ->
                        val modulars = definingModuleName.maybeModularNameToModulars(element.containingFile, useCall = null, incompleteCode = incompleteCode)

                        val nameInDefiningModule = nameInDefiningModule(element, headName)

                        if (modulars.isNotEmpty() && nameInDefiningModule != null) {
                            for (modular in modulars) {
                                // Call recursively to get all the proper `for` and `use` handling.
                                val modularResolveResults = resolveResults(nameInDefiningModule, resolvedPrimaryArity, incompleteCode, modular)

                                for (modularResultResult in modularResolveResults) {
                                    when (val modularResultResultElement = modularResultResult.element) {
                                        is Call -> addToResolveResults(
                                            modularResultResultElement,
                                            nameInDefiningModule,
                                            modularResultResult.isValidResult,
                                            state
                                        )
                                        is BeamCallDefinition -> addToResolveResults(
                                            modularResultResultElement,
                                            nameInDefiningModule,
                                            modularResultResult.isValidResult,
                                            state
                                        )
                                        // Anything else is not a definition a delegation can target.
                                        else -> Unit
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
                val validArity = declaration.arityInterval?.let { resolvedPrimaryArity in it } ?: false

                addIfNameOrArityToResolveResults(call, declaration.name, validArity, state)
            }

    private fun addIfNameOrArityToResolveResults(callDefinition: BeamCallDefinition,
                                                 nameArityInterval: NameArityInterval,
                                                 state: ResolveState): Boolean {
        val name = nameArityInterval.name
        val validArity = resolvedPrimaryArity in nameArityInterval.arityInterval

        return addIfNameOrArityToResolveResults(callDefinition, name, validArity, state)
    }

    private fun addIfNameOrArityToResolveResults(call: Call, name: String, validArity: Boolean, state: ResolveState): Boolean =
            if ((this.name == null && (incompleteCode || validArity)) ||
                    (this.name != null && name.startsWith(this.name))) {
                val validResult = validArity && name == this.name

                addToResolveResults(call, name, validResult, state)
            } else {
                true
            }

    private fun addIfNameOrArityToResolveResults(callDefinition: BeamCallDefinition,
                                                 name: String,
                                                 validArity: Boolean,
                                                 state: ResolveState) : Boolean =
        if ((this.name == null && (incompleteCode || validArity)) ||
            (this.name != null && name.startsWith(this.name))) {
            val validResult = validArity && name == this.name

            addToResolveResults(callDefinition, name, validResult, state)
        } else {
            true
        }

    override fun keepProcessing(): Boolean = resolveResultOrderedSet.keepProcessing(incompleteCode)
    fun resolveResults(): List<VisitedElementSetResolveResult> = resolveResultOrderedSet.toList()

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    private fun addToResolveResults(call: Call, name: String, validResult: Boolean, state: ResolveState): Boolean =
            (call as? Named)?.nameIdentifier?.let { nameIdentifier ->
                if (PsiTreeUtil.isAncestor(state.get(ENTRANCE), nameIdentifier, false)) {
                    resolveResultOrderedSet.add(call, name, validResult, emptySet())
                } else {
                    resolveResultOrderedSet.add(call, name, validResult, state.visitedElementSet())
                }

                keepProcessing()
            } ?: true

    private fun addToResolveResults(callDefinition: BeamCallDefinition,
                                    name: String,
                                    validResult: Boolean,
                                    state: ResolveState): Boolean {
        resolveResultOrderedSet.add(callDefinition, name, validResult, state.visitedElementSet())

        return keepProcessing()
    }

    companion object {
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

/** The `as:` name, the head's when there is no `as:`, or `null` when `as:` names nothing fixed - which targets nothing. */
private fun nameInDefiningModule(delegation: Call, headName: String): String? =
    when (val asArgument = delegation.keywordArgument("as")) {
        null -> headName
        else -> (asArgument as? ElixirAtom)?.literalName()
    }
