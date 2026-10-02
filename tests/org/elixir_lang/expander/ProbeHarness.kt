package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.expressionNodes
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Compiles case module bodies through [Quoter.compile], one batch per call, with a probe macro after each statement
 * that sends its `__CALLER__` back, and an identity probe macro around each of a case's [Case.identities], which sends
 * its `__CALLER__` and returns its argument. Each probe is a macro expansion, so its module's hygiene counter advances
 * once it returns; each also sends the counter as it was before that, as `counter_before`.
 * Probes are inserted on their statement's own line, so lines are kept and columns are not.
 *
 * The harness's modules carry a token unique to the compile: test forks share one quoter node, and two compiles
 * defining the same module at once would make Elixir raise. So a case body may define only modules nested in its case
 * module.
 *
 * @param parse parses source text into a file, whose top-level expressions are the statements
 */
class ProbeHarness(private val parse: (String) -> ElixirFile) {
    /**
     * `{case, block, statement}`: statement 0 is the start of the block, statement `n` follows its `n`th statement.
     * Block 0 is the case body, and block `n` the `n`th of [Case.bodies]. An identity probe has [identity], its
     * 1-based index in [Case.identities], and the top-level statement it is in.
     */
    data class Tag(val case: Int, val block: Int, val statement: Int, val identity: Int = 0) {
        override fun toString() = listOfNotNull(case, block, statement, identity.takeIf { it > 0 }).joinToString(".")
    }

    /**
     * A case body, the ranges of it to wrap in an identity probe, which must not overlap, and the statements of each
     * body nested in it, such as a `->` clause's.
     *
     * @property value the 1-based top-level statement after which the run-time value of the case's variable `q` is
     *   sent, as a module-body statement of its own with no probe after it
     */
    class Case(
        val body: String,
        val identities: List<TextRange> = emptyList(),
        val bodies: List<List<TextRange>> = emptyList(),
        val value: Int? = null,
    )

    /**
     * What the probe at [tag] saw: `__CALLER__` as a map, and its module's hygiene counter before it took one, or
     * `null` outside any module.
     */
    data class Observation(val tag: Tag, val env: OtpErlangMap, val counterBefore: Long?)

    /**
     * [probeModule] and [caseModule] are atom text, as [Env] holds modules.
     *
     * @property values each case's [Case.value] as it was sent, by case
     * @property hooks what the hook saw of each module it ran in, by module
     */
    class Batch(
        private val token: String,
        val observations: List<Observation>,
        val values: Map<Int, OtpErlangObject> = emptyMap(),
        val hooks: Map<String, List<HookEntry>> = emptyMap(),
    ) {
        val probeModule = "Elixir." + probeModule(token)

        fun caseModule(case: Int) = "Elixir." + caseModule(token, case)
    }

    /**
     * A compile that may have failed.
     *
     * @property tags every probe inserted
     * @property bodyLines the source line of each case body's first line
     * @property source the probed source compiled
     */
    class Attempt(
        val compiled: Quoter.Compiled,
        val tags: List<Tag>,
        val bodyLines: List<Int>,
        val batch: Batch,
        val source: String,
        val layout: Layout,
    )

    /**
     * A batch laid out for one compile: [cases], whose bodies already have `{token}` replaced by [token], after the
     * probe module, the hook module if [hook], and [preamble]'s modules, and the top-level probe at the end of the file
     * if [top].
     *
     * The expander reads [plain]: the source without the harness's own text. The probes are left out, and the probe
     * module's and the hook's bodies, the hook's header, the `require` of the probe module and the step-0 probe are
     * blanked to their newlines, so lines are kept. The top-level probe is `:ok` on its line, so the file still holds
     * more than modules.
     *
     * @property bodyStarts where each case body starts in [plain]
     * @property bodyLines the line each case body starts on, in [plain] and in the compiled source alike
     * @property topLine the top-level probe's line, if there is one
     */
    class Layout(
        val token: String,
        val cases: List<Case>,
        val preamble: String = "",
        val hook: Boolean = false,
        val top: Boolean = false,
    ) {
        val probeModule = "Elixir." + probeModule(token)
        val hookModule = "Elixir." + hookModule(token)
        val plain: String
        val bodyStarts: List<Int>
        val bodyLines: List<Int>
        val topLine: Int?

        init {
            val written = write(this, null) { _, case -> case.body }

            plain = written.source
            bodyStarts = written.bodyStarts
            bodyLines = written.bodyLines
            topLine = written.topLine
        }

        fun caseModule(case: Int) = "Elixir." + caseModule(token, case)
    }

