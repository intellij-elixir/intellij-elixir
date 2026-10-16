package org.elixir_lang.documentation

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.beam.BeamLibraryTestCase

/**
 * A dependency compiled with docs but no debug info: its decompiled source writes each lower arity of a function with
 * defaults as a clause of its own, beside the head that carries the defaults. The function is the head with the
 * defaults, at every arity (`build.exs` rebuilds the fixture).
 */
class DocsOnlyDefaultsQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/docs_only_defaults"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
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

    private fun configure(use: String) {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(a), do: $use\nend\n")
        // Let the library's roots change and refresh finish before a gesture's background read waits on them.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
