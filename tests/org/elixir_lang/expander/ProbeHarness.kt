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
 * its `__CALLER__` and returns its argument. Each probe is a macro expansion, so it advances its module's counter.
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

    /** What the probe at [tag] saw: `__CALLER__` as a map. */
    data class Observation(val tag: Tag, val env: OtpErlangMap)

    /**
     * [probeModule] and [caseModule] are atom text, as [Env] holds modules.
     *
     * @property values each case's [Case.value] as it was sent, by case
     */
    class Batch(
        private val token: String,
        val observations: List<Observation>,
        val values: Map<Int, OtpErlangObject> = emptyMap(),
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

    /** Compiles each of [cases] as the body of a module of its own, and returns what happened, however it ended. */
    fun attempt(cases: List<Case>): Attempt {
        val token = "ProbeCase" + UUID.randomUUID().toString().replace("-", "")
        val probeModule = probeModule(token)
        val tags = mutableListOf<Tag>()
        val bodyLines = mutableListOf<Int>()
        val source = buildString {
            append(
                """
                defmodule $probeModule do
                  defmacro p(tag) do
                    IntellijElixir.Quoter.Probe.send(__CALLER__, {List.to_tuple(tag), Map.from_struct(__CALLER__)})
                    nil
                  end

                  defmacro i(tag, expr) do
                    IntellijElixir.Quoter.Probe.send(__CALLER__, {List.to_tuple(tag), Map.from_struct(__CALLER__)})
                    expr
                  end
                end

                """.trimIndent()
            )

            cases.forEachIndexed { index, case ->
                append("\ndefmodule ${caseModule(token, index)} do\n")
                append("require $probeModule\n")
                append(probe(probeModule, Tag(index, 0, 0).also(tags::add)))
                append("\n")
                bodyLines.add(count { it == '\n' } + 1)
                append(probed(probeModule, index, case, tags))
                append("\nend\n")
            }
        }
        val compiled = Quoter.compile(source, COMPILE_TIMEOUT)
        val (values, probes) = compiled.messages.partition(::isValue)
        val valuesByCase = values.associate { value ->
            val (_, case, q) = (value as OtpErlangTuple).elements()

            (case as OtpErlangLong).intValue() to q
        }

        return Attempt(compiled, tags, bodyLines, Batch(token, probes.map(::observation), valuesByCase), source)
    }

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

    private fun probe(probeModule: String, tag: Tag): String = "$probeModule.p(${tagList(tag)})"

    private fun tagList(tag: Tag) =
        listOfNotNull(tag.case, tag.block, tag.statement, tag.identity.takeIf { it > 0 }).joinToString(", ", "[", "]")

    private fun isValue(message: OtpErlangObject): Boolean =
        message is OtpErlangTuple && message.arity() == 3 && message.elementAt(0) == OtpErlangAtom("value")

    private fun observation(message: OtpErlangObject): Observation {
        val (tag, env) = (message as OtpErlangTuple).elements()
        val numbers = (tag as OtpErlangTuple).elements().map { (it as OtpErlangLong).intValue() }

        return Observation(Tag(numbers[0], numbers[1], numbers[2], numbers.getOrElse(3) { 0 }), env as OtpErlangMap)
    }

    private companion object {
        val COMPILE_TIMEOUT = 30.seconds

        fun probeModule(token: String) = "$token.Probe"

        fun caseModule(token: String, case: Int) = "$token.Case$case"

        fun List<Tag>.sorted() = sortedWith(compareBy({ it.case }, { it.block }, { it.statement }, { it.identity }))
    }
}

/** The elements of a `{severity, line, column, message}` diagnostic, as the quoter reports one. */
internal class Diagnostic(val severity: String, val line: Int?, val message: String) {
    companion object {
        fun of(term: OtpErlangObject): Diagnostic {
            val (severity, line, _, message) = (term as OtpErlangTuple).elements()

            return Diagnostic(
                (severity as OtpErlangAtom).atomValue(),
                (line as? OtpErlangLong)?.intValue(),
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
