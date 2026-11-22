package org.elixir_lang.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.*
import org.elixir_lang.psi.ModuleAttribute.isDocumentationName
import org.elixir_lang.psi.ModuleAttribute.isTypeName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.siblingExpression
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.operation.infix.Normalized

internal class Builder : FoldingBuilderEx() {
    /**
     * Builds the folding regions for the specified node in the AST tree and its children.
     *
     * @param root     the element for which folding is requested.
     * @param document the document for which folding is built. Can be used to retrieve line
     * numbers for folding regions.
     * @param quick    whether the result should be provided as soon as possible. Is true, when
     * an editor is opened and we need to auto-fold something immediately, like Java imports.
     * If true, one should perform no reference resolving and avoid complex checks if possible.
     * @return the array of folding descriptors.
     */
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        // An Elixir code block in documentation is a Frankenstein fragment stitched from lines of a
        // heredoc that also has Markdown injected over it. A fold region there anchors a smart pointer the
        // platform cannot reliably restore: InjectedSelfElementInfo.getInjectedFileIn picks between the
        // two injected files by comparing each one's *hull* (injectedToHost(0, length)), and the Markdown
        // file's hull still spans the whole documentation body - the code block included - even though
        // its shreds no longer overlap the Elixir ones. A pointer inside the Elixir fragment therefore
        // also falls inside the Markdown hull, and the platform's tie-break is HashSet iteration order,
        // with no language filter - tracked upstream as IJPL-18265.
        if (InjectedLanguageManager.getInstance(root.project).isFrankensteinInjection(root)) {
            return FoldingDescriptor.EMPTY_ARRAY
        }

        val foldingDescriptorList: MutableList<FoldingDescriptor> = ArrayList()
        PsiTreeUtil.processElements(root,
                                    object : PsiElementProcessor<PsiElement> {
                                        override fun execute(element: PsiElement): Boolean {
                                            when (element) {
                                                is AtUnqualifiedNoParenthesesCall<*> -> execute(element)
                                                is ElixirDoBlock -> execute(element)
                                                is ElixirStabOperation -> execute(element)
                                                is Call -> execute(element)
                                            }
                                            return true
                                        }

                                        private fun execute(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>) {
                                            val moduleAttributeName =
                                                ElixirPsiImplUtil.moduleAttributeName(atUnqualifiedNoParenthesesCall)
                                            val name = moduleAttributeName.substring(1)
                                            if (isDocumentationName(name)) {
                                                foldIfSpansLines(atUnqualifiedNoParenthesesCall.noParenthesesOneArgument, "\"...\"")
                                            } else if (isTypeName(name)) {
                                                (atUnqualifiedNoParenthesesCall.argument as? Type)
                                                    ?.let { Normalized.rightOperand(it) }
                                                    ?.let { foldIfSpansLines(it, "...") }
                                            }
                                        }

                                        private fun foldIfSpansLines(element: PsiElement, placeholderText: String) {
                                            if (spansLines(element.textRange)) {
                                                foldingDescriptorList.add(
                                                    FoldingDescriptor(element.node, element.textRange, null, placeholderText)
                                                )
                                            }
                                        }

                                        private fun execute(call: Call) {
                                            val directive = directiveName(call) ?: return
                                            if (!isFirstInGroup(call, directive)) return
                                            val firstFinalArgument = call.finalArguments()?.firstOrNull() ?: return
                                            val textRange = TextRange(
                                                firstFinalArgument.textOffset,
                                                lastInGroup(call, directive).textRange.endOffset
                                            )
                                            if (spansLines(textRange)) {
                                                foldingDescriptorList.add(
                                                    FoldingDescriptor(
                                                        call.parent.node,
                                                        textRange,
                                                        null,
                                                        "...",
                                                        ElixirFoldingSettings.getInstance().isCollapseElixirModuleDirectiveGroups,
                                                        emptySet()
                                                    )
                                                )
                                            }
                                        }

                                        private fun execute(doBlock: ElixirDoBlock) {
                                            foldingDescriptorList.add(FoldingDescriptor(doBlock, doBlock.textRange))
                                        }

                                        private fun execute(stabOperation: ElixirStabOperation) {
                                            val startOffset = stabOperation.operator().textOffset
                                            val endOffset = stabOperation.textRange.endOffset
                                            val textRange = TextRange(startOffset, endOffset)
                                            foldingDescriptorList.add(FoldingDescriptor(stabOperation, textRange))
                                        }

                                        /**
                                         * The gutter offers no fold icon for a one-line region, so once expanded it could
                                         * not be folded again by mouse.
                                         */
                                        private fun spansLines(textRange: TextRange): Boolean =
                                            document.getLineNumber(textRange.startOffset) !=
                                                    document.getLineNumber(textRange.endOffset)

                                        private fun isFirstInGroup(call: Call, resolvedFunctionName: String): Boolean =
                                            (ElixirPsiImplUtil.previousSiblingExpression(call) as? Call)
                                                ?.isCalling(Module.KERNEL, resolvedFunctionName) != true

                                        private fun lastInGroup(first: Call, resolvedFunctionName: String): Call =
                                            generateSequence(first) { last ->
                                                (last.siblingExpression(ElixirPsiImplUtil.NEXT_SIBLING) as? Call)
                                                    ?.takeIf { it.isCalling(Module.KERNEL, resolvedFunctionName) }
                                            }.last()
                                    }
        )
        return foldingDescriptorList.toTypedArray()
    }

    /**
     * Returns the text which is displayed in the editor for the folding region related to the
     * specified node when the folding region is collapsed.
     *
     * @param node the node for which the placeholder text is requested.
     * @return the placeholder text.
     */
    override fun getPlaceholderText(node: ASTNode): String? =
        when (node.psi) {
            is ElixirDoBlock -> "do: ..."
            is ElixirStabOperation -> "-> ..."
            else -> null
        }

    /** Only a module directive group can start collapsed, and its descriptor says whether it does. */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

private val RESOLVED_FUNCTION_NAMES = arrayOf(Function.ALIAS, Function.IMPORT, Function.REQUIRE, Function.USE)

/** The module directive, `alias`, `import`, `require` or `use`, [call] is, or `null` when it is none. */
private fun directiveName(call: Call): String? =
    RESOLVED_FUNCTION_NAMES.firstOrNull { call.isCalling(Module.KERNEL, it) }
