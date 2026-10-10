package org.elixir_lang.code_insight.lookup.element_renderer.mix.generator

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.Declarations

class EmbedTemplate(val name: String) : LookupElementRenderer<LookupElement>() {
    override fun renderElement(element: LookupElement, lookupElementPresentation: LookupElementPresentation) {
        lookupElementPresentation.itemText = name

        (element.psiElement as? Call)
            ?.let { call -> Declarations.named(Form.GENERATOR_EMBED, call, name)?.let { Signature.of(it, call) } }
            ?.let { lookupElementPresentation.appendTailText("(${it.parameters.joinToString(", ")})", true) }
    }
}
