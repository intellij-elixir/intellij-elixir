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
     * options are as they were.
     */
    private fun assertTablesMatch(cases: List<String>, ran: (String, ProbeHarness.Attempt, String) -> Unit) {
        val options = compilerOptions()
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for (case in cases) {
            val expansions = probes.expandAll(listOf(case), hook = ProbeHarness.Hook())
            val expansion = expansions.cases.single()

            assertFalse("${case}: ${expansion.outcome}", expansion.ended is ExpansionResult.Ended.Stopped)

            val attempt = harness.attempt(expansions.layout)
            val module = expansions.layout.caseModule(0)

            ran(case, attempt, module)
            assertLeftNothingBehind(expansions.layout)

            val bodyLine = expansions.layout.bodyLines[0]

            attempt.batch.hooks[module]?.let { hooked ->
                expected += render(case, hooked.map { hookRow(it, bodyLine) })
                actual += render(case, expansion.result.table.entries.map { (nameArity, entry) ->
                    tableRow(nameArity.name, nameArity.arity, entry, bodyLine)
                })
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
        assertEquals("compiler options", options, compilerOptions())
    }

    private fun render(case: String, rows: List<String>) =
        "== ${case.replace("\n", "; ")}\n" + rows.sorted().joinToString("") { "  $it\n" }

    private fun hookRow(entry: ProbeHarness.HookEntry, bodyLine: Int): String =
        row(entry.name, entry.arity, entry.kind, entry.line?.let { it - bodyLine + 1 }, entry.clauses, entry.default)

    private fun tableRow(name: String, arity: Int, entry: DefinitionTable.Entry, bodyLine: Int): String {
        val level = legLevel()
        val detailed = level.elixir >= GET_DEFINITION.elixir

        return row(
            name,
            arity,
            entry.kind.name.lowercase(),
            (entry.line - bodyLine + 1).takeIf { detailed },
            entry.clauses.takeIf { detailed },
            (entry.default && level.elixir >= DEFAULT_CLAUSES_TAKE_CONTEXT.elixir).takeIf { detailed },
        )
    }

    private fun row(name: String, arity: Int, kind: String, line: Int?, clauses: Int?, default: Boolean?) =
        "$name/$arity $kind" + (line?.let { " line $it" } ?: "") + (clauses?.let { " clauses $it" } ?: "") +
            (default?.let { " default $it" } ?: "")

    private companion object {
        /** `Module.get_definition/2`, which gives a definition's meta and clauses. */
        val GET_DEFINITION: ElixirLanguageLevel = ElixirLanguageLevel.of("1.12.0-rc.0")

        /** `elixir-lang/elixir@f1bbb2cd3` puts `:context` in a default arity's meta. */
        val DEFAULT_CLAUSES_TAKE_CONTEXT: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.0-rc.0")

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

        /** Each case, and whether the hook runs at the leg's level. */
        val ERROR_CASES: Map<String, (ElixirLanguageLevel) -> Boolean> = mapOf(
            "def f, do: nope()\ndef g(a \\\\ 1), do: a" to { _ -> true },
            "def f, do: __STACKTRACE__\ndef g(a \\\\ 1), do: a" to { level -> FUNCTION_ERRORS_CONTINUE.isSufficient(level) },
        )
    }
}
