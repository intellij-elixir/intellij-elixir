package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.moduleAttributeAtom

object ModuleAttribute {
    private const val BEHAVIOUR_NAME = "behaviour"
    private val CALLBACK_NAME_SET = setOf("callback", "macrocallback")
    @JvmField
    val DOCUMENTATION_NAME_SET = setOf("doc", "moduledoc", "typedoc")
    private const val SPECIFICATION_NAME = "spec"
    private val TYPE_NAME_SET = setOf("opaque", "type", "typep")
    private val NON_REFERENCING_NAME_SET =
        mutableSetOf<String>().apply {
            add(BEHAVIOUR_NAME)
            addAll(CALLBACK_NAME_SET)
            addAll(DOCUMENTATION_NAME_SET)
            add(SPECIFICATION_NAME)
            addAll(TYPE_NAME_SET)
        }.toSet()

    @JvmStatic
    fun isDeclaration(element: PsiElement): Boolean = element is Call && isDeclaration(SyntacticCall.of(element))

    @JvmStatic
    fun isDeclaration(call: SyntacticCall): Boolean =
        call.shape == SyntacticCall.Shape.AT_UNQUALIFIED_NO_PARENTHESES && call.resolvedFinalArity() == 1

    @JvmStatic
    fun isTypeSpecName(name: String): Boolean =
        isCallbackName(name) || isSpecificationName(name) || isTypeName(name)

    @JvmStatic
    fun isCallbackName(name: String): Boolean = CALLBACK_NAME_SET.contains(name)

    @JvmStatic
    fun isDocumentationName(name: String): Boolean = DOCUMENTATION_NAME_SET.contains(name)

    @JvmStatic
    @RequiresReadLock
    fun isNonReferencing(moduleAttribute: ElixirAtIdentifier): Boolean =
        moduleAttributeAtom(moduleAttribute)?.let(::isNonReferencingName) ?: false

    @JvmStatic
    fun isSpecificationName(name: String): Boolean = SPECIFICATION_NAME == name

    @JvmStatic
    fun isTypeName(name: String): Boolean = TYPE_NAME_SET.contains(name)

    @JvmStatic
    fun isNonReferencingName(name: String): Boolean = NON_REFERENCING_NAME_SET.contains(name)

    @JvmStatic
    @RequiresReadLock
    fun isHead(element: PsiElement): Boolean {
        val declaration = when (element) {
            is AtUnqualifiedNoParenthesesCall<*> -> element
            else -> generateSequence(element) { it.parent }
                .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                .firstOrNull()
        } ?: return false
        if (!isDeclaration(declaration) || isNonReferencing(declaration.atIdentifier)) return false

        return element == declaration || PsiTreeUtil.isAncestor(declaration.atIdentifier, element, false)
    }
}
