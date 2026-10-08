package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirFile

/**
 * Each struct case, compiled as a module body on the leg's Elixir, ends as the expander says, and each that expands
 * traces its dispatches and struct expansions as the compiler does. A build that omits a field of a struct whose
 * enforced keys the leg's metadata doesn't record is unported at its `%`, and isn't compared, as is a case unported
 * on the leg's release, or whose struct a loaded module of the same name could answer.
 */
class StructProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testOnlyTheCasesExpectedUnportedAreUnported() {
        val cases = cases()

        assertEquals(
            cases.joinToString("\n") { "${it.code}: ${if (isUnported(it)) "unported `${it.at}`" else "ported"}" },
            cases.joinToString("\n") { case ->
                val expansion = probes.expand(case.code)
                val outcome = expansion.outcome as? Expansion.Unported

                "${case.code}: ${outcome?.let { "unported `${expansion.source(it.at)}`" } ?: "ported"}"
            },
        )
    }

    fun testEachCaseMatchesElixir() {
        probes.assertMatchesElixir(
            cases().filterNot(::isUnported).associate { it.code to probes.expand(it.code) }
        )
    }

    fun testEachExpandedCaseTracesAsTheCompilerDoes() {
        probes.assertTracesMatchElixir(probes.expandAll(EXPANDS.filterNot(::isUnported).map { it.code }))
    }

    /**
     * [code] is compared from [from]. When it builds [at] omitting a field of [omits], it is unported on a leg whose
     * metadata doesn't record [omits]'s enforced keys. It is unported at [at] on a leg before [comparedFrom], or on
     * every leg where [unported].
     */
    private class Case(
        val code: String,
        val omits: String? = null,
        val at: String? = null,
        val from: String? = null,
        val comparedFrom: String? = null,
        val unported: Boolean = false,
    )

    private fun cases(): List<Case> {
        val level = legLevel()

        return (EXPANDS + OTHERS).filter { it.from == null || level.elixir >= ElixirLanguageLevel.of(it.from).elixir }
    }

    private fun isUnported(case: Case): Boolean =
        case.unported ||
            case.omits?.let { (legStructs.of(it) as? ModuleStruct.Present)?.enforced } == Enforced.Unknown ||
            case.comparedFrom?.let { legLevel().elixir < ElixirLanguageLevel.of(it).elixir } == true

    private companion object {
        const val URI = "Elixir.URI"
        const val VERSION = "Elixir.Version"

        /** Each expands, unless it is unported. */
        val EXPANDS = listOf(
            Case("%URI{}", URI, "%URI{}"),
            Case("%URI{host: String.trim(\"a\")}", URI, "%URI{host: String.trim(\"a\")}"),
            Case("%Version{major: 1, minor: 0, patch: 0}", VERSION, "%Version{major: 1, minor: 0, patch: 0}"),
            Case("%Version{major: 1, minor: 0, patch: 0, pre: [], build: nil}"),
            Case("alias URI, as: U\n%U{}", URI, "%U{}"),
            Case("%URI{__struct__: Foo}", URI, "%URI{__struct__: Foo}"),
            Case("case 1 do\n  y when %URI{} == y -> y\n  _ -> 0\nend", URI, "%URI{}"),
            Case("%URI{host: &Integer.to_string/1}", URI, "%URI{host: &Integer.to_string/1}"),
            Case("%x{} = %URI{}", URI, "%URI{}"),
            Case("x = URI\n%^x{} = %URI{}", URI, "%URI{}"),
            Case("%_{} = %URI{}", URI, "%URI{}"),
            Case("%URI{host: h} = %URI{host: \"a\"}", URI, "%URI{host: \"a\"}"),
            Case("u = URI.parse(\"\")\n%URI{host: h} = u"),
            Case("u = URI.parse(\"\")\n%URI{u | host: \"b\"}"),
            Case("v = Version.parse!(\"1.0.0\")\n%Version{major: m} = v"),
            Case("def f do\n  %URI{host: \"a\"}\nend", URI, "%URI{host: \"a\"}"),
            Case("def f(u) do\n  %URI{host: h} = u\n  h\nend"),
        )

        /** Each errors or raises, on every leg or on some. */
        val OTHERS = listOf(
            Case("r = 1..2\n%Range{step: s} = r"),
            Case("%NoSuchStruct{}"),
            Case("%:lists{}"),
            Case("u = URI.parse(\"\")\n%NoSuchStruct{} = u"),
            // Before 1.14.1 a loaded module of the same name answers.
            Case("%__MODULE__{}", at = "%__MODULE__{}", comparedFrom = "1.14.1"),
            Case("x = URI\n%x{}"),
            Case("u = URI.parse(\"\")\n%URI{nope: x} = u"),
            Case("u = URI.parse(\"\")\n%URI{u | nope: 1}"),
            Case("%URI{\"host\" => 1}"),
            Case("u = URI.parse(\"\")\n%URI{\"host\" => h} = u"),
            Case("u = URI.parse(\"\")\n%1{} = u", from = "1.17.0"),
            Case("%URI{nope: 1}"),
            Case("%Version{nope: 1}"),
            Case("%Version{}", VERSION, "%Version{}"),
            // Inside a function an unknown or invalid key is reported and expansion carries on from 1.15.
            Case("def f(u) do\n  %URI{nope: x} = u\n  x\nend"),
            Case("def f(u) do\n  %URI{u | nope: 1}\nend"),
            Case("def f do\n  %URI{nope: 1}\nend"),
            Case("def f(u) do\n  %URI{nope: x, nah: y} = u\n  {x, y}\nend"),
            Case("def f(u) do\n  %URI{nope: x} = u\n  h(x)\nend"),
            Case("def f(u) do\n  %URI{\"host\" => x} = u\n  x\nend"),
            Case("def f(u) do\n  %URI{u | \"host\" => 1}\nend"),
            Case("def f do\n  %URI{\"host\" => 1}\nend"),
            Case("def f(u) do\n  %NoSuchStruct{} = u\nend"),
            Case("def f(u) do\n  %NoSuchStruct{u | a: 1}\nend"),
            Case("def f do\n  %NoSuchStruct{}\nend"),
            Case("def f do\n  %:lists{}\nend"),
            Case("def f do\n  %Version{}\nend", VERSION, "%Version{}"),
            // The enclosing module's body is still expanding, so its struct isn't defined yet, and a loaded module of the
            // same name would answer.
            Case("alias __MODULE__, as: Outer\ndefmodule Inner do\n  _ = %Outer{}\nend", at = "%Outer{}", unported = true),
            Case(
                "alias __MODULE__, as: Outer\ndefmodule Inner do\n  _ = %Outer{}\nend\ndefstruct [:a]",
                at = "%Outer{}",
                unported = true,
            ),
        )
    }
}
