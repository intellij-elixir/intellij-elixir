package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.None
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.*
import org.elixir_lang.psi.ModuleAttribute.isDocumentationName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.declaringModuleCall
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.isInterpolatedWithin
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.resolver.Callable
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.reference.Resolver
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.util.function.Consumer


private val LOG = logger<ElixirDocumentationProvider>()

internal class ElixirDocumentationProvider : DocumentationProvider {
    /** Rendered documentation, and the anchor it opens at: the entry of the arity the original element uses, or the arity a followed link gave. */
    class Rendered(val html: String, val anchor: String?)

    fun render(element: PsiElement, originalElement: PsiElement?, linkedArity: Int? = null): Rendered? =
        // Where no use is known, as for a link followed, the function is seen from outside its module.
        fetchDocs(element, remote = !isInside(element, originalElement))?.let { fetched ->
            Rendered(formatDocs(element, fetched), anchor(fetched, linkedArity ?: calledArity(originalElement)))
        }

    /** Whether [original] is inside the module that holds [element]: the name of the call that declares [element] is, whatever it spells. */
    private fun isInside(element: PsiElement, original: PsiElement?): Boolean =
        headAt(original)?.call == element || choiceAt(original)?.takeIf { it.element == element }?.inside == true

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? =
        render(element, originalElement)?.html

    private fun anchor(fetched: FetchedDocs, arity: Int?): String? =
        (fetched as? FetchedDocs.Arities)
            ?.takeIf { it.entries.size > 1 }
            ?.let { arities -> arity?.let(arities::entryAt)?.id }

    /** The use written where [original] is: at a call or a capture. */
    private fun useAt(original: PsiElement?): Use? = callAt(original)?.let(::useOf)

    /** What the use written where [original] is documents: at a call or a capture. */
    private fun choiceAt(original: PsiElement?): Documented.Choice? = callAt(original)?.let(::documentedChoice)

