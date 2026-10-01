package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * After each statement of a case module body, the expander's aliases, requires, imports and other env fields equal
 * Elixir's on the leg's Elixir, and a case the expander reports an error for fails at expansion for the same reason.
 * Every case is ported.
 */
class DirectiveProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testOwnCases() {
        val expansions = CASES.associateWith { probes.expand(it) }

        assertEquals("", expansions.filterValues { it.outcome is Expansion.Unported }.keys.joinToString("\n"))

        probes.assertMatchesElixir(expansions)
    }

    private companion object {
        val CASES = listOf(
            "alias Foo.Bar",
            "alias Foo.Bar, as: Baz",
            "alias Foo.{A, B.C}",
            "alias Foo.Bar\nalias Bar.Baz",
            "alias Elixir.Foo",
            "alias Foo.Bar\nalias Bar, as: Bar",
            "require Integer",
            "require Integer, as: I",
            "require List\nrequire Integer",
            "import Integer, only: [parse: 1, is_odd: 1]",
            "import Integer, only: :macros",
            "import List, only: [first: 1, last: 1]\nimport List, except: [first: 1]",
            "import List, only: [first: 1]\nimport Integer, only: [parse: 1]\nimport List, only: [last: 1]",
            "import List, only: [first: 1]\nimport List, only: []",
            "import List, only: [first: 1]\nimport Enum, only: [at: 2]\nimport :lists, only: [last: 1]\n" +
                "import List, only: [last: 1]",
            "import GenServer",
            "import Enum, only: :functions",
            // The version differences
            "alias Foo, as: Bar\nalias Baz.Foo\nalias Bar.X",
            "alias Foo.Bar\nalias Foo.Bar, as: nil",
            "alias :lists",
            "import Kernel.SpecialForms, only: [alias: 2]",
            "import Kernel.SpecialForms",
            "import :lists, only: :macros",
            "import Integer, only: [nope: 1]",
            "import Integer, only: [parse: 1, parse: 1]",
            "import List, only: [first: 1, first: 1], except: [last: 1]",
            "import :gen_server, only: [behaviour_info: 1, module_info: 0]",
            "import :gen_server",
            "import Integer, only: :sigils",
            "import Integer, only: [parse: 1], except: :bad",
            "alias List, as: Integer\nrequire Elixir.Integer",
            // The errors
            "alias(Foo.Bar) = 1",
            "require NoSuchModule",
            "import NoSuchModule",
            "import Integer, as: I",
            "import Integer, :foo",
            "import Integer, only: [:parse]",
            "alias Foo.{A, B}, as: C",
            "alias Foo.{1}",
            "x = Foo\nalias x",
            "x = 1\nalias x.Foo",
            "alias Foo.Bar, as: Baz.Qux",
            // An alias outlives the scope it is defined in
            "{alias(Foo.Bar), 1}\n:ok",
            "(alias Foo.Bar; :ok)\n:ok",
            "x = (alias Foo.Bar)\nx",
            "%{Bar => a, Foo.Bar => b} = (alias Foo.Bar; %{})",
            "[alias(Foo.Bar)]\n:ok",
            "%{a: alias(Foo.Bar)}\n:ok",
            "<<(alias(Foo.Bar); 1)>>\n:ok",
        )
    }
}
