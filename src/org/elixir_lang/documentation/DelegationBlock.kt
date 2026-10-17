package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/**
 * What documentation of a function shows for the delegation a use reached it through, when the delegation has no `@doc`
 * of its own to show instead: each delegation of the name in its module, with the functions it delegates to.
 */
internal object DelegationBlock {
    /** A delegation's [module] and [head], and the `Target.name/arity` of each function it delegates to. */
    class Entry(val module: String?, val head: String, val targets: List<String>)

    /**
     * The delegation reached through [delegation] and those of its name in its module, ascending by arity, as many as
     * the arities card lists: from outside the module ([remote]) not one marked `@doc false` or `@doc nil`. [module] is
     * the name of the module that holds them, which a delegation a `use` injects does not write in its `quote`.
     */
    @RequiresReadLock
    fun of(delegation: Call, module: String?, remote: Boolean): List<Entry> {
        val siblings = SourceFileDocsHelper.listedDelegations(delegation, remote).ifEmpty { listOf(delegation) }

        return siblings
            .sortedBy { UseSymbols.headUse(it)?.arity ?: 0 }
            .map { sibling ->
                val head = Delegation.head(sibling)
                val headName = UseSymbols.headUse(sibling)?.name
                val text = if (head != null && headName != null) SourceFileDocsHelper.spelled(head, headName) else head?.text.orEmpty()

                Entry(module, text, targets(sibling))
            }
    }

    @RequiresReadLock
    private fun targets(delegation: Call): List<String> {
        val use = UseSymbols.headUse(delegation) ?: return emptyList()
        val preferred = Documented.atItsHead(delegation) ?: return emptyList()
        val passed = UseSymbols.passed(preferred, use)

        return preferred.directlyDelegatedTo.mapNotNull { target ->
            target.declaringModuleName()?.let { "$it.${target.candidate.declaration.name}/$passed" }
        }
    }

    fun appendTo(documentationHtml: StringBuilder, entries: List<Entry>) {
        for (entry in entries) {
            documentationHtml.append(DocumentationMarkup.DEFINITION_START)
            entry.module?.let { documentationHtml.append("<i>module</i> <b>").append(it).append("</b>\n") }
            documentationHtml.append(StringUtil.escapeXmlEntities(entry.head)).append("\n").append(DocumentationMarkup.DEFINITION_END)

            for (target in entry.targets.map(StringUtil::escapeXmlEntities)) {
                documentationHtml
                    .append(DocumentationMarkup.CONTENT_START)
                    .append("Delegates to <a href=\"").append(PSI_ELEMENT_PROTOCOL).append(target).append("\"><code>").append(target)
                    .append("</code></a>.")
                    .append(DocumentationMarkup.CONTENT_END)
            }
        }
    }
}
