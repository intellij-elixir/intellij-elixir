package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.DEFAULT_ARGUMENTS_THREAD_STATE
import org.elixir_lang.psi.ElixirFile

/**
 * The errors of a module's definitions, compiled on the leg's Elixir: up to 1.14 the first raises; from 1.15 an error
 * inside a function is logged and its body expanded on, so each error is reported, in the order the bodies run, then
 * the checks Elixir makes once the module's body has run, and the compile ends as Elixir's does.
 */
class ContinuingErrorProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    /** One site of each error a function body reports, then a statement reading what the site gave. */
    fun testEachSiteInADefinition() {
        assertMatchesElixir(SITES.map { "def f(x) do\n  y = $it\n  {x, y}\nend" })
    }

    fun testErrorsInDefinitions() {
        assertMatchesElixir(DEFINITIONS)
    }

    fun testLocalCallChecks() {
        assertMatchesElixir(LOCAL_CALLS)
    }

    /** A segment's later checks run on a unit that isn't an integer. */
    fun testBadUnitArguments() {
        assertMatchesElixir(BAD_UNITS)
    }

    /**
     * Before 1.14 a default doesn't see the variables an earlier one binds. From 1.14 it does, and the compile then
     * fails after expansion, on an unbound variable or, from 1.18, in the type checker, so it is compared before 1.14.
     */
    fun testADefaultBeforeDefaultsShareVariables() {
        if (DEFAULT_ARGUMENTS_THREAD_STATE.isSufficient(legLevel())) return

        assertMatchesElixir(listOf("def f(a \\\\ (x = 1), b \\\\ x), do: {a, b}"))
    }

    /** A bodiless head's missing clauses, and an import that conflicts with a definition, after a body's errors. */
    fun testBodilessHeadsAndImportConflictsAfterBodyErrors() {
        assertMatchesElixir(LATER_CHECKS)
    }

    /** The checks once the body has run of bodiless heads, imports and the functions attributes name, in each band. */
    fun testPostModuleChecks() {
        assertMatchesElixir(POST_MODULE)
    }

    /** A definition whose kind or defaults conflict with an earlier one's, after its own body's errors. */
    fun testDefinitionTimeChecks() {
        assertMatchesElixir(DEFINITION_TIME)
    }

    /** What `@` raises as it expands, and what an attribute's validation raises as the module body runs. */
    fun testAttributeRaises() {
        assertMatchesElixir(ATTRIBUTE_RAISES)
    }

    private fun assertMatchesElixir(cases: List<String>) {
        val expansions = cases.associateWith { probes.expand(it) }

        assertEquals("", expansions.filterValues { it.ended is ExpansionResult.Ended.Stopped }.keys.joinToString("\n"))

        probes.assertMatchesElixir(expansions)
    }

    private companion object {
        val SITES = listOf(
            "__CALLER__",
            "__STACKTRACE__",
            "(^1 = x)",
            "^x",
            "_",
            "nope",
            "(^nope = x)",
            ":a.()",
            "(g(1) = x)",
            "(case x do\n    z when g(z) -> z\n  end)",
            "(case x do\n    m when m.a() == 1 -> m\n  end)",
            "(<<a::binary, b>> = x)",
            "(<<a::bitstring, b>> = x)",
            "<<(<<1::size(1)>>)::binary>>",
            // On 1.18 the type checker crashes on this pattern once the module is expanded.
            "(<<{a}>> = x)",
            "<<x::integer-binary>>",
            "<<x::size(8)-size(16)>>",
            "<<x::bad>>",
            "<<x::\"a\">>",
            "<<x::size(8)-unit(:a)>>",
            "(<<a::binary-unit(8), b>> = x)",
            "<<(<<1>>)::size(8)>>",
            "<<\"ab\"::size(8)>>",
            "<<x::utf8-size(8)>>",
            "<<x::utf8-signed>>",
            "<<x::bitstring-unit(8)>>",
            "<<x::binary-signed>>",
            "<<x::float-size(10)>>",
            "<<x::unit(8)>>",
            "(%{k: 1, k: 2} = x)",
        )

        val DEFINITIONS = listOf(
            "def f do\n  a = __STACKTRACE__\n  {a, undefined_var_q}\nend",
            "def f do\n  __STACKTRACE__\nend\ndef g do\n  __STACKTRACE__\nend",
            "def f, do: __STACKTRACE__\n__STACKTRACE__",
            "def f(a) do\n  b = __STACKTRACE__\n  {a, b, undefined_q}\nend\ndef g, do: :ok",
            // `g` is defined inside an `fn`, so its body may be expanded elsewhere in Elixir's order.
            "later = fn -> def g, do: __STACKTRACE__ end\ndef f, do: __CALLER__\nlater.()",
            "def f, do: __CALLER__\nlater = fn -> def g, do: __STACKTRACE__ end\nlater.()",
            "later = fn -> def g, do: __STACKTRACE__ end\ndef f, do: :ok\nlater.()",
            "def f(1, 2)\ndef f(a, b), do: {a, b}",
            "def g(1) when true\ndef g(a), do: a",
        )

        val LOCAL_CALLS = listOf(
            "def f, do: __STACKTRACE__\ndef g, do: nope()",
            "def g, do: nope()",
            "def f, do: m()\ndef g, do: __STACKTRACE__\ndefmacro m, do: 1",
            "def b, do: x()\ndef a, do: y()",
            "def f do\n  m()\n  nope()\nend\ndefmacro m, do: 1",
            "import List, only: [first: 1]\ndefmacro first(x), do: x\ndef g(x), do: first(x)",
            "def b(0), do: :ok\ndef a, do: y()\ndef b(1), do: x()",
            "def a do\n  x1()\n  b()\n  x2()\nend\ndef b, do: y()\ndef d, do: z()",
            "def z1 do\n  p()\n  q1()\nend\ndefp p, do: q2()\ndef a1, do: q3()",
            "defp c, do: w()\ndef b, do: v()",
            "defp pa, do: u1()\ndefp pb, do: u2()\ndef m do\n  k()\n  u3()\nend\ndef k, do: u4()\n" +
                "def n do\n  k()\n  u5()\nend",
            "def a do\n  x()\n  x()\nend",
            "def a, do: {x(), x()}",
            "def f(a \\\\ u(), b \\\\ v()), do: w()",
            "defp f(a \\\\ u()), do: a\ndef g, do: f()",
            "defmacro m(a \\\\ u()), do: a",
            "def f, do: b(a())",
            "def f, do: k(a())\ndef k(x), do: c(x)",
            "def f, do: m()\ndefmacrop m, do: 1",
            "def f, do: m()\ndefmacro m, do: 1",
            "def f, do: nope()",
            // A macro calling itself calls the function of its name, which it isn't.
            "defmacro m(x), do: m(x)",
            // Even once an earlier clause has defined the macro.
            "defmacro m(0), do: 0\ndefmacro m(x), do: m(x - 1)",
            "def f, do: &h/1",
            // A macro capturing itself captures the function of its name, as a call does.
            "defmacro m(x), do: &m/1",
            "def f, do: &m/1\ndefmacro m(x), do: x",
            "def f do\n  _ = &h/1\n  k()\nend",
            "def f, do: k(&h/1)",
        )

        val BAD_UNITS = listOf(
            "def f(x), do: <<x::integer-size(8)-unit(:a)>>",
            "def f(x), do: <<x::integer-unit(:a)>>",
            "def f(x), do: <<x::float-unit(:a)>>",
            "def f(x), do: <<x::binary-unit(:a)>>",
            "def f(x), do: <<x::bitstring-unit(:a)>>",
            "def f(x), do: <<x::utf8-unit(:a)>>",
        )

        val LATER_CHECKS = listOf(
            "def f(:a)\ndef g(:b)",
            "def f(a) when is_integer(a)\ndef g(b) when is_integer(b)",
            "import List, only: [first: 1]\ndef f, do: __STACKTRACE__\ndef g(x), do: first(x)\ndef first(x), do: x",
        )

        /**
         * Bodiless heads, import conflicts and the functions attributes name, alone and together. An imported macro
         * that conflicts with a definition isn't here: its use in a function stops at a macro the expander doesn't
         * expand yet.
         */
        val POST_MODULE = listOf(
            "def f(a)",
            "def b(x)\ndef a(x)",
            "def f(a)\ndef g, do: nope()",
            "import List\ndef f(l), do: first(l)\ndef first(x), do: x",
            "import List\ndef f, do: &first/1\ndef first(x), do: x",
            "import List\ndef f(l), do: {first(l), last(l)}\ndef first(x), do: x\ndef last(x), do: x",
            "import List\ndef f(l), do: first(l)\ndef first(x), do: x\ndef g, do: nope()",
            "import List\n_ = first([1])\ndef first(x), do: x",
            "@on_load :init\ndef f, do: 1",
            "@on_load :init\ndefmacro init, do: :ok",
            "@on_load :init\ndefp init, do: :ok",
            "@on_load :init\ndef g, do: nope()",
            "@on_load :init\n@compile {:inline, i: 0}\ndef g, do: 1",
            "@dialyzer {:nowarn_function, d: 0}\n@on_load :init\ndef g, do: 1",
            "@dialyzer {:nowarn_function, nope: 0}\ndef f, do: 1",
            "@dialyzer {:nowarn_function, m: 0}\ndefmacro m, do: 1",
            "@dialyzer {:nowarn_function, a: 0}\n@dialyzer {:nowarn_function, [c: 0, b: 0]}",
            "@dialyzer {:nowarn_function, f: 0}\n@dialyzer {:nowarn_function, nope: 0}\ndef f, do: 1",
            "@dialyzer nil\ndef f, do: 1",
            "@dialyzer [nil]\ndef f, do: 1",
            "@nifs [nope: 0]\ndef f, do: 1",
            "@nifs [:nope]\ndef f, do: 1",
            "@nifs [m: 0]\ndefmacro m, do: 1",
            "@nifs [c: 0, b: 0]",
            "@nifs [f: 0]\n@nifs [nope: 0]\ndef f, do: 1",
            "@compile {:inline, nope: 0}\ndef f, do: 1",
            "@compile {:inline, nope: 0}\ndef g, do: nope2()",
            "@compile {:inline, m: 0}\ndefmacro m, do: 1",
            "@compile {:inline, a: 0}\n@compile {:inline, [c: 0, b: 0]}",
            "@on_load :init\n@dialyzer {:nowarn_function, d: 0}\n@compile {:inline, i: 0}\n@nifs [n: 0]\ndef h(a)\n" +
                "import List\ndef f(l), do: first(l)\ndef first(x), do: x\ndef g, do: nope()",
            "def unquote(:f)(a)",
        )

        /** Definitions whose kind or defaults conflict with an earlier one's, with and without body errors. */
        val DEFINITION_TIME = listOf(
            "def f, do: 1\ndefp f, do: 2",
            "def f(a \\\\ 1), do: a\ndef f(a \\\\ 2), do: a",
            "def f(a, b \\\\ 1), do: {a, b}\ndef f(a), do: a",
            "def g, do: __STACKTRACE__\ndef f, do: 1\ndefp f, do: 2",
            "def f, do: 1\ndefp f, do: __STACKTRACE__\ndef g, do: __STACKTRACE__",
            "def f(a, b \\\\ 1), do: {a, b}\ndef f(a), do: __STACKTRACE__",
            "def f(a \\\\ 1), do: a\ndef f(a \\\\ 2), do: __STACKTRACE__",
        )

        /** What `@` and the attribute validations raise, less a read whose value has no Term. */
        val ATTRIBUTE_RAISES = listOf(
            "def f, do: @x 1",
            "def g, do: __STACKTRACE__\ndef f, do: @x 1\ndef h, do: 1",
            "@behavior Foo",
            "@x = 1",
            "@x 1\ncase 1 do\n  y when y == @x -> y\nend",
            "@x 1, 2",
            "@x foo do\n  1\nend",
            "@impl \"s\"\ndef f, do: 1",
            "def g, do: __STACKTRACE__\n@impl \"s\"\ndef f, do: __STACKTRACE__",
            "@behaviour \"s\"",
            "@doc 1\ndef f, do: 1",
            "@deprecated 1\ndef f, do: 1",
            "@on_load 1",
            "@on_load :a\n@on_load :b\ndef a, do: :ok\ndef b, do: :ok",
            "@external_resource 1",
            "@x 1\ndef f(@x), do: 1",
            "def f, do: @spec(f() :: any)",
            "@x 1\ndef f, do: @x()",
        )
    }
}
