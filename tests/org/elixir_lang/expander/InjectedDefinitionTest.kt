package org.elixir_lang.expander

import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** The definitions a module's table holds of those a macro's output wrote into it. */
class InjectedDefinitionTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    /** The `use` stops, as the evaluator declines a `receive`, after `defexception` made its definitions overridable. */
    fun testAStoppedModuleKeepsTheDefinitionsDefexceptionMadeOverridable() {
        val code = "defmodule U do\n  defmacro __using__(_), do: (receive do: (_ -> nil), after: (0 -> nil))\nend\n\n" +
            "defmodule A do\n  defexception [:message]\n  use U\nend\n"

        assertEquals(
            "module Elixir.A stopped `use U` | def __struct__/0 line 6 clauses 1 | def __struct__/1 line 6 clauses 1 | " +
                "def message/1 line 6 clauses 1 | def exception/1 line 6 clauses 2",
            render(code),
        )
    }

    private fun render(code: String): String {
        val file = myFixture.addFileToProject("a.ex", code) as ElixirFile
        val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.20.4"), legKernel)

        return ModuleRendering.render(code, holder.expansion(file, inputs).modules.single { it.module == "Elixir.A" })
    }
}
