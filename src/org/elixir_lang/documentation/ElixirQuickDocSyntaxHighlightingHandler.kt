package org.elixir_lang.documentation

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.richcopy.SyntaxInfoBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.ElixirLexer
import org.elixir_lang.ElixirSyntaxHighlighter
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.Operation
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * Produces semantic highlighting ranges for Elixir code blocks in rendered documentation.
 *
 * The platform's lexer-based highlighting handles basic token coloring (strings, numbers,
 * atoms, operators, keywords). This highlighter adds semantic overlays that require PSI
 * analysis:
 * - [ElixirAlias] -> alias color (e.g. `Enum`, `Logger`)
 * - [CallDefinitionClause] -> keyword+macro styling on `def`/`defp`/`defmacro`,
 *   plus function/macro declaration styling on the defined name
 * - Named function/macro calls -> function call or macro call styling
 * - `end` keyword tokens -> keyword styling (via lexer scan, since `end` is not a Call)
 *
 * Operators ([Operation] instances like `|>`, `+`, `==`) are explicitly skipped since
 * their styling is handled correctly by the lexer.
 */
internal object ElixirRenderedDocSemanticHighlighter {
	/**
	 * Creates an additional [SyntaxInfoBuilder.RangeIterator] that overlays semantic
	 * highlighting on top of the lexer-based highlighting for rendered Elixir code blocks.
	 *
	 * @param project the current project (used to create a temporary PSI file for analysis)
	 * @param code the Elixir source code to analyze (already trimmed/dedented)
	 * @return a range iterator with semantic highlights, or `null` if none are applicable
	 */
	fun additionalIterator(project: Project, code: String): SyntaxInfoBuilder.RangeIterator? {
		if (code.isEmpty()) {
			return null
		}

		val file = PsiFileFactory.getInstance(project).createFileFromText(ElixirLanguage, code)
		val ranges = collectRanges(file)

		return if (ranges.isEmpty()) {
			null
		} else {
			SemanticRangeIterator(ranges)
		}
	}

	private fun collectRanges(file: PsiFile): List<SemanticRange> {
		val ranges = mutableListOf<SemanticRange>()

		file.accept(object : PsiRecursiveElementWalkingVisitor() {
			override fun visitElement(element: PsiElement) {
				when (element) {
					is ElixirAlias -> addRange(
						ranges,
						element.textRange.startOffset,
						element.textRange.endOffset,
						arrayOf(ElixirSyntaxHighlighter.ALIAS)
					)

					is Call -> addCallRanges(element, ranges)
				}

				super.visitElement(element)
			}
		})

		addEndKeywordRanges(file.text, ranges)

		return ranges
			.filter { range -> range.startOffset < range.endOffset }
			.sortedWith(compareBy(SemanticRange::startOffset, SemanticRange::endOffset))
	}

	private fun addCallRanges(call: Call, ranges: MutableList<SemanticRange>) {
		// Skip module attribute calls and operators - their styling comes from the lexer
		if (call is AtOperation || call is AtUnqualifiedNoParenthesesCall<*> || call is Operation) {
			return
		}

		if (CallDefinitionClause.`is`(call)) {
			call.functionNameElement()?.let { definitionMacro ->
				addRange(
					ranges,
					definitionMacro.textRange.startOffset,
					definitionMacro.textRange.endOffset,
					arrayOf(
						ElixirSyntaxHighlighter.KEYWORD,
						ElixirSyntaxHighlighter.MACRO_CALL
					)
				)
			}

			addDefinitionRange(call, ranges)
			return
		}

		call.functionNameElement()?.let { functionNameElement ->
			val textAttributesKeys =
				if (call.hasDoBlockOrKeyword()) arrayOf(ElixirSyntaxHighlighter.MACRO_CALL)
				else arrayOf(ElixirSyntaxHighlighter.FUNCTION_CALL)

			addRange(ranges, functionNameElement.textRange.startOffset, functionNameElement.textRange.endOffset, textAttributesKeys)
		}
	}

	private fun addDefinitionRange(call: Call, ranges: MutableList<SemanticRange>) {
		val head = CallDefinitionClause.head(call) ?: return
		val stripped = CallDefinitionHead.strip(head)

		if (stripped is Call) {
			stripped.functionNameElement()?.let { functionNameElement ->
				CallableDeclaration.definerOf(call)?.let { definer ->
					addRange(
						ranges,
						functionNameElement.textRange.startOffset,
						functionNameElement.textRange.endOffset,
						arrayOf(ElixirSyntaxHighlighter.declarationKey(definer.capabilities.presentation))
					)
				}
			}
		}
	}

	private fun addRange(
		ranges: MutableList<SemanticRange>,
		startOffset: Int,
		endOffset: Int,
		textAttributesKeys: Array<TextAttributesKey>
	) {
		mergeTextAttributes(textAttributesKeys)?.let { textAttributes ->
			ranges.add(SemanticRange(startOffset, endOffset, textAttributes))
		}
	}

	private fun mergeTextAttributes(textAttributeKeys: Array<TextAttributesKey>): TextAttributes? {
		val scheme = EditorColorsManager.getInstance().globalScheme

		return textAttributeKeys.fold(null as TextAttributes?) { merged, key ->
			TextAttributes.merge(merged, scheme.getAttributes(key))
		}
	}

	private fun addEndKeywordRanges(text: String, ranges: MutableList<SemanticRange>) {
		val lexer = ElixirLexer()
		lexer.start(text)

		while (true) {
			val tokenType = lexer.tokenType ?: break

			if (tokenType == ElixirTypes.END) {
				addRange(
					ranges,
					lexer.tokenStart,
					lexer.tokenEnd,
					arrayOf(
						ElixirSyntaxHighlighter.KEYWORD,
						ElixirSyntaxHighlighter.MACRO_CALL
					)
				)
			}

			lexer.advance()
		}
	}

	private data class SemanticRange(
		val startOffset: Int,
		val endOffset: Int,
		val textAttributes: TextAttributes
	)

	private class SemanticRangeIterator(
		private val ranges: List<SemanticRange>
	) : SyntaxInfoBuilder.RangeIterator {
		private var index = -1

		override fun atEnd(): Boolean = index >= ranges.lastIndex

		override fun advance() {
			index++
		}

		override fun getRangeStart(): Int = current().startOffset

		override fun getRangeEnd(): Int = current().endOffset

		override fun getTextAttributes(): TextAttributes = current().textAttributes

		override fun dispose() = Unit

		private fun current(): SemanticRange = ranges[index.coerceIn(0, ranges.lastIndex)]
	}
}
