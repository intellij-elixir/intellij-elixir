package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.SAME_NAME_MACRO_LIB
import org.elixir_lang.documentation.SAME_NAME_MACRO_SOURCE
import org.elixir_lang.documentation.addCompiledLibrary

/** A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it. */
class PlacedHeadParameterInfoTest : ExpanderSettingTestCase() {
    /** `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub. */
    fun testAHeadAMacroPlacedShowsTheStubWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("arg1"),
            shown(
                """
                defmodule Placed do
                  use Application

                  def calls, do: stop(1<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** A head a compiled macro places at its own clause, of the function's name, shows the stub, not the macro's parameters. */
    fun testAHeadPlacedAtACompiledMacroOfItsNameShowsTheStubWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(SAME_NAME_MACRO_LIB, CompiledBuild("CompiledSameNameParameterInfo"))

        assertEquals(
            listOf("arg1"),
            shown(
                """
                defmodule Placed do
                  require CompiledSameNameParameterInfo
                  CompiledSameNameParameterInfo.f(1, 2, 3)

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** A source macro of the function's name writes its quote's `def f(x)`, whose parameters the function shows. */
    fun testADefASourceMacroOfItsNameQuotesShowsItsParametersWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addFileToProject("same_name.ex", SAME_NAME_MACRO_SOURCE)

        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Placed do
                  require SameName
                  SameName.f(1, 2, 3)

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("placed.ex", text)
}
