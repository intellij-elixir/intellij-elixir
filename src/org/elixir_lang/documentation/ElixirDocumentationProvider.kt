package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.None
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ModuleAttribute.isDocumentationName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.structure_view.element.Delegation
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.util.function.Consumer


private val LOG = logger<ElixirDocumentationProvider>()

/** The line above a definition the expander could not confirm is the one a use reaches. */
private const val UNCONFIRMED = "The expander could not confirm that this is the definition used here."

internal class ElixirDocumentationProvider : DocumentationProvider {
    /** Rendered documentation, and the anchor it opens at. */
    class Rendered(val html: String, val anchor: String?)

    @RequiresReadLock
    fun render(element: PsiElement, originalElement: PsiElement?, followed: DocumentationLink.Followed? = null): Rendered? {
        val head = headAt(originalElement)
        val used = DocumentedAt.useAt(originalElement)
        val choice = (followed?.opened() ?: used?.choice())?.takeIf { it.element == element }

        // Where no use is known, the function is seen from outside its module, as from a link followed. Its signature is
        // made in the module the use or link found it declared in, as Parameter Info's is.
        val remote = !isInside(element, head, choice)

        return fetchDocs(element, remote, choice?.found)?.let { own ->
            val (fetched, everyCopyShown) = withCopies(own, choice?.copies.orEmpty(), remote)
            val reached = reachedThrough(head, choice)?.takeIf { it.delegation != element }
            val delegations = reached?.let(DelegationBlock::of).orEmpty()
            // Through a delegation the target is called at the arity the delegation passes on, not the one written at the use.
            val opened = reached?.let { DelegationBlock.passedTo(it, element) } ?: followed?.arity ?: calledArity(head, used?.use)

            Rendered(formatDocs(element, fetched, delegations, choice?.unconfirmed(everyCopyShown) == true), anchor(fetched, opened))
        }
    }

    /**
     * [fetched], then each of [copies]' entries, as its own entry from its own copy of the module, and whether each copy,
     * [fetched]'s included, has entries there.
     */
    private fun withCopies(fetched: FetchedDocs, copies: List<Found>, remote: Boolean): Pair<FetchedDocs, Boolean> {
        if (fetched !is FetchedDocs.Arities) return fetched to false

        val entries = copies.mapIndexed { index, copy ->
            (fetchDocs(copy.element, remote, copy) as? FetchedDocs.Arities)
                ?.entries
                ?.map { it.copy(otherCopy = index + 1) }
                .orEmpty()
        }

        return fetched.copy(entries = fetched.entries + entries.flatten()) to
            (fetched.entries.isNotEmpty() && entries.all { it.isNotEmpty() })
    }

    /**
     * Whether the caret is inside the module that holds [element]: [head], the declaring call at the caret, is the call
     * that declares [element], whatever it spells, or the use [choice] of [element] is.
     */
    private fun isInside(element: PsiElement, head: Head?, choice: Documented.Choice?): Boolean =
        head?.call == element || choice?.inside == true

    /**
     * The delegation the caret reached the documented element through, which has no `@doc` of its own to show instead:
     * the one [head], the declaring call at the caret, is, else the one the use [choice] went through.
     */
    private fun reachedThrough(head: Head?, choice: Documented.Choice?): DelegationBlock.Reached? =
        if (head != null) {
            head.call.takeIf { head.form == Form.DELEGATION }?.let { delegation -> DelegationBlock.Reached(delegation, Delegation.head(delegation), moduleOf(delegation), inside = true) }
        } else {
            val found = choice?.delegation

            (found?.element as? Call)?.let { delegation ->
                // What a `use` injects is in the module that holds the `use`, which `quote` does not name.
                val injectedInto = found.takeIf { it.candidate.reach == Reach.USE }?.declaringModuleName()

                DelegationBlock.Reached(delegation, found.head.written, injectedInto ?: moduleOf(delegation), inside = choice.insideDelegation)
            }
        }

    /** The index name of the module [delegation] is written in, `null` where it is in a `quote`. */
    private fun moduleOf(delegation: Call): String? =
        enclosingModularMacroCall(delegation)?.let(SourceFileDocsHelper::indexName)

    @RequiresReadLock
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? =
        render(element, originalElement)?.html

    private fun anchor(fetched: FetchedDocs, arity: Int?): String? =
        (fetched as? FetchedDocs.Arities)
            ?.takeIf { it.linked }
            ?.let { arities -> arity?.let(arities::entryAt)?.id }

    /**
     * The arity used at the caret: at a call, a capture or an MFA atom, [use]'s; at a declaration's head, [head]'s where
     * one is there.
     */
    private fun calledArity(head: Head?, use: Use?): Int? =
        head?.declaration?.let(UseSymbols::headArity) ?: when (use) {
            is Use.OfCall -> use.arity
            is Use.Specified -> use.arity
            is Use.Remote -> use.arity
            else -> null
        }

