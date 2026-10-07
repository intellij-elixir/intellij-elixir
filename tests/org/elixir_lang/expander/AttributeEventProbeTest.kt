package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * The dispatches of module attributes' writes, reads and typespecs, and of the `@` a public definition's signature
 * expands again, against the events the leg's compiler traces for the same case module body.
 */
class AttributeEventProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testAttributeDispatchesMatchTheCompilersEvents() {
        probes.assertTracesMatchElixir(probes.expandAll(WRITES_AND_READS, PREAMBLE))
    }

    /** `Module.compile_definition_attributes/6` expands a public definition's signature `@`s once more. */
    fun testASignatureExpandsItsAttributesAgain() {
        probes.assertTracesMatchElixir(probes.expandAll(SIGNATURES, PREAMBLE))
    }

    /**
     * From 1.18.4 Elixir traces each `@on_definition` callback it runs for a clause with the clause's meta and env, as
     * it traces a call on the clause's line to the same function.
     */
    fun testOnlyAClausesCallbacksAreDropped() {
        val expansions = probes.expandAll(CALLBACKS, PREAMBLE + CALLBACK_PREAMBLE, hook = ProbeHarness.Hook())

        probes.assertTracesMatchElixir(expansions)
    }

    /** An attribute whose value is a macro the expander doesn't expand stops there, and agrees with Elixir up to it. */
    fun testAnAttributeValueStopsAtItsMacro() {
        probes.assertMatchesElixirUpToMacro(OPAQUE_VALUES.associateWith { probes.expand(it, PREAMBLE) })
    }

    private companion object {
        const val PREAMBLE = "\ndefmodule {token}.Beh do\n  @callback cb() :: any\nend\n"

        /**
         * Writes and reads of each kind, less those whose values are macros ([OPAQUE_VALUES]), and the attributes
         * Elixir calls back: it traces `behaviour_info/1`, the `@on_load` function and `__after_compile__/2` after the
         * body on some legs, which [DispatchEvents] drops.
         */
        val WRITES_AND_READS = listOf(
            """
            @a 1
            @b Foo.Bar
            @c [Foo.Baz, String.length("a")]
            @compile {:no_warn_undefined, Foo.Qux}
            @behaviour {token}.Beh
            @doc "d"
            def cb, do: :ok
            _ = @b
            def f, do: {@a, @b}
            def g(x \\ @a), do: x
            """.trimIndent(),
            """
            @on_load :init
            def init, do: :ok
            @spec f(integer) :: integer
            @type t :: integer
            def f(x), do: x
            @after_compile __MODULE__
            def __after_compile__(_env, _bin), do: :ok
            """.trimIndent(),
        )

        /** Each `build_signature` branch that expands an `@`. */
        val SIGNATURES = listOf(
            "@x 1\ndef f(a \\\\ @x), do: a",
            "@x 1\ndef f(a \\\\ 1), do: {a, @x}",
            "@x 1\n@y 2\ndef f(a \\\\ @x, b \\\\ @y), do: {a, b}",
            "@x 1\ndefp f(a \\\\ @x), do: a\ndef g, do: f()",
            "@x 1\ndefmacro f(a \\\\ @x), do: a",
            "def f(a \\\\ abs(-1)), do: a",
            "@x 1\ndef f(a \\\\ [@x]), do: a",
            "@x 1\n@doc false\ndef f(a \\\\ @x), do: a",
            "@x 1\ndef f(@x), do: 1",
            "@x -1\ndef f(a \\\\ abs(@x)), do: a",
            "@s URI\ndef f(%@s{}), do: 1",
            "@s URI\ndef f(%@s{} = v), do: v",
            "@x 1\ndef f(@x \\\\ 1), do: 1",
            "@x 1\ndef f(a \\\\ @x)\ndef f(1), do: 1\ndef f(a), do: a",
            "@x 1\ndef f(a \\\\ @x) when is_integer(a), do: a",
        )

        const val CALLBACK_PREAMBLE = "\ndefmodule {token}.Cb do\n" +
            "  def on_def(_env, _kind, _name, _args, _guards, _body), do: :ok\n" +
            "  def on_def2(_env, _kind, _name, _args, _guards, _body), do: :ok\nend\n"

        /**
         * A delegation written as the call `defdelegate` makes, to a function of six arguments as a callback is, and
         * callbacks changing between clauses.
         */
        val CALLBACKS = listOf(
            """
            @on_definition {{token}.Cb, :on_def}
            @on_definition {{token}.Cb, :on_def2}
            def len(s), do: String.length(s)
            def six(a, b, c, d, e, f), do: {token}.Cb.on_def(a, b, c, d, e, f)
            def own(a), do: String.length(a)
            """.trimIndent(),
            "def six(a, b, c, d, e, f), do: {token}.Cb.on_def(a, b, c, d, e, f)",
            """
            def a, do: 1
            @on_definition {{token}.Cb, :on_def}
            def b, do: 2
            @on_definition {{token}.Cb, :on_def2}
            def c, do: 3
            """.trimIndent(),
        )

        /** Two attributes whose values are macros the expander doesn't expand yet. */
        val OPAQUE_VALUES = listOf("@s ~w(a b)\n@i if(true, do: 1)")
    }
}
