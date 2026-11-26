package org.elixir_lang.injection.markdown

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.ElixirInterpolation
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * The code in a documentation string's interpolation is Elixir to every feature, never Markdown.
 *
 * Injection is driven through `enumerateEx` on every element, as [ExpressionDocInjectorTest] does, because
 * `getInjectedPsiFiles` returns `null` for an invalid host instead of throwing. The first check of each shape is
 * that no injected file reaches an offset inside an interpolation: Markdown paints only where it is injected.
 */
class DocumentationInterpolationInjectionTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    // ---- The Markdown text, and no injected file inside an interpolation ----

    fun testLine() = assertShape("@doc \"text #{x} more\"", "text  more")

    fun testTwoInterpolationsOnALine() = assertShape("@doc \"a #{x} b #{y} c\"", "a  b  c")

    fun testInterpolationsAtTheStartAndTheEnd() = assertShape("@doc \"#{x} text #{y}\"", " text ")

    fun testEmptyInterpolation() = assertShape("@doc \"a #{} b\"", "a  b")

    fun testCharlist() = assertShape("@doc 'a #{x} b'", "a  b")

    fun testModuledoc() = assertShape("@moduledoc \"a #{x} b\"", "a  b")

    fun testTypedoc() = assertShape("@typedoc \"a #{x} b\"", "a  b")

    fun testDeprecated() = assertShape("@doc deprecated: \"a #{x} b\"", "a  b")

    fun testQuotedDeprecated() = assertShape("@doc \"deprecated\": \"a #{x} b\"", "a  b")

    fun testDeprecatedHeredoc() = assertShape(heredoc("@doc deprecated: ", "a #{x} b"), "a  b\n")

    fun testHeredoc() = assertShape(flushHeredoc("@doc ", "Hello #{name}.", "Next line"), "Hello .\nNext line\n")

    fun testCharlistHeredoc() = assertShape("@doc '''\n  Hello #{name}.\n  '''", "Hello .\n")

    fun testSigilHeredoc() = assertShape(heredoc("@doc ~s", "Hello #{name}."), "Hello .\n")

    fun testInterpolationSpanningLines() =
        assertShape("@doc \"\"\"\n  Hello #{\n    name\n  } there\n  \"\"\"", "Hello  there\n")

    fun testNestedInterpolation() = assertShape("@doc \"a #{\"b#{c}\"} d\"", "a  d")

    fun testNestedInterpolationInAHeredoc() = assertShape(heredoc("@doc ", "a #{\"b#{c}\"} d"), "a  d\n")

    fun testValueThatIsAllInterpolationInjectsNothing() {
        configure("@doc \"#{x}\"")

        assertEquals("a value that is all interpolation injects no Markdown", emptyList<String>(), markdownTexts())
        assertNoInjectionInsideInterpolations()
    }

    fun testRunThatIsOnlyAnInterpolationKeepsTheCodeBlocksIndent() =
        assertShape(flushHeredoc("@doc ", "    code", "#{x}", "Text"), "    \nText\n")

    // ---- The Elixir injected into a code block ----

    fun testCodeBlockLineHoldsNoInterpolation() {
        val doc = flushHeredoc("@doc ", "Hello #{name}.", "    code #{x}", "Text")

        assertShape(doc, "Hello .\n    Text\n")
        assertEquals(listOf("code \n"), elixirTexts())
    }

    fun testCodeBlockLinesHoldNoInterpolation() {
        configure(heredoc("@doc ", "    foo(#{a})", "    bar(#{b})"))

        assertEquals(listOf("foo()\nbar()\n"), elixirTexts())
        assertNoInjectionInsideInterpolations()
    }

    fun testIexPromptLineHoldsNoInterpolation() {
        configure(heredoc("@doc ", "    iex> foo(#{a})"))

        assertEquals(listOf("foo()\n"), elixirTexts())
        assertNoInjectionInsideInterpolations()
    }

    // ---- Typing keeps it ----

    fun testTypingInALineInterpolation() {
        configure("@doc \"a #{<caret>} b\"")
        myFixture.type("x")

        assertNoInjectionInsideInterpolations()
        assertEquals(listOf("a  b"), markdownTexts())
    }

    fun testTypingInAHeredocInterpolation() {
        configure(heredoc("@doc ", "a #{<caret>} b"))
        myFixture.type("x")

        assertNoInjectionInsideInterpolations()
        assertEquals(listOf("a  b\n"), markdownTexts())
    }

    // ---- Completion in an interpolation is what it is anywhere else ----

    fun testCompletionInALine() = assertCompletionLikeFoo("@ATTRIBUTE \"text #{CARET}\"")

    fun testCompletionInALineAfterAnOperator() = assertCompletionLikeFoo("@ATTRIBUTE \"text #{1 + CARET}\"")

    fun testCompletionInALineAfterADot() = assertCompletionLikeFoo("@ATTRIBUTE \"text #{Kernel.CARET}\"")

    fun testCompletionBetweenText() = assertCompletionLikeFoo("@ATTRIBUTE \"a #{1 + CARET} b\"")

    fun testCompletionInAHeredoc() = assertCompletionLikeFoo("@ATTRIBUTE \"\"\"\n  text #{CARET}\n  \"\"\"")

    fun testCompletionInAHeredocAfterAnOperator() = assertCompletionLikeFoo("@ATTRIBUTE \"\"\"\n  text #{1 + CARET}\n  \"\"\"")

    fun testCompletionInAHeredocAfterADot() = assertCompletionLikeFoo("@ATTRIBUTE \"\"\"\n  text #{Kernel.CARET}\n  \"\"\"")

    fun testCompletionInAModuledoc() = assertCompletionLikeFoo("@ATTRIBUTE \"text #{1 + CARET}\"", documentation = "moduledoc")

    fun testCompletionInATypedoc() = assertCompletionLikeFoo("@ATTRIBUTE \"text #{1 + CARET}\"", documentation = "typedoc")

    // ---- Pins: what already works stays working ----

    fun testGotoDeclarationFromACallInterpolation() {
        configure("@default 1\n  @doc \"Defaults to #{inspect(@def<caret>ault)}.\"")

        assertGotoDeclarationLandsOnDefault()
    }

    fun testGotoDeclarationFromAnInterpolation() {
        configure("@default 1\n  @doc \"max #{@default<caret>} chars\"")

        assertGotoDeclarationLandsOnDefault()
    }

    fun testFindUsagesCountsTheInterpolatedReads() {
        configure(
            "@<caret>default 1\n  @doc \"Defaults to #{inspect(@default)}.\"\n  @moduledoc \"max #{@default} chars\""
        )

        assertEquals(2, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    fun testLiteralHeredocKeepsItsInterpolationAsMarkdown() {
        configure(heredoc("@doc ~S", "text #{x}"))

        assertEquals(listOf("text #{x}\n"), markdownTexts())
    }

    fun testStringNestedInAnInterpolationGetsNoMarkdown() {
        configure("@doc \"a #{\"b\"} c\"")

        assertEquals("the nested string is no documentation: only the outer string has Markdown", 1, markdownTexts().size)
    }

    // ---- Helpers ----

    /** [attribute] before a `"""` heredoc of [lines], indented as the module's body is. */
    private fun heredoc(attribute: String, vararg lines: String): String =
        "$attribute\"\"\"\n" + lines.joinToString("") { "  $it\n" } + "  \"\"\""

    /**
     * [attribute] before a `"""` heredoc of [lines] at column 0. In a multi-line Markdown file the platform patches the
     * end-of-line token with the host text up to the next line's content, so an indented heredoc's indent follows each
     * newline; a row that compares a multi-line text uses this to leave it out.
     */
    private fun flushHeredoc(attribute: String, vararg lines: String): String =
        "$attribute\"\"\"\n" + lines.joinToString("") { "$it\n" } + "\"\"\""

    /** The configured file, whose `myFixture.file` is the injected file once the caret is in one. */
    private fun hostFile(): PsiFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)

    private fun configure(body: String): PsiFile =
        myFixture.configureByText("doc.ex", "defmodule Doc do\n  $body\n  def f(x), do: 1\nend\n")

    /** No injected file inside an interpolation, and the Markdown text is [markdown]. */
    private fun assertShape(doc: String, markdown: String) {
        configure(doc)

        val problems = listOfNotNull(
            injectionInsideInterpolation(),
            markdownTexts().let { if (it == listOf(markdown)) null else "Markdown text: expected <$markdown> but was $it" }
        )

        assertEmpty("`$doc`:\n${problems.joinToString("\n")}", problems)
    }

    private fun assertNoInjectionInsideInterpolations() {
        injectionInsideInterpolation()?.let(::fail)
    }

    /** A message for the first offset inside an interpolation that an injected file reaches, or `null` for none. */
    private fun injectionInsideInterpolation(): String? {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // run the injectors and highlighting before probing offsets
        injectedFiles()

        val file = hostFile()
        val manager = InjectedLanguageManager.getInstance(project)
        val interpolations = PsiTreeUtil.findChildrenOfType(file, ElixirInterpolation::class.java)

        assertFalse("no interpolation in <${file.text}>", interpolations.isEmpty())

        for (interpolation in interpolations) {
            // from just after `#` to `}` inclusive
            for (offset in interpolation.textRange.startOffset + 1 until interpolation.textRange.endOffset) {
                val injected = manager.findInjectedElementAt(file, offset)

                if (injected != null) {
                    return "a ${injected.containingFile.language.id} file (<${injected.containingFile.text}>) is injected at offset " +
                        "$offset (`${file.text[offset]}`) of the interpolation `${interpolation.text}`"
                }
            }
        }

        return null
    }

    private fun markdownTexts(): List<String> = injectedFiles().filter { it.language == MarkdownLanguage.INSTANCE }.map { it.text }

    private fun elixirTexts(): List<String> = injectedFiles().filter { it.language.isKindOf(ElixirLanguage) }.map { it.text }

    /** Every file injected into the configured one, driven through `enumerateEx` on every element. */
    private fun injectedFiles(): List<PsiFile> {
        val file = hostFile()
        val manager = InjectedLanguageManager.getInstance(project)
        val injected = LinkedHashSet<PsiFile>()

        val (_, loggedErrors) = captureLoggedErrors {
            PsiTreeUtil.collectElements(file) { true }.forEach { element ->
                manager.enumerateEx(element, file, true) { injectedPsi, _ -> injected.add(injectedPsi) }
            }
            myFixture.doHighlighting()
        }

        assertEmpty("logged errors: ${loggedErrors.map { it.message }}", loggedErrors)

        return injected.toList()
    }

    /**
     * Completion at `CARET` in [template] with `ATTRIBUTE` as the documentation attribute is non-empty, and the same as with
     * `ATTRIBUTE` as `foo`, where the string is no documentation.
     */
    private fun assertCompletionLikeFoo(template: String, documentation: String = "doc") {
        expanderOn()

        val suffix = if (documentation == "typedoc") "\n  @type t :: integer" else ""
        val offeredAs = { attribute: String ->
            configure(template.replace("ATTRIBUTE", attribute).replace("CARET", "<caret>") + suffix)
            myFixture.completionStringsAtCaret().orEmpty()
        }

        val inDocumentation = offeredAs(documentation)
        val inFoo = offeredAs("foo")

        assertNotEmpty(inFoo)
        assertEquals("completion in `@$documentation` differs from `@foo`", inFoo, inDocumentation)
    }

    private fun assertGotoDeclarationLandsOnDefault() {
        val declaration = hostFile().text.indexOf("@default 1")
        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration produced no destination element", destination)

        assertTrue(
            "Go To Declaration must land on the `@default 1` declaration at $declaration, landed at ${destination!!.textOffset} (`${destination.text}`) of ${destination.containingFile.name}",
            destination.textOffset in declaration..declaration + "@default 1".length
        )
    }
}
