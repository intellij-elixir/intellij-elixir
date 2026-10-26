package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile

/**
 * Each module's definitions against what a `@before_compile` hook reads of them on the leg's Elixir: names, arities
 * and kinds on every leg, and from 1.12, when `Module.get_definition/2` gives them, each definition's line and
 * clause count, and from 1.20 whether it was stored for a default arity.
 */
class DefinitionTableProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testDefinitionsMatchTheHook() {
        assertTablesMatch(CASES) { case, attempt, module ->
            val status = attempt.compiled.status

            assertEquals(
                "$case: ${(status as? OtpErlangTuple)?.let { utf8(it.elementAt(2)) }} " +
                    "${attempt.compiled.diagnostics.map(::inspect)}\n${attempt.source}",
                OtpErlangAtom("ok"),
                status,
            )
            assertTrue("the hook ran in $module", module in attempt.batch.hooks)
        }
    }

    /** A module the case defines has its definitions compared too, not the case module's only. */
    fun testANestedModulesDefinitionsMatchTheHook() {
        val case = "defmodule Fixture do\n  def f(a), do: a\nend"
        val rendered = assertTablesMatch(listOf(case)) { _, attempt, module ->
            assertEquals("compile status", OtpErlangAtom("ok"), attempt.compiled.status)
            assertTrue("the hook ran in $module.Fixture", "$module.Fixture" in attempt.batch.hooks)
        }

        assertTrue("a row for Fixture's f/1 in\n$rendered", rendered.contains(".Fixture f/1 def"))
    }

    /** A module that compiled before the case module failed is compared, though the case module's hook never ran. */
    fun testANestedModuleThatCompiledBeforeTheCaseModuleFailedIsCompared() {
        val case = "defmodule M do\n  def f(a), do: a\nend\nraise \"boom\""
        val rendered = assertTablesMatch(listOf(case)) { _, attempt, module ->
            assertFalse("the hook ran in $module", module in attempt.batch.hooks)
            assertTrue("the hook ran in $module.M", "$module.M" in attempt.batch.hooks)
        }

        assertTrue("a row for M's f/1 in\n$rendered", rendered.contains(".M f/1 def"))
    }

    /** A module Elixir compiled under the case module that the hook never reached fails the comparison, naming it. */
    fun testAModuleTheHookMissedFailsTheComparison() {
        val case = "Module.create(__MODULE__.Made, quote(do: def(f, do: :ok)), __ENV__)"
        val expansions = probes.expandAll(listOf(case), hook = ProbeHarness.Hook())
        val attempt = harness.attempt(expansions.layout)
        val module = expansions.layout.caseModule(0)

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf(module), attempt.batch.hooks.keys.toList())

        val failure = try {
            DefinitionTables.compare(
                module,
                attempt.batch.hooks,
                expansions.cases.single().result,
                expansions.layout.bodyLines[0],
                attempt.compiled,
            )

            null
        } catch (failure: AssertionError) {
            failure
        }

        assertNotNull("the comparison failed", failure)
        assertTrue("the failure names $module.Made: ${failure!!.message}", failure.message!!.contains("$module.Made"))
        harness.assertLeftNothingBehind(attempt)
    }

    /**
     * What a macro's effect does to the table: a guard's macro, a definition made overridable, `super`'s hidden entry
     * and a definition in a branch that may not run, each last in the module body.
     */
    fun testEntryEffects() {
        assertTablesMatch(EFFECT_CASES, stopsAtTheGuardTemplate) { case, attempt, module ->
            assertTrue("the hook ran in $module: $case", module in attempt.batch.hooks)
        }
    }

    /** The hook runs after an error the module's checks report, and from 1.15 after one in a function body. */
    fun testDefinitionsUnderAnError() {
        val level = legLevel()

        assertTablesMatch(ERROR_CASES.keys.toList()) { case, attempt, module ->
            assertFalse("compiled", attempt.compiled.status == OtpErlangAtom("ok"))
            assertEquals("the hook ran in $module", ERROR_CASES.getValue(case)(level), module in attempt.batch.hooks)
        }
    }

    /**
     * Expands and compiles each of [cases] alone with the hook, asserts [ran] of each compile, then compares the
     * expander's table with the hook's if it ran; afterwards each compile left no module behind and the compiler
     * options are as they were. A case may stop only where [stopsAt] allows, and only its table is compared.
     */
    private fun assertTablesMatch(
        cases: List<String>,
        stopsAt: (ExpansionResult) -> Boolean = { false },
        ran: (String, ProbeHarness.Attempt, String) -> Unit,
    ): String {
        val options = compilerOptions()
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for (case in cases) {
            val expansions = probes.expandAll(listOf(case), hook = ProbeHarness.Hook())
            val expansion = expansions.cases.single()

            if (expansion.ended is ExpansionResult.Ended.Stopped) {
                assertTrue("${case}: ${expansion.outcome}", stopsAt(expansion.result))
            }

            val attempt = harness.attempt(expansions.layout)
            val module = expansions.layout.caseModule(0)

            ran(case, attempt, module)
            harness.assertLeftNothingBehind(attempt)

            DefinitionTables.compare(
                module,
                attempt.batch.hooks,
                expansion.result,
                expansions.layout.bodyLines[0],
                attempt.compiled,
            )
                ?.let { rows ->
                    expected += DefinitionTables.render(case, rows.elixir)
                    actual += DefinitionTables.render(case, rows.expander)
                }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
        assertEquals("compiler options", options, compilerOptions())

        return expected.joinToString("\n")
    }

    /** A bodied `defguard` stops the module at `Kernel.Utils.defguard/2`, whose output isn't followed, and nowhere else. */
    private val stopsAtTheGuardTemplate = { result: ExpansionResult ->
        result.opaque.isNotEmpty() &&
            result.opaque.all { it.dispatch.receiver == "Elixir.Kernel.Utils" && it.dispatch.name == "defguard" }
    }

    private companion object {
        val CASES = listOf(
            """
            @behaviour Access
            Module.register_attribute(__MODULE__, :acc, accumulate: true)
            @acc :a
            @acc :b
            @x 1
            @x 2
            def f(a, b \\ 1, c \\ 2), do: {a, b, c}
            defp g(x), do: x
            defmacro m(x \\ nil), do: x
            defmacrop mp, do: 1
            def h(x) when is_integer(x), do: g(x)
            def h(x), do: x
            case 1 do
              1 -> def in_case, do: 1
            end
            def gen1, do: :gen1
            def gen2, do: :gen2
            def fetch(_, _), do: :error
            def get_and_update(_, _, _), do: :error
            def pop(_, _), do: :error
            """.trimIndent(),
            "def unquote(:bare)(x), do: x\ndef bare(y), do: y",
            // A definition named `defmodule` defines that name, and no module.
            "def defmodule(name, do: block), do: {name, block}",
            "defmacro defmodule(name, do: block) when is_atom(name), do: {name, block}",
        )

        val EFFECT_CASES = listOf(
            "defguard is_one(x) when x == 1",
            "defguard is_three(x)",
            "defguardp is_two(x) when x == 2",
            "defstruct [:a, :b]",
            "defexception [:reason]",
            "defexception [:message]",
            "@enforce_keys [:a]\ndefstruct [:a]",
            "if System.get_env(\"UNSET_FOR_PROBE\") == nil do\n  def t, do: 1\nend",
            "if System.get_env(\"UNSET_FOR_PROBE\") != nil do\n  def t, do: 1\nend",
            "if System.get_env(\"UNSET_FOR_PROBE\") == nil do\n  def t, do: 1\nelse\n  def u, do: 2\nend",
            "case System.get_env(\"UNSET_FOR_PROBE\") do\n  nil -> def t, do: 1\n  _ -> :ok\nend",
            "case System.get_env(\"UNSET_FOR_PROBE\") do\n  nil -> :ok\n  _ -> def u, do: 2\nend",
            "def before, do: 0\ncase System.get_env(\"UNSET_FOR_PROBE\") do\n  nil -> def t, do: 1\n  _ -> def u, do: 2\nend",
            "case System.get_env(\"UNSET_FOR_PROBE\") do\n  \"set\" -> def s(x), do: x\n  nil -> def s(x), do: -x\nend",
            "def g(a, b), do: a + b\ndefoverridable g: 2\ndef g(a, b), do: super(a, b)",
            "def g(a, b), do: a + b\ndefoverridable g: 2\ndef g(a, b), do: super(a, b)\n" +
                "defoverridable g: 2\ndef g(a, b), do: super(a, b)",
            "defmacro g(a, b), do: {a, b}\ndefoverridable g: 2\ndefmacro g(a, b), do: super(a, b)",
            "def g(a, b), do: a + b\ndefoverridable g: 2",
            "def f(a \\\\ 1), do: a\ndefoverridable f: 1\ndef f(a, b \\\\ 2), do: a + b",
            "def f(a, b \\\\ 1), do: a + b\ndefoverridable f: 2\ndef f(a \\\\ 0), do: a",
        )

        /** Each case, and whether the hook runs at the leg's level. */
        val ERROR_CASES: Map<String, (ElixirLanguageLevel) -> Boolean> = mapOf(
            "def f, do: nope()\ndef g(a \\\\ 1), do: a" to { _ -> true },
            "def f, do: __STACKTRACE__\ndef g(a \\\\ 1), do: a" to { level -> FUNCTION_ERRORS_CONTINUE.isSufficient(level) },
        )
    }
}