    /**
     * A definition as the hook saw it before its module compiled. Before `Module.get_definition/2` (1.12) only the
     * name, arity and kind are known.
     *
     * @property default whether the definition's meta holds `:context`, which from 1.20 marks a default arity's
     */
    data class HookEntry(
        val name: String,
        val arity: Int,
        val kind: String,
        val line: Int?,
        val clauses: Int?,
        val default: Boolean?,
    )

    /** Compiles each of [bodies] as the body of a module of its own. */
    fun compile(bodies: List<String>): Batch = compileCases(bodies.map { Case(it) })

    /** Compiles each of [cases] as the body of a module of its own, which must compile and deliver every probe. */
    fun compileCases(cases: List<Case>): Batch {
        val attempt = attempt(cases)

        assertTrue(
            "compile failed: ${inspect(attempt.compiled.status)} ${attempt.compiled.diagnostics.map(::inspect)}\n" +
                attempt.source,
            attempt.compiled.status == OtpErlangAtom("ok")
        )
        assertEquals("probes that reported", attempt.tags.sorted(), attempt.batch.observations.map { it.tag }.sorted())

        return attempt.batch
    }

    /**
     * Compiles each of [cases] as the body of a module of its own, after [preamble]'s modules, and returns what
     * happened, however it ended.
     */
    fun attempt(cases: List<Case>, preamble: String = ""): Attempt = attempt(Layout(token(), cases, preamble))

    /** Compiles [layout], and returns what happened, however it ended. */
    fun attempt(layout: Layout): Attempt {
        val tags = mutableListOf<Tag>()
        val written = write(layout, tags) { index, case -> probed(probeModule(layout.token), index, case, tags) }
        val compiled = Quoter.compile(written.source, COMPILE_TIMEOUT)
        val values = mutableMapOf<Int, OtpErlangObject>()
        val hooks = mutableMapOf<String, List<HookEntry>>()
        val observations = mutableListOf<Observation>()

        for (message in compiled.messages) {
            val elements = (message as OtpErlangTuple).elements()

            when (elements[0]) {
                OtpErlangAtom("value") -> values[(elements[1] as OtpErlangLong).intValue()] = elements[2]
                OtpErlangAtom("hook") -> hooks[(elements[1] as OtpErlangAtom).atomValue()] = hookEntries(elements[2])
                else -> observations += observation(message)
            }
        }

        return Attempt(
            compiled,
            tags,
            written.bodyLines,
            Batch(layout.token, observations, values, hooks),
            written.source,
            layout,
        )
    }

    /** Compiles [source] as it is, with no probes. */
    fun compileSource(source: String): Quoter.Compiled = Quoter.compile(source, COMPILE_TIMEOUT)

    /** A token unique to one compile, which names its modules. */
    fun token(): String = "ProbeCase" + UUID.randomUUID().toString().replace("-", "")

