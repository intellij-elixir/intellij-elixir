package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/**
 * What documentation of a function shows for the delegation a use reached it through, when the delegation has no `@doc`
 * of its own to show instead: each function of the name in its module, with the functions a delegation delegates to, or
 * its own `@doc` where it writes one.
 */
internal object DelegationBlock {
    /**
     * A function's [module] and [heads], one for each of its declarations, and the `Target.name/arity` of each function
     * it delegates to; a delegation that writes an `@doc` of its own, as [writesDoc] says, shows that ([own]) and no target.
     */
    class Entry(val module: String?, val heads: List<String>, val targets: List<String>, val own: FetchedDocs.FunctionOrMacroDocumentation?)

    /** One declaration of a function: a `def`, a clause or a `defdelegate`. */
    private class Part(val call: Call, val form: Form, val documentation: FetchedDocs.FunctionOrMacroDocumentation)

    /**
     * The delegation reached through [delegation] and the other functions of its name in its module, ascending by arity,
     * as many as the arities card lists, each as the card groups its declarations: from outside the module ([remote]) not
     * one marked `@doc false` or `@doc nil`. [module] is the name of the module that holds them, which a delegation a
     * `use` injects does not write in its `quote`.
     */
    @RequiresReadLock
    fun of(delegation: Call, module: String?, remote: Boolean): List<Entry> =
        SourceFileDocsHelper.listedGroups(delegation, remote)
            .map { function -> entry(module, function.map { Part(it.call, it.form, it.documentation) }) }
            .ifEmpty {
                val documentation = FetchedDocs.FunctionOrMacroDocumentation.fromCallDefinitionClauseCall(module, delegation, headOf(delegation))

                listOf(entry(module, listOf(Part(delegation, Form.DELEGATION, documentation))))
            }

    /** The head a delegation written alone shows, with the name Elixir gives it. */
    @RequiresReadLock
    private fun headOf(delegation: Call): String {
        val head = Delegation.head(delegation)
        val headName = UseSymbols.headUse(head)?.second?.name

        return if (head != null && headName != null) SourceFileDocsHelper.spelled(head, headName) else head?.text.orEmpty()
    }

    /** What a function of the block shows: the docs of its declarations, or for a delegation that writes none the functions it delegates to. */
    @RequiresReadLock
    private fun entry(module: String?, parts: List<Part>): Entry {
        val (written, linked) = parts.partition { it.form != Form.DELEGATION || writesDoc(it.call) }

        return Entry(
            module,
            FetchedDocs.FunctionOrMacroDocumentation.headsOf(parts.map(Part::documentation)),
            linked.flatMap { targets(it.call) }.distinct(),
            written.takeIf { it.isNotEmpty() }?.let { SourceFileDocsHelper.merged(it.map(Part::documentation)) }
        )
    }

    /** The arity [delegation]'s head calls its targets at, which fills in the defaults of a call written at a lower one. */
    @RequiresReadLock
    fun passed(delegation: Call): Int? =
        UseSymbols.headUse(Delegation.head(delegation))?.let { (_, use) -> Documented.atItsHead(delegation)?.let { UseSymbols.passed(it, use) } }

    @RequiresReadLock
    private fun targets(delegation: Call): List<String> {
        val (_, use) = UseSymbols.headUse(Delegation.head(delegation)) ?: return emptyList()
        val preferred = Documented.atItsHead(delegation) ?: return emptyList()
        val passed = UseSymbols.passed(preferred, use)

        return preferred.directlyDelegatedTo.mapNotNull { target ->
            target.declaringModuleName()?.let { "$it.${InspectAtom.remoteCall(target.candidate.declaration.name)}/$passed" }
        }
    }

    /** Appends each of [entries], with [appendOwn] writing the docs of one that has its own, as written in its module. */
    fun appendTo(documentationHtml: StringBuilder, entries: List<Entry>, appendOwn: (FetchedDocs.FunctionOrMacroDocumentation, String?) -> Unit) {
        for (entry in entries) {
            documentationHtml.append(DocumentationMarkup.DEFINITION_START)
            entry.module?.let { documentationHtml.append("<i>module</i> <b>").append(it).append("</b>\n") }
            for (head in entry.heads) documentationHtml.append(StringUtil.escapeXmlEntities(head)).append("\n")
            documentationHtml.append(DocumentationMarkup.DEFINITION_END)
            entry.own?.let { appendOwn(it, entry.module) }

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
