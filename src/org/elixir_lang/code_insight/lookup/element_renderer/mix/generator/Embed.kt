package org.elixir_lang.code_insight.lookup.element_renderer.mix.generator

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import org.elixir_lang.psi.mix.Generator

class Embed(val name: String, private val embed: Generator.Embed) : LookupElementRenderer<LookupElement>() {
    override fun renderElement(element: LookupElement, lookupElementPresentation: LookupElementPresentation) {
        lookupElementPresentation.itemText = name
        lookupElementPresentation.appendTailText(embed.parameters.joinToString(", ", "(", ")"), true)
    }
}
