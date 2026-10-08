package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * The twelve `Kernel` sigils compiled in module bodies, function bodies, matches and guards on the leg's Elixir: at
 * each probe the env, the variables and the hygiene counters taken, each error a sigil raises, and the dispatch events
 * where the module compiles, `struct_expansion` of a calendar struct and the `Regex` calls of a `~r` among them.
 *
 * A sigil with no modifiers is written inside a list: its PSI range runs on over the whitespace after it, which a
 * probe's statement range then doesn't contain.
 */
class SigilProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    fun testStrings() = assertMatchesElixir(STRINGS.map(::inModuleBody))

    fun testWords() = assertMatchesElixir(WORDS.map(::inModuleBody))

    fun testRegexes() = assertMatchesElixir(REGEXES.map(::inModuleBody))

    fun testCalendars() = assertMatchesElixir(CALENDARS.map(::inModuleBody))

    /** A sigil in a function body expands there, and its dispatch is traced in the function's env. */
    fun testFunctionBodies() = assertMatchesElixir(FUNCTION_BODIES.map { "def f(x) do\n  ${listed(it)}\nend" })

    /** A sigil in a match expands in the match, and from 1.20 a `~r` there raises. */
    fun testMatches() = assertMatchesElixir(MATCHES.map { "x = [1]\ncase x do\n  ${listed(it)} -> 1\n  _ -> 2\nend" })

    /** A sigil in a guard expands in it, and from 1.20 a `~r` there raises. */
    fun testGuards() = assertMatchesElixir(GUARDS.map { "x = 1\n_ = case x do\n  z when $it -> z\n  _ -> 0\nend" })

    /**
     * A modifier a sigil has no clause for, a non-binary argument, a modifier `~w` rejects, a pattern's option that
     * `Regex` rejects, an escape that does not unescape and a calendar string that does not parse, each raise where
     * the compiler raises.
     */
    fun testRaises() = assertMatchesElixir(RAISES.map(::inModuleBody))

    private fun inModuleBody(case: String): String {
        val (setup, sigil) = if (case.startsWith("x = ")) {
            case.substringBefore("\n_ = ") + "\n" to case.substringAfter("\n_ = ")
        } else {
            "" to case
        }

        return "${setup}_ = ${listed(sigil)}"
    }

    /** [sigil] inside a list when it has no modifiers, as the class comment says, and as it is written when it has. */
    private fun listed(sigil: String) = if (sigil.last().isLetter()) sigil else "[$sigil]"

    /**
     * Compares each of [bodies], none of which stops where the expander can't follow, with Elixir, then the dispatch
     * events of those that expand. A `~r` that ends at `Regex.__import_pattern__/1`, the macro a compiled `Regex` is
     * escaped to from OTP 28, is compared up to it.
     */
    private fun assertMatchesElixir(bodies: List<String>) {
        val expansions = bodies.associateWith { probes.expand(it) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque && !isRegexImport(it.outcome) }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }

        val opaque = expansions.filterValues { it.outcome is Expansion.Opaque }

        if (opaque.isNotEmpty()) {
            printed { probes.assertMatchesElixirUpToMacro(opaque) }
        }

        val expanded = bodies.filter { expansions.getValue(it).outcome is Expansion.Expanded }

        if (expanded.isNotEmpty()) {
            printed { probes.assertTracesMatchElixir(probes.expandAll(expanded)) }
        }
    }

    private fun isRegexImport(outcome: Expansion) =
        outcome is Expansion.Opaque && outcome.dispatch.receiver == "Elixir.Regex" && outcome.dispatch.name == "__import_pattern__"

    private companion object {
        val STRINGS = listOf(
            "~S(a b)",
            "~S(a#{b})",
            "~S(a\\nb)",
            "~s(a b)",
            "~s(a\\nb)",
            "~s(a\\u00e9b)",
            "x = 1\n_ = ~s(a#{x}b)",
            "x = 1\n_ = ~s(#{x})",
            "~s()",
            "~C(a b)",
            "~C(a\\nb)",
            "~c(a b)",
            "~c(a\\nb)",
            "x = 1\n_ = ~c(a#{x}b)",
            "~S\"\"\"\n  a\n  b\n  \"\"\"",
            "~s\"\"\"\n  a\n  b\n  \"\"\"",
        )

        val WORDS = listOf(
            "~w(a b)",
            "~w(a b)s",
            "~w(a b)a",
            "~w(a b)c",
            "~w()",
            "~w(a\\nb c)",
            "x = 1\n_ = ~w(a #{x} b)",
            "x = 1\n_ = ~w(a #{x} b)s",
            "x = 1\n_ = ~w(a #{x} b)a",
            "x = 1\n_ = ~w(a #{x} b)c",
            "~W(a b)",
            "~W(a b)s",
            "~W(a b)a",
            "~W(a b)c",
            "~W(a#{b} c)",
            "~W(a\\nb c)",
        )

        val REGEXES = listOf(
            "~r/a+/",
            "~r/a+/i",
            "~r/a+/ims",
            "~r/a+/u",
            "~r/a+/x",
            "~r/a+/U",
            "~r/a\\tb/",
            "~r/a\\nb/",
            "~r/a\\/b/",
            "~r\"\"\"\n  a+\n  \"\"\"",
            "x = 1\n_ = ~r/a#{x}b/",
            "x = 1\n_ = ~r/a#{x}b/i",
            "x = 1\n_ = ~r/a#{x}\\tb/",
            "~R/a+/",
            "~R/a+/i",
            "~R/a\\tb/",
            "~R/a#{b}c/",
        )

        val CALENDARS = listOf(
            "~D[2020-01-01]",
            "~D[2020-01-01 Calendar.ISO]",
            "~D[-0001-01-01]",
            "~T[01:02:03]",
            "~T[01:02:03.123]",
            "~T[01:02:03 Calendar.ISO]",
            "~N[2020-01-01 01:02:03]",
            "~N[2020-01-01T01:02:03.5]",
            "~N[2020-01-01 01:02:03 Calendar.ISO]",
            "~N[2020-01-01 01:02:03.000]",
            "~U[2020-01-01 01:02:03Z]",
            "~U[2020-01-01T01:02:03.5Z]",
            "~U[2020-01-01 01:02:03+00:00]",
            "~U[2020-01-01 01:02:03Z Calendar.ISO]",
        )

        val FUNCTION_BODIES = listOf(
            "~s(a#{x}b)",
            "~w(a #{x})a",
            "~r/a+/i",
            "~r/a#{x}b/",
            "~D[2020-01-01]",
            "~U[2020-01-01 01:02:03Z]",
        )

        val MATCHES = listOf(
            "~S(a)",
            "~s(a)",
            "~c(a)",
            "~w(a b)",
            "~D[2020-01-01]",
            "~T[01:02:03]",
            "~N[2020-01-01 01:02:03]",
            "~U[2020-01-01 01:02:03Z]",
            "~r/a+/",
            "~r/a+/i",
            "~R/a+/",
            "~w(a b)a",
        )

        val GUARDS = listOf(
            "is_binary([~s(a)])",
            "z == [~S(a)]",
            "z == [~w(a b)]",
            "z == [~D[2020-01-01]]",
            "z == [~r/a+/]",
            "z == [~R/a+/]",
        )

        val RAISES = listOf(
            // no clause for a modifier, or for an argument that is not the sigil's binary
            "~S(a)x",
            "~s(a)x",
            "~C(a)x",
            "~c(a)x",
            "~D[2020-01-01]x",
            "~T[01:02:03]x",
            "~N[2020-01-01 01:02:03]x",
            "~U[2020-01-01 01:02:03Z]x",
            "~w(a b)z",
            "~W(a b)z",
            "sigil_s(1, [])",
            "sigil_S(1, [])",
            "sigil_w(1, [])",
            "sigil_r(1, [])",
            "sigil_D(1, [])",
            // a pattern's option that Regex rejects
            "~r/a/z",
            "~R/a/z",
            // escapes that do not unescape
            "~s(\\xZZ)",
            "~s(\\x)",
            "~s(\\uZZZZ)",
            "~s(\\u{110000})",
            "x = 1\n_ = ~s(\\xZZ#{x})",
            "~c(\\xZZ)",
            "~w(\\xZZ)",
            // a calendar string that does not parse
            "~D[2020-13-01]",
            "~D[2020-02-30]",
            "~D[nope]",
            "~T[25:00:00]",
            "~T[nope]",
            "~N[2020-01-01]",
            "~N[2020-01-01 25:00:00]",
            "~U[2020-01-01 01:02:03]",
            "~U[2020-01-01 01:02:03+01:00]",
        )
    }
}
