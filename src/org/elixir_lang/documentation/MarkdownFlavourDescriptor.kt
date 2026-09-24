package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.stub.index.ModularName
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
class MarkdownFlavourDescriptor(private val project: Project) : GFMFlavourDescriptor() {
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
                                                    .let { modulars(project, it) }
                                                    .flatMap { modular ->
                                                        org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.remoteResults(
                                                            relative,
                                                            arity,
                                                            false,
                                                            modular,
                                                            runtime = false
                                                        )
                                                    }
                                                    .count { it.isValidResult }

                                            functionCount > 0
                                        } else {
                                            true
                                        }
                                    } else {
                                        modulars(project, name).isNotEmpty()
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
                                                    modulars(project, name).isNotEmpty()
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

        fun modulars(project: Project, name: String): Collection<PsiElement> {
            val globalSearchScope = GlobalSearchScope.allScope(project)

            return StubIndex
                .getElements(
                    ModularName.KEY,
                    name,
                    project,
                    globalSearchScope,
                    null,
                    NamedElement::class.java
                )
        }
    }
}
