package org.elixir_lang.psi.scope.callable_table

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.scope.WalkProbe
import org.elixir_lang.psi.scope.WalkProbe.Counter
import org.elixir_lang.psi.scope.WalkTestSupport.location

/** While indexing, a resolve walks the module as before: building a table reads the indexes. */
class DumbModeTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            WalkProbe.reset()
        } finally {
            super.tearDown()
        }
    }

    fun testAResolveWhileIndexingBuildsNoTable() {
        val file = myFixture.configureByText(
            "dumb.ex",
            """
            defmodule Dumb do
              def go, do: target()
              def target, do: :ok
            end
            """.trimIndent()
        )
        val reference = file.viewProvider.findReferenceAt(file.text.indexOf("target()"), ElixirLanguage) as PsiPolyVariantReference

        WalkProbe.reset()
        val answer = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            WalkProbe.counting { reference.multiResolve(false).filter { it.isValidResult }.map { location(it.element) } }
        }

        assertEquals(listOf("dumb.ex:3:3"), answer)
        assertEquals(0L, WalkProbe.snapshot().getValue(Counter.TABLE_BUILD))
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"
}