    /**
     * Compiles a module whose body reports `Code.get_compiler_option([name])` as its compile sees it.
     */
    fun compilerOption(name: String): OtpErlangObject {
        val module = probeModule("ProbeOption" + UUID.randomUUID().toString().replace("-", ""))
        val compiled = Quoter.compile(
            """
            defmodule $module do
              defmacro o do
                IntellijElixir.Quoter.Probe.send(__CALLER__, Code.get_compiler_option(:$name))
                nil
              end
            end

            defmodule $module.Case do
              require $module
              $module.o()
            end
            """.trimIndent(),
            COMPILE_TIMEOUT
        )

        assertEquals("compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.single()
    }

    /**
     * [case]'s body with a probe after each statement, top-level or nested, on the statement's own line, and each
     * identity range wrapped; each probe's tag is added to [tags]. A keyword value such as `do: x` takes parentheses
     * around it and its probe.
     */
    private fun probed(probeModule: String, index: Int, case: Case, tags: MutableList<Tag>): String {
        val ends = statementEnds(parse(case.body))
        val insertions = mutableListOf<Insertion>()

        ends.forEachIndexed { statement, end ->
            val tag = Tag(index, 0, statement + 1)
            tags.add(tag)
            insertions.add(Insertion(end, 1, "; " + probe(probeModule, tag)))

            if (case.value == statement + 1) {
                insertions.add(Insertion(end, 2, "; IntellijElixir.Quoter.Probe.send(__ENV__, {:value, $index, q})"))
            }
        }
        case.bodies.forEachIndexed { block, statements ->
            val start = statements.first().startOffset
            val keywordValue = case.body.substring(0, start).trimEnd().endsWith(":")

            if (keywordValue) insertions.add(Insertion(start, 3, "("))

            statements.forEachIndexed { statement, range ->
                val tag = Tag(index, block + 1, statement + 1)
                val close = if (keywordValue && statement == statements.lastIndex) ")" else ""
                tags.add(tag)
                insertions.add(Insertion(range.endOffset, 1, "; " + probe(probeModule, tag) + close))
            }
        }
        case.identities.forEachIndexed { identity, range ->
            val statement = ends.indexOfFirst { range.endOffset <= it } + 1
            val tag = Tag(index, 0, statement, identity + 1)
            tags.add(tag)
            insertions.add(Insertion(range.startOffset, 4, "$probeModule.i(${tagList(tag)}, "))
            insertions.add(Insertion(range.endOffset, 0, ")"))
        }

        val probedBody = StringBuilder(case.body)

        // Each insertion at an offset lands left of those already there, so at one offset the result reads: the end of
        // a wrapped range, then a statement probe, then a value send, then a keyword value's opening parenthesis, then
        // the start of the next range.
        insertions.sortedWith(compareByDescending<Insertion> { it.offset }.thenByDescending { it.order }).forEach {
            probedBody.insert(it.offset, it.text)
        }

        return probedBody.toString()
    }

    private class Insertion(val offset: Int, val order: Int, val text: String)

    /**
     * Where each statement ends, as the lowering splits a file into statements: a parenthesised block is one, though
     * it quotes as several do.
     */
    private fun statementEnds(file: ElixirFile): List<Int> =
        ReadAction.computeBlocking<List<Int>, Throwable> {
            check(!PsiTreeUtil.hasErrorElements(file)) { "a case body must parse without errors" }

            expressionNodes(file).map { it.textRange.endOffset }
        }

    private fun observation(message: OtpErlangObject): Observation {
        val (tag, env, counterBefore) = (message as OtpErlangTuple).elements()
        val numbers = (tag as OtpErlangTuple).elements().map { (it as OtpErlangLong).intValue() }

        return Observation(
            Tag(numbers[0], numbers[1], numbers[2], numbers.getOrElse(3) { 0 }),
            env as OtpErlangMap,
            (counterBefore as? OtpErlangLong)?.longValue(),
        )
    }

    /** A layout's source, where and on which line each case body starts in it, and the top-level probe's line. */
    private class Written(val source: String, val bodyStarts: List<Int>, val bodyLines: List<Int>, val topLine: Int?)

    private companion object {
        val COMPILE_TIMEOUT = 30.seconds

        val PROBE_BODY = """
            |  defmacro p(tag) do
            |    IntellijElixir.Quoter.Probe.send(__CALLER__, observation(tag, __CALLER__))
            |    nil
            |  end
            |
            |  defmacro i(tag, expr) do
            |    IntellijElixir.Quoter.Probe.send(__CALLER__, observation(tag, __CALLER__))
            |    expr
            |  end
            |
            |  defp observation(tag, caller) do
            |    {data, _} = :elixir_module.data_tables(caller.module)
            |
            |    {List.to_tuple(tag), Map.from_struct(caller), :ets.lookup_element(data, {:elixir, :counter}, 2)}
            |  end
            |""".trimMargin()

        /**
         * Sends each definition of the module it is compiled into, before the module compiles. `on_def/6` only makes
         * the module run its `@on_definition` callbacks.
         */
        val HOOK_BODY = """
            |  defmacro __before_compile__(env) do
            |    defs =
            |      if function_exported?(Module, :get_definition, 2) do
            |        for {name, arity} <- Module.definitions_in(env.module) do
            |          {:v1, kind, meta, clauses} = apply(Module, :get_definition, [env.module, {name, arity}])
            |          {name, arity, kind, meta[:line], length(clauses), Keyword.has_key?(meta, :context)}
            |        end
            |      else
            |        for kind <- [:def, :defp, :defmacro, :defmacrop],
            |            {name, arity} <- Module.definitions_in(env.module, kind),
            |            do: {name, arity, kind}
            |      end
            |
            |    IntellijElixir.Quoter.Probe.send(env, {:hook, env.module, defs})
            |    nil
            |  end
            |
            |  def on_def(_env, _kind, _name, _args, _guards, _body), do: nil
            |""".trimMargin()

        const val TOP_PROBE =
            "IntellijElixir.Quoter.Probe.send(__ENV__, {List.to_tuple([-1, 0, 0]), Map.from_struct(__ENV__), nil})"

        /**
         * [layout]'s source, each case body written by [body]: the probed source when [tags] collects the probes'
         * tags, and [Layout.plain] when it is `null`.
         */
        fun write(layout: Layout, tags: MutableList<Tag>?, body: (Int, Case) -> String): Written {
            val probeModule = probeModule(layout.token)
            val hookModule = hookModule(layout.token)
            val bodyStarts = mutableListOf<Int>()
            val bodyLines = mutableListOf<Int>()
            var topLine: Int? = null

            fun StringBuilder.harness(text: String) {
                append(if (tags == null) text.filter { it == '\n' } else text)
            }

            val source = buildString {
                append("defmodule $probeModule do\n")
                harness(PROBE_BODY)
                append("end\n")

                if (layout.hook) {
                    append("\ndefmodule $hookModule do\n")
                    harness(HOOK_BODY)
                    append("end\n")
                }

                append(layout.preamble)

                layout.cases.forEachIndexed { index, case ->
                    append("\ndefmodule ${caseModule(layout.token, index)} do\n")
                    harness("require $probeModule\n")

                    if (layout.hook) harness("@before_compile $hookModule\n@on_definition {$hookModule, :on_def}\n")

                    harness(probe(probeModule, Tag(index, 0, 0).also { tags?.add(it) }) + "\n")
                    bodyStarts.add(length)
                    bodyLines.add(count { it == '\n' } + 1)
                    append(body(index, case))
                    append("\nend\n")
                }

                if (layout.top) {
                    append("\n")
                    topLine = count { it == '\n' } + 1

                    if (tags == null) {
                        append(":ok")
                    } else {
                        append(TOP_PROBE)
                        tags.add(Tag(-1, 0, 0))
                    }

                    append("\n")
                }
            }

            return Written(source, bodyStarts, bodyLines, topLine)
        }

        fun probe(probeModule: String, tag: Tag): String = "$probeModule.p(${tagList(tag)})"

        fun tagList(tag: Tag) =
            listOfNotNull(tag.case, tag.block, tag.statement, tag.identity.takeIf { it > 0 })
                .joinToString(", ", "[", "]")

        fun probeModule(token: String) = "$token.Probe"

        fun hookModule(token: String) = "$token.Hook"

        fun caseModule(token: String, case: Int) = "$token.Case$case"

        /** The definitions a hook sent: 6-tuples from 1.12, and before it `{name, arity, kind}`. */
        fun hookEntries(term: OtpErlangObject): List<HookEntry> =
            (term as OtpErlangList).elements().map { entry ->
                val elements = (entry as OtpErlangTuple).elements()
                val name = (elements[0] as OtpErlangAtom).atomValue()
                val arity = (elements[1] as OtpErlangLong).intValue()
                val kind = (elements[2] as OtpErlangAtom).atomValue()

                if (elements.size == 3) {
                    HookEntry(name, arity, kind, null, null, null)
                } else {
                    HookEntry(
                        name,
                        arity,
                        kind,
                        (elements[3] as? OtpErlangLong)?.intValue(),
                        (elements[4] as OtpErlangLong).intValue(),
                        (elements[5] as OtpErlangAtom).booleanValue(),
                    )
                }
            }

        fun List<Tag>.sorted() = sortedWith(compareBy({ it.case }, { it.block }, { it.statement }, { it.identity }))
    }
}

/** The elements of a `{severity, line, column, message}` diagnostic, as the quoter reports one. */
internal class Diagnostic(val severity: String, val line: Int?, val column: Int?, val message: String) {
    companion object {
        fun of(term: OtpErlangObject): Diagnostic {
            val (severity, line, column, message) = (term as OtpErlangTuple).elements()

            return Diagnostic(
                (severity as OtpErlangAtom).atomValue(),
                (line as? OtpErlangLong)?.intValue(),
                (column as? OtpErlangLong)?.intValue(),
                utf8(message)
            )
        }
    }
}

internal fun utf8(term: OtpErlangObject): String =
    when (term) {
        is com.ericsson.otp.erlang.OtpErlangBinary -> String(term.binaryValue(), Charsets.UTF_8)
        is com.ericsson.otp.erlang.OtpErlangString -> term.stringValue()
        is OtpErlangList -> term.stringValue()
        else -> throw AssertionError("not text: ${inspect(term)}")
    }
