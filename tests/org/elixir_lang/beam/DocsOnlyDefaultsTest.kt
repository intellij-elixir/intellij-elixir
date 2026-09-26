package org.elixir_lang.beam

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.documentation.quickDocumentationAtCaret

/**
 * A dependency compiled with docs but no debug info: its decompiled source writes each lower arity of a function with
 * defaults as a clause of its own, beside the head that carries the defaults. The function is the head with the
 * defaults, at every arity (`build.exs` rebuilds the fixture).
 */
class DocsOnlyDefaultsTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/docs_only_defaults"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
    }

    fun testEitherArityResolvesToTheHeadWithTheDefaults() {
        for (call in listOf("snoc(a<caret>)", "snoc(a, a<caret>)")) {
            configure("DocsOnlyDefaults.$call")
            val lines = destinationLines()
            assertTrue("$call went to $lines", lines.isNotEmpty() && lines.all { "x \\\\ nil" in it })
        }
    }

    fun testEitherArityOfABodilessHeadResolvesToTheHead() {
        for (call in listOf("pair(a<caret>)", "pair(a, a<caret>)")) {
            configure("DocsOnlyDefaults.$call")
            val lines = destinationLines()
            assertTrue("$call went to $lines", lines.isNotEmpty() && lines.all { "b \\\\ nil" in it })
        }
    }

    fun testQuickDocsAtALowerArityShowTheFunctionsDoc() {
        for (use in listOf("DocsOnlyDefaults.sn<caret>oc(a)", "apply(DocsOnlyDefaults, :sn<caret>oc, [a])", "{DocsOnlyDefaults, :sn<caret>oc, 1}")) {
            configure(use)
            val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()
            assertTrue("$use: $documentation", "Appends an element." in documentation && "x \\\\ nil" in documentation)
        }
    }

    fun testQuickDocsAtALowerArityOfABodilessHeadShowTheFunctionsDoc() {
        for (use in listOf("DocsOnlyDefaults.pa<caret>ir(a)", "apply(DocsOnlyDefaults, :pa<caret>ir, [a])", "{DocsOnlyDefaults, :pa<caret>ir, 1}")) {
            configure(use)
            val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()
            assertTrue("$use: $documentation", "Joins two, both optional." in documentation && "b \\\\ nil" in documentation)
        }
    }

    /** The first line of the decompiled source each valid result navigates to. */
    private fun destinationLines(): List<String> =
        generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .mapNotNull { it.reference as? PsiPolyVariantReference }
            .first()
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.navigationElement?.text?.lineSequence()?.firstOrNull()?.trim() }

    private fun configure(use: String) {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(a), do: $use\nend\n")
        // Let the library's roots change and refresh finish before a gesture's background read waits on them.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
