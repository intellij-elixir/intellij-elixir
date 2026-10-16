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
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.None
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
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
import org.elixir_lang.structure_view.element.Callback
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.util.function.Consumer
import java.util.regex.Pattern


private val LOG = logger<ElixirDocumentationProvider>()

internal class ElixirDocumentationProvider : DocumentationProvider {
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? =
        fetchDocs(element)?.let { formatDocs(element, it) }

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

    private val linkRelativePattern: Pattern =
        Pattern.compile("((?<kind>[ct]):)?((?<module>.+)\\.)?(?<relative>.+)/(?<arity>\\d+)")

    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement
    ): PsiElement? {
        val relativeLinkMatcher = linkRelativePattern.matcher(link)

        return if (relativeLinkMatcher.matches()) {
            val module = relativeLinkMatcher.group("module")

            when (val kind = relativeLinkMatcher.group("kind")) {
                null -> {
                    val relative = identifierAtomName(relativeLinkMatcher.group("relative")) { languageLevelFor(context) }
                    val arity = relativeLinkMatcher.group("arity").toInt()

                    callDefinitionResolveResults(context, module, relative, arity)
                        .let { Resolver.preferred(context, false, it) }.firstNotNullOfOrNull { it.element }
                }

                "c" -> {
                    val relative = identifierAtomName(relativeLinkMatcher.group("relative")) { languageLevelFor(context) }
                    val arity = relativeLinkMatcher.group("arity").toInt()

                    callDefinitionResolveResults(context, module, relative, arity)
                        .filter { resolveResult ->
                            resolveResult
                                .element
                                .let { it as? Call }
                                ?.let(Callback.Companion::`is`)
                                ?: false
                        }
                        .let { Resolver.preferred(context, false, it) }.firstNotNullOfOrNull { it.element }
                }

                "t" -> {
                    val relative = identifierAtomName(relativeLinkMatcher.group("relative")) { languageLevelFor(context) }
                    val arity = relativeLinkMatcher.group("arity").toInt()

                    val resolveResults = if (module != null) {
                        MarkdownFlavourDescriptor
                            .modulars(context, module)
                            .flatMap { modular ->
                                org.elixir_lang.psi.scope.type.MultiResolve.resolveResults(
                                    relative,
                                    arity,
                                    false,
                                    modular
                                )
                            }
                    } else {
                        org.elixir_lang.psi.scope.type.MultiResolve.resolveResults(relative, arity, false, context)
                    }

                    resolveResults
                        .let { Resolver.preferred(context, false, it) }
                        .firstNotNullOfOrNull { it.element }
                }

                else -> {
                    LOG.warn("Don't know how to find element for link ($link) of kind ($kind) for ${context.javaClass.name}")

                    null
                }
            }
        } else {
            MarkdownFlavourDescriptor.modulars(context, link).firstOrNull()
        }
    }

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
    private fun documentedChoice(call: Call): Documented.Choice? {
        val capture = PsiTreeUtil.getParentOfType(call, NonNumeric::class.java)
            ?.reference
            ?.let { it as? CaptureNameArity }
            ?.takeIf { it.nameElement == call }

        return if (capture != null) {
            Use.capture(call, capture.arity)?.let(Documented::of)
        } else {
            when (val reference = call.reference) {
                is org.elixir_lang.reference.Callable -> Use.of(call)?.let {
                    Documented.of(it, bound = Callable.bound(call, false))
                }

                is org.elixir_lang.reference.CallDefinitionClause ->
                    Use.specification(call, reference.moduleAttribute)?.let(Documented::of)

                is PsiPolyVariantReference -> Documented.of(reference.multiResolve(false).toList())
                else -> null
            }
        }
    }

    /** A `defdelegate` [head] documents its delegation, then what it delegates to. */
    private fun delegationHeadDocumented(head: Call): PsiElement? {
        val delegation = DeclaringForm.named(head)?.call ?: return null
        val use = Use.of(head) as? Use.Named ?: return null
        val delegatedTo = atItsHead(delegation, use, Feature.DOCUMENTATION)?.admittedDelegatedTo.orEmpty()

        return Documented.documented(listOf(delegation) + delegatedTo.map { it.element })
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

        is Call if DeclaringForm.named(contextElement)?.form == Form.DELEGATION ->
            delegationHeadDocumented(contextElement)

        is Call -> documentedChoice(contextElement)?.let { choice ->
            // multiResolve matches names by prefix for completion, so a wrong-arity call falls back to the reached
            // definitions with exactly the called name, not to every name it starts.
            choice.element ?: run {
                val callName = contextElement.functionName()

                Documented.documented(
                    choice.reached.filter { element ->
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

    /** The definitions named [relative] at [arity] in the modules named [module], or in [context]'s module. */
    private fun callDefinitionResolveResults(
        context: PsiElement,
        module: String?,
        relative: String,
        arity: Int
    ): List<ResolveResult> =
        if (module != null) {
            MarkdownFlavourDescriptor
                .modulars(context, module)
                .flatMap { modular ->
                    org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                        relative,
                        arity,
                        false,
                        modular,
                        atom = true
                    )
                }
        } else {
            context
                .let { it as? Call }
                ?.let { call ->
                    call.takeIf(::isModular) ?: enclosingModularMacroCall(call)
                }
                ?.let { modular ->
                    org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                        relative,
                        arity,
                        false,
                        modular,
                        atom = true
                    )
                }
                ?.toList()
                .orEmpty()
        }

    /** The atom whose quoted name holds [element], unless [element] is inside an interpolation in that name. */
    private fun enclosingQuotedAtom(element: PsiElement): ElixirAtom? =
        PsiTreeUtil.getParentOfType(element, ElixirLine::class.java, false)
            ?.takeUnless { element.isInterpolatedWithin(it) }
            ?.parent as? ElixirAtom

    private fun formatDocs(element: PsiElement, fetchedDocs: FetchedDocs): String {
        val documentationHtml = StringBuilder()

        documentationHtml.append(DocumentationMarkup.DEFINITION_START)

        documentationHtml.append("<i>module</i> <b>").append(fetchedDocs.module).append("</b>\n")

        when (fetchedDocs) {
            is FetchedDocs.CallbackDocumentation -> {
                documentationHtml.append(fetchedDocs.head).append("\n")
            }

            is FetchedDocs.FunctionOrMacroDocumentation -> {
                for (head in fetchedDocs.heads) {
                    documentationHtml.append(head).append("\n")
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

            is FetchedDocs.FunctionOrMacroDocumentation -> {
                fetchedDocs.doc?.let { doc ->
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

                val deprecated = fetchedDocs.deprecated
                val impls = fetchedDocs.impls
                val implsIsNotEmpty = impls.isNotEmpty()
                val specs = fetchedDocs.specs
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

    private fun fetchDocs(element: PsiElement): FetchedDocs? =
    // For compiled Beam elements, prefer docs from the decompiled source mirror so hover uses
        // the cached inline docs from decompilation.
        if (element.containingFile?.originalFile is BeamFileImpl) {
            val sourceElement = (element as? PsiCompiledElement)?.mirror ?: element.navigationElement

            SourceFileDocsHelper.fetchDocs(sourceElement)
                ?: BeamDocsHelper.fetchDocs(element)
        } else {
            SourceFileDocsHelper.fetchDocs(element)
        }
}
