package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.project.Project
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
import org.intellij.markdown.ast.getTextInNode
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
 * with hyperlink resolution for module and function references.
 */
class MarkdownFlavourDescriptor(private val element: PsiElement) : GFMFlavourDescriptor() {
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
                            when (node.children.size) {
                                3 -> {
                                    val nameChild = node.children[1]
                                    val name = nameChild.getTextInNode(text).toString()
                                    val moduleRelativeArityMatcher = MODULE_RELATIVE_ARITY_PATTERN.matcher(name)

                                    val link = if (moduleRelativeArityMatcher.matches()) {
                                        val module = moduleRelativeArityMatcher.group("module")

                                        if (module != null) {
                                            val relative = moduleRelativeArityMatcher.group("relative")
                                            val arity = moduleRelativeArityMatcher.group("arity").toInt()

                                            val functionCount =
                                                module
                                                    .let { modulars(element, it) }
                                                    .flatMap { modular ->
                                                        org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                                                            relative,
                                                            arity,
                                                            false,
                                                            modular
                                                        )
                                                    }
                                                    .count { it.isValidResult }

                                            functionCount > 0
                                        } else {
                                            true
                                        }
                                    } else {
                                        modulars(element, name).isNotEmpty()
                                    }

                                    if (link) {
                                        visitor.consumeTagOpen(
                                            node,
                                            "a",
                                            "href=\"${DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL}${name}\""
                                        )
                                        gfmHtmlGeneratingProvider!!.processNode(visitor, text, node)
                                        visitor.consumeTagClose("a")
                                    } else {
                                        renderGenericCode(gfmHtmlGeneratingProvider, visitor, text, node)
                                    }
                                }
                                5 -> {
                                    when (val kind = node.children[1].getTextInNode(text).toString()) {
                                        "c", "t" -> {
                                            val nameChild = node.children[3]
                                            val name = nameChild.getTextInNode(text).toString()
                                            val moduleRelativeArityMatcher = MODULE_RELATIVE_ARITY_PATTERN.matcher(name)

                                            if (moduleRelativeArityMatcher.matches()) {
                                                val module = moduleRelativeArityMatcher.group("module")

                                                val link = if (module != null) {
                                                    modulars(element, module).isNotEmpty()
                                                } else {
                                                    true

                                                }

                                                if (link) {
                                                    visitor.consumeTagOpen(
                                                        node,
                                                        "a",
                                                        "href=\"${
                                                            DocumentationManagerProtocol
                                                                .PSI_ELEMENT_PROTOCOL
                                                        }${kind}:${name}\""
                                                    )
                                                    gfmHtmlGeneratingProvider!!.processNode(visitor, text, node)
                                                    visitor.consumeTagClose("a")
                                                } else {
                                                    renderGenericCode(gfmHtmlGeneratingProvider, visitor, text, node)
                                                }
                                            }
                                        }
                                        else -> renderGenericCode(gfmHtmlGeneratingProvider, visitor, text, node)
                                    }
                                }
                                else -> renderGenericCode(gfmHtmlGeneratingProvider, visitor, text, node)
                            }
                        }
                    }
                }
            }

    companion object {
        val MODULE_RELATIVE_ARITY_PATTERN: Pattern =
            Pattern.compile("((?<module>.+)\\.)?(?<relative>.+)/(?<arity>\\d+)")

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
