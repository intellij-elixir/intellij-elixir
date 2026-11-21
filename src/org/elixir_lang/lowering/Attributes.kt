package org.elixir_lang.lowering

import com.intellij.psi.PsiElement
import org.elixir_lang.psi.AtNumericBracketOperation
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.AtUnqualifiedBracketOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ElixirBracketArguments
import org.elixir_lang.psi.ElixirTypes

/** Module attributes: `@` of an expression, `@name value` and `@name[key]`. */
internal fun Lowering.attribute(element: PsiElement): ElixirAst =
    when (element) {
        is AtOperation -> {
            val (operator, operand) = element.children.let { it.getOrNull(0) to it.getOrNull(1) }

            if (operator != null && operand != null) at(element, operator, lower(operand)) else broken(element)
        }
        is AtUnqualifiedNoParenthesesCall<*> -> {
            val atIdentifier = element.atIdentifier
            val name = atIdentifier.node.findChildByType(ElixirTypes.IDENTIFIER_TOKEN)?.psi ?: return broken(element)
            val call = noParenthesesCall(element, name, element.noParenthesesOneArgument, element.doBlock)

            at(element, atIdentifier.atPrefixOperator, call)
        }
        is AtUnqualifiedBracketOperation -> {
            val atOperation = element.matchedAtOperation
            val name = atOperation.operand()?.firstChild ?: return broken(element)

            bracketAccess(element, atOperation.atPrefixOperator, variable(name), element.bracketArguments)
        }
        is AtNumericBracketOperation -> {
            val number = element.children.getOrNull(1) ?: return broken(element)

            bracketAccess(element, element.atPrefixOperator, lower(number), element.bracketArguments)
        }
        else -> unlowered(element)
    }

/** `{:@, meta, [operand]}`, at the `@`. */
private fun Lowering.at(element: PsiElement, operator: PsiElement, operand: ElixirAst): ElixirAst =
    ElixirAst.Call(meta(element, location(operator)), ElixirAst.Literal.Atom(meta(operator), "@"), listOf(operand))

/** `@operand[key]`: the key is looked up in the attribute, not the attribute in `operand[key]`. */
private fun Lowering.bracketAccess(
    element: PsiElement,
    operator: PsiElement,
    operand: ElixirAst,
    bracketArguments: PsiElement,
): ElixirAst =
    (bracketArguments as? ElixirBracketArguments)
        ?.let { access(element, at(element, operator, operand), it, BracketForm.IDENTIFIER) }
        ?: broken(element)
