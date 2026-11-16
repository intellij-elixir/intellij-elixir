package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.structure_view.element.Delegation

/**
 * What documentation of a function shows for the delegation a use reached it through, when the delegation has no `@doc`
 * of its own to show instead: each function of the name in its module, with the functions a delegation delegates to, or
 * its own `@doc` where it writes one.
 */
internal object DelegationBlock {
    /**
     * A function's [module] and [heading], with a head for each of its declarations, and the `Target.name/arity` of each
     * function it delegates to; a delegation that writes an `@doc` of its own, as [writesDoc] says, shows that ([own])
     * and no target.
     */
    class Entry(val module: String?, val heading: FetchedDocs.Heading, val targets: List<String>, val own: FetchedDocs.FunctionOrMacroDocumentation?)

    /**
     * The [head] of [delegation] a use reached, and what the step prefers at it, read once for the block and the arity;
     * [index] is the index name of the module that holds it when it is known, and [inside] whether the use is in that
     * module.
     */
    class Reached(val delegation: Call, val head: PsiElement?, val index: String?, val inside: Boolean) {
        val preferred: Preferred? by lazy(LazyThreadSafetyMode.NONE) { Documented.headed(head) }

        /** What the step prefers at [other], a head of a function the block lists. */
        @RequiresReadLock
        fun preferredAt(other: PsiElement?): Preferred? = if (other == head) preferred else Documented.headed(other)
    }

    /** One declaration of a function, a `def`, a clause or a `defdelegate`, by its [head]. */
    private class Part(val call: Call, val form: Form, val head: PsiElement?, val documentation: FetchedDocs.FunctionOrMacroDocumentation)

    /**
     * The delegation [reached] and the other functions of its name in its module, ascending by arity,
     * as many as the arities card lists, each as the card groups its declarations: from outside the module (not
     * [Reached.inside]) not one marked `@doc false` or `@doc nil`. [Reached.index] names the module that holds them,
     * which a delegation a `use` injects does not write in its `quote`.
     */
    @RequiresReadLock
    fun of(reached: Reached): List<Entry> {
        val index = reached.index
        val module = index?.let(Module::inspect)
        val delegation = reached.delegation

        return SourceFileDocsHelper.listedGroups(delegation, remote = !reached.inside)
            .map { function ->
                entry(reached, module, function.map { Part(it.call, it.form, it.head, it.documentation) }, SourceFileDocsHelper.titles(index, function))
            }
            .ifEmpty {
                val documentation = FetchedDocs.FunctionOrMacroDocumentation.fromCallDefinitionClauseCall(module, delegation, headOf(delegation, reached.head))

                listOf(entry(reached, module, listOf(Part(delegation, Form.DELEGATION, reached.head, documentation)), titles = emptyList()))
            }
    }

    /** The [head] a delegation written alone shows, after its keyword and with the name Elixir gives it. */
    @RequiresReadLock
    private fun headOf(delegation: Call, head: PsiElement?): String? {
        val headName = UseSymbols.headUse(head)?.second?.name
        val written = if (head != null && headName != null) SourceFileDocsHelper.spelled(head, headName) else head?.text.orEmpty()

        return SourceFileDocsHelper.keyworded(delegation, written)
    }

    /** What a function of the block shows: the docs of its declarations, or for a delegation that writes none the functions it delegates to. */
    @RequiresReadLock
    private fun entry(reached: Reached, module: String?, parts: List<Part>, titles: List<String>): Entry {
        val (written, linked) = parts.partition { it.form != Form.DELEGATION || writesDoc(it.call) }
        val documentation = parts.map(Part::documentation)

        return Entry(
            module,
            FetchedDocs.Heading(titles, documentation.flatMap { it.specs }, FetchedDocs.FunctionOrMacroDocumentation.headsOf(documentation)),
            linked.flatMap { targets(it.head, reached.preferredAt(it.head)) }.distinct(),
            written.takeIf { it.isNotEmpty() }?.let { SourceFileDocsHelper.merged(it.map(Part::documentation)) }
        )
    }

    /**
     * The arity [element], which [reached] reaches, is called at: the arity the delegation nearest it on the way passes
     * on, at its head that reaches [element], which fills in the defaults of a call written at a lower one.
     */
    @RequiresReadLock
    fun passedTo(reached: Reached, element: PsiElement): Int? {
        val delegation = reached.delegation
        val head = reached.head
        val preferred = reached.preferred ?: return null
        val nearest = preferred.admittedDelegatedTo
            .firstOrNull { it.element == element }
            ?.via
            ?.lastOrNull { Delegation.`is`(it) }
            ?.takeUnless { it == delegation }
            ?: return passed(head, preferred)

        return DeclaringForm.heads(nearest, Form.DELEGATION).firstNotNullOfOrNull { nearestHead ->
            Documented.headed(nearestHead)
                ?.takeIf { Documented.reaches(it, element) }
                ?.let { passed(nearestHead, it) }
        }
    }

    /** The arity a delegation's [head] calls its targets at, [preferred] being what the step prefers at that head. */
    @RequiresReadLock
    private fun passed(head: PsiElement?, preferred: Preferred): Int? =
        UseSymbols.headUse(head)?.let { (_, use) -> UseSymbols.passed(preferred, use) }

    /** The `Target.name/arity` of each function a delegation's [head] delegates to, [preferred] being what the step prefers at it. */
    @RequiresReadLock
    private fun targets(head: PsiElement?, preferred: Preferred?): List<String> {
        preferred ?: return emptyList()
        val passed = passed(head, preferred) ?: return emptyList()

        return preferred.directlyDelegatedTo.mapNotNull { target ->
            target.declaringModuleName()?.let { "$it.${InspectAtom.remoteCall(target.candidate.declaration.name)}/$passed" }
        }
    }

    /** Appends each of [entries], with [appendOwn] writing the docs of one that has its own, as written in its module. */
    fun appendTo(documentationHtml: StringBuilder, entries: List<Entry>, appendOwn: (FetchedDocs.FunctionOrMacroDocumentation, String?) -> Unit) {
        for (entry in entries) {
            documentationHtml.append(DocumentationMarkup.DEFINITION_START)
            entry.module?.let { documentationHtml.append("<i>module</i> <b>").append(it).append("</b>\n") }
            entry.heading.appendTo(documentationHtml, link = null)
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