    /** A declaring call whose name is written where the caret is, and what the call declares, when that is one function. */
    private class Head(val call: Call, val form: Form, val declaration: Declaration?)

    /**
     * The one read of a declaring call's name: what it declares, which its spelling may not give, as `unquote(:f)(a, b)`
     * declares `f/2` and spells `unquote/1`.
     */
    private fun headAt(original: PsiElement?): Head? =
        original?.let(DeclaringForm::named)?.let { (call, form) ->
            Head(call, form, SourceFileDocsHelper.declaredFunction(call, form)?.declaration)
        }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? =
        generateDoc(element, originalElement)

    override fun generateRenderedDoc(comment: PsiDocCommentBase): String? =
        comment
            .let(::markdown)
            ?.let { html(comment, it) }

    private fun markdown(comment: PsiDocCommentBase): String? =
        when (comment) {
            is Comment -> comment.moduleAttribute.moduleAttributeValue()?.documentationMarkdownText()
            else -> null
        }


    override fun collectDocComments(file: PsiFile, sink: Consumer<in PsiDocCommentBase>) {
        file.let { it as? ElixirFile }?.let { collectDocComments(it, sink) }
    }

    private fun collectDocComments(file: ElixirFile, sink: Consumer<in PsiDocCommentBase>) {
        file.childExpressions().forEach { collectDocComments(it, sink) }
    }

    private fun collectDocComments(element: PsiElement, sink: Consumer<in PsiDocCommentBase>) {
        ProgressManager.checkCanceled()

        if (element is AtUnqualifiedNoParenthesesCall<*> && isDocumentationName(element.atIdentifier.identifierName())) {
            sink.accept(Comment(element))
        } else {
            element.children.forEach { collectDocComments(it, sink) }
        }
    }

