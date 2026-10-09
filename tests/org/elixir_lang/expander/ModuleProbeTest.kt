package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINER_REFUSED_IN_MATCH_OR_GUARD
import org.elixir_lang.psi.ElixirFile

/**
 * Modules compiled on the leg's Elixir: the modules being defined around each probe, as a file of only modules and a
 * file with other forms compile them, the alias a nested module defines, and the variables its body reads.
 */
class ModuleProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    /** From 1.13 a file of only modules compiles each directly, so each sees only itself; before, those before it. */
    fun testAFileOfOnlyModules() {
        val names = listOf("first", "second", "third")

        probes.assertMatchesElixir(names, probes.expandAll(listOf("", "", "")))
    }

    /** A file with another form compiles each module through `Kernel.defmodule/2`, and ends outside any module. */
    fun testAFileWithAnotherForm() {
        val names = listOf("first", "second", "third")

        probes.assertMatchesElixir(names, probes.expandAll(listOf("", "", ""), top = true))
    }

    fun testModules() {
        val expansions = CASES.associateWith { probes.expand(it) }

        assertEquals("", expansions.filterValues { it.ended is ExpansionResult.Ended.Stopped }.keys.joinToString("\n"))

        probes.assertMatchesElixir(expansions)
    }

    /** From 1.15 `defmodule` refuses a guard. Before it, Elixir raises on the `alias` it expands to there. */
    fun testAModuleInAGuardFrom1_15() {
        if (!DEFINER_REFUSED_IN_MATCH_OR_GUARD.isSufficient(legLevel())) return

        probes.assertMatchesElixir(mapOf(IN_A_GUARD to probes.expand(IN_A_GUARD)))
    }

    private companion object {
        const val IN_A_GUARD = "case 1 do\n  x when defmodule(B, do: 1) -> x\nend"

        val CASES = listOf(
            "defmodule Inner do\nend",
            "defmodule Inner do\n  defmodule Deeper do\n    :ok\n  end\nend",
            "defmodule Inner.Deeper do\n  :ok\nend\n:ok",
            // The alias is the case module's, not the name the alias before it expands to.
            "alias {token}.Foo.Bar\ndefmodule Bar.Baz do\nend\n:ok",
            "x = 1\ndefmodule Inner do\n  y = x\n  {x, y}\nend",
            // An `Elixir.`-rooted module removes an alias of its own name up to 1.15, and from 1.16 leaves it.
            "alias {token}.Other.{token}Root\ndefmodule Elixir.{token}Root do\nend\n:ok",
            "defmodule :{token}_atom do\n  defmodule Inner do\n    :ok\n  end\nend",
            "require __MODULE__",
            "alias __MODULE__, as: Outer\ndefmodule Inner do\n  import Outer\nend",
            "defmodule Inner do\n  :ok\nelse\n  :error\nend",
            "defmodule __MODULE__.Inner do\n  :ok\nend",
            // A name `Macro.expand/2` makes an atom of is required, whatever the name is written as.
            "defmodule alias!(Inner) do\nend",
            // The case module is being defined.
            "defmodule __MODULE__, do: :ok",
            "defmodule \"foo\", do: :ok",
            "defmodule 1, do: :ok",
            "defmodule nil, do: :ok",
            "defmodule true, do: :ok",
            "defmodule false, do: :ok",
            "defmodule :\"{token}\\\\b\", do: :ok",
            // A module is loaded once it is compiled, and not before.
            "defmodule Inner1 do\nend\ndefmodule Inner2 do\n  require Inner1\nend",
            "defmodule Inner2 do\n  require Inner1\nend\ndefmodule Inner1 do\nend",
        )
    }
}
