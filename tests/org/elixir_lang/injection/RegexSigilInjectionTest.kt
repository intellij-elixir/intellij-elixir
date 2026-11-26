package org.elixir_lang.injection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.intellij_elixir.refactoring.InPlaceSymbolRenameTestCase
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.intellij.lang.regexp.RegExpLanguage

/**
 * Tests for regex sigil injection to ensure interpolation doesn't cause false warnings
 */
class RegexSigilInjectionTest : InPlaceSymbolRenameTestCase() {
    private var originalEnableHtmlInjection = false

    override fun setUp() {
        super.setUp()
        val settings = ElixirExperimentalSettings.instance
        originalEnableHtmlInjection = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        ensureSigilInjectorRegistered()
    }

    override fun tearDown() {
        try {
            ElixirExperimentalSettings.instance.state.enableHtmlInjection = originalEnableHtmlInjection
        } finally {
            super.tearDown()
        }
    }

    /**
     * Test that interpolated regex sigils don't produce "Number expected" warnings
     * when using interpolation with curly braces.
     *
     * The issue: ElixirSigilInjector injects RegExpLanguage across the entire sigil body,
     * including interpolation tokens like #{...}. The regex parser treats { as a quantifier
     * start and expects a number, producing a false "Number expected" warning.
     */
    fun testInterpolatedRegexSigilNoFalseWarning() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test do
                    my_var = %{item: "value"}
                    my_regex = ~r/some text#{my_var.item}somemore text/
                  end
                end
            """.trimIndent()
        )
    }

    /**
     * Test with multiple interpolations in a regex sigil
     */
    fun testMultipleInterpolationsInRegexSigil() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test(params) do
                    regex = ~r|/text_values/#{params.param1}/more_text_values/#{params.param2}|
                  end
                end
            """.trimIndent()
        )
    }

    /**
     * A variable interpolated inside an injected regex sigil must be the *same* symbol as its
     * outer declaration: renaming it via the real Shift+F6 gesture (with the caret on the
     * interpolated usage) must rewrite both the declaration and the interpolated usage.
     */
    fun testInterpolatedRegexSigilVariableRenamesDeclarationAndUsage() {
        val file = myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    regex = ~r|/items/#{<caret>payload_type}|
                  end
                end
            """.trimIndent()
        )
        assertRegexInjectionPresent(file)

        inPlaceRenameAtCaret("resource_type")

        myFixture.checkResult(
            """
                defmodule Test do
                  def test do
                    resource_type = "foo"
                    regex = ~r|/items/#{resource_type}|
                  end
                end
            """.trimIndent()
        )
    }

    fun testInterpolatedRegexHeredocVariableRenamesDeclarationAndUsage() {
        val file = myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    regex = ~r'''
                    ^/items/#{<caret>payload_type}$
                    '''
                  end
                end
            """.trimIndent()
        )
        assertRegexInjectionPresent(file)

        inPlaceRenameAtCaret("resource_type")

        myFixture.checkResult(
            """
                defmodule Test do
                  def test do
                    resource_type = "foo"
                    regex = ~r'''
                    ^/items/#{resource_type}$
                    '''
                  end
                end
            """.trimIndent()
        )
    }

    // ---- Navigation (Ctrl+Click / Go To Declaration) ----

    /**
     * Ctrl+Click on a variable interpolated inside an injected regex sigil must navigate to its
     * outer declaration (`GTDUOutcome.GTD`), exactly as Ctrl+Click does anywhere else.
     */
    fun testCtrlClickOnInterpolatedRegexSigilVariableChoosesGotoDeclaration() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    regex = ~r|/items/#{<caret>payload_type}|
                  end
                end
            """.trimIndent()
        )
        assertGotoDeclarationAtCaret()
    }

    fun testCtrlClickOnInterpolatedRegexHeredocVariableChoosesGotoDeclaration() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    regex = ~r'''
                    ^/items/#{<caret>payload_type}$
                    '''
                  end
                end
            """.trimIndent()
        )
        assertGotoDeclarationAtCaret()
    }

    // ---- Completion ----

    /**
     * Completion at a caret inside an interpolation within an injected regex sigil must offer the
     * in-scope variables (the interpolation is host Elixir, excluded from the RegExp injection).
     */
    fun testCompletionInsideInterpolatedRegexSigilOffersInScopeVariables() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    payload_name = "bar"
                    regex = ~r|/items/#{payload<caret>}|
                  end
                end
            """.trimIndent()
        )
        assertInterpolationCompletionOffersInScopeVariables()
    }

    fun testCompletionInsideInterpolatedRegexHeredocOffersInScopeVariables() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    payload_name = "bar"
                    regex = ~r'''
                    ^/items/#{payload<caret>}$
                    '''
                  end
                end
            """.trimIndent()
        )
        assertInterpolationCompletionOffersInScopeVariables()
    }

    // ---- Find Usages ----

    /**
     * Find Usages from the declaration must include the read that lives inside the interpolation of
     * an injected regex sigil.
     */
    fun testFindUsagesFromDeclarationIncludesInterpolatedRegexSigilUsage() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    <caret>payload_type = "foo"
                    regex = ~r|/items/#{payload_type}|
                  end
                end
            """.trimIndent()
        )
        assertEquals(1, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    fun testFindUsagesFromDeclarationIncludesInterpolatedRegexHeredocUsage() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    <caret>payload_type = "foo"
                    regex = ~r'''
                    ^/items/#{payload_type}$
                    '''
                  end
                end
            """.trimIndent()
        )
        assertEquals(1, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    // ---- Negative: non-interpolating sigil (~R) treats #{...} as literal text ----

    /**
     * Uppercase sigils (e.g. `~R`) do **not** interpolate: `#{payload_type}` is literal text, not a
     * variable read. None of navigation, completion, or find-usages may treat it as a reference.
     */
    fun testCtrlClickInsideNonInterpolatingSigilFindsNoDeclaration() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    literal = ~R|/items/#{<caret>payload_type}|
                  end
                end
            """.trimIndent()
        )
        myFixture.assertNoNavigationAtCaret(
            "Literal text inside a non-interpolating sigil must not navigate to a declaration"
        )
    }

    fun testCompletionInsideNonInterpolatingSigilDoesNotOfferVariables() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    payload_type = "foo"
                    payload_name = "bar"
                    literal = ~R|/items/#{payload<caret>}|
                  end
                end
            """.trimIndent()
        )
        val strings = myFixture.completionStringsAtCaret()
        assertTrue(
            "Non-interpolating sigil text must not offer in-scope variables, got: $strings",
            strings == null || (!strings.contains("payload_type") && !strings.contains("payload_name"))
        )
    }

    fun testFindUsagesFromDeclarationExcludesNonInterpolatingSigilText() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def test do
                    <caret>payload_type = "foo"
                    literal = ~R|/items/#{payload_type}|
                  end
                end
            """.trimIndent()
        )
        assertEquals(0, myFixture.nonDeclarationUsageCountAtCaret(project))
    }


    /**
     * Test that literal regex sigils (uppercase ~R) work correctly without interpolation
     */
    fun testLiteralRegexSigilWithBraces() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test do
                    # Literal sigil with actual regex quantifier - should be valid
                    regex = ~R/a{2,3}/
                  end
                end
            """.trimIndent(),
            expectInjection = false
        )
    }

    /**
     * Test interpolation with nested braces
     */
    fun testInterpolatedRegexWithNestedBraces() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test do
                    pattern = "test"
                    regex = ~r/prefix #{pattern <> "{suffix}"} end/
                  end
                end
            """.trimIndent()
        )
    }

    /**
     * Test interpolated regex heredoc sigils
     */
    fun testInterpolatedRegexHeredoc() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test(params) do
                    regex = ~r'''
                    ^/devices/#{params.device_id}$
                    '''
                  end
                end
            """.trimIndent()
        )
    }

    /**
     * Test interpolated regex heredoc sigils with multiple lines
     */
    fun testInterpolatedRegexHeredocMultipleLines() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test(params) do
                    regex = ~r'''
                    ^/events/#{params.event_id}/devices/#{params.device_id}$
                    ^/events/#{params.event_id}/users/#{params.user_id}$
                    '''
                  end
                end
            """.trimIndent()
        )
    }

    /**
     * Test interpolated regex heredoc sigils with multi-line interpolation
     */
    fun testInterpolatedRegexHeredocMultilineInterpolation() {
        assertNoProblemHighlights(
            """
                defmodule Test do
                  def test(params) do
                    regex = ~r'''
                    ^/items/#{
                      params.item_id
                    }$
                    '''
                  end
                end
            """.trimIndent()
        )
    }

    /** An interpolation inside an interpolation's string lies inside the outer one, so only the outer one is cut. */
    fun testNestedInterpolationInRegexSigilIsCutWhereItIs() {
        myFixture.configureByText(
            "test.ex",
            "defmodule Test do\n  def test(c), do: ~r/aaaa#{\"b#{c}\"}d/\nend\n"
        )

        assertEquals("aaaad", injectedRegExpText())
    }

    fun testNestedInterpolationInRegexHeredocIsCutWhereItIs() {
        myFixture.configureByText(
            "test.ex",
            "defmodule Test do\n  def test(c) do\n~r\"\"\"\naaaa#{\"b#{c}\"}d\n\"\"\"\n  end\nend\n"
        )

        assertEquals("aaaad\n", injectedRegExpText())
    }

    private fun injectedRegExpText(): String {
        return InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(sigilHost(myFixture.file))
            .orEmpty()
            .map { it.first }
            .distinct()
            .filter { it.language.isKindOf(RegExpLanguage.INSTANCE) }
            .joinToString("") { it.text }
    }

    private fun assertNoProblemHighlights(text: String, expectInjection: Boolean = true) {
        val file = myFixture.configureByText("test.ex", text)
        if (expectInjection) {
            assertRegexInjectionPresent(file)
        }
        val problemHighlights = problemHighlights()
        assertTrue(
            "Unexpected highlights: ${formatHighlights(problemHighlights)}",
            problemHighlights.isEmpty()
        )
    }

    private fun assertGotoDeclarationAtCaret() {
        // Ctrl+Click chooses "go to declaration" (a single unambiguous target), not "show usages".
        myFixture.assertGotoDeclarationChosenAtCaret()

        // Perform the real navigation and assert WHERE it landed: the payload_type *declaration*
        // (the first occurrence in the file), not merely that some target existed.
        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration produced no destination element", destination)
        assertEquals("payload_type", destination!!.text)
        assertEquals(
            "Go To Declaration must land on the payload_type declaration",
            myFixture.file.text.indexOf("payload_type"),
            destination.textRange.startOffset
        )
    }

    private fun assertInterpolationCompletionOffersInScopeVariables() {
        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("Completion not shown inside interpolation", strings)
        assertTrue("Expected 'payload_type' offered inside interpolation, got: $strings", strings!!.contains("payload_type"))
        assertTrue("Expected 'payload_name' offered inside interpolation, got: $strings", strings.contains("payload_name"))
    }

    private fun assertRegexInjectionPresent(file: PsiFile) {
        val sigilLine = PsiTreeUtil.findChildOfType(file, SigilLine::class.java)
        val sigilHeredoc = PsiTreeUtil.findChildOfType(file, SigilHeredocLiteral::class.java)
        val host = sigilLine ?: sigilHeredoc
        checkNotNull(host) { "Sigil host not found in test file" }

        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
        assertTrue(
            "Expected RegExp injection in sigil",
            injected.any { it.first.language.isKindOf(RegExpLanguage.INSTANCE) }
        )
    }

    private fun sigilHost(file: PsiFile): PsiElement =
        checkNotNull(
            PsiTreeUtil.findChildOfType(file, SigilLine::class.java)
                ?: PsiTreeUtil.findChildOfType(file, SigilHeredocLiteral::class.java)
        ) { "Sigil host not found in test file" }

    private fun problemHighlights(): List<HighlightInfo> {
        val minimumSeverity = HighlightSeverity.WEAK_WARNING.myVal
        return myFixture.doHighlighting().filter { info ->
            info.severity.myVal >= minimumSeverity
        }
    }

    private fun formatHighlights(highlights: List<HighlightInfo>): String {
        return highlights.joinToString { info ->
            val description = info.description ?: info.severity.name
            "$description @ ${info.startOffset}-${info.endOffset}"
        }
    }

    private fun ensureSigilInjectorRegistered() {
        InjectedLanguageManager
            .getInstance(project)
            .registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)
    }

}