    /** The arity used where [original] is written. */
    private fun calledArity(original: PsiElement?): Int? {
        return headAt(original)?.declaration?.let(UseSymbols::headArity) ?: when (val use = useAt(original)) {
            is Use.OfCall -> use.arity
            is Use.Specified -> use.arity
            is Use.Remote -> use.arity
            null -> null
        }
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

    private tailrec fun callAt(element: PsiElement?): Call? = when (element) {
        is LeafPsiElement, is ElixirIdentifier, is ElixirRelativeIdentifier -> callAt(element.parent)
        is Call -> element
        else -> null
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
        return contextElement?.let(::getCustomDocumentationElement)
    }

    /**
     * What [call] documents. Inside `&Mod.name/arity` the name carries no reference of its own - it lives on the
     * enclosing capture operator, which is the only element that also spans the `/arity` - so a captured name is
     * looked up through that instead.
     */
    private fun documentedChoice(call: Call): Documented.Choice? =
        useOf(call)?.let { use ->
            val bound = use is Use.OfCall && captureOf(call) == null && Callable.bound(call, false)

            Documented.of(use, bound)
        }

    private fun captureOf(call: Call): CaptureNameArity? =
        PsiTreeUtil.getParentOfType(call, NonNumeric::class.java)
            ?.reference
            ?.let { it as? CaptureNameArity }
            ?.takeIf { it.nameElement == call }

    /** The use [call] is, read from its reference's kind; `null` where it has no reference to document through. */
    private fun useOf(call: Call): Use? {
        val capture = captureOf(call)

        return if (capture != null) {
            Use.capture(call, capture.arity)
        } else {
            when (val reference = call.reference) {
                is org.elixir_lang.reference.Callable,
                is org.elixir_lang.psi.__module__.Reference -> Use.of(call)

                is org.elixir_lang.reference.CallDefinitionClause ->
                    Use.specification(call, reference.moduleAttribute)

                else -> null
            }
        }
    }

    private tailrec fun getCustomDocumentationElement(contextElement: PsiElement): PsiElement? = when (contextElement) {
        is LeafPsiElement, is ElixirIdentifier, is ElixirRelativeIdentifier ->
            getCustomDocumentationElement(contextElement.parent)

        is ElixirAtom -> {
            contextElement.reference
                ?.let { it as? PsiPolyVariantReference }
                ?.multiResolve(false)
                ?.filter(ResolveResult::isValidResult)
                ?.mapNotNull(ResolveResult::getElement)
                ?.filterIsInstance<PsiNamedElement>()
                ?.let { Resolver.preferSource(it) }
                ?.firstOrNull()
        }

        is Call -> documentedChoice(contextElement)?.let { choice ->
            // multiResolve matches names by prefix for completion, so a wrong-arity call falls back to the reached
            // definitions with exactly the called name, not to every name it starts.
            choice.element ?: run {
                val callName = contextElement.functionName()

                Documented.documented(
                    choice.reached.filter { (_, element) ->
                        when (element) {
                            is BeamCallDefinition -> element.exportedName() == callName
                            is Call -> CallDefinitionClause.nameArityInterval(element, ResolveState.initial())
                                ?.name == callName

                            else -> false
                        }
                    }
                )
            }
        }

        is QualifiableAlias if declaringModuleCall(contextElement) != null ->
            declaringModuleCall(contextElement)

        is QualifiableAlias if contextElement.reference != null ->
            contextElement.reference!!
                .let { it as PsiPolyVariantReference }
                .multiResolve(false)
                .filter(ResolveResult::isValidResult)
                .mapNotNull(ResolveResult::getElement)
                .filterIsInstance<Call>()
                .singleOrNull { isModular(it) }

        is QualifiableAlias -> getCustomDocumentationElement(contextElement.parent)

        else -> {
            val quotedAtom = enclosingQuotedAtom(contextElement)

            if (quotedAtom != null) getCustomDocumentationElement(quotedAtom) else null
        }
    }

    /** The atom whose quoted name holds [element], unless [element] is inside an interpolation in that name. */
    private fun enclosingQuotedAtom(element: PsiElement): ElixirAtom? =
        PsiTreeUtil.getParentOfType(element, ElixirLine::class.java, false)
            ?.takeUnless { element.isInterpolatedWithin(it) }
            ?.parent as? ElixirAtom

    private fun formatDocs(element: PsiElement, fetchedDocs: FetchedDocs): String {
        val documentationHtml = StringBuilder()

        documentationHtml.append(DocumentationMarkup.DEFINITION_START)

        fetchedDocs.module?.let { documentationHtml.append("<i>module</i> <b>").append(it).append("</b>\n") }

        when (fetchedDocs) {
            is FetchedDocs.CallbackDocumentation -> {
                documentationHtml.append(fetchedDocs.head).append("\n")
            }

            is FetchedDocs.FunctionOrMacroDocumentation -> {
                for (head in fetchedDocs.heads) {
                    documentationHtml.append(head).append("\n")
                }
            }

            is FetchedDocs.Arities -> {
                val linked = fetchedDocs.entries.size > 1

                for (entry in fetchedDocs.entries) {
                    for (head in entry.documentation.heads) {
                        if (linked) {
                            documentationHtml
                                .append("<a href=\"#").append(entry.id).append("\">")
                                .append(StringUtil.escapeXmlEntities(head))
                                .append("</a>")
                        } else {
                            documentationHtml.append(head)
                        }

                        documentationHtml.append("\n")
                    }
                }
            }

            is FetchedDocs.ModuleDocumentation -> Unit
            is FetchedDocs.TypeDocumentation -> {
                documentationHtml.append(fetchedDocs.head).append("\n")
            }
        }

        documentationHtml.append(DocumentationMarkup.DEFINITION_END)


        when (fetchedDocs) {
            is FetchedDocs.CallbackDocumentation -> {
                fetchedDocs.doc.let { doc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, doc))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }

            is FetchedDocs.ModuleDocumentation -> {
                fetchedDocs.moduledoc.let { moduledoc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, moduledoc))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }

            is FetchedDocs.FunctionOrMacroDocumentation -> appendFunctionContent(documentationHtml, element, fetchedDocs)

            is FetchedDocs.Arities -> {
                val linked = fetchedDocs.entries.size > 1

                for (entry in fetchedDocs.entries) {
                    if (linked) {
                        documentationHtml
                            .append(DocumentationMarkup.CONTENT_START)
                            .append("<a id=\"").append(entry.id).append("\"></a><b>")
                            .append(entry.documentation.heads.joinToString("<br>") { StringUtil.escapeXmlEntities(it) })
                            .append("</b>")
                            .append(DocumentationMarkup.CONTENT_END)
                    }

                    appendFunctionContent(documentationHtml, element, entry.documentation)
                }
            }

            is FetchedDocs.TypeDocumentation -> {
                fetchedDocs.typedoc.let { typedoc ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(html(element, typedoc))
                        .append(DocumentationMarkup.CONTENT_END)
                }
            }
        }


