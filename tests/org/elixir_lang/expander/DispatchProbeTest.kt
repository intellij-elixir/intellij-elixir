package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.ElixirFile
import java.math.BigInteger

/**
 * Each call the expander dispatches and each import a `quote` traces, in its order, against the events the leg's
 * compiler traces for the same case module body, as [DispatchEvents] normalises them.
 */
class DispatchProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testDispatchesMatchTheCompilersEvents() {
        probes.assertTracesMatchElixir(probes.expandAll(CASES))
    }

    /** Below 1.13, Elixir reads an unrequired module's macros only if the module happens to be loaded. */
    fun testAnUnrequiredMacroCaptureIsARemoteFunctionFrom113() {
        val expansions = probes.expandAll(listOf(UNREQUIRED_MACRO_CAPTURE))

        if (UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(legLevel())) {
            probes.assertTracesMatchElixir(expansions)
        } else {
            val expansion = expansions.cases.single()
            val unported = expansion.outcome as? Expansion.Unported

            assertEquals("&Integer.is_odd/1", unported?.at?.let(expansion::source))
        }
    }

    /** A call of a local macro in a function body stops that body at the call, and up to there agrees with Elixir. */
    fun testLocalMacrosStopAtTheCall() {
        probes.assertMatchesElixirUpToMacro(LOCAL_MACRO_CASES.associateWith { probes.expand(it) })
    }

    /** Each call a macro's quote marks with the import it saw dispatches to that import, as the leg's `quote` marks it. */
    fun testQuotedImportsMatchTheCompilersEvents() {
        val everyArity = QUOTE_IMPORTS_EVERY_ARITY.isSufficient(legLevel())

        printed {
            probes.assertTracesMatchElixir(
                probes.expandAll(
                    QUOTED_CASES,
                    quotedPreamble(everyArity),
                    QUOTED_EXPORTS,
                    standIns = standIns(everyArity),
                )
            )
        }
    }

    /**
     * The preamble's `H<n>.m/0`, as each leg's `quote` gives it: `F` is imported beside `List`, and `H7` and `H8` write
     * their metadata by hand, `H8` in the other era's form.
     */
    private fun quotedPreamble(everyArity: Boolean): String {
        val h7 = if (everyArity) "[imports: [{1, List}]]" else "[import: List]"
        val h8 = if (everyArity) "[context: __MODULE__, import: List]" else "[context: __MODULE__, imports: [{1, List}]]"

        return """
            defmodule $Q.F do
              def first(x), do: x
              def first(x, y), do: {x, y}
            end

            defmodule $Q.H1 do
              import List, only: [first: 1]
              defmacro m, do: quote(do: first([1]))
            end

            defmodule $Q.H2 do
              import List, only: [first: 1]
              defmacro m, do: quote(do: first([1]))
            end

            defmodule $Q.H3 do
              defmacro m, do: quote(do: def(f(x), do: x))
            end

            defmodule $Q.H4 do
              defmacro m, do: quote(do: length([1]))
            end

            defmodule $Q.H5 do
              defmacro m, do: quote(do: first([1]))
            end

            defmodule $Q.H6 do
              import List, only: [first: 1]
              defmacro m, do: quote(do: first([1], 2))
            end

            defmodule $Q.H7 do
              defmacro m, do: {:first, $h7, [[1]]}
            end

            defmodule $Q.H8 do
              defmacro m, do: {:first, $h8, [[1]]}
            end
        """.trimIndent() + "\n"
    }

    /** Each `H<n>.m()` of [QUOTED_CASES], expanded to what the preamble's macro gives, with its leg's metadata. */
    private fun standIns(everyArity: Boolean): List<ExpansionProbes.StandIn> =
        (1..8).map { n ->
            ExpansionProbes.StandIn("$Q.H$n.m()", "Elixir.$Q.H$n", "m", 0) { call, receiver ->
                quoted(n, receiver, call, everyArity)
            }
        }

    /** What `H<n>.m()` at [call] gives, linified to [call]'s line. */
    private fun quoted(n: Int, receiver: String, call: ElixirAst.Call, everyArity: Boolean): ElixirAst {
        val line = call.meta.keys.filterIsInstance<Meta.Key.Location>()
        val base = Meta(call.meta.origin, call.meta.start, call.meta.end, line)

        fun atom(name: String) = Meta.Value.Atom(name)

        fun imports(vararg arities: Int, module: String) =
            Meta.Value.List(arities.map { Meta.Value.Tuple(listOf(Meta.Value.Integer(it.toLong()), atom(module))) })

        fun meta(vararg entries: Pair<String, Meta.Value>) =
            Meta(base.origin, base.start, base.end, line + entries.map { (key, value) -> Meta.Key.Entry(key, value) })

        /** The metadata `quote` gives a call imported from [module] at [arities]. */
        fun imported(module: String, vararg arities: Int) =
            if (everyArity) {
                meta("context" to atom(receiver), "imports" to imports(*arities, module = module))
            } else {
                meta("context" to atom(receiver), "import" to atom(module))
            }

        fun call(meta: Meta, name: String, vararg arguments: ElixirAst) =
            ElixirAst.Call(meta, ElixirAst.Literal.Atom(base, name), arguments.toList())

        fun integer(value: Long) = ElixirAst.Literal.Integer(base, BigInteger.valueOf(value))
        fun ones() = ElixirAst.ListNode(base, listOf(integer(1)))
        fun pair(key: String, value: ElixirAst) = ElixirAst.Tuple(base, listOf(ElixirAst.Literal.Atom(base, key), value))

        val context = "context" to atom(receiver)

        return when (n) {
            1, 2 -> call(imported(LIST, 1), "first", ones())
            3 -> {
                val x = ElixirAst.Call(
                    base,
                    ElixirAst.Literal.Atom(base, "x"),
                    null,
                    ElixirAst.VariableContext.Atom(receiver),
                )

                call(imported(KERNEL, 1, 2), "def", call(base, "f", x), ElixirAst.ListNode(base, listOf(pair("do", x))))
            }
            4 -> call(imported(KERNEL, 1), "length", ones())
            5 -> call(base, "first", ones())
            6 -> call(if (everyArity) imported(LIST, 1) else base, "first", ones(), integer(2))
            7 -> {
                val meta = if (everyArity) meta("imports" to imports(1, module = LIST)) else meta("import" to atom(LIST))

                call(meta, "first", ones())
            }
            8 -> {
                val meta = if (everyArity) {
                    meta(context, "import" to atom(LIST))
                } else {
                    meta(context, "imports" to imports(1, module = LIST))
                }

                call(meta, "first", ones())
            }
            else -> error("no H$n")
        }
    }

    private companion object {
        /** The quoted cases' module prefix. */
        const val Q = "{token}.Qi"
        const val LIST = "Elixir.List"
        const val KERNEL = "Elixir.Kernel"

        /** A macro's call (`H<n>.m()`) whose output carries the imports its `quote` saw, and the calls they reach. */
        val QUOTED_CASES = listOf(
            "require $Q.H1\n$Q.H1.m()",
            "import List, only: [first: 1]\nimport $Q.F\nrequire $Q.H2\n$Q.H2.m()",
            "import Kernel, except: [def: 2]\nrequire $Q.H3\n$Q.H3.m()",
            "import Kernel, except: [length: 1]\nrequire $Q.H4\n$Q.H4.m()",
            "import List, only: [first: 1]\nrequire $Q.H5\n$Q.H5.m()",
            "import $Q.F\nrequire $Q.H6\n$Q.H6.m()",
            "import $Q.F\nrequire $Q.H7\n$Q.H7.m()",
            "import $Q.F\nrequire $Q.H8\n$Q.H8.m()",
        )

        val QUOTED_EXPORTS = Exports { module ->
            when {
                module.endsWith(".Qi.F") ->
                    ModuleExports.Present(listOf(NameArity("first", 1), NameArity("first", 2)), emptyList(), hasInfo = true)
                Regex("""\.Qi\.H\d$""").containsMatchIn(module) ->
                    ModuleExports.Present(emptyList(), listOf(NameArity("m", 0)), hasInfo = true)
                else -> legExports.of(module)
            }
        }

        /** Each must expand, and compile in a module body; a call that would raise when the body runs is in an `fn`. */
        val CASES = listOf(
            "x = 1\n_ = x + 1",
            "x = 1\n_ = Integer.to_string(x)",
            "t = {1}\n_ = elem(t, 0)",
            "t = {1}\n_ = abs(elem(t, 0) + 1)",
            "x = -1\n_ = abs(:erlang.abs(x))",
            "m = %{}\n_ = Map.get(m, String.length(\"a\"))",
            "m = %{a: 1}\n_ = m.a",
            "f = fn y -> y end\n_ = f.(1)",
            "l = [1]\n_ = :lists.reverse(l)",
            "_ = fn -> __MODULE__.foo() end",
            "_ = fn -> __ENV__.module.foo() end",
            "x = 1\n_ = -x",
            "_ = String.Chars.to_string(\"a\")",
            "_ = fn -> Kernel.alias(Foo) end",
            "_ = fn -> Kernel.require(Foo) end",
            "_ = fn -> Kernel.import(Foo) end",
            "_ = fn -> Kernel.quote(do: 1) end",
            "_ = fn -> (alias Foo.Bar).baz() end",
            "_ = fn -> (alias Foo).baz() end",
            "_ = fn -> (alias Foo.Bar, warn: false).baz() end",
            "_ = fn -> (import Integer, only: [parse: 1]).baz() end",
            "_ = fn -> (require Integer).baz() end",
            "case 1 do\n  y when y > 0 -> y\nend",
            "case {1} do\n  t when elem(t, 0) == 1 -> t\nend",
            "[1] ++ x = [1, 2]",
            "<<x::+8>> = <<1>>",
            "<<x::+(+8)>> = <<1>>",
            "try do\n  :ok\nrescue\n  _ -> System.stacktrace()\nend",
            "_ = System.stacktrace()",
            "import System, only: [stacktrace: 0]\ntry do\n  :ok\nrescue\n  _ -> stacktrace()\nend",
            "x = 1\n_ = fn -> Integer.parse(\"1\") end\n_ = x",
            "_ = quote(do: is_atom(1) and is_nil(2))",
            "_ = quote(do: &inspect/1)",
            "_ = quote(do: inspect)",
            "l = 3\n_ = quote(line: l, do: is_atom(1))",
            "import String, only: [split: 1, split: 3]\nimport Regex, only: [split: 2]\n_ = quote(do: split)",
            "var!(x) = 1",
            "var!(x, Kernel) = 1",
            "Kernel.var!(z) = 3",
            "_ = alias!(Foo)",
            "_ = &abs/1",
            "_ = &abs(&1)",
            "_ = &Integer.to_string/1",
            "_ = &Integer.to_string(&1)",
            "_ = &Integer.to_string(&1, 2)",
            "_ = &:lists.reverse/1",
            "_ = &NoSuchMod.foo/1",
            "_ = &__MODULE__.foo/0",
            "m = URI\n_ = &m.parse/1",
            "f = fn x -> x end\n_ = &f.(&1)",
            "def f, do: g()\ndef g, do: 1",
            "def f(0), do: 0\ndef f(n), do: f(n - 1)",
            "def f, do: &g/1\ndef g(x), do: x",
            "def f(x), do: &f/1",
            "Kernel.def h, do: 1",
            "defmodule Inner do\n  def f, do: 1\nend",
            // Before 1.20 `unless` gives an `if` that its quote marks as imported from `Kernel`.
            "import Kernel, except: [if: 2]\nx = 1\n_ = unless x, do: 1",
            // A quoted import is dispatched before the local macros are looked up.
            "defmacro if(a, b), do: {a, b}\ndef f(x), do: unless(x, do: 1)",
        )

        val LOCAL_MACRO_CASES = listOf(
            "defmacro m, do: 1\ndef f, do: m()",
            "defmacro m(a, b), do: {a, b}\ndefmacro m(x), do: m(x, x)",
            "defmacro m(x), do: x\ndef f, do: &m/1",
        )

        const val UNREQUIRED_MACRO_CAPTURE = "_ = &Integer.is_odd/1"
    }
}
