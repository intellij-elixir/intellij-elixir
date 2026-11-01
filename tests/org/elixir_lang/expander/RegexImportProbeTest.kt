package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_REGEX_IMPORTS_ON_OTP_28_1
import org.elixir_lang.lowering.ElixirAst

/**
 * A compiled `~r` on OTP 28.1 and later, which Elixir writes as `Regex.__import_pattern__/1` on a tuple holding bytes
 * only PCRE can build, runs that macro's own clauses from the leg's `Regex`, compiled as module bodies on the leg's
 * Elixir.
 */
class RegexImportProbeTest : SdkProbeTestCase(accounting = true) {
    fun testAFunctionExpandsTheImport() {
        val bodies = listOf(
            // In a list: a sigil with no modifiers has a PSI range running on past it, which a probe's range doesn't contain.
            "def a, do: [~r/a/]",
            "def a, do: ~R/a/i",
            "def a do\nx = ~r/a/\nx\nend",
            "def a(x \\\\ ~r/a/), do: x",
            "x = ~r/a/\n_ = x",
            "_ = quote(do: ~r/a/)",
        )
        val expansions = bodies.associateWith { expand(it, "") }

        assertEquals(
            "",
            expansions.filter { (_, expansion) -> stops(expansion) }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome} ${expansion.result.opaque}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    fun testAModuleContinuesPastARegex() {
        val expansion = expand("def a, do: ~r/a/\ndef b, do: 1", "")

        assertFalse("${expansion.outcome} ${expansion.result.opaque}", stops(expansion))
        assertNotNull(expansion.result.table.entries[NameArity("b", 0)])
    }

    fun testAnAttributeHoldingARegexIsNotKnown() {
        val expansion = expand("@re ~r/a/\ndef a, do: @re", "")

        assertFalse("${expansion.outcome} ${expansion.result.opaque}", stops(expansion))
        assertEquals(AttributeValue.Unknown, expansion.result.attributes.final["re"])
    }

    /** The macro's own raise answers, the same on a written call as on the one `~r` writes. */
    fun testAnImportInAMatchOrGuardRaises() {
        val expansions = listOf(
            "require Regex\ndef a(Regex.__import_pattern__(1)), do: 1",
            "require Regex\ndef a(x) when Regex.__import_pattern__(1) == x, do: x",
        ).associateWith { expand(it, "") }

        expansions.forEach { (body, expansion) ->
            assertTrue("$body: ${expansion.outcome} ${expansion.ended}", expansion.ended.raises)
        }
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** Broken source has no value for the macro to carry, where a compiled node has one it can't read. */
    fun testAMacroArgumentForSourceWithNoRuleStops() {
        if (!ESCAPED_REGEX_IMPORTS_ON_OTP_28_1.isSufficient(legLevel())) return

        val call = "Regex.__import_pattern__(1)"
        val brokenArgument = ExpansionProbes.StandIn(call, "Elixir.Regex", "__import_pattern__", 1) { written, _ ->
            ElixirAst.Call(written.meta, written.callee, listOf(ElixirAst.Placeholder(written.meta, ElixirAst.Placeholder.Reason.Error)))
        }
        val expansion = probes.expandAll(listOf("require Regex\ndef a, do: $call"), standIns = listOf(brokenArgument), clauses = sdk).cases.single()

        assertTrue("${expansion.outcome} ${expansion.result.opaque}", expansion.result.opaque.singleOrNull()?.dispatch?.name == "__import_pattern__")
    }

    /** Elixir gives such a macro bytes the expander can't build, so what it makes of them is never `Known`. */
    fun testAMacroReadingAnExpandedRegexIsNotKnown() {
        val expansions = listOf(
            "@x {token}.Reader.expanded(~r/a/)\ndef a, do: @x",
            "@x {token}.Reader.once(~r/a/)\ndef a, do: @x",
            "@x {token}.Reader.nested(~r/a/)\ndef a, do: @x",
        ).map { "require {token}.Reader\n$it" }.associateWith { expand(it, READER) }

        assertEquals(
            "",
            expansions.filter { (_, expansion) -> expansion.result.attributes.final["x"] is AttributeValue.Known }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.result.attributes.final["x"]}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** A macro given a regex it doesn't expand has nothing compiled to read. */
    fun testAMacroPassingARegexOnContinues() {
        val expansion = expand("require {token}.Reader\ndef a, do: {token}.Reader.passed(~r/a/)\ndef b, do: 1", READER)

        assertFalse("${expansion.outcome} ${expansion.result.opaque}", stops(expansion))
        assertNotNull(expansion.result.table.entries[NameArity("b", 0)])
    }

    /** `@on_definition` is given the written body, so no compiled node reaches the callback. */
    fun testADefinitionCallbackIsGivenTheWrittenRegex() {
        val expansions = listOf("@on_definition {{token}.Reader, :on_def}\ndef a, do: ~r/a/\ndef b, do: 1")
            .associateWith { expand(it, READER) }

        expansions.forEach { (body, expansion) ->
            assertFalse("$body: ${expansion.outcome} ${expansion.result.opaque}", stops(expansion))
            assertFalse("$body: ${expansion.ended}", expansion.ended.raises)
        }
        printed { probes.assertMatchesElixir(expansions) }
    }

    private fun stops(expansion: ExpansionProbes.CaseExpansion) =
        expansion.outcome is Expansion.Unported || expansion.outcome is Expansion.Opaque || expansion.result.opaque.isNotEmpty()

    private companion object {
        const val READER = "\ndefmodule {token}.Reader do\n" +
            "  defp binaries(ast) do\n" +
            "    {_, count} = Macro.prewalk(ast, 0, fn\n" +
            "      x, count when is_binary(x) -> {x, count + 1}\n" +
            "      x, count -> {x, count}\n" +
            "    end)\n" +
            "    count\n" +
            "  end\n" +
            "  defmacro expanded(re), do: binaries(Macro.expand(re, __CALLER__))\n" +
            "  defmacro once(re), do: binaries(Macro.expand_once(re, __CALLER__))\n" +
            "  defmacro nested(re) do\n" +
            "    expanded = Macro.expand(re, __CALLER__)\n" +
            "    quote(do: unquote(__MODULE__).expanded(unquote(expanded)))\n" +
            "  end\n" +
            "  defmacro passed(re), do: re\n" +
            "  def on_def(_env, _kind, :a, _args, _guards, body) do\n" +
            "    {_, true} = Macro.prewalk(body, false, fn\n" +
            "      {:sigil_r, _, _} = x, _ -> {x, true}\n" +
            "      x, found -> {x, found}\n" +
            "    end)\n" +
            "    :ok\n" +
            "  end\n" +
            "  def on_def(_env, _kind, _name, _args, _guards, _body), do: :ok\n" +
            "end\n"
    }
}
