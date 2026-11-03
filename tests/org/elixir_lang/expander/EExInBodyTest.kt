package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangMap
import org.elixir_lang.FIXED_STACK
import java.io.File

/**
 * `EEx.function_from_string/5` of a page-sized template in a module body, whose tokenizer recurses once per character:
 * the module compiles on [FIXED_STACK], with the functions and macros Elixir's `__info__/1` gives it.
 */
class EExInBodyTest : SdkProbeTestCase() {
    /** A page whose template branches, loops over a map and runs pipelines across lines. */
    fun testAPageTemplateWithBranchesAndAComprehensionCompiles() =
        assertCompiledAsElixirDoes(
            "Dashboard",
            "dashboard.html.eex",
            """
            def call(report, csrf), do: render(report, csrf, nil, nil)

            require EEx
            EEx.function_from_string(:defp, :render, @page, [:report, :csrf, :style_nonce, :script_nonce])

            defp label(name), do: name |> to_string() |> Macro.camelize()
            defp cell(_label, value), do: inspect(value)
            defp encode(term), do: inspect(term)
            """,
        )

    /** A page that is mostly script, with one branch. */
    fun testAPageTemplateOfScriptCompiles() =
        assertCompiledAsElixirDoes(
            "SignedOut",
            "signed_out.html.eex",
            """
            def call(script_nonce), do: render(script_nonce)

            require EEx
            EEx.function_from_string(:defp, :render, @page, [:script_nonce])
            """,
        )

    /**
     * The module [name] whose `@page` is the template [template] and whose body then runs [definitions], as the
     * expander ends it on [FIXED_STACK] against Elixir's compile of it.
     */
    private fun assertCompiledAsElixirDoes(name: String, template: String, definitions: String) {
        val module = "${harness.token()}.$name"
        val source = "defmodule $module do\n  @page \"\"\"\n${File(TEMPLATES, template).readText()}\"\"\"\n\n" +
            definitions.trimIndent().prependIndent("  ") + "\nend\n"

        val expected = harness.withEex {
            val compiled = harness.compileSource(
                "$source\nIntellijElixir.Quoter.Probe.send(__ENV__, %{functions: $module.__info__(:functions), macros: $module.__info__(:macros)})",
            )

            assertEquals("Elixir's compile: ${compiled.diagnostics}", OtpErlangAtom("ok"), compiled.status)

            "compiled ${exported(compiled.messages.single() as OtpErlangMap)}"
        }
        val level = legLevel()
        val forms = lowered(source, level)
        val result = expandedOnAStack(forms, level, sdk, FIXED_STACK)

        assertEquals(expected, "${ending(result)} ${stored(result)}")
    }

    private companion object {
        val TEMPLATES = File("testData/org/elixir_lang/expander/eex_in_body").absoluteFile
    }
}
