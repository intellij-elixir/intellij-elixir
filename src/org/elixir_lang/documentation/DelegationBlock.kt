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

    /**
     * The delegation [reached] and the other functions of its name in its module, ascending by arity,
     * as many as the arities card lists, each as the card groups its declarations: from outside the module (not
     * [Reached.inside]) not one marked `@doc false`. [Reached.index] names the module that holds them,
     * which a delegation a `use` injects does not write in its `quote`.
     */
    @RequiresReadLock
    fun of(reached: Reached): List<Entry> {
        val module = reached.index?.let(Module::inspect)
        val delegation = reached.delegation

        val listing = SourceFileDocsHelper.listedGroups(delegation, remote = !reached.inside)

        return listing.functions
            .map { function ->
                // A `defdelegate` that writes no docs links its targets.
                val (written, linked) = function.partition { it.form != Form.DELEGATION || writesDoc(it.call) }
                val documentation = if (written.isEmpty()) listing.replacedDocs(function) else listing.documentation(function, written)

                entry(reached, module, function.map { it.documentation }, linked.map { it.head }, SourceFileDocsHelper.titles(function), documentation)
            }
            .ifEmpty {
                val attributes = DeclarationAttributes.of(delegation)
                val documentation = attributes.documentation(module, headOf(delegation, reached.head))
                val writes = attributes.writesDoc

                listOf(entry(reached, module, listOf(documentation), if (writes) emptyList() else listOf(reached.head), titles = emptyList(), documentation.takeIf { writes }))
            }
    }

    /** The [head] a delegation written alone shows, after its keyword and with the name Elixir gives it. */
    @RequiresReadLock
    private fun headOf(delegation: Call, head: PsiElement?): String? {
        val headName = UseSymbols.headUse(head)?.second?.name
        val written = if (head != null && headName != null) SourceFileDocsHelper.spelled(head, headName) else head?.text.orEmpty()

        return SourceFileDocsHelper.keyworded(delegation, written)
    }

    /**
     * What a function of the block shows: the heading of its [declared] docs, its [documentation], and for each head in
     * [linked], a delegation's that writes no docs, the functions it delegates to.
     */
    @RequiresReadLock
    private fun entry(
        reached: Reached,
        module: String?,
        declared: List<FetchedDocs.FunctionOrMacroDocumentation>,
        linked: List<PsiElement?>,
        titles: List<String>,
        documentation: FetchedDocs.FunctionOrMacroDocumentation?,
    ): Entry =
        Entry(
            module,
            FetchedDocs.Heading(titles, declared.flatMap { it.specs }, FetchedDocs.FunctionOrMacroDocumentation.headsOf(declared)),
            linked.flatMap { targets(it, reached.preferredAt(it)) }.distinct(),
            documentation
        )

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
