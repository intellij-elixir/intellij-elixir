package org.elixir_lang.heex.xml

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.xml.XmlTagNameProvider
import org.elixir_lang.heex.isInHeex
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.impl.headAtomValue

/**
 * Offers the containing view module's arity-1 call definitions as `<.name>` completions. The
 * platform's own prefix matching (over the dummy identifier under the caret) filters these down to
 * what the user has typed - [prefix] here is [XmlTag.getNamespacePrefix], not the typed text, so
 * every candidate is added unconditionally, the same way `DefaultXmlTagNameProvider` does.
 */
class HeexComponentTagNameProvider : XmlTagNameProvider {
    @RequiresReadLock
    override fun addTagNameVariants(elements: MutableList<LookupElement>, tag: XmlTag, prefix: String) {
        if (!tag.isInHeex()) {
            return
        }

        for (definition in HeexComponentResolver.localComponentDefinitions(tag)) {
            ProgressManager.checkCanceled()
            val name = CallDefinitionClause.head(definition)?.let(::headAtomValue) ?: continue
            elements.add(LookupElementBuilder.create(definition, ".$name"))
        }
    }
}
