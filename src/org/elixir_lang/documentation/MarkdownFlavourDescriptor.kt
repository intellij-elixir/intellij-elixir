package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import org.elixir_lang.Module
import org.elixir_lang.psi.ElementFactory
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.name.Module.ELIXIR_PREFIX
import org.elixir_lang.psi.impl.indexName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.reference.resolver.Module as ModuleResolver
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.URI
import org.intellij.markdown.parser.LinkMap
import java.util.regex.Pattern

/**
 * GFM-based markdown flavour for Elixir documentation rendering.
 *
 * Overrides the default code block providers to add syntax highlighting
 * ([CodeBlockHtmlProvider], [CodeFenceHtmlProvider]) and augments `CODE_SPAN`
 * with hyperlink resolution for module and function references. Where the text was written in another module than the
 * one [element] is in, [writtenIn] names it, and a link to a function of no module is to that module's.
 */
class MarkdownFlavourDescriptor(private val element: PsiElement, private val writtenIn: String? = null) : GFMFlavourDescriptor() {
    private val project: Project = element.project

    fun renderGenericCode(
        gfmHtmlGeneratingProvider: GeneratingProvider?,
        visitor: HtmlGenerator.HtmlGeneratingVisitor,
        text: String,
        node: ASTNode
    ) {
        visitor.consumeTagOpen(
            node,
            "code",
        )
        gfmHtmlGeneratingProvider!!.processNode(visitor, text, node)
        visitor.consumeTagClose("code")
    }

    override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> =
        super
            .createHtmlGeneratingProviders(linkMap, baseURI)
            .toMutableMap()
            .apply {
                compute(MarkdownElementTypes.CODE_BLOCK) { _, _ ->
                    CodeBlockHtmlProvider(project)
                }

                compute(MarkdownElementTypes.CODE_FENCE) { _, _ ->
                    CodeFenceHtmlProvider(project)
                }

                compute(MarkdownElementTypes.CODE_SPAN) { _, gfmHtmlGeneratingProvider ->
                    object : GeneratingProvider {
                        override fun processNode(
                            visitor: HtmlGenerator.HtmlGeneratingVisitor,
                            text: String,
                            node: ASTNode
                        ) {
                            val span = SpanLink.of(node, text)

                            if (span != null && linkable(span)) {
                                visitor.consumeTagOpen(
                                    node,
                                    "a",
                                    "href=\"${DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL}${StringUtil.escapeXmlEntities(span.qualifiedBy(writtenIn).link)}\""
                                )
                                gfmHtmlGeneratingProvider!!.processNode(visitor, text, node)
                                visitor.consumeTagClose("a")
                            } else {
                                renderGenericCode(gfmHtmlGeneratingProvider, visitor, text, node)
                            }
                        }
                    }
                }
            }

    /** Whether [span] names something the project has; a function of no module is the document's own, and not looked up. */
    private fun linkable(span: SpanLink): Boolean {
        val parsed = DocumentationLink.parse(span.link)

        return if (parsed != null) {
            val module = parsed.module
            val arity = parsed.arity

            when {
                arity == null -> false
                module == null -> true
                parsed.kind == null || parsed.kind == "c" -> FunctionLink.exists(
                    element,
                    module,
                    parsed.name,
                    arity,
                    callback = parsed.kind == "c"
                )
                else -> modulars(element, module).isNotEmpty()
            }
        } else {
            span.kind == null && !span.name.startsWith(":") && modulars(element, span.name).isNotEmpty()
        }
    }

    /**
     * A code span read as a link from its text, however the Markdown parser split it: a `c` or `t` [kind] for a callback
     * or type, then the [name], a module or function, whose module may be written as an atom.
     */
    private class SpanLink(val kind: String?, val name: String) {
        val link: String get() = if (kind == null) name else "$kind:$name"

        /** This link as written in [module]: a function, callback or type of no module is [module]'s. */
        fun qualifiedBy(module: String?): SpanLink {
            val parsed = if (module != null) DocumentationLink.parse(link) else null

            return if (parsed != null && parsed.module == null && parsed.arity != null) SpanLink(kind, "$module.$name") else this
        }

        companion object {
            private val SHAPE: Pattern =
                Pattern.compile("((?<kind>[ct]):)?(?<name>(:\"[^\"]*\"|:)?[^\\s\"]+|:\"[^\"]*\")")

            fun of(node: ASTNode, text: String): SpanLink? {
                if (node.children.size < 2) return null

                val content = text.substring(node.children.first().endOffset, node.children.last().startOffset)
                val matcher = SHAPE.matcher(content)

                return if (matcher.matches()) SpanLink(matcher.group("kind"), matcher.group("name")) else null
            }
        }
    }

    companion object {
        /** The modulars a link's [name] names, as they are named from [element]. */
        fun modulars(element: PsiElement, name: String): List<PsiElement> =
            indexName(element.project, name)
                ?.let { indexName ->
                    ModuleResolver
                        .resolvePreferred(element, indexName, incompleteCode = false, inScope = false)
                        .map { it.element }
                }
                .orEmpty()

        /**
         * A module named with an atom is indexed by the atom's value, however the link quotes it; `null` when the atom
         * has no value. An `Elixir.` link names the atom it spells, as `Elixir.Foo` does in source.
         */
        private fun indexName(project: Project, name: String): String? =
            when {
                name.startsWith(":") ->
                    when (val atom = ElementFactory.createFile(project, name).firstChild?.stripAccessExpression()) {
                        is ElixirAtom -> atom.indexName()
                        else -> name
                    }
                name.startsWith(ELIXIR_PREFIX) -> Module.indexName(name)
                else -> name
            }
    }
}
