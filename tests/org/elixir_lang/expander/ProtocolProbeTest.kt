package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BEFORE_COMPILE
import org.elixir_lang.psi.ElixirFile

/**
 * `defprotocol`, `Protocol.def/1` and `defimpl` compiled in module bodies on the leg's Elixir: at each probe the env,
 * the variables and the hygiene counters taken, each error they raise, the definitions they store and the dispatches
 * of the output Elixir expands them to.
 */
class ProtocolProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    /** Each case ends as the compiler ends it, whether it compiles or raises, and ports every macro it calls. */
    fun testEachCaseMatchesElixir() {
        val bodies = PROTOCOLS + IMPLEMENTATIONS + RAISING
        val expansions = bodies.associateWith { probes.expand(it, PREAMBLE) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** The dispatches of each case that compiles, as the compiler traces them. */
    fun testEachCompilingCaseTracesAsTheCompilerDoes() {
        printed { probes.assertTracesMatchElixir(probes.expandAll(PROTOCOLS + OWN_PROTOCOL, PREAMBLE)) }
    }

    /** `Protocol.__impl__/4` unrolls a list: one module each, in order. */
    fun testAListOfTypesIsOneModuleEach() {
        val modules = probes.expandAll(
            listOf("defimpl {token}.Proto, for: [Integer, Atom, Float] do\n  def f(x), do: x\nend"),
            PREAMBLE,
        ).modules.flatMap(::compiled).map { it.module }

        assertEquals(
            listOf("Integer", "Atom", "Float"),
            modules.filter { it.contains(".Proto.") }.map { it.substringAfterLast(".Proto.") },
        )
    }

    /** The module is named by `Protocol.__concat__/2` from the protocol and the type, each as the file's aliases expand it. */
    fun testAnImplementationIsNamedByTheProtocolAndTheType() {
        NAMES.forEachIndexed { index, (body, expected) ->
            val expansions = probes.expandAll(listOf(body), PREAMBLE)
            val token = expansions.layout.token
            val case = expansions.layout.caseModule(0).removePrefix("Elixir.")

            val others = setOf("$token.Proto", "$token.Probe", case, "$case.Host", "$case.Host.Q")

            assertEquals(
                "case $index: ${body.replace("\n", "; ")}",
                expected.map { it.replace("{token}", token).replace("{case}", case) },
                expansions.modules.flatMap(::compiled).map { it.module.removePrefix("Elixir.") }.filterNot { it in others },
            )
        }
    }

    /**
     * `Module.concat/2` drops a `nil` until v1.18.3, so `for: nil` names the protocol's own module there, redefining
     * it and leaving later implementations of it without a protocol; the case is a file of its own.
     */
    fun testAnImplementationForNilIsNamedAsTheCompilerNamesIt() {
        val body = "defimpl {token}.Proto, for: nil do\n  def f(x), do: x\nend"

        printed { probes.assertMatchesElixir(mapOf(body to probes.expand(body, PREAMBLE))) }
    }

    /** A name the expander can't compute stops the module at the `defimpl`, as every unevaluated definition does. */
    fun testAComputedNameIsNotFollowed() {
        UNPORTED.forEach { body ->
            val expansion = probes.expand(body, PREAMBLE)

            assertTrue("$body: ${expansion.outcome}", expansion.outcome is Expansion.Unported)
        }
    }

    /**
     * A definition the body gives an unquote the expander can't evaluate isn't one the macro that made the module
     * knows: only the macro's own are settled with what it knows, the user's stay stuck.
     */
    fun testAStuckDefinitionTheBodyWritesIsNotSettled() {
        STUCK.forEach { body ->
            val expansion = probes.expand(body, PREAMBLE)

            assertTrue("$body: ${expansion.ended}", expansion.ended is ExpansionResult.Ended.Stopped)
        }
    }

    /**
     * Only the loop of `defprotocol`'s own `impl_for/1` is run for each built-in type. A definition of the body with an
     * unquoted call in its head, whose value the expander can't compute, is one definition stuck, not eleven.
     */
    fun testAUserHeadWithAnUnquotedCallIsNotTheBuiltInLoop() {
        val body = "defprotocol P do\n  def f(x)\n  Kernel.def g(x) when :erlang.unquote(String.to_atom(\"is_atom\"))(x), do: x\nend"
        val modules = probes.expandAll(listOf(body), PREAMBLE).modules.flatMap(::compiled)

        assertEquals(1, modules.single { it.module.endsWith(".P") }.table.entries.getValue(NameArity("g", 1)).clauses)
    }

    /**
     * Before v1.13 the functions a protocol declares are in `@functions`, which the body can write too; `:lists.sort/1`
     * takes anything in it, so what isn't `{name, arity}` is Elixir's to sort and the module stops there. From v1.13 the
     * attribute is `@__functions__`, which the body can't write, so there is nothing to compare.
     */
    fun testFunctionsThatAreNotNamesAndAritiesAreNotFollowed() {
        listOf("[{1, 2, 3}]", "[{:a}]", "[{:a, 1} | :x]").forEach { value ->
            val body = "defprotocol P do\n  @functions $value\n  def f(x)\nend"
            val expansion = probes.expand(body, PREAMBLE)

            if (!PROTOCOL_BEFORE_COMPILE.isSufficient(legLevel())) {
                assertTrue("$body: ${expansion.ended}", expansion.ended is ExpansionResult.Ended.Stopped)
            }
        }
    }

    /** [result] and the modules it compiled inside it, outermost first. */
    private fun compiled(result: ExpansionResult): List<ExpansionResult> = listOf(result) + result.nested.flatMap(::compiled)

    private companion object {
        /** A protocol of the file, which the implementations read as the exports of a module the run compiled. */
        const val PREAMBLE = "defprotocol {token}.Proto do\n  def f(x)\nend\n"

        val PROTOCOLS = listOf(
            // protocol_one
            "defprotocol P do\n  def f(x)\nend",
            "defprotocol P do\n  def f(x)\n  def g(x, y)\nend",
            "defprotocol P do\n  def f(x)\n  def g(x, y, z)\n  def h(x)\nend",
            "defprotocol P do\nend",
            "defprotocol P do\n  def f(x)\nend\ny = 1",
            // protocol_fallback_any
            "defprotocol P do\n  @fallback_to_any true\n  def f(x)\nend",
            "defprotocol P do\n  @fallback_to_any false\n  def f(x)\nend",
            "defprotocol P do\n  @fallback_to_any true\n  Module.delete_attribute(__MODULE__, :fallback_to_any)\n  def f(x)\nend",
            // before v1.13 `@functions` is the body's to write too, and `{name, arity}` pairs in it are functions
            "defprotocol P do\n  @functions [{:g, 2}]\n  def f(x)\nend",
            // the attributes and types it keeps beside the functions
            "defprotocol P do\n  @moduledoc \"d\"\n  @doc \"f\"\n  def f(x)\nend",
            "defprotocol P do\n  @spec f(t) :: term\n  def f(x)\nend",
            "defprotocol P do\n  @type u :: integer\n  def f(x)\nend",
            "defprotocol P do\n  @type t :: integer\n  def f(x)\nend",
            "defprotocol P do\n  def f(x)\n  def f(x, y)\nend",
            "defprotocol P do\n  def f(x, y \\\\ 1)\nend",
            "defprotocol P do\n  @undefined_impl_description \"nope\"\n  def f(x)\nend",
            // the protocol as the module it defines
            "defmodule Host do\n  defprotocol P do\n    def f(x)\n  end\nend",
            "alias Foo.Bar\ndefprotocol P do\n  def f(x)\nend",
        )

        val IMPLEMENTATIONS = listOf(
            // impl_for
            "defimpl {token}.Proto, for: Integer do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Atom do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Any do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: BitString do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: URI do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: __MODULE__ do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: :atom_module do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Integer, do: (def f(x), do: x)",
            "defimpl {token}.Proto, for: Integer do\nend",
            "defimpl {token}.Proto, for: Integer do\n  def f(x), do: x\n  def g(y), do: y\nend",
            // impl_for_list
            "defimpl {token}.Proto, for: [Integer, Atom] do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: [Integer] do\n  def f(x), do: x\nend",
            // a literal block is the one node in each module, and one step in each
            "defimpl {token}.Proto, for: [Integer, Atom], do: :ok",
            "defimpl {token}.Proto, for: [] do\nend",
            "defimpl {token}.Proto, for: [Integer, Atom, Float, Map, Tuple] do\n  def f(x), do: x\nend",
            // impl_inside
            "defimpl {token}.Proto do\n  def f(x), do: x\nend",
            "defmodule Host do\n  defimpl {token}.Proto do\n    def f(x), do: x\n  end\nend",
            // the names, which NAMES pins
            "alias Other.X\ndefimpl {token}.Proto, for: X do\n  def f(x), do: x\nend",
            "alias {token}.Proto, as: Pr\ndefimpl Pr, for: Integer do\n  def f(x), do: x\nend",
            "alias Other.X\nalias Other.Y\ndefimpl {token}.Proto, for: [X, Y] do\n  def f(x), do: x\nend",
            "defmodule Host do\n  defprotocol Q do\n    def f(x)\n  end\n\n  defimpl Q, for: Integer do\n    def f(x), do: x\n  end\nend",
            // the variables it leaves, and what follows it
            "defimpl {token}.Proto, for: Integer do\n  def f(x), do: x\nend\ny = 1",
            "for = 1\ndefimpl {token}.Proto, for: Integer do\n  def f(x), do: x\nend",
            // two implementations of one protocol
            "defimpl {token}.Proto, for: Integer do\n  def f(x), do: x\nend\ndefimpl {token}.Proto, for: Atom do\n  def f(x), do: x\nend",
            // the attributes the implementation writes, read in its body
            "defimpl {token}.Proto, for: Integer do\n  def f(x), do: {x, __MODULE__, @for, @protocol}\nend",
            "defimpl {token}.Proto, for: Integer do\n  @moduledoc \"mine\"\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Integer do\n  @impl true\n  def f(x), do: x\nend",
        )

        /**
         * The implementations again, of a protocol the case defines, so that the modules they define are the case
         * module's own, whose dispatches the compiler's events are filtered to.
         */
        val OWN_PROTOCOL = IMPLEMENTATIONS.filter { "{token}.Proto" in it }
            .map { "defprotocol P do\n  def f(x)\nend\n" + it.replace("{token}.Proto", "P") }

        /** The case bodies whose modules [NAMES] names, with the modules each defines. */
        val NAMES = listOf(
            "alias Other.X\ndefimpl {token}.Proto, for: X do\n  def f(x), do: x\nend" to
                listOf("{token}.Proto.Other.X"),
            "alias {token}.Proto, as: Pr\ndefimpl Pr, for: Integer do\n  def f(x), do: x\nend" to
                listOf("{token}.Proto.Integer"),
            "alias Other.X\nalias Other.Y\ndefimpl {token}.Proto, for: [X, Y] do\n  def f(x), do: x\nend" to
                listOf("{token}.Proto.Other.X", "{token}.Proto.Other.Y"),
            "defmodule Host do\n  defimpl {token}.Proto do\n    def f(x), do: x\n  end\nend" to
                listOf("{token}.Proto.{case}.Host"),
            "defmodule Host do\n  defprotocol Q do\n    def f(x)\n  end\n\n  defimpl Q, for: Integer do\n    def f(x), do: x\n  end\nend" to
                listOf("{case}.Host.Q.Integer"),
        )

        val RAISING = listOf(
            // protocol_def_no_args
            "defprotocol P do\n  def f()\nend",
            "defprotocol P do\n  def f\nend",
            // protocol_def_bad
            "defprotocol P do\n  def Foo.bar(x)\nend",
            "defprotocol P do\n  def f(x), do: x\nend",
            // impl_no_do
            "defimpl {token}.Proto, for: Integer",
            "defimpl {token}.Proto, for: [Integer, Atom]",
            // impl_bad_opt
            "defimpl {token}.Proto, for: Integer, foo: 1 do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Integer, foo: 1, bar: 2 do\nend",
            // impl_not_protocol
            "defimpl Enum, for: Integer do\nend",
            "defimpl {token}.Missing, for: Integer do\nend",
            "defimpl :lists, for: Integer do\nend",
            // the Kernel definitions other than `def`, which the body keeps
            "defprotocol P do\n  defp g(x), do: x\n  def f(x)\nend",
            "defprotocol P do\n  defmacro g(x), do: x\n  def f(x)\nend",
            "defprotocol P do\n  defmacrop g(x), do: x\n  def f(x)\nend",
            "defprotocol P do\n  defdelegate g(x), to: Kernel\n  def f(x)\nend",
            // the protocol's own definitions cannot be defined again
            "defprotocol P do\n  def impl_for(x)\nend",
            "defprotocol P do\n  Kernel.def(g(x))\n  def f(x)\nend",
        )

        /** Definitions the body writes with an unquote it evaluates, which Elixir raises at or defines differently. */
        val STUCK = listOf(
            "defimpl {token}.Proto, for: Integer do\n  args = [Macro.var(:a, nil)]\n  def f(unquote_splicing(args)), do: :ok\nend",
            "defprotocol P do\n  def f(x)\n  l = :x\n  Kernel.def g(y), do: unquote(:lists.sort(l))\nend",
        )

        /** What the expander can't compute: the module an implementation defines. */
        val UNPORTED = listOf(
            "defimpl {token}.Proto, for: String.to_atom(\"A\") do\n  def f(x), do: x\nend",
            "defimpl String.to_atom(\"A\"), for: Integer do\n  def f(x), do: x\nend",
            "defimpl {token}.Proto, for: Enum.at([Integer], 0) do\n  def f(x), do: x\nend",
            "x = {token}.Proto\ndefimpl x, for: Integer do\n  def f(x), do: x\nend",
            "@p {token}.Proto\ndefimpl @p, for: Integer do\n  def f(x), do: x\nend",
        )
    }
}
