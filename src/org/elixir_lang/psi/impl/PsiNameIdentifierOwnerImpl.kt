package org.elixir_lang.psi.impl

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.module.PutAttribute
import org.elixir_lang.module.RegisterAttribute
import org.elixir_lang.psi.*
import org.elixir_lang.psi.operation.Operation
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Type
import org.elixir_lang.structure_view.element.modular.Module

object PsiNameIdentifierOwnerImpl {
    @JvmStatic
    fun getNameIdentifier(alias: ElixirAlias): PsiElement? {
        val parent = alias.parent

        return when (parent) {
            is ElixirAccessExpression -> {
                val grandParent = parent.parent

                // alias is first alias in chain of qualifiers
                if (grandParent is QualifiableAlias) {
                    grandParent.nameIdentifier
                } else {
                    /* NamedStubbedPsiElementBase#getTextOffset assumes getNameIdentifier is null when the NameIdentifier is
                       the element itself. */
                    // alias is single, unqualified alias
                    null
                }
            }
            // alias is last in chain of qualifiers
            is QualifiableAlias -> parent.nameIdentifier
            else -> null
        }
    }

    @JvmStatic
    fun getNameIdentifier(keywordKey: ElixirKeywordKey): PsiElement? =
            if (keywordKey.line != null) {
                null
            } else {
                keywordKey
            }

    @JvmStatic
    fun getNameIdentifier(variable: ElixirVariable): PsiElement = variable

    @RequiresReadLock
    @JvmStatic
    fun getNameIdentifier(named: org.elixir_lang.psi.call.Named): PsiElement? =
            when {
                /* can't be a {@code public static PsiElement getNameIdentifier(@NotNull Operation operation)} because
                   it leads to "reference to getNameIdentifier is ambiguous" */
                named is Operation -> {
                    val operation = named as Operation
                    operation.operator()
                }
                CallableDeclaration.syntacticFormOf(named) != null -> CallableDeclaration.syntacticNameIdentifier(named)
                CallDefinitionSpecification.`is`(named) -> CallDefinitionSpecification.nameIdentifier(named)
                /* have to set to null so that {@code else} clause doesn't return the {@code defimpl} element as the
                   name identifier */
                Implementation.`is`(named) -> null
                org.elixir_lang.psi.Module.`is`(named) -> Module.nameIdentifier(named)
                Protocol.`is`(named) -> Module.nameIdentifier(named)
                Type.`is`(named) -> Type.nameIdentifier(named)

                // module attributes
                RegisterAttribute.`is`(named) -> RegisterAttribute.nameIdentifier(named)
                PutAttribute.`is`(named) -> PutAttribute.nameIdentifier(named)
                named is AtUnqualifiedNoParenthesesCall<*> ->  named.atIdentifier

                else -> named.functionNameElement()
            }

    /**
     *
     * @param qualifiableAlias
     * @return null if qualifiableAlias is its own name identifier
     * @return PsiElement if qualifiableAlias is a subalias of a bigger qualified alias
     */
    @JvmStatic
    fun getNameIdentifier(qualifiableAlias: QualifiableAlias): PsiElement? =
        (qualifiableAlias.parent as? QualifiableAlias)?.nameIdentifier
}
