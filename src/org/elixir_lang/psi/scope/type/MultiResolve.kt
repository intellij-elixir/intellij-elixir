package org.elixir_lang.psi.scope.type

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.beam.psi.TypeDefinition as BeamTypeDefinition
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.*
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.structure_view.element.Type as TypeElement

class MultiResolve
private constructor(private val name: String,
                    private val arity: Int,
                    private val incompleteCode: Boolean) : org.elixir_lang.psi.scope.Type() {
    override fun executeOnType(definition: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean =
            typeHead(definition)
                    ?.let { executeOnTypeHead(definition, it, state) }
                    ?: true

    private fun executeOnTypeHead(definition: Call, typeHead: Call, state: ResolveState): Boolean {
        // Only `@type`/`@typep`/`@opaque` heads declare type variables. In `@spec`/`@callback`/`@macrocallback`
        // heads the arguments are type usages (references to real types or `when`-bound variables), so processing
        // them as declarations would wrongly self-resolve a bare name like `config` in `@spec check(config, opts)`
        // and short-circuit resolution before reaching the actual `@type config` or `when` binding.
        val argumentsKeepProcessing =
            if (TypeElement.`is`(definition)) executeOnTypeHeadArguments(typeHead, state) else true

        return argumentsKeepProcessing && executeOnTypeHeadName(definition, typeHead, state)
    }

    private fun executeOnTypeHeadArguments(typeHead: Call, state: ResolveState): Boolean =
            typeHead.finalArguments()?.let { executeOnTypeHeadArguments(it, state) }
                    ?: true

    private fun executeOnTypeHeadArguments(arguments: Array<PsiElement>, state: ResolveState): Boolean =
            whileIn(arguments) {
                executeOnParameter(it, state)
            }

    private fun executeOnTypeHeadName(definition: Call, typeHead: Call, state: ResolveState): Boolean =
            typeHead.functionName()?.let { name ->
                val nameMatch = NameMatch.of(this.name, name, typeHead)

                if (nameMatch != NameMatch.NONE) {
                    val arity = typeHead.resolvedFinalArity()
                    val validResult = nameMatch == NameMatch.EXACT && arity == this.arity

                    resolveResultOrderedSet.add(definition, typeHead.text, validResult, state.visitedElementSet())

                    keepProcessing()
                } else {
                    null
                }
            } ?: true

    override fun execute(typeDefinition: BeamTypeDefinition, state: ResolveState): Boolean {
        val name = typeDefinition.name
        val nameMatch = NameMatch.of(this.name, name, typeDefinition)

        return if (nameMatch != NameMatch.NONE) {
            val arity = typeDefinition.arity
            val validResult = nameMatch == NameMatch.EXACT && arity == this.arity

            resolveResultOrderedSet.add(typeDefinition, "$name/$arity", validResult, state.visitedElementSet())

            keepProcessing()
        } else {
            true
        }
    }

    override fun executeOnParameter(parameter: PsiElement, state: ResolveState): Boolean {
        // The left side of a `name :: type` annotation (e.g. the `my_id` in `@spec do_it(my_id :: my_id())`) is a
        // label for the type on its right, not a type-variable declaration. Registering it would shadow a real
        // `@type`/`@spec ... when` binding of the same name and short-circuit resolution, so skip it here.
        if (parameter.isNamedTypeLabelOperand()) return true

        return when (parameter) {
                is ElixirAccessExpression -> executeOnParameter(parameter.stripAccessExpression(), state)
                is ElixirParentheticalStab ->
                    parameter.stab?.stabOperationList?.singleOrNull()
                            ?.leftOperand().let { it as? ElixirStabNoParenthesesSignature }
                            ?.noParenthesesArguments?.noParenthesesOneArgument
                            ?.children
                            ?.let { anonymousFunctionParameters ->

                                whileIn(anonymousFunctionParameters) {
                                    executeOnParameter(it, state)
                                }
                            } ?: true
                is UnqualifiedNoArgumentsCall<*> -> executeOnParameter(parameter, parameter.name, state)
                // A qualified type cannot be declared here
                is Qualified,
                // Parenthetical calls must be a usage instead of a declaration.
                is UnqualifiedParenthesesCall<*>,
                // Literals
                is QualifiableAlias,
                // -1 and other negative integer literals
                is UnaryOperation,
                is ElixirAtom,
                is ElixirAtomKeyword,
                is ElixirBitString,
                is ElixirDecimalWholeNumber,
                is ElixirList,
                is ElixirMapOperation,
                is ElixirTuple -> true
                is ElixirKeywordKey -> executeOnParameter(parameter, parameter.text, state)
                is Pipe -> (parameter.leftOperand()?.let { executeOnParameter(it, state) } ?: true)
                        && (parameter.rightOperand()?.let { executeOnParameter(it, state) } ?: true)
                // putting defaults in type specs isn't valid, but it can occur when copying the def to write the type
                is InMatch -> {
                    val nameElement = parameter.leftOperand()

                    if (nameElement != null) {
                        executeOnParameter(nameElement, state)
                    } else {
                        true
                    }
                }
                is Ternary ->
                    (parameter.leftOperand()?.let { executeOnParameter(it, state) } ?: true) &&
                            (parameter.rightOperand()?.let { executeOnParameter(it, state) } ?: true)
                is Type -> {
                    val nameElement = parameter.leftOperand()

                    if (nameElement != null) {
                        executeOnParameter(nameElement, state)
                    } else {
                        true
                    }
                }
                is Two ->
                    (parameter.leftOperand()?.let { executeOnParameter(it, state) } ?: true) &&
                            (parameter.rightOperand()?.let { executeOnParameter(it, state) } ?: true)
                is QuotableKeywordList -> {
                    whileIn(parameter.quotableKeywordPairList()) { keywordPair ->
                        executeOnParameter(keywordPair.keywordValue, state)
                    }
                }
                is ElixirStructOperation -> {
                    whileIn(parameter.children.drop(1)) { child ->
                        executeOnParameter(child, state)
                    }
                }
                is ElixirMapArguments -> {
                    parameter.mapConstructionArguments?.let { executeOnParameter(it, state) } ?: true
                }
                is ElixirMapConstructionArguments -> {
                    whileIn(parameter.children) { child ->
                        executeOnParameter(child, state)
                    }
                }
                is ElixirAssociations -> {
                    whileIn(parameter.associationsBase.children) { child ->
                        executeOnParameter(child, state)
                    }
                }
                is ElixirVariable -> executeOnParameter(parameter, parameter.name, state)
                // Anything else names no type variable; keep walking.
                else -> true
            }
    }

    /**
     * Whether this element is the left-hand label of a `name :: type` annotation (e.g. `my_id` in `my_id :: my_id()`).
     * Such a label names the type on its right rather than declaring a type variable, so it must not be registered as
     * a resolvable declaration.
     */
    private fun PsiElement.isNamedTypeLabelOperand(): Boolean {
        val typeOperation = PsiTreeUtil.getParentOfType(this, Type::class.java) ?: return false
        return typeOperation.leftOperand()?.stripAccessExpression() === this
    }

    private fun executeOnParameter(parameter: PsiElement, name: String?, state: ResolveState): Boolean {
        val nameMatch = name?.let { NameMatch.of(this.name, it, parameter) } ?: NameMatch.NONE

        return if (this.arity == 0 && nameMatch != NameMatch.NONE) {
            resolveResultOrderedSet.add(parameter, name!!, nameMatch == NameMatch.EXACT, state.visitedElementSet())

            keepProcessing()
        } else {
            true
        }
    }

    override fun keepProcessing(): Boolean = resolveResultOrderedSet.keepProcessing(incompleteCode)

    private fun resolveResults(): List<ResolveResult> = resolveResultOrderedSet.toList()
    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    companion object {
        /** @param querySite where [name] is written, whose language level decides how it normalizes */
        fun resolveResults(
            name: String,
            resolvedFinalArity: Int,
            incompleteCode: Boolean,
            entrance: PsiElement,
            resolveState: ResolveState = ResolveState.initial(),
            querySite: PsiElement = entrance
        ): List<ResolveResult> {
            val multiResolve = MultiResolve(NameMatch.query(name, querySite), resolvedFinalArity, incompleteCode)
            val maxScope = entrance.containingFile
            val entranceResolveState = resolveState.put(ElixirPsiImplUtil.ENTRANCE, entrance).putInitialVisitedElement(entrance)

            if (entrance is BeamModule) {
                multiResolve.execute(entrance, entranceResolveState)
            } else {
                PsiTreeUtil.treeWalkUp(multiResolve, entrance, maxScope, entranceResolveState)
            }

            return multiResolve.resolveResults()
        }

        private tailrec fun typeHead(typeSpec: PsiElement): Call? =
            when (typeSpec) {
                is AtUnqualifiedNoParenthesesCall<*> -> {
                    val finalArgument = typeSpec.finalArguments()?.singleOrNull()

                    if (finalArgument != null) {
                        typeHead(finalArgument)
                    } else {
                        null
                    }
                }
                is Type -> typeSpec.leftOperand() as? Call
                is When -> {
                    val leftOperand = typeSpec.leftOperand()

                    if (leftOperand != null) {
                        typeHead(leftOperand)
                    } else {
                        null
                    }
                }
                else -> null
            }
    }
}
