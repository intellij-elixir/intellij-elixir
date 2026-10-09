package org.elixir_lang.code_insight.lookup.element_renderer

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.Declarations

/**
 * Renders a `defdelegate` like an ordinary clause: name, head parameters, then where it is declared.
 *
 * Its own item presentation reads `defdelegate append_first: false, to: Mod`, which yields no tail and
 * so reads as a missing signature. Parameters come from the head, named whether or not `to:` resolves.
 */
class Delegation(private val name: String) : LookupElementRenderer<LookupElement>() {
    override fun renderElement(element: LookupElement, lookupElementPresentation: LookupElementPresentation) {
        lookupElementPresentation.itemText = name
        lookupElementPresentation.isItemTextBold = true

        val delegationCall = element.psiElement as? Call ?: return
        val itemPresentation = org.elixir_lang.structure_view.element.Delegation
            .fromCall(delegationCall)
            ?.presentation

        lookupElementPresentation.icon = itemPresentation?.getIcon(true)

        Declarations.named(Form.DELEGATION, delegationCall, name)
            ?.let { Signature.of(it, delegationCall) }
            ?.let { lookupElementPresentation.appendTailText("(${it.parameters.joinToString(", ")})", true) }

        itemPresentation?.locationString?.let { lookupElementPresentation.appendTailText(" ($it)", false) }
    }
}