    override fun findDocComment(file: PsiFile, range: TextRange): PsiDocCommentBase? {
        val moduleAttribute = PsiTreeUtil.getParentOfType(
            file.findElementAt(range.startOffset),
            AtUnqualifiedNoParenthesesCall::class.java,
            false
        )
        return if (moduleAttribute == null || range != moduleAttribute.textRange) null else Comment(moduleAttribute)
    }

    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager?,
        `object`: Any?,
        element: PsiElement?
    ): PsiElement? {
        return super.getDocumentationElementForLookupItem(psiManager, `object`, element)
    }

    @RequiresReadLock
    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement
    ): PsiElement? = DocumentationLink.element(link, context)

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        return contextElement?.let(DocumentedAt::of)
    }

    /** [fetchedDocs] rendered, with [delegations]' entries, under the line that the expander could not confirm it where [unconfirmed]. */
    private fun formatDocs(
        element: PsiElement,
        fetchedDocs: FetchedDocs,
        delegations: List<DelegationBlock.Entry>,
        unconfirmed: Boolean,
    ): String {
        val documentationHtml = StringBuilder()
        val linkables = HashMap<String, Boolean>()

        if (unconfirmed) documentationHtml.append("<p>").append(UNCONFIRMED).append("</p>")

        documentationHtml.append(DocumentationMarkup.DEFINITION_START)

        fetchedDocs.module?.let { documentationHtml.append("<i>module</i> <b>").append(it).append("</b>\n") }

        when (fetchedDocs) {
            is FetchedDocs.CallbackDocumentation -> {
                documentationHtml.append(fetchedDocs.head).append("\n")
            }

            is FetchedDocs.FunctionOrMacroDocumentation -> fetchedDocs.heading.appendTo(documentationHtml, link = null)

            is FetchedDocs.Arities -> {
                for (entry in fetchedDocs.entries) {
                    entry.documentation.heading.appendTo(documentationHtml, entry.id.takeIf { fetchedDocs.linked })
                }
            }

            is FetchedDocs.ModuleDocumentation -> Unit
            is FetchedDocs.TypeDocumentation -> {
                documentationHtml.append(fetchedDocs.head).append("\n")
            }
        }

        documentationHtml.append(DocumentationMarkup.DEFINITION_END)
        DelegationBlock.appendTo(documentationHtml, delegations) { own, writtenIn -> appendFunctionContent(documentationHtml, element, own, linkables, writtenIn) }

        when (fetchedDocs) {
            is FetchedDocs.CallbackDocumentation -> {
                fetchedDocs.doc.let { doc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, doc, linkables = linkables))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }

            is FetchedDocs.ModuleDocumentation -> {
                fetchedDocs.moduledoc.let { moduledoc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, moduledoc, linkables = linkables))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }

            is FetchedDocs.FunctionOrMacroDocumentation -> appendFunctionContent(documentationHtml, element, fetchedDocs, linkables)

            is FetchedDocs.Arities -> {
                for (entry in fetchedDocs.entries) {
                    if (fetchedDocs.linked) {
                        documentationHtml.append(DocumentationMarkup.CONTENT_START)
                        entry.documentation.heading.appendAnchorTo(documentationHtml, entry.id)
                        documentationHtml.append(DocumentationMarkup.CONTENT_END)
                    }

                    // Another copy's doc is written in that copy, so a link of no module there is to that copy's function.
                    appendFunctionContent(documentationHtml, element, entry.documentation, linkables, entry.documentation.module.takeIf { entry.otherCopy != 0 })
                }
            }

            is FetchedDocs.TypeDocumentation -> {
                fetchedDocs.typedoc.let { typedoc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, typedoc, linkables = linkables))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }
        }


        return documentationHtml.toString()
    }

    private fun appendFunctionContent(
        documentationHtml: StringBuilder,
        element: PsiElement,
        function: FetchedDocs.FunctionOrMacroDocumentation,
        linkables: MutableMap<String, Boolean>,
        writtenIn: String? = null
    ) {
        function.doc?.let { doc ->
            when (doc) {
                is None -> Unit
                is Hidden ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append("Not part of the public API")
                        .append(function.hiding?.because?.let { ": $it" }.orEmpty())
                        .append(".")
                        .append(DocumentationMarkup.CONTENT_END)

                is MarkdownByLanguage ->
                    doc.formattedByLanguage.values.map { formatted ->
                        documentationHtml
                            .append(DocumentationMarkup.CONTENT_START)
                            .append(html(element, formatted, writtenIn, linkables))
                            .append(DocumentationMarkup.CONTENT_END)
                    }
            }
        }

        val deprecated = function.deprecated
        val impls = function.impls
        val implsIsNotEmpty = impls.isNotEmpty()

        if (deprecated != null || implsIsNotEmpty) {
            documentationHtml.append(DocumentationMarkup.SECTIONS_START)

            if (deprecated != null) {
                documentationHtml
                    .append(DocumentationMarkup.SECTION_HEADER_START)
                    .append("Deprecated")
                    .append(DocumentationMarkup.SECTION_SEPARATOR)
                    .append(html(element, deprecated, writtenIn, linkables))
                    .append(DocumentationMarkup.SECTION_END)
            }

            if (implsIsNotEmpty) {
                documentationHtml
                    .append(DocumentationMarkup.SECTION_HEADER_START)
                    .append("Behaviors Implemented")
                    .append(DocumentationMarkup.SECTION_SEPARATOR)
                    .append("<ul>")

                for (impl in impls) {
                    documentationHtml
                        .append("<pre>")
                        .append(impl)
                        .append("</pre>")
                }

                documentationHtml
                    .append("</ul>")
                    .append(DocumentationMarkup.SECTION_END)
            }

            documentationHtml.append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private fun html(
        element: PsiElement,
        otpErlangObject: OtpErlangObject,
        writtenIn: String?,
        linkables: MutableMap<String, Boolean>,
    ): String =
        when (otpErlangObject) {
            is OtpErlangBinary ->
                otpErlangObject
                    .binaryValue()
                    .let { String(it, Charsets.UTF_8) }
                    .let { html(element, it, writtenIn, linkables) }

            else -> {
                LOG.warn("Don't know how to render deprecated metadata: $otpErlangObject")

                ""
            }
        }

    private fun html(
        element: PsiElement,
        markdownText: String,
        writtenIn: String? = null,
        linkables: MutableMap<String, Boolean> = HashMap(),
    ): String {
        val flavour = MarkdownFlavourDescriptor(element, writtenIn, linkables)
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdownText)

        return HtmlGenerator(markdownText, parsedTree, flavour, false)
            .generateHtml()
    }

    /** [element]'s docs, read as [found], the find of the use or link that documents it, declares it. */
    private fun fetchDocs(element: PsiElement, remote: Boolean, found: Found?): FetchedDocs? {
        // For compiled Beam elements, prefer docs from the decompiled source mirror so hover uses
        // the cached inline docs from decompilation, headed by what the `.beam` itself records.
        return if (element.containingFile?.originalFile is BeamFileImpl) {
            val sourceElement = (element as? PsiCompiledElement)?.mirror ?: element.navigationElement
            val decompiled = SourceFileDocsHelper.fetchDocs(sourceElement, remote, found)

            // A placed head's element is the macro's clause, which is what the `.beam` records there.
            if (found?.head?.placed == true) {
                decompiled
            } else {
                decompiled?.let { BeamDocsHelper.headed(element, it) } ?: BeamDocsHelper.fetchDocs(element)
            }
        } else {
            SourceFileDocsHelper.fetchDocs(element, remote, found)
        }
    }
}
