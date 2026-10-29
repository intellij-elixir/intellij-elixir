package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import java.util.IdentityHashMap

/**
 * A call of a guard `defguard` defined in source, against Elixir's, where the call is in the module that defines the
 * guard and where it is in another that imports or requires it. A call reads the macro its definition stored and never
 * expands the guard again, so each case checks:
 *
 * - the output, against `Macro.expand_once/2` of the call where it is, up to meta and counters, and then the
 *   counters of the variables the template gave it;
 * - the probes, with the hygiene counters taken;
 * - the definition table, against `@before_compile`, on an expansion of its own with the hook;
 * - silence: the case module's traces against the compiler's events, and no error or warning in the caller.
 */
class DefguardCallTest : SdkProbeTestCase(accounting = true) {
    private val tables = ExpansionProbes(harness, definitions = true) { text ->
        ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, text) as ElixirFile
        }
    }

    fun testTheOutputIsElixirs() = assertEveryCase { case ->
        val expansions = expanded(case)
        val elixir = elixirOutputs(case)

        elixir.render() to case.marks.indices.joinToString("\n") { index ->
            "$index ${expansions.plugin(index)?.let { detokened(inspect(stripped(it.toOtp())), expansions.token) } ?: "no output"}"
        }
    }

    fun testTheOutputsVariablesTakeElixirsCounters() = assertEveryCase { case ->
        val expansions = expanded(case)
        val elixir = elixirOutputs(case)

        elixir.counters() to case.marks.indices.joinToString("\n") { index ->
            "$index ${expansions.plugin(index)?.let { templateCounters(it.toOtp(), expansions.token) } ?: "no output"}"
        }
    }

    fun testTheProbesMatchElixir() = assertEveryCase { case ->
        val comparison = probes.compare(mapOf(case.name to expanded(case).expansion)).first.getValue(case.name)

        comparison.actual to comparison.expected
    }

    fun testTheDefinitionTableMatchesTheHook() = assertEveryCase { case ->
        val expansions = tables.expandAll(listOf(case.plainBody), case.defs, hook = ProbeHarness.Hook(), clauses = sdk)
        val attempt = harness.attempt(expansions.layout)
        val rows = DefinitionTables.compare(
            expansions.layout.caseModule(0),
            attempt.batch.hooks,
            expansions.cases.single().result,
            expansions.layout.bodyLines[0],
            attempt.compiled,
        ) ?: throw AssertionError("the hook didn't run in ${case.name}")

        DefinitionTables.render(case.name, rows.elixir) to DefinitionTables.render(case.name, rows.expander)
    }

    fun testTheCallIsSilent() = assertEveryCase { case ->
        val expansions = expanded(case)
        val expansion = expansions.expansion
        val outcome = try {
            probes.assertTracesMatchElixir(expansions.all)

            "traces match"
        } catch (failure: AssertionError) {
            failure.message.orEmpty()
        }

        "traces match, errors [], warnings []" to
            "$outcome, errors ${expansion.result.errors}, warnings ${expansion.result.warnings}"
    }

    /** Each case's check, which gives Elixir's side then the plugin's; the cases that differ are all named. */
    private fun assertEveryCase(check: (Case) -> Pair<String, String>) {
        val failures = CASES.mapNotNull { case ->
            val (elixir, plugin) = try {
                check(case)
            } catch (failure: Throwable) {
                "no failure" to "${failure::class.simpleName}: ${failure.message}"
            }

            if (elixir == plugin) null else "${case.name}\n${elixir.prependIndent("  elixir> ")}\n${plugin.prependIndent("  plugin> ")}"
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** [case]'s module expanded, and the output the expander gave each marked call. */
    private class Expanded(val all: ExpansionProbes.Expansions, val outputs: Map<ElixirAst, ElixirAst>, val case: Case) {
        val expansion: ExpansionProbes.CaseExpansion get() = all.cases.single()

        /** The compile's token, which the expander's output names the case module by. */
        val token: String get() = all.layout.token

        /** What the call at [mark] expanded to: the first node entered after its dispatch. */
        fun plugin(mark: Int): ElixirAst? {
            val before = case.plainBody.substring(0, case.marks[mark])
            val at = case.marks[mark] + before.split(PLACEHOLDER).size.dec() * (token.length - PLACEHOLDER.length)
            val bodyStart = all.layout.bodyStarts.single()

            return outputs.entries.firstOrNull { (call, _) -> call.meta.origin.startOffset - bodyStart == at }?.value
        }
    }

    private fun expanded(case: Case): Expanded {
        val observer = CallOutputs()
        val expansions = probes.expandAll(listOf(case.plainBody), case.defs, clauses = sdk, tap = observer)

        assertEquals("${case.name} ended", ExpansionResult.Ended.Compiled, expansions.cases.single().ended)

        return Expanded(expansions, observer.outputs, case)
    }

    /**
     * The first node entered after each macro call's dispatch, which is the output [expandQuoted] passes on to be
     * expanded where the call is.
     */
    private class CallOutputs : ExpansionObserver {
        val outputs = IdentityHashMap<ElixirAst, ElixirAst>()
        private val awaiting = mutableListOf<ElixirAst>()

        override fun entering(node: ElixirAst, state: ExState, env: Env) {
            awaiting.forEach { outputs[it] = node }
            awaiting.clear()
        }

        override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
            if (dispatch.kind in MACROS) awaiting += node
        }
    }

    /** Elixir's `Macro.expand_once/2` of each marked call where it is, in the case module, by mark. */
    private class ElixirOutputs(val token: String, val byMark: Map<Int, OtpErlangObject>, val failure: String?) {
        fun render(): String =
            failure ?: byMark.toSortedMap().entries.joinToString("\n") { (index, term) ->
                "$index ${detokened(inspect(stripped(term)), token)}"
            }

        fun counters(): String =
            failure ?: byMark.toSortedMap().entries.joinToString("\n") { (index, term) ->
                "$index ${templateCounters(term, token)}"
            }
    }

    private fun elixirOutputs(case: Case): ElixirOutputs {
        val token = harness.token()
        val source = """
            |defmodule $token.Expansion do
            |  defmacro out(id, ast) do
            |    expanded = Macro.expand_once(ast, __CALLER__)
            |    IntellijElixir.Quoter.Probe.send(__CALLER__, {:out, id, expanded})
            |    ast
            |  end
            |end
            |
            |${case.defs.replace("{token}", token)}
            |
            |defmodule $token.Case0 do
            |  require $token.Expansion
            |${case.wrappedBody("$token.Expansion").replace("{token}", token)}
            |end
            |""".trimMargin()
        val compiled = harness.compileSource(source)
        val byMark = compiled.messages.mapNotNull { message ->
            val elements = (message as? OtpErlangTuple)?.elements() ?: return@mapNotNull null

            if (elements.size == 3 && elements[0] == OtpErlangAtom("out")) {
                (elements[1] as OtpErlangLong).intValue() to elements[2]
            } else {
                null
            }
        }.toMap()
        val failure = if (byMark.size == case.marks.size) {
            null
        } else {
            "elixir sent ${byMark.keys}: ${compiled.status} ${compiled.diagnostics.joinToString(" | ") { inspect(it) }}"
        }

        return ElixirOutputs(token, byMark, failure)
    }

    /**
     * A case: [defs], the modules before the case module, and [body], the case module's, whose calls of the guard are
     * marked `«call»`.
     */
    private class Case(val name: String, val defs: String, val body: String) {
        val plainBody: String = body.replace("«", "").replace("»", "")

        /** Where each marked call starts in [plainBody]. */
        val marks: List<Int> = buildList {
            var removed = 0

            body.forEachIndexed { index, char ->
                when (char) {
                    '«' -> {
                        add(index - removed)
                        removed++
                    }
                    '»' -> removed++
                }
            }
        }

        /** [body] with each marked call as the argument of [out]'s `out/2`, numbered by mark. */
        fun wrappedBody(out: String): String {
            var mark = 0

            return Regex("«([^»]*)»").replace(body) { "$out.out(${mark++}, ${it.groupValues[1]})" }
        }
    }

    private companion object {
        /** Stands for the compile's token in a case, which the harness replaces. */
        const val PLACEHOLDER = "{token}"

        val MACROS = setOf(Dispatch.Kind.LOCAL_MACRO, Dispatch.Kind.IMPORTED_MACRO, Dispatch.Kind.REMOTE_MACRO)

        /** Keys that name a position, and the counter the output's variables are checked for apart. */
        val UP_TO_COUNTERS = ExpandedAstTest.GENERATED - "generated" + "counter"

        fun stripped(term: OtpErlangObject) = ExpandedAstTest.stripped(term, UP_TO_COUNTERS)

        fun detokened(text: String, token: String) = text.replace(token, "{token}")

        /** Each variable in [term] that carries a counter, by name and context, in order. */
        fun templateCounters(term: OtpErlangObject, token: String): String {
            val found = mutableListOf<String>()

            fun walk(term: OtpErlangObject) {
                when (term) {
                    is OtpErlangTuple -> {
                        val elements = term.elements()

                        if (elements.size == 3 && elements[0] is OtpErlangAtom && elements[2] is OtpErlangAtom &&
                            elements[1] is OtpErlangList
                        ) {
                            val counter = (elements[1] as OtpErlangList).elements()
                                .filterIsInstance<OtpErlangTuple>()
                                .firstOrNull { it.elementAt(0) == OtpErlangAtom("counter") }
                                ?.elementAt(1)

                            if (counter != null) {
                                found += "${(elements[0] as OtpErlangAtom).atomValue()}/" +
                                    "${(elements[2] as OtpErlangAtom).atomValue()} ${inspect(counter)}"
                            }
                        }
                        elements.forEach(::walk)
                    }
                    is OtpErlangList -> term.elements().forEach(::walk)
                }
            }

            walk(term)

            return detokened(found.joinToString(", ").ifEmpty { "none" }, token)
        }

        const val GUARD = "defguard is_even(x) when rem(x, 2) == 0"

        val DEF = """
            |defmodule {token}.Def do
            |  $GUARD
            |  defguard is_even_pos(x) when is_even(x) and x > 0
            |  defguard is_uri(x) when is_struct(x, URI)
            |  defguard is_none(x) when is_nil(x)
            |end
            """.trimMargin()

        fun own(name: String, body: String) = Case("own-$name", "", "$GUARD\n$body")

        fun other(name: String, body: String) = Case(name, DEF, body)

        val CASES = listOf(
            own("def-guard", "def f(y) when «is_even(y)», do: y"),
            own("case-guard", "def g(y) do\n  case y do\n    z when «is_even(z)» -> z\n    _ -> 0\n  end\nend"),
            own("fn-guard", "def h, do: fn z when «is_even(z)» -> z end"),
            own("for-guard", "def k(l), do: for(z when «is_even(z)» <- l, do: z)"),
            own("plain", "def p(y), do: «is_even(y)»"),
            own("capture", "def c, do: &«is_even(&1)»"),
            own(
                "nested-guard",
                "defguard is_even_pos(x) when is_even(x) and x > 0\ndef f(y) when «is_even_pos(y)», do: y",
            ),
            other("import-def-guard", "import {token}.Def, only: [is_even: 1]\ndef f(y) when «is_even(y)», do: y"),
            other("import-plain", "import {token}.Def, only: [is_even: 1]\ndef p(y), do: «is_even(y)»"),
            other("import-capture", "import {token}.Def, only: [is_even: 1]\ndef c, do: &«is_even(&1)»"),
            other("require-def-guard", "require {token}.Def\ndef f(y) when «{token}.Def.is_even(y)», do: y"),
            other("require-plain", "require {token}.Def\ndef p(y), do: «{token}.Def.is_even(y)»"),
            other("require-capture", "require {token}.Def\ndef c, do: &«{token}.Def.is_even(&1)»"),
            other("nested-guard-def-guard", "require {token}.Def\ndef f(y) when «{token}.Def.is_even_pos(y)», do: y"),
            other("nested-guard-plain", "require {token}.Def\ndef p(y), do: «{token}.Def.is_even_pos(y)»"),
            other("is_struct-def-guard", "require {token}.Def\ndef f(y) when «{token}.Def.is_uri(y)», do: y"),
            other("is_struct-plain", "require {token}.Def\ndef p(y), do: «{token}.Def.is_uri(y)»"),
            other("is_nil-def-guard", "require {token}.Def\ndef f(y) when «{token}.Def.is_none(y)», do: y"),
            other("is_nil-plain", "require {token}.Def\ndef p(y), do: «{token}.Def.is_none(y)»"),
        )
    }
}
