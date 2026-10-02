package org.elixir_lang.psi

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.Module
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.getModuleName

/** `defmodule :foo do defmodule Inner` defines `elixir_aliases:concat([:foo, :Inner])`, `:"Elixir.foo.Inner"`. */
class ModuleNestedInAnAtomNamedModuleTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testModuleName() {
        myFixture.configureByText("nested.ex", "defmodule :foo do\n  defmodule Inner do\n    <caret>x = 1\n  end\nend\n")

        assertEquals(
            Module.indexName("Elixir.foo.Inner"),
            myFixture.file.findElementAt(myFixture.caretOffset)!!.getModuleName(),
        )
    }

    fun testQualifierSpelledAsTheModuleAtomReachesIt() = assertDestinations(":\"Elixir.foo.Inner\"", "def f, do: 1")

    fun testQualifierSpelledAsTheOuterAtomJoinedToTheAliasDoesNotReachIt() = assertDestinations(":\"foo.Inner\"")

    private fun assertDestinations(qualifier: String, vararg expected: String) {
        myFixture.configureByText(
            "nested_caller.ex",
            "defmodule :foo do\n  defmodule Inner do\n    def f, do: 1\n  end\nend\n\n" +
                "defmodule Caller do\n  def g, do: $qualifier.f<caret>()\nend\n"
        )

        assertEquals(
            expected.toList(),
            myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { target ->
                generateSequence(target.destination) { it.parent }
                    .filterIsInstance<Call>()
                    .firstOrNull { CallDefinitionClause.`is`(it) }
                    ?.text
            },
        )
    }
}
