package org.elixir_lang.code_insight.lookup.element_renderer

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import com.intellij.util.concurrency.annotations.RequiresReadLock

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

        // Only strip the name when the head actually starts with it. `removePrefix` returns the string
        // unchanged on a mismatch, which would render the name twice - `values` + `unquote(:values)(map)`
        // for an unquoted head, or `<>` + `left <> right` for an operator delegate.
        headText(delegationCall)
            ?.takeIf { it.startsWith(name) }
            ?.removePrefix(name)
            ?.takeIf(String::isNotEmpty)
            ?.let { lookupElementPresentation.appendTailText(it, true) }

        itemPresentation?.locationString?.let { lookupElementPresentation.appendTailText(" ($it)", false) }
    }

    /** The head as written, whitespace-normalised, e.g. `values(map)`. */
    @RequiresReadLock
    private fun headText(delegationCall: Call): String? =
        CallableDeclaration
            .delegationHead(delegationCall)
            ?.text
            ?.replace(WHITESPACE_RUN, " ")

    companion object {
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
