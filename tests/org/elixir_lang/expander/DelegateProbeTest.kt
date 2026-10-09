package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * `defdelegate` compiled in module bodies on the leg's Elixir: at each probe the env, the variables and the hygiene
 * counters taken, each error it raises, the definitions it stores and the dispatches of the output Elixir expands it to.
 */
class DelegateProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    /** Each case ends as the compiler ends it, whether it compiles or raises, and ports every macro it calls. */
    fun testEachCaseMatchesElixir() {
        val bodies = COMPILING + RAISING
        val expansions = bodies.associateWith { probes.expand(it, PREAMBLE) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** Up to the `defdelegate` the module stops at, each case is as the compiler has it. */
    fun testAnOptionThatIsNotReadStopsTheModule() {
        val expansions = STOPPING.associateWith { probes.expand(it, PREAMBLE) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome !is Expansion.Unported }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** The dispatches of each case that compiles, as the compiler traces them. */
    fun testEachCompilingCaseTracesAsTheCompilerDoes() {
        printed { probes.assertTracesMatchElixir(probes.expandAll(COMPILING, PREAMBLE)) }
    }

    /** A delegate is a `def` of its own, so each head runs the module's `@on_definition` callbacks. */
    fun testEachHeadRunsTheDefinitionCallbacks() {
        printed {
            probes.assertTracesMatchElixir(
                probes.expandAll(CALLBACKS, PREAMBLE + CALLBACK_PREAMBLE, hook = ProbeHarness.Hook())
            )
        }
    }

    /** A head the expander can't name stops the module at the `defdelegate`, as every unevaluated definition does. */
    fun testAHeadWithAnUnquotedNameIsNotFollowed() {
        val body = "defdelegate unquote(String.to_atom(\"a\"))(x), to: M"
        val expansion = probes.expand(body, PREAMBLE)

        assertTrue("$body: ${expansion.outcome}", expansion.outcome is Expansion.Unported)
    }

    private companion object {
        const val PREAMBLE = ""

        const val CALLBACK_PREAMBLE = "\ndefmodule {token}.Cb do\n" +
            "  def on_def(_env, _kind, _name, _args, _guards, _body), do: :ok\nend\n"

        val CALLBACKS = listOf(
            "@on_definition {{token}.Cb, :on_def}\ndefdelegate count(x), to: Enum",
            "@on_definition {{token}.Cb, :on_def}\ndefdelegate [a(x), b(y)], to: M",
        )

        val COMPILING = listOf(
            // delegate_one
            "defdelegate count(x), to: Enum",
            "defdelegate count(x, y), to: Enum",
            "defdelegate count, to: Enum",
            "defdelegate count(), to: Enum",
            "defdelegate count(x), to: M",
            "defdelegate count(x), to: :erlang",
            "defdelegate count(x), to: __MODULE__.Inner",
            "alias Foo.Bar\ndefdelegate count(x), to: Bar",
            "alias Foo.Bar, as: B\ndefdelegate count(x), to: B",
            // `as:`
            "defdelegate count(x), to: Enum, as: :map",
            "defdelegate count(x), to: M, as: :count",
            "defdelegate count(x), to: __MODULE__, as: :other",
            // delegate_list
            "defdelegate [a(x), b(y)], to: M",
            "defdelegate [a(x), b(y)], to: M, as: :c",
            "defdelegate [a(x)], to: M",
            "defdelegate [a(x), a(x, y)], to: M",
            // delegate_default
            "defdelegate count(x, y \\\\ 1), to: Enum",
            "defdelegate count(x \\\\ 1, y \\\\ 2), to: Enum",
            // delegate_append_first
            "defdelegate count(x, y), to: Enum, append_first: true",
            "defdelegate count(x, y), to: Enum, append_first: false",
            "defdelegate count(x), to: Enum, append_first: true",
            "@doc \"delegates\"\ndefdelegate count(x), to: Enum",
            "@doc false\ndefdelegate count(x), to: Enum",
            // statements around it
            "def before, do: 1\ndefdelegate count(x), to: Enum\ndef after_it, do: 2",
            "defdelegate count(x), to: Enum\ndef count(y, z), do: {y, z}",
            "a = 1\ndefdelegate count(x), to: Enum\nb = a",
            "x = 1\ndefdelegate count(x), to: Enum",
            // variables of the head and body
            "defdelegate count(_x), to: Enum",
        )

        /** A `to:` or an `as:` the expander can't read stops the module at the `defdelegate`, as every unevaluated definition does. */
        val STOPPING = listOf(
            "defdelegate count(x), to: Application.get_env(:a, :b) || Enum",
            "@t Enum\ndefdelegate count(x), to: @t",
            // an `as:` that isn't an atom is the function the delegate calls, or raises as the `delegate_to` metadata does
            "defdelegate f(x), to: Enum, as: \"count\"",
            "defdelegate f(x), to: Enum, as: String.to_atom(\"count\")",
        )

        val RAISING = listOf(
            // delegate_no_to
            "defdelegate count(x)",
            "defdelegate count(x), as: :map",
            "defdelegate count(x), to: nil",
            // delegate_self
            "defdelegate count(x), to: __MODULE__",
            "defdelegate count(x), to: __MODULE__, as: nil",
            // delegate_guard
            "defdelegate count(x) when is_list(x), to: Enum",
            "defdelegate [count(x), b(y) when y], to: Enum",
            // delegate_bad_call
            "defdelegate 1, to: Enum",
            "defdelegate Enum.count(x), to: Enum",
            "defdelegate [count(x), 1], to: Enum",
            // delegate_literal_arg
            "defdelegate count(_), to: Enum",
            "defdelegate count(x = y), to: Enum",
            "defdelegate count(1), to: Enum",
            "defdelegate count(x, :a), to: Enum",
            "defdelegate count([x]), to: Enum",
            "defdelegate count(%{}), to: Enum",
            "defdelegate count({x, y}), to: Enum",
            "defdelegate count(x \\\\ 1, 2), to: Enum",
            // the options are not a keyword list
            "defdelegate count(x), 1",
            // a second definition of the same name and arity with other kinds
            "defdelegate count(x), to: Enum\ndefmacro count(x), do: x",
            "defp count(x), do: x\ndefdelegate count(x), to: Enum",
        )
    }
}
