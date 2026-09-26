package org.elixir_lang.heex.xml

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlTagNameProvider
import org.elixir_lang.heex.isInHeex

/**
 * Offers the containing view module's arity-1 call definitions as `<.name>` completions. The
 * platform's own prefix matching (over the dummy identifier under the caret) filters these down to
 * what the user has typed - [prefix] here is [XmlTag.getNamespacePrefix], not the typed text, so
 * every candidate is added unconditionally, the same way `DefaultXmlTagNameProvider` does.
 */
class HeexComponentTagNameProvider : XmlTagNameProvider {
    override fun addTagNameVariants(elements: MutableList<LookupElement>, tag: XmlTag, prefix: String) {
        if (!tag.isInHeex()) {
            return
        }

        for ((name, definition) in HeexComponentResolver.localComponents(tag)) {
            elements.add(LookupElementBuilder.create(definition, ".$name"))
        }
    }
}