        return documentationHtml.toString()
    }

    private fun appendFunctionContent(
        documentationHtml: StringBuilder,
        element: PsiElement,
        function: FetchedDocs.FunctionOrMacroDocumentation
    ) {
        function.doc?.let { doc ->
            when (doc) {
                is None -> Unit
                is Hidden ->
                    documentationHtml
                        .append(DocumentationMarkup.CONTENT_START)
                        .append(false)
                        .append(DocumentationMarkup.CONTENT_END)

                is MarkdownByLanguage ->
                    doc.formattedByLanguage.values.map { formatted ->
                        documentationHtml
                            .append(DocumentationMarkup.CONTENT_START)
                            .append(html(element, formatted))
                            .append(DocumentationMarkup.CONTENT_END)
                    }
            }
        }

        val deprecated = function.deprecated
        val impls = function.impls
        val implsIsNotEmpty = impls.isNotEmpty()
        val specs = function.specs
        val specsIsNotEmpty = specs.isNotEmpty()

        if (deprecated != null || implsIsNotEmpty || specsIsNotEmpty) {
            documentationHtml.append(DocumentationMarkup.SECTIONS_START)

            if (deprecated != null) {
                documentationHtml
                    .append(DocumentationMarkup.SECTION_HEADER_START)
                    .append("Deprecated")
                    .append(DocumentationMarkup.SECTION_SEPARATOR)
                    .append(html(element, deprecated))
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

            if (specsIsNotEmpty) {
                documentationHtml
                    .append(DocumentationMarkup.SECTION_HEADER_START)
                    .append("Specifications")
                    .append(DocumentationMarkup.SECTION_SEPARATOR)

                for (spec in specs) {
                    documentationHtml.append(spec).append("<br />\n")
                }

                documentationHtml.append(DocumentationMarkup.SECTION_END)
            }

            documentationHtml.append(DocumentationMarkup.SECTIONS_END)
        }
    
    }

    private fun html(element: PsiElement, otpErlangObject: OtpErlangObject): String =
        when (otpErlangObject) {
            is OtpErlangBinary ->
                otpErlangObject
                    .binaryValue()
                    .let { String(it, Charsets.UTF_8) }
                    .let { html(element, it) }

            else -> {
                LOG.warn("Don't know how to render deprecated metadata: $otpErlangObject")

                ""
            }
        }

    private fun html(element: PsiElement, markdownText: String): String {
        val flavour = MarkdownFlavourDescriptor(element)
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdownText)

        return HtmlGenerator(markdownText, parsedTree, flavour, false)
            .generateHtml()
    }

    private fun fetchDocs(element: PsiElement, remote: Boolean): FetchedDocs? =
    // For compiled Beam elements, prefer docs from the decompiled source mirror so hover uses
        // the cached inline docs from decompilation.
        if (element.containingFile?.originalFile is BeamFileImpl) {
            val sourceElement = (element as? PsiCompiledElement)?.mirror ?: element.navigationElement

            SourceFileDocsHelper.fetchDocs(sourceElement, remote)
                ?: BeamDocsHelper.fetchDocs(element)
        } else {
            SourceFileDocsHelper.fetchDocs(element, remote)
        }
}
