package org.elixir_lang.documentation

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.tree.IElementType
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.ModuleAttribute.isTypeName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.stub.type.call.Stub.isModular

class Comment(val moduleAttribute: AtUnqualifiedNoParenthesesCall<*>) : FakePsiElement(), PsiDocCommentBase {
    override fun getParent(): PsiElement = moduleAttribute

    override fun getTokenType(): IElementType = ElixirTypes.COMMENT

    override fun getOwner(): PsiElement? =
        when (moduleAttribute.atIdentifier.identifierName()) {
            "moduledoc" -> CallDefinitionClause.enclosingModularMacroCall(moduleAttribute)?.takeIf(::isModular)
            "doc" -> {
                moduleAttribute.siblingExpressions(forward = true, withSelf = false).firstOrNull { expression ->
                    expression is Call && CallDefinitionClause.`is`(expression)
                }
            }
            "typedoc" -> {
                moduleAttribute.siblingExpressions(forward = true, withSelf = false).firstOrNull { expression ->
                    expression is AtUnqualifiedNoParenthesesCall<*> &&
                            isTypeName(expression.atIdentifier.identifierName())
                }
            }
            // Any other attribute documents nothing, and `findDocComment` wraps every attribute
            else -> null
        }

    override fun getTextRange(): TextRange = moduleAttribute.textRange
}
