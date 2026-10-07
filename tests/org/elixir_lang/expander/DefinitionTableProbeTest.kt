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
    ) {
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
            assertLeftNothingBehind(expansions.layout)

            val bodyLine = expansions.layout.bodyLines[0]

            attempt.batch.hooks[module]?.let { hooked ->
                val (theirs, ours) = compared(hooked, expansion.result.table, bodyLine)

                expected += render(case, theirs)
                actual += render(case, ours)
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
        assertEquals("compiler options", options, compilerOptions())
    }

    /**
     * The hook's rows, and the expander's [table] read against them by the may-be-present rule: Elixir's table holds
     * every ordered entry, an unordered entry may be absent and compares its kind only, and every Elixir entry is one
     * of the expander's. An unnamed entry pairs with an Elixir entry of its kind that no named entry claims; one that
     * is a statement, so unordered only for its name, must find one.
     */
    private fun compared(
        hooked: List<ProbeHarness.HookEntry>,
        table: DefinitionTable,
        bodyLine: Int,
    ): Pair<List<String>, List<String>> {
        val elixir = hooked.associateBy { it.name to it.arity }
        val actual = mutableListOf<String>()
        val claimed = mutableSetOf<Pair<String, Int>>()

        for ((nameArity, entry) in table.entries) {
            val key = nameArity.name to nameArity.arity
            val theirs = elixir[key]

            when {
                entry.ordered || theirs != null && theirs.kind != entry.kind.name.lowercase() ->
                    actual += tableRow(nameArity.name, nameArity.arity, entry, bodyLine)
                theirs != null -> actual += hookRow(theirs, bodyLine)
            }

            if (theirs != null) claimed += key
        }

        val unclaimed = hooked.filterTo(mutableListOf()) { (it.name to it.arity) !in claimed }

        for ((unnamedKind, _, statement) in table.unnamed) {
            val kind = unnamedKind.name.lowercase()
            val pair = unclaimed.firstOrNull { it.kind == kind }

            when {
                pair != null -> {
                    unclaimed -= pair
                    actual += hookRow(pair, bodyLine)
                }
                statement -> actual += "<unnamed> $kind"
            }
        }

        return hooked.map { hookRow(it, bodyLine) } to actual
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
            // The hook reads whether the meta holds `:context`, which a definition a macro quoted has on every leg.
            (entry.default && level.elixir >= DEFAULT_CLAUSES_TAKE_CONTEXT.elixir ||
                metaValue(entry.at.meta, "context") != null).takeIf { detailed },
        )
    }

    private fun row(name: String, arity: Int, kind: String, line: Int?, clauses: Int?, default: Boolean?) =
        "$name/$arity $kind" + (line?.let { " line $it" } ?: "") + (clauses?.let { " clauses $it" } ?: "") +
            (default?.let { " default $it" } ?: "")

    /** A bodied `defguard` stops the module at `Kernel.Utils.defguard/2`, whose output isn't followed, and nowhere else. */
    private val stopsAtTheGuardTemplate = { result: ExpansionResult ->
        result.opaque.isNotEmpty() &&
            result.opaque.all { it.dispatch.receiver == "Elixir.Kernel.Utils" && it.dispatch.name == "defguard" }
    }

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
