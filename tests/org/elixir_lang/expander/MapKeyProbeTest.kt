package org.elixir_lang.expander

import com.intellij.util.system.OS
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ENTRY_WITHOUT_ASSOCIATION

/**
 * A map's pairs compiled as module bodies on the leg's Elixir, which validates each pair and key as expanded: a macro
 * or an attribute read may give what the source doesn't show.
 */
class MapKeyProbeTest : SdkProbeTestCase() {
    fun testAnExpandedKeyIsValid() = assertMatches(
        listOf(
            "@k :a\ndef f(%{@k => s}), do: s",
            "@k :a\ndef f(m) do\n%{@k => s} = m\ns\nend",
            "@k :a\ndef f(m) do\ncase m do\n%{@k => s} -> s\nend\nend",
            "@k System.get_env(\"HOME\")\ndef f(%{@k => s}), do: s",
            "@k System.get_env(\"HOME\")\ndef f(m) do\n%{@k => s} = m\ns\nend",
            "@k System.get_env(\"HOME\")\ndef f(m) do\ncase m do\n%{@k => s} -> s\nend\nend",
            "@a :a\n@b :b\ndef f(%{@a => %{@b => c}}), do: c",
            "def f(%{__MODULE__ => s}), do: s",
            "def f(%{__DIR__ => s}), do: s",
            "require $MACROS\ndef f(%{$MACROS.k() => s}), do: s",
            "def f(%{~s(a) => s}), do: s",
            "@k :path\ndef f(%URI{@k => s}), do: s",
        ),
    )

    /** With a file and an OS, `__DIR__` is the directory, where without them it stays the variable. */
    fun testAKeyOfTheDirectoryIsValid() {
        val body = "def f(%{__DIR__ => s}), do: s"
        val expansion = probes.expandAll(listOf(body), clauses = sdk, file = "/a/b.ex", os = OS.Linux).cases.single()

        assertFalse("${expansion.outcome} ${expansion.ended}", expansion.ended.raises)
    }

    fun testAnExpandedPairIsValid() = assertMatches(
        listOf(
            "require $MACROS\ndef f, do: %{$MACROS.p()}",
            "require $MACROS\ndef f(%{$MACROS.p()}), do: 1",
            "require $MACROS\ndef f(m), do: %{m | $MACROS.p()}",
            "require $MACROS\ndef f, do: %URI{$MACROS.path()}",
            "require $MACROS\ndef f(%URI{$MACROS.path()}), do: 1",
            "@p {:path, \"a\"}\ndef f, do: %{@p}",
            "@p {:path, \"a\"}\ndef f(%{@p}), do: 1",
            "@p {:path, \"a\"}\ndef f(m), do: %{m | @p}",
            "@p {:path, \"a\"}\ndef f, do: %URI{@p}",
            "@p {:path, \"a\"}\ndef f(%URI{@p}), do: 1",
            "require $MACROS\ndef f, do: %{$MACROS.with_case()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_fn()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_try()}",
            "def f(%{a: fn -> 1 end}), do: 1",
            "@k :a\ndef f(%{@k => fn -> 1 end}), do: 1",
            "def f, do: %{a: case 1 do\nx -> x\nend}",
            "require $MACROS\ndef f, do: %{$MACROS.with_rescue_in()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_for()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_remote()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_map()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_with()}",
            "require $MACROS\ndef f, do: %{$MACROS.with_capture()}",
            "require $MACROS\ndef f(x), do: %{$MACROS.with_match(x)}",
        ),
    )

    /** Elixir's answer depends on the value of a read the expander doesn't have, which is not a raise here. */
    fun testAnUnknownPairStops() {
        val bodies = listOf(
            "@p {:a, System.get_env(\"HOME\")}\ndef f, do: %{@p}",
            "@p {:a, System.get_env(\"HOME\")}\ndef f(%{@p}), do: 1",
            "@p {:path, System.get_env(\"HOME\")}\ndef f, do: %URI{@p}",
            "@p {:path, System.get_env(\"HOME\")}\ndef f(%URI{@p}), do: 1",
        )

        assertEquals(
            bodies.joinToString("\n") { "$it: Unported" },
            bodies.joinToString("\n") { body -> "$body: ${expand(body, PREAMBLE).outcome::class.simpleName}" },
        )
    }

    fun testAVariableStillRaises() = assertMatches(
        listOf(
            "@p 1\ndef f, do: %{@p}",
            "def f(k), do: fn %{k => s} -> {k, s} end",
            "def f(%{k => s}), do: s",
            "require $MACROS\ndef f(x, %{$MACROS.k(x) => s}), do: s",
            "def f(x, %{{:a, x} => s}), do: s",
            "def f(%{^k => s}), do: s",
            "def f(%{__ENV__ => t}), do: t",
            "defmacro f(%{__CALLER__ => t}), do: t",
            "def f(%{__STACKTRACE__ => t}), do: t",
            "@k :a\n%{@k => s} = %{a: 1}\n_ = s",
        ),
    )

    fun testARepeatedExpandedKeyRaises() = assertMatches(
        listOf(
            "@k :s\ndef f(%{@k => s, @k => t}), do: {s, t}",
            "@k :s\ndef f(%{@k => s, :s => t}), do: {s, t}",
        ),
    )

    private fun assertMatches(bodies: List<String>) {
        val parsed = if (MAP_ENTRY_WITHOUT_ASSOCIATION.isSufficient(legLevel())) bodies else bodies.filterNot { "@p}" in it }
        val expansions = parsed.associateWith { expand(it, PREAMBLE) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    private companion object {
        const val MACROS = "MapKeyMacros{token}"

        val PREAMBLE =
            """
            defmodule $MACROS do
              defmacro k, do: :a
              defmacro k(x), do: quote(do: {:a, unquote(x)})
              defmacro p, do: {:a, 1}
              defmacro path, do: {:path, "a"}
              defmacro with_case, do: {:a, quote(do: case 1 do x -> x end)}
              defmacro with_fn, do: {:a, quote(do: fn -> 1 end)}
              defmacro with_try, do: {:a, quote(do: try do 1 rescue _ -> 2 end)}
              defmacro with_rescue_in, do: {:a, quote(do: try do 1 rescue e in ArgumentError -> e end)}
              defmacro with_for, do: {:a, quote(do: for(x <- [1], do: x))}
              defmacro with_remote, do: {:a, quote(do: String.to_atom("a"))}
              defmacro with_map, do: {:a, quote(do: %{b: try do 1 rescue e in ArgumentError -> e end})}
              defmacro with_with, do: {:a, quote(do: with(x <- 1, do: x))}
              defmacro with_capture, do: {:a, quote(do: &String.to_atom/1)}
              defmacro with_match(x), do: {:a, quote(do: y = unquote(x))}
            end
            """.trimIndent()
    }
}
