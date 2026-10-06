package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.expander.ProbeHarness.Tag
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.REMOTE_CAPTURE_REPORTED_AT_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDEFINED_VARIABLE_RAISES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.expressionNodes
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Expands case module bodies as Elixir compiles them, and compares the expander with Elixir on the leg's Elixir
 * through a [ProbeHarness]. A batch of cases is one file, the harness's modules and a preamble's, then a module for each
 * case, which [Expander.expandFile] expands whole; each node it reaches belongs to the case whose body holds it.
 *
 * - a case module that compiles is compiled in a batch, and if the batch fails, alone, where it must compile, or fail
 *   only after expansion: raise when run, or raise with no error diagnostic once every probe has delivered;
 * - a case module the expander reports an error in is compiled alone, and must report the same errors at the same
 *   lines, and end as the expander says, failing at expansion where the expander's error raised;
 *
 * and at each probe either delivers, the variables fall into the same classes and the env's fields are equal. A case
 * that stops at a node the expander doesn't cover, or at a macro whose expansion isn't modelled, is compared up to
 * there only, by [assertMatchesElixirUpToMacro].
 *
 * Each variable and `_` of a pattern, each `^` and each non-literal bitstring size in one, and each variable of a
 * clause's or a definition's guard outside a `Kernel` macro's arguments, is wrapped in an identity probe, which the
 * expander matches when it enters that node. Each statement of a body nested in a clause, a definition or a module, is
 * followed by a probe, which the expander matches when it leaves that statement. Elixir may expand a definition that
 * isn't a statement of its module's body, and every definition after it, elsewhere than the expander does, so the
 * comparison stops at the first probe in such a body.
 *
 * With [accounting], each probe also compares the hygiene counters taken: Elixir's `counter_before`, less the probes
 * delivered before it in its module, each of which took one, against the expander's count.
 */
internal class ExpansionProbes(
    private val harness: ProbeHarness,
    private val accounting: Boolean = false,
    private val parse: (String) -> ElixirFile,
) {
    /**
     * What the expander saw at the probe [tag], whose `case` is always 0.
     *
     * @property offset where the node at [tag] starts in its case body, or -1 at the body's start
     * @property count the hygiene counters its module had given, or `null` outside any module
     */
    class Step(
        val tag: Tag,
        val read: Map<Variable, Int>,
        val env: Env,
        val stacktrace: Boolean,
        val caller: Boolean,
        val offset: Int,
        val count: Long?,
    )

    /**
     * [case]'s module compiled as the expander compiles it.
     *
     * @property steps the statement probes, and the identity probes the expander entered, in its order, up to where
     *   the comparison stops
     * @property cut whether the comparison stops before the expander's last probe
     * @property outcome the error the module raised, or the node it stopped at, or else its body's expansion
     * @property statements the top-level statements the expander reached, as the file lowers them
     * @property starts the state and env each of [statements] was expanded from
     * @property traces each dispatch, quoted import and struct expansion the expander reported, as [DispatchEvents]
     *   keys it, in order, up to where it stopped
     * @property macro the key of the macro dispatch [outcome] stopped at, if it did
     * @property tagRanges where the statement each statement probe follows is in the case body
     * @property definitions where each `def*` call is in the case body
     * @property unordered whether the comparison stops at a body Elixir may expand elsewhere
     */
    class CaseExpansion(
        val case: ProbeHarness.Case,
        val steps: List<Step>,
        val cut: Boolean,
        val outcome: Expansion,
        val result: ExpansionResult,
        val statements: List<ElixirAst>,
        val starts: List<Pair<ExState, Env>>,
        val traces: List<String>,
        val macro: String?,
        val tagRanges: Map<Tag, TextRange>,
        val definitions: List<TextRange>,
        val unordered: Boolean,
        val origin: Origin,
    ) {
        val ended: Ended get() = result.ended

        /** Where [node] is in the case body. */
        fun range(node: ElixirAst): TextRange = node.meta.origin.shiftLeft(origin.layout.bodyStarts[origin.index])

        /** [node]'s source in the case body. */
        fun source(node: ElixirAst): String = range(node).substring(case.body)
    }

    /**
     * What a case was expanded from: [body] and [preamble] before their `{token}` was replaced, and where, as
     * [layout]'s case [index].
     */
    class Origin(
        val body: String,
        val preamble: String,
        val exports: Exports,
        val structs: Structs,
        val hook: ProbeHarness.Hook?,
        val standIns: List<StandIn>,
        val layout: ProbeHarness.Layout,
        val index: Int,
    )

    /**
     * The output of a macro of the preamble's, which the expander doesn't run: each call whose source is [source] is
     * expanded as [output] gives it, after the `remote_macro` dispatch of [receiver]'s [name]/[arity] and the counter
     * Elixir takes for it. `{token}` in [source] and [receiver] stands for the compile's token; [output] is given the
     * call and the receiver with it replaced.
     */
    class StandIn(
        val source: String,
        val receiver: String,
        val name: String,
        val arity: Int,
        val output: (call: ElixirAst.Call, receiver: String) -> ElixirAst,
    )

    /**
     * A batch expanded as one file laid out by [layout], what the top-level probe stands for when it has one, the
     * traces outside any module, keyed as [DispatchEvents.top] keys them, and every module the file compiled, nested
     * ones among them.
     */
    class Expansions(
        val layout: ProbeHarness.Layout,
        val cases: List<CaseExpansion>,
        val top: Step?,
        val topTraces: List<String>,
        val modules: List<ExpansionResult>,
    )

    /**
     * [body] as the body of a case module of its own, after [preamble]'s modules, with [exports] and [structs] standing
     * for the modules Elixir loads and [standIns] for the preamble's macros.
     */
    fun expand(
        body: String,
        preamble: String = "",
        exports: Exports = legExports,
        structs: Structs = legStructs,
        standIns: List<StandIn> = emptyList(),
    ): CaseExpansion = expandAll(listOf(body), preamble, exports, structs, standIns = standIns).cases.single()

    /**
     * Each of [bodies] as the body of a case module, in one file after [preamble]'s modules, with `{token}` in either
     * standing for the compile's token, [exports] and [structs] for the modules Elixir loads and [standIns] for the
     * preamble's macros. [hook] adds that hook to each case module, [top] the top-level probe to the end of the
     * file, and [values] sends each case's `q` after its last statement.
     */
    fun expandAll(
        bodies: List<String>,
        preamble: String = "",
        exports: Exports = legExports,
        structs: Structs = legStructs,
        hook: ProbeHarness.Hook? = null,
        top: Boolean = false,
        values: Boolean = false,
        standIns: List<StandIn> = emptyList(),
    ): Expansions {
        val level = legLevel()
        val token = harness.token()
        val shapes = bodies.map { shape(it.replace(TOKEN, token), level) }
        val layout = ProbeHarness.Layout(
            token,
            shapes.map { shape ->
                ProbeHarness.Case(
                    shape.body,
                    shape.sites.map { it.meta.origin },
                    shape.bodies.map { body -> body.map { it.meta.origin } },
                    if (values) shape.statements.size else null,
                )
            },
            preamble.replace(TOKEN, token),
            hook,
            top,
        )
        // Elixir keeps the counter in the module, across its body and its definitions.
        val counters = Counters()
        val recorders = shapes.indices.map {
            Recorder(shapes[it], layout.bodyStarts[it], layout.bodyLines[it], counters)
        }
        val standing = IdentityHashMap<ElixirAst, Dispatch>()
        val observer = Observer(recorders, counters, level, standing)
        val file = parse(layout.plain)
        val lowered = ReadAction.computeBlocking<ElixirAst, Throwable> { Lowering.lower(file, level) }
        val forms = if (standIns.isEmpty()) lowered else stoodIn(lowered, layout.plain, token, standIns, standing)
        val expansion =
            Expander.expandFile(forms, Env.empty(level, legKernel), level, exports, structs, observer, counters)
        val cases = recorders.mapIndexed { index, recorder ->
            val module = layout.caseModule(index)
            val result = expansion.modules.firstOrNull { it.module == module }
                ?: throw AssertionError(
                    "$module wasn't compiled: top ${expansion.top}, " +
                        expansion.modules.joinToString { "${it.module} ${it.ended}" }
                )

            recorder.expansion(
                result,
                observer.leftAt,
                Origin(bodies[index], preamble, exports, structs, hook, standIns, layout, index),
            )
        }
        val topStep = (expansion.top as? Expansion.Expanded)
            ?.takeIf { top }
            ?.let { Step(Tag(-1, 0, 0), it.state.read, it.env, it.state.stacktrace, it.state.caller, -1, null) }

        return Expansions(layout, cases, topStep, observer.top, expansion.modules)
    }

    /**
     * Compares each of [cases] whose module compiles or reports an error with Elixir, and returns how long the
     * compiles of the erroring ones took.
     */
    fun assertMatchesElixir(cases: Map<String, CaseExpansion>): Duration {
        assertDefaultCompilerOptions()

        val compiled = cases.filterValues { it.ended == Ended.Compiled }
        val erroring = cases.filterValues { it.ended.raises }
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        compareCompiled(compiled, expected, actual)

        val start = TimeSource.Monotonic.markNow()
        erroring.forEach { (name, expansion) -> compareError(name, expansion, expected, actual) }
        val elapsed = start.elapsedNow()

        println("${compiled.size} compiled and ${erroring.size} erroring cases; erroring compiles took $elapsed")

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))

        return elapsed
    }

    /**
     * Compares [expansions], whose cases must compile, with Elixir compiling its layout: each case's probes, the
     * top-level probe's, and the traces outside any module.
     */
    fun assertMatchesElixir(names: List<String>, expansions: Expansions) {
        assertDefaultCompilerOptions()

        val attempt = harness.attempt(expansions.layout)
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        assertEquals(
            "compile status ${attempt.compiled.diagnostics.map(::inspect)}",
            OtpErlangAtom("ok"),
            attempt.compiled.status,
        )
        compareBatch(names, expansions, attempt, expected, actual)
        expected.add("== outside any module\n" + expansions.topTraces.joinToString("\n"))
        actual.add(
            "== outside any module\n" +
                DispatchEvents.top(attempt.compiled.events, expansions.layout.topLine).joinToString("\n")
        )
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /**
     * Compares each of [cases], which stop at [Expansion.Opaque], with Elixir up to the macro, compiled together, or
     * alone if that fails: the probes Elixir delivers before the macro's statement in the body it is in, and those the
     * expander reached, equal the expander's steps, and the traces equal the leg's events up to and including the
     * first macro event on the macro's line. What follows the macro isn't compared.
     */
    fun assertMatchesElixirUpToMacro(cases: Map<String, CaseExpansion>) {
        assertDefaultCompilerOptions()

        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for ((names, batch) in batches(cases)) {
            val together = harness.attempt(batch.layout)
            // A case that fails to compile stops the modules after it, so then each case is compiled alone.
            val attempts = if (together.compiled.status == OtpErlangAtom("ok")) {
                names.indices.map { Triple(batch.cases[it], together, it) }
            } else {
                names.map { alone(cases.getValue(it)).let { (expansion, attempt) -> Triple(expansion, attempt, 0) } }
            }

            names.forEachIndexed { position, name ->
                val (expansion, attempt, index) = attempts[position]
                val tags = expansion.steps.map { it.tag }.toSet()
                val before = beforeMacro(expansion)
                val observed = attempt.batch.observations.filter {
                    it.tag.case == index && it.tag.copy(case = 0).let { tag -> tag in tags || before(tag) }
                }
                val macro = expansion.macro ?: "not opaque: ${expansion.outcome}"
                val events = events(attempt, index)
                val line = DispatchEvents.line(macro)
                val end = events.indexOf(macro).takeIf { it >= 0 }
                    ?: events.indexOfFirst { DispatchEvents.isMacro(it) && DispatchEvents.line(it) == line }
                val prefix = if (end < 0) events + "no macro event on line $line" else events.take(end + 1)

                expected.add(render(name, expansion.steps) + "\n" + (expansion.traces + macro).joinToString("\n"))
                actual.add(
                    render(name, observed, attempt.batch.probeModule, delivered(attempt, index)) + "\n" +
                        prefix.joinToString("\n")
                )
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /**
     * Compares the traces of [expansions], whose cases all expand, with the events the leg's compiler traces for the
     * same case module bodies, as [DispatchEvents] normalises them.
     */
    fun assertTracesMatchElixir(expansions: Expansions) {
        expansions.cases.forEach { assertTrue("${it.case.body}: ${it.outcome}", it.outcome is Expansion.Expanded) }

        val attempt = harness.attempt(expansions.layout)
        val raised = (attempt.compiled.status as? OtpErlangTuple)?.elementAt(2)?.let(::utf8)

        assertEquals(
            "compile status $raised ${attempt.compiled.diagnostics.map(::inspect)}\n${attempt.source}",
            OtpErlangAtom("ok"),
            attempt.compiled.status,
        )
        assertEquals(
            expansions.cases.joinToString("\n") { renderTraces(it.case.body, it.traces) },
            expansions.cases.withIndex().joinToString("\n") { (index, expansion) ->
                renderTraces(expansion.case.body, events(attempt, index))
            },
        )
    }

    private fun renderTraces(body: String, traces: List<String>) =
        "== ${body.replace("\n", "; ")}\n" + traces.joinToString("") { "  $it\n" }

    /**
     * Whether Elixir delivers a tag's probe before [expansion]'s macro: it is in the body the macro is in, in a
     * statement before the macro's, and not in a definition or module nested there, whose body Elixir expands later.
     */
    private fun beforeMacro(expansion: CaseExpansion): (Tag) -> Boolean {
        val opaque = expansion.outcome as? Expansion.Opaque ?: return { false }
        val at = expansion.range(opaque.at)
        // The case module's own body is the first unit.
        val nested = units(expansion.result).drop(1).map { expansion.range(it.node) }
        val body = nested.filter { it.contains(at) }.minByOrNull { it.length }
        val inner = nested.filter { it != body && (body == null || body.contains(it)) }

        fun inBody(range: TextRange) = (body == null || body.contains(range)) && inner.none { it.contains(range) }

        val statements = expansion.tagRanges.values.filter(::inBody)
        val statement = statements.filter { it.contains(at) }.maxByOrNull { it.length } ?: return { false }
        val earlier = earlierBodies(expansion, at)

        return { tag ->
            val range = tagRange(expansion, tag)

            range != null && (range.endOffset <= statement.startOffset && inBody(range) || earlier(range))
        }
    }

    /** The dispatch events of [attempt]'s case [index], as [DispatchEvents] normalises them. */
    fun events(attempt: ProbeHarness.Attempt, index: Int): List<String> =
        DispatchEvents.of(
            attempt.compiled.events,
            attempt.batch.caseModule(index),
            attempt.batch.probeModule,
            attempt.bodyLines[index],
            attempt.layout.hookModule,
            attempt.batch.definitionAttributes,
        )

    private fun compareCompiled(
        cases: Map<String, CaseExpansion>,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        for ((names, batch) in batches(cases)) {
            val attempt = harness.attempt(batch.layout)

            if (attempt.compiled.status == OtpErlangAtom("ok") && batch.cases.all { it.ended == Ended.Compiled }) {
                compareBatch(names, batch, attempt, expected, actual)
            } else {
                names.forEach { name -> compareAlone(name, cases.getValue(name), expected, actual) }
            }
        }
    }

    private fun compareBatch(
        names: List<String>,
        expansions: Expansions,
        attempt: ProbeHarness.Attempt,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        val byCase = attempt.batch.observations.groupBy { it.tag.case }
        val cut = expansions.cases.indices.filter { expansions.cases[it].cut }.toSet()
        val missing = attempt.tags.filter { it.case !in cut }.toSet() - byCase.values.flatten().map { it.tag }.toSet()

        expected.add("probes not delivered: []")
        actual.add("probes not delivered: $missing")

        names.forEachIndexed { index, name ->
            val expansion = expansions.cases[index]

            expected.add(render(name, expansion.steps))
            val observed = delivered(expansion, byCase[index].orEmpty())

            actual.add(render(name, observed, attempt.batch.probeModule, byCase[index].orEmpty()))
        }

        if (expansions.layout.top) {
            expected.add(render("top", listOfNotNull(expansions.top)))
            actual.add(render("top", byCase[-1].orEmpty(), attempt.batch.probeModule))
        }
    }

    private fun compareAlone(
        name: String,
        expansion: CaseExpansion,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        val (alone, attempt) = alone(expansion)
        val compiled = attempt.compiled
        val observations = attempt.batch.observations
        val status = compiled.status
        // A raise once every probe has delivered comes after expansion: when the body runs, or, for a
        // `CompileError` with no error diagnostic, from a later pass.
        val afterExpansion = status is OtpErlangTuple &&
            status.elementAt(0) == OtpErlangAtom("raise") &&
            errors(compiled.diagnostics).isEmpty() &&
            attempt.tags.toSet() == observations.map { it.tag }.toSet() &&
            !isPostModuleError(status)

        expected.add(render(name, alone.steps))
        actual.add(
            if (status == OtpErlangAtom("ok") || afterExpansion) {
                render(name, delivered(alone, observations), attempt.batch.probeModule, observations)
            } else {
                "== $name\ncompile failed: ${inspect(compiled.status)} ${compiled.diagnostics.map(::inspect)}"
            }
        )
    }

    /** Whether [status] is the `CompileError` of a check Elixir makes once the body has run, which the expander predicts. */
    private fun isPostModuleError(status: OtpErlangTuple): Boolean {
        if (status.elementAt(1) != OtpErlangAtom(COMPILE_ERROR)) return false

        val message = PREFIXED_MESSAGE.find(utf8(status.elementAt(2)))?.groupValues?.get(2) ?: return false

        return POST_MODULE_KINDS.any { ErrorKinds.pattern(it).containsMatchIn(message) }
    }

    /**
     * Compares the errors of [expansion]'s module with Elixir's for the case compiled alone: up to 1.14, the error
     * that raised; from 1.15, every error in order, then how the compile ended. Where the comparison stops at a body
     * Elixir may expand elsewhere, only the errors before it are compared, as a prefix of Elixir's.
     */
    private fun compareError(
        name: String,
        expansion: CaseExpansion,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        val (alone, attempt) = alone(expansion)
        val compiled = attempt.compiled
        val observations = attempt.batch.observations
        val level = legLevel()
        val witness = witness(alone)
        val errors = expectedErrors(alone, level)

        expected.add(render(name, alone.steps) + "\n" + errors.joinToString("\n") { it.render() })
        actual.add(
            render(name, delivered(alone, observations), attempt.batch.probeModule, observations) + "\n" +
                if (witness != null && observations.any { it.tag == witness }) {
                    "not an expansion error: ${inspect(compiled.status)} ${compiled.diagnostics.map(::inspect)}"
                } else if (FUNCTION_ERRORS_CONTINUE.isSufficient(level)) {
                    elixirErrors(errors, compiled, alone.unordered)
                } else {
                    elixirError(errors.single(), compiled)
                }
        )
    }

    /** An error the expander expects Elixir to report, or how it expects the compile to end. */
    private class ExpectedError(val kind: String, val line: Int?, val status: String? = null) {
        fun render(): String = status ?: "error $kind${line?.let { " at line $it" } ?: ""}"
    }

    /**
     * The errors [expansion]'s module reports, in Elixir's order, at [level]: up to 1.14 the one that raises; from
     * 1.15 those Elixir logs as diagnostics, then the raise that ends the compile. Where the comparison stops at a body
     * Elixir may expand elsewhere, only those before it, and no raise.
     */
    private fun expectedErrors(
        expansion: CaseExpansion,
        level: ElixirLanguageLevel,
    ): List<ExpectedError> {
        val result = expansion.result

        if (!FUNCTION_ERRORS_CONTINUE.isSufficient(level)) {
            val error = timeline(result).firstOrNull() ?: return listOf(ExpectedError("none", null, "no error"))
            val raised = (result.ended as? Ended.Raised)?.error ?: (result.ended as? Ended.Crashed)?.error
            val units = units(result)
            val unit = units.indexOfFirst { it.expansion === raised }

            // Elixir may store a body that isn't ordered elsewhere, so which error raises first isn't known.
            if (unit >= 0 && units.take(unit + 1).any { !it.ordered }) {
                return listOf(ExpectedError("", null, "raise CompileError"))
            }

            return listOf(ExpectedError(error.kind, line(error.at).takeIf { ErrorKinds.hasLine(error.kind, level) }))
        }

        val logged = collapsed(timeline(result, expansion.takeIf { it.unordered }), level) { error ->
            error.kind.takeIf { it in LOCAL_CHECK_KINDS }?.let { Triple(it, line(error.at), column(error.at)) }
        }
            .filter { ErrorKinds.hasLine(it.kind, level) }
            .map { ExpectedError(it.kind, line(it.at)) }

        if (expansion.unordered) return logged

        val status = when (val ended = result.ended) {
            is Ended.Raised ->
                if (ended.error.kind in FILE_ERROR_KINDS) {
                    FILE_NOT_COMPILED
                } else if (ended.error.kind == NESTED_RAISED || ErrorKinds.hasLine(ended.error.kind, level)) {
                    "raise CompileError"
                } else {
                    "raise ${ended.error.kind}"
                }
            is Ended.Crashed -> "raise ${ended.exception}"
            Ended.Tainted -> "raise CompileError"
            Ended.Compiled, is Ended.Stopped -> "ended ${result.ended}"
        }

        return logged + ExpectedError((result.ended as? Ended.Raised)?.error?.kind ?: "", null, status)
    }

    /**
     * [result]'s errors in the order Elixir reports them: the module body's, then each definition's and each nested
     * module's in the order the body defines them, then the checks once the body has run, then the error that raised,
     * when it raised in this module. With [unordered], only those before the first body Elixir may expand elsewhere.
     */
    private fun timeline(result: ExpansionResult, unordered: CaseExpansion? = null): List<Reported> {
        val definitions = result.units.filter { it.owner is Owner.Definition }

        fun unit(error: Reported) = definitions.firstOrNull { it.node.meta.origin.contains(error.at.meta.origin) }

        val after = result.errors.filter {
            it.kind in POST_MODULE_KINDS && (it.kind !in LOCAL_CHECK_KINDS || unit(it) != null)
        }
        val during = result.errors.filterNot { error -> after.any { it === error } }
        // A nested module whose name raised has no body, and its error is at its `defmodule`.
        val placed = definitions.map { unit -> start(unit.node) to during.filter { unit(it) === unit } } +
            result.nested.map { nested ->
                val at = nested.units.firstOrNull()?.node ?: (nested.ended as? Ended.Raised)?.error?.at

                (at?.let(::start) ?: Int.MAX_VALUE) to timeline(nested)
            }
        val stop = unordered?.let { definitions.firstOrNull { !it.ordered } }?.node?.let(::start)
        val raised = (result.ended as? Ended.Raised)?.error?.takeIf { error ->
            error.kind != NESTED_RAISED && result.nested.none { (it.ended as? Ended.Raised)?.error === error }
        }
        val ordered = during.filter { unit(it) == null } +
            placed.sortedBy { it.first }.filter { stop == null || it.first < stop }.flatMap { it.second }

        return if (stop != null) ordered else ordered + after + listOfNotNull(raised?.let { Reported(it.kind, it.at) })
    }

    /**
     * [errors] with only the first of those [key] gives one key, from [REPEATED_LOCAL_ERRORS_COLLAPSED]: how many times
     * Elixir reports a default's call there depends on the types it infers, which the expander doesn't.
     */
    private fun <T> collapsed(errors: List<T>, level: ElixirLanguageLevel, key: (T) -> Any?): List<T> {
        if (level.elixir < REPEATED_LOCAL_ERRORS_COLLAPSED.elixir) return errors

        val seen = mutableSetOf<Any>()

        return errors.filter { error -> key(error)?.let(seen::add) ?: true }
    }

    /**
     * Elixir's logged errors, each as `error <kind> at line <n>` where its message is the [expected] error's kind at
     * its place, then how the compile ended, unless [prefix], where only as many errors as [expected] are compared.
     */
    private fun elixirErrors(
        expected: List<ExpectedError>,
        compiled: org.elixir_lang.intellij_elixir.Quoter.Compiled,
        prefix: Boolean,
    ): String {
        val logged = collapsed(errors(compiled.diagnostics), legLevel()) { diagnostic ->
            LOCAL_CHECK_KINDS.firstOrNull { ErrorKinds.pattern(it).containsMatchIn(diagnostic.message) }
                ?.let { Triple(it, diagnostic.line, diagnostic.column) }
        }
            .let { if (prefix) it.take(expected.size) else it }
        val errors = logged.mapIndexed { index, diagnostic ->
            val kind = expected.getOrNull(index)?.takeIf { it.status == null }?.kind

            if (kind != null && ErrorKinds.pattern(kind).containsMatchIn(diagnostic.message)) {
                "error $kind at line ${diagnostic.line}"
            } else {
                "error at line ${diagnostic.line}: ${diagnostic.message}"
            }
        }

        return (if (prefix) errors else errors + status(expected.last(), compiled.status)).joinToString("\n")
    }

    /** How a compile ended, as [expected] renders it when they agree. */
    private fun status(expected: ExpectedError, status: OtpErlangObject): String {
        val raised = status as? OtpErlangTuple

        if (raised == null || raised.elementAt(0) != OtpErlangAtom("raise")) return "ended ${inspect(status)}"

        val exception = (raised.elementAt(1) as OtpErlangAtom).atomValue()
        val message = utf8(raised.elementAt(2))

        return when {
            exception == COMPILE_ERROR && expected.status == FILE_NOT_COMPILED && message.contains(FILE_NOT_COMPILED_MESSAGE) ->
                FILE_NOT_COMPILED
            exception == COMPILE_ERROR -> "raise CompileError"
            "raise $exception" == expected.status -> expected.status
            expected.kind.isNotEmpty() && ErrorKinds.pattern(expected.kind).containsMatchIn(message) ->
                "raise ${expected.kind}"
            else -> "raise $exception: $message"
        }
    }

    /**
     * The error [compiled] failed with up to 1.14, as `error <kind> at line <n>` when its message is the [expected]
     * kind's, or as `error <kind>` for a kind with no line.
     */
    private fun elixirError(expected: ExpectedError, compiled: org.elixir_lang.intellij_elixir.Quoter.Compiled): String {
        val status = compiled.status as? OtpErlangTuple

        if (status == null || status.elementAt(0) != OtpErlangAtom("raise")) {
            return "not an expansion error: ${inspect(compiled.status)} ${compiled.diagnostics.map(::inspect)}"
        }

        val message = utf8(status.elementAt(2))

        if (expected.status == "raise CompileError" && status.elementAt(1) == OtpErlangAtom(COMPILE_ERROR)) {
            return "raise CompileError"
        }

        if (status.elementAt(1) != OtpErlangAtom(COMPILE_ERROR)) {
            return if (expected.line == null && ErrorKinds.pattern(expected.kind).containsMatchIn(message)) {
                "error ${expected.kind}"
            } else {
                "error ${inspect(status.elementAt(1))}: $message"
            }
        }

        val match = PREFIXED_MESSAGE.find(message) ?: return "no <file>:<line>: prefix: ${inspect(status.elementAt(2))}"
        val line = match.groupValues[1].toInt()

        return if (ErrorKinds.pattern(expected.kind).containsMatchIn(match.groupValues[2])) {
            "error ${expected.kind} at line $line"
        } else {
            "error at line $line: ${match.groupValues[2]}"
        }
    }

    /**
     * The probe that delivers only if [expansion]'s raise came after expansion: the one after the innermost statement
     * holding the error, of the body the error raised in. `null` for a raise from the checks once the body has run, and
     * for an error no probed statement holds.
     */
    private fun witness(expansion: CaseExpansion): Tag? {
        val error = when (val ended = expansion.ended) {
            is Ended.Raised -> ended.error
            is Ended.Crashed -> ended.error
            else -> return null
        }
        val unit = units(expansion.result).firstOrNull { it.expansion === error } ?: return null
        val at = expansion.range(error.at)
        val body = if (unit.owner is Owner.ModuleBody) null else expansion.range(unit.node)

        return expansion.tagRanges.entries
            .filter { (_, range) -> range.contains(at) && (body == null || body.contains(range) && range != body) }
            .minByOrNull { (_, range) -> range.length }
            ?.key
    }

    /**
     * [observations] of [expansion]'s probes. Where its comparison stops early, only those its steps have, and those
     * Elixir delivers before the body it stops in.
     */
    private fun delivered(
        expansion: CaseExpansion,
        observations: List<ProbeHarness.Observation>,
    ): List<ProbeHarness.Observation> {
        if (!expansion.cut) return observations

        val tags = expansion.steps.map { it.tag }.toSet()
        val earlier: (TextRange) -> Boolean = stopRange(expansion)?.let { earlierBodies(expansion, it) } ?: { false }

        return observations.filter { observation ->
            val tag = observation.tag.copy(case = 0)

            tag in tags || tagRange(expansion, tag)?.let(earlier) == true
        }
    }

    /** Where [expansion]'s comparison stops: the first body Elixir may expand elsewhere, or the node it stopped at. */
    private fun stopRange(expansion: CaseExpansion): TextRange? =
        if (expansion.unordered) {
            units(expansion.result).filterNot { it.ordered }.map { expansion.range(it.node) }.minByOrNull { it.startOffset }
        } else {
            at(expansion.outcome)?.let(expansion::range)
        }

    /**
     * Whether Elixir delivers the probe at a range before it reaches [at] in a definition or nested module, whatever
     * the expander recorded: one in a module body that holds [at], outside its definitions, which Elixir expands whole
     * before them, or one in an ordered body wholly before [at]. None for [at] in the case module's own body.
     */
    private fun earlierBodies(expansion: CaseExpansion, at: TextRange): (TextRange) -> Boolean {
        val units = units(expansion.result)
        val ranges = units.map { expansion.range(it.node) }

        fun innermost(range: TextRange): Int? =
            units.indices.filter { ranges[it].contains(range) }.minByOrNull { ranges[it].length }

        if ((innermost(at) ?: 0) == 0) return { false }

        return { range ->
            innermost(range)?.let { index ->
                units[index].ordered && (
                    ranges[index].endOffset <= at.startOffset ||
                        units[index].owner is Owner.ModuleBody && ranges[index].contains(at) &&
                        // A definition the expander didn't reach, because a unit before it raised.
                        expansion.definitions.none { it.contains(range) && it.startOffset >= at.endOffset }
                    )
            } == true
        }
    }

    /** Where in [expansion]'s case body the probe with [tag] is. */
    private fun tagRange(expansion: CaseExpansion, tag: Tag): TextRange? =
        if (tag.identity > 0) expansion.case.identities[tag.identity - 1] else expansion.tagRanges[tag]

    /**
     * [cases] expanded again together, a batch for each preamble, exports, structs and hook they were expanded with.
     */
    private fun batches(cases: Map<String, CaseExpansion>): List<Pair<List<String>, Expansions>> =
        cases.keys
            .groupBy {
                cases.getValue(it).origin.let { listOf(it.preamble, it.exports, it.structs, it.hook, it.standIns) }
            }
            .values
            .map { names ->
                val origin = cases.getValue(names.first()).origin
                val bodies = names.map { cases.getValue(it).origin.body }

                names to expandAll(
                    bodies,
                    origin.preamble,
                    origin.exports,
                    origin.structs,
                    origin.hook,
                    standIns = origin.standIns,
                )
            }

    /** [expansion]'s case expanded and compiled in a file of its own. */
    private fun alone(expansion: CaseExpansion): Pair<CaseExpansion, ProbeHarness.Attempt> {
        val origin = expansion.origin
        val alone = if (origin.layout.cases.size == 1) {
            expansion
        } else {
            expandAll(
                listOf(origin.body),
                origin.preamble,
                origin.exports,
                origin.structs,
                origin.hook,
                standIns = origin.standIns,
            ).cases.single()
        }

        return alone to harness.attempt(alone.origin.layout)
    }

    /**
     * [node], lowered from [file], with each call one of [standIns] is for replaced by its output, which [standing]
     * maps to the call's dispatch.
     */
    private fun stoodIn(
        node: ElixirAst,
        file: String,
        token: String,
        standIns: List<StandIn>,
        standing: IdentityHashMap<ElixirAst, Dispatch>,
    ): ElixirAst {
        val bySource = standIns.associateBy { it.source.replace(TOKEN, token) }

        fun replaced(node: ElixirAst): ElixirAst =
            when (node) {
                is ElixirAst.Call -> {
                    val standIn = bySource[node.meta.origin.substring(file)]

                    if (standIn == null) {
                        ElixirAst.Call(node.meta, replaced(node.callee), node.arguments?.map(::replaced), node.context)
                    } else {
                        val receiver = standIn.receiver.replace(TOKEN, token)

                        standIn.output(node, receiver).also {
                            standing[it] = Dispatch(Dispatch.Kind.REMOTE_MACRO, receiver, standIn.name, standIn.arity)
                        }
                    }
                }
                is ElixirAst.Alias -> ElixirAst.Alias(node.meta, node.segments.map(::replaced))
                is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map(::replaced))
                is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map(::replaced))
                is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map(::replaced))
                is ElixirAst.Literal, is ElixirAst.Placeholder -> node
            }

        return replaced(node)
    }

    /**
     * One line per probe: its tag, the variables' classes, numbered across [steps], and with [accounting] the counters
     * taken; then its env's fields.
     */
    private fun render(name: String, steps: List<Step>): String =
        render(
            name,
            steps.map { it.tag },
            VariableClasses.canonical(steps.map { it.read }),
            steps.map { it.count },
            ProbedEnvNormaliser.numberedCounters(
                steps.map { ProbedEnvNormaliser.projected(it.env, it.stacktrace, it.caller, legLevel()) }
            ).map(ProbedEnvNormaliser::render)
        )

    /**
     * As the other [render], for what Elixir's probes saw, where the counters taken at each are its `counter_before`
     * less the probes [delivered] before it in its module.
     */
    private fun render(
        name: String,
        observations: List<ProbeHarness.Observation>,
        probeModule: String,
        delivered: List<ProbeHarness.Observation> = observations,
    ): String =
        render(
            name,
            observations.map { if (it.tag.case < 0) it.tag else it.tag.copy(case = 0) },
            VariableClasses.canonical(observations.map { VariableClasses.observed(it.env) }),
            if (accounting) observations.map { counted(it, delivered) } else emptyList(),
            ProbedEnvNormaliser.numberedCounters(observations.map { ProbedEnvNormaliser.observed(it.env, probeModule) })
                .map(ProbedEnvNormaliser::render)
        )

    private fun render(name: String, tags: List<Tag>, classes: List<String>, counts: List<Long?>, envs: List<String>) =
        "== $name\n" +
            tags.indices.joinToString("\n") {
                "$it ${tags[it]}: ${classes[it]}" + if (accounting) " counters ${counts[it]}" else ""
            } + "\n" +
            tags.indices.joinToString("\n") { "${tags[it]}\n${envs[it].prependIndent("  ")}" }

    /** [observation]'s `counter_before`, less the probes [delivered] before it in its module, each of which took one. */
    private fun counted(observation: ProbeHarness.Observation, delivered: List<ProbeHarness.Observation>): Long? =
        observation.counterBefore?.let { before ->
            val module = observation.env.get(OtpErlangAtom("module"))

            before - delivered.takeWhile { it !== observation }.count { it.env.get(OtpErlangAtom("module")) == module }
        }

    /** The observations of [attempt]'s case [index], in the order they were delivered. */
    private fun delivered(attempt: ProbeHarness.Attempt, index: Int): List<ProbeHarness.Observation> =
        attempt.batch.observations.filter { it.tag.case == index }

    private fun errors(diagnostics: List<OtpErlangObject>) =
        diagnostics.map(Diagnostic::of).filter { it.severity == "error" }

    private fun assertDefaultCompilerOptions() {
        if (!UNDEFINED_VARIABLE_RAISES.isSufficient(legLevel()) || optionsAsserted) return

        // The expander assumes the default, which makes an undefined variable an error.
        assertEquals(OtpErlangAtom("raise"), harness.compilerOption("on_undefined_variable"))
        optionsAsserted = true
    }

    /** [body] lowered alone: its statements, its identity sites and the bodies nested in it, where they are in it. */
    private fun shape(body: String, level: ElixirLanguageLevel): Shape {
        val file = parse(body)
        val (statements, ends) = ReadAction.computeBlocking<Pair<List<ElixirAst>, List<Int>>, Throwable> {
            val lowering = Lowering.of(file, level)
            val nodes = expressionNodes(file)

            nodes.map { lowering.lower(it.psi) } to nodes.map { it.textRange.endOffset }
        }

        check(statements.none(::hasPlaceholder)) { "a case body must lower without placeholders: $body" }
        // The module body runs when it compiles, and a `receive` waits for its timeout, or for ever without one.
        check(statements.none(::hasReceiveWithoutAfterZero)) { "a case's receive must wait after 0: $body" }

        return Shape(
            body,
            statements,
            ends,
            statements.flatMap { identitySites(it, false) }.sortedBy { it.meta.origin.startOffset },
            statements.flatMap(::nestedBodies).sortedBy { it.first().meta.origin.startOffset },
        )
    }

    /**
     * A case body as it lowers alone, each range in it: the tag of the probe after each of its [statements], which end
     * at [ends], of the identity probe around each of its [sites], and of the probe after each statement of its nested
     * [bodies].
     */
    private class Shape(
        val body: String,
        val statements: List<ElixirAst>,
        ends: List<Int>,
        val sites: List<ElixirAst>,
        val bodies: List<List<ElixirAst>>,
    ) {
        val statementTags = statements.withIndex().associate { (index, node) -> node.meta.origin to Tag(0, 0, index + 1) }
        val identityTags = sites.withIndex().associate { (index, site) ->
            site.meta.origin to Tag(0, 0, ends.indexOfFirst { site.meta.origin.endOffset <= it } + 1, index + 1)
        }
        val nestedTags = bodies.withIndex()
            .flatMap { (block, body) ->
                body.withIndex().map { (statement, node) -> node.meta.origin to Tag(0, block + 1, statement + 1) }
            }
            .toMap()
        val tagRanges = (statementTags + nestedTags).entries.associate { (range, tag) -> tag to range }

        /** Each `def*` call's range, inside which only an unquote fragment is expanded outside the definition. */
        val definitions = statements.flatMap { definitions(it) }.map { it.meta.origin }
    }

    /** What the expander did in one case body, which starts at [start] in the file, on [bodyLine]. */
    private class Recorder(val shape: Shape, val start: Int, val bodyLine: Int, val counters: Counters) {
        val range = TextRange(start, start + shape.body.length)
        val steps = mutableListOf<Step>()

        /** When each of [steps] was taken, counted across the file. */
        val serials = mutableListOf<Int>()
        val statements = mutableListOf<ElixirAst>()
        val starts = mutableListOf<Pair<ExState, Env>>()
        val traces = mutableListOf<String>()

        /** Where each of [traces] was made: its node's start in the file, and the function it was made in. */
        val sites = mutableListOf<Pair<Int, NameArity?>>()

        /** The node first entered at each probed statement, which, being outermost, is the one the probe follows. */
        val outermost = mutableMapOf<TextRange, ElixirAst>()
        val entered = mutableSetOf<TextRange>()
        var stop: Expansion? = null
        var stopSteps = 0
        var stopTraces = 0
        var macro: String? = null

        fun add(step: Step, serial: Int) {
            steps.add(step)
            serials.add(serial)
        }

        fun expansion(result: ExpansionResult, leftAt: Map<ElixirAst, Int>, origin: Origin): CaseExpansion {
            // An empty body has no node to enter, so its start is where it ends.
            if (steps.isEmpty()) {
                (result.units.firstOrNull()?.expansion as? Expansion.Expanded)?.let {
                    val count = counters.count(it.env.module)

                    add(Step(Tag(0, 0, 0), it.state.read, it.env, it.state.stacktrace, it.state.caller, -1, count), -1)
                }
            }

            val bodyEnd = result.units.firstOrNull()?.let { leftAt[it.node] }?.let { end -> serials.count { it < end } }
                ?: steps.size
            val unorderedRanges = units(result).filterNot { it.ordered }.map { it.node.meta.origin.shiftLeft(start) }
            val stopped = stop != null && result.ended.let {
                it is Ended.Stopped || it is Ended.Raised || it is Ended.Crashed
            }
            val stopAt = at(stop)?.meta?.origin?.shiftLeft(start)
            // A body that isn't ordered can stop before any step is taken in it.
            val unordered = (bodyEnd until steps.size).firstOrNull { index ->
                unorderedRanges.any { it.contains(steps[index].offset) }
            } ?: stopSteps.takeIf { stopped && stopAt != null && unorderedRanges.any { it.contains(stopAt) } }
            val cut = listOfNotNull(stopSteps.takeIf { stopped }, unordered).minOrNull()
            val outcome = when (val ended = result.ended) {
                is Ended.Raised -> ended.error
                is Ended.Crashed -> ended.error
                is Ended.Stopped -> stop ?: Expansion.Unported(ended.at)
                Ended.Compiled, Ended.Tainted -> result.units.first().expansion
            }

            val (signed, signedStop) = withSignatureReads(result)

            return CaseExpansion(
                origin.layout.cases[origin.index],
                steps.take(cut ?: steps.size),
                // A stop in a body that isn't ordered cuts what Elixir delivers after it, even at the last step.
                cut != null && (cut < steps.size || unordered == cut),
                outcome,
                result,
                statements,
                starts,
                if (stopped) signed.take(signedStop) else signed,
                macro.takeIf { stopped },
                shape.tagRanges,
                shape.definitions,
                unordered != null && unordered == cut,
                origin,
            )
        }

        /**
         * [traces] with the `@` reads `Module.compile_definition_attributes/6` makes again once each public clause
         * is stored, and where [stopTraces] falls among them. Its `build_signature` expands each `@` in a default's
         * value and each argument that is an `@`, once more, in the clause's function; they follow the
         * clause's own last dispatch. A clause that didn't expand doesn't reach the callback.
         */
        private fun withSignatureReads(result: ExpansionResult): Pair<List<String>, Int> {
            val after = mutableMapOf<Int, MutableList<String>>()

            for (unit in units(result)) {
                val owner = unit.owner as? Owner.Definition ?: continue
                val name = owner.name ?: continue
                val start = start(unit.node)

                if (!owner.kind.public || unit.expansion !is Expansion.Expanded || !range.contains(start)) continue

                val function = NameArity(name, owner.arity)
                val end = unit.node.meta.origin.endOffset
                val last = sites.indices.lastOrNull { sites[it].first in start until end && sites[it].second == function }
                    ?: continue

                after.getOrPut(last) { mutableListOf() } += signatureReads(unit.node).map { at ->
                    DispatchEvents.key(at, Dispatch(Dispatch.Kind.IMPORTED_MACRO, KERNEL, "@", 1), function, bodyLine)
                }
            }

            val signed = mutableListOf<String>()
            var signedStop = 0

            for ((index, trace) in traces.withIndex()) {
                signed += trace
                signed += after[index].orEmpty()

                if (index < stopTraces) signedStop = signed.size
            }

            return signed to signedStop
        }
    }

    /**
     * Attributes each node the expander reaches to the case whose body holds it, and reports the dispatch [standing]
     * maps a stand-in's output to as the expander enters it.
     */
    private class Observer(
        private val recorders: List<Recorder>,
        private val counters: Counters,
        private val level: ElixirLanguageLevel,
        private val standing: IdentityHashMap<ElixirAst, Dispatch>,
    ) : ExpansionObserver {
        /** When each node was left, counted as [Recorder.serials] counts. */
        val leftAt = IdentityHashMap<ElixirAst, Int>()

        /** The traces outside any module, keyed by file line. */
        val top = mutableListOf<String>()
        private var serial = 0
        private var env: Env? = null

        /** The nodes entered and not yet left, innermost last. */
        private val open = ArrayDeque<ElixirAst>()

        private fun step(tag: Tag, state: ExState, env: Env, offset: Int) =
            Step(tag, state.read, env, state.stacktrace, state.caller, offset, counters.count(env.module))

        private fun recorder(node: ElixirAst): Recorder? =
            recorders.firstOrNull { it.range.contains(node.meta.origin.startOffset) }

        override fun entering(node: ElixirAst, state: ExState, env: Env) {
            open.addLast(node)
            this.env = env

            val recorder = recorder(node) ?: return
            val at = node.meta.origin.shiftLeft(recorder.start)
            val shape = recorder.shape

            if (recorder.steps.isEmpty()) {
                recorder.add(step(Tag(0, 0, 0), state, env, -1), serial++)
            }

            standing.remove(node)?.let {
                dispatched(node, it)
                counters.next(env.module)
            }

            // The first node entered within a probed range is the outermost one expanded there: the statement itself,
            // or the `for` of a discarded `_ = for`, which is all `expand_block` expands of it. An unquote fragment in
            // a definition is expanded where the definition is, before its body.
            for (range in shape.tagRanges.values) {
                val fragment = env.function == null && shape.definitions.any { it.contains(range) && it != range }

                if (range.contains(at) && !fragment && !recorder.outermost.containsKey(range)) {
                    recorder.outermost[range] = node

                    if (range in shape.statementTags) {
                        recorder.starts.add(state to env)
                        recorder.statements.add(node)
                    }
                }
            }

            shape.identityTags[at]?.let { tag ->
                if (recorder.entered.add(at)) {
                    recorder.add(step(tag, state, env, at.startOffset), serial++)
                }
            }
        }

        override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
            val recorder = recorder(node)
            val nodes = listOfNotNull(node, retraced(open.lastOrNull(), node, dispatch, level))

            if (recorder != null) {
                nodes.forEach {
                    recorder.traces.add(DispatchEvents.key(it, dispatch, env?.function, recorder.bodyLine))
                    recorder.sites.add(start(it) to env?.function)
                }
            } else if (env?.module == null) {
                nodes.forEach { top.add(DispatchEvents.key(it, dispatch, null, 1)) }
            }
        }

        override fun quotedImport(
            node: ElixirAst,
            kind: QuotedImportKind,
            module: String,
            name: String,
            arities: List<Int>,
        ) {
            val recorder = recorder(node)

            if (recorder != null) {
                recorder.traces.add(
                    DispatchEvents.key(node, kind, module, name, arities, env?.function, recorder.bodyLine)
                )
                recorder.sites.add(start(node) to env?.function)
            } else if (env?.module == null) {
                top.add(DispatchEvents.key(node, kind, module, name, arities, null, 1))
            }
        }

        override fun structExpanded(node: ElixirAst, module: String, keys: List<String>) {
            val recorder = recorder(node)

            if (recorder != null) {
                recorder.traces.add(DispatchEvents.structKey(node, module, keys, env?.function, recorder.bodyLine))
                recorder.sites.add(start(node) to env?.function)
            } else if (env?.module == null) {
                top.add(DispatchEvents.structKey(node, module, keys, null, 1))
            }
        }

        override fun left(node: ElixirAst, expansion: Expansion) {
            open.removeLast()
            leftAt[node] = serial

            val recorder = recorder(node) ?: return

            if (expansion !is Expansion.Expanded) {
                if (recorder.stop == null) {
                    recorder.stop = expansion
                    recorder.stopSteps = recorder.steps.size
                    recorder.stopTraces = recorder.traces.size
                    recorder.macro = (expansion as? Expansion.Opaque)?.let {
                        DispatchEvents.key(it.at, it.dispatch, env?.function, recorder.bodyLine)
                    }
                }

                return
            }

            val shape = recorder.shape
            val state = expansion.state

            for (at in recorder.outermost.filterValues { it === node }.keys.sortedBy { it.length }) {
                val tag = shape.statementTags[at] ?: shape.nestedTags.getValue(at)

                recorder.add(step(tag, state, expansion.env, at.startOffset), serial++)
            }
        }
    }

    private companion object {
        const val COMPILE_ERROR = "Elixir.CompileError"

        /** In a case body or preamble, the compile's token. */
        const val TOKEN = "{token}"

        /** The errors that, from 1.15, fail the file's compile, not the module's: `elixir_def` gives them the file. */
        val FILE_ERROR_KINDS = setOf("changed_kind", "duplicate_defaults")

        const val FILE_NOT_COMPILED_MESSAGE = "cannot compile file (errors have been logged)"

        const val FILE_NOT_COMPILED = "raise CompileError: $FILE_NOT_COMPILED_MESSAGE"

        /** What a module raises once a module nested in it has logged errors. */
        const val NESTED_RAISED = "compile_error"

        /** The errors of the checks of a module's local calls once its body has run, which its body can also report. */
        val LOCAL_CHECK_KINDS = setOf("undefined_function", "incorrect_dispatch")

        /** The errors of the checks a module makes once its body has run. */
        val POST_MODULE_KINDS = LOCAL_CHECK_KINDS +
            setOf("function_head", "import_conflict", "undefined_attribute_function", "wrong_kind_attribute_function")

        /**
         * `elixir-lang/elixir@41353c6cf` checks a default's calls once for each type inferred for it, so how many times
         * a call is reported once the body has run isn't the expander's to give.
         */
        val REPEATED_LOCAL_ERRORS_COLLAPSED: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.0-rc.5")

        /**
         * `elixir-lang/elixir@73d776256`: before it, `expand_fn_capture` traced a remote capture again after `inline/3`.
         */
        val REMOTE_CAPTURE_TRACED_ONCE_SINCE: ElixirLanguageLevel = ElixirLanguageLevel.of("1.17.0-rc.1")

        /**
         * The node the compiler's second trace of a remote capture is at, if [dispatch] of [node] has one: the `&`,
         * [open], before 1.14.0-rc.1, and the call from it.
         */
        fun retraced(open: ElixirAst?, node: ElixirAst, dispatch: Dispatch, level: ElixirLanguageLevel): ElixirAst? =
            open?.takeIf {
                dispatch.kind == Dispatch.Kind.REMOTE_FUNCTION &&
                    isCall(it, "&", 1) &&
                    level.elixir < REMOTE_CAPTURE_TRACED_ONCE_SINCE.elixir
            }?.let { amp -> if (REMOTE_CAPTURE_REPORTED_AT_CALL.isSufficient(level)) node else amp }

        val PREFIXED_MESSAGE = Regex("""^[^:\n]*:(\d+): (.*)""", RegexOption.DOT_MATCHES_ALL)

        @Volatile
        var optionsAsserted = false

        /** [result]'s units, then each nested module's. */
        fun units(result: ExpansionResult): List<ExpansionResult.Unit> =
            result.units + result.nested.flatMap(::units)

        fun start(node: ElixirAst): Int = node.meta.origin.startOffset

        fun line(node: ElixirAst): Int? =
            node.meta.keys.filterIsInstance<Meta.Key.Location>().firstOrNull()?.position?.line

        fun column(node: ElixirAst): Int? =
            node.meta.keys.filterIsInstance<Meta.Key.Location>().firstOrNull()?.position?.column

        /** The node [expansion] stopped at, if it stopped. */
        fun at(expansion: Expansion?): ElixirAst? =
            when (expansion) {
                is Expansion.Error -> expansion.at
                is Expansion.Unported -> expansion.at
                is Expansion.Opaque -> expansion.at
                is Expansion.Expanded, null -> null
            }

        /**
         * The `@` nodes `build_signature` expands in [definition]'s head (`Module`'s `simplify_arg/3`): each in a
         * default's value, in `Macro.prewalk/2`'s order and not inside another, an argument that is one, a default's
         * left side that is one, and a struct argument's name that is one.
         */
        fun signatureReads(definition: ElixirAst): List<ElixirAst> {
            val head = (definition as? ElixirAst.Call)?.arguments?.firstOrNull() ?: return emptyList()
            val call = extractGuards(head).first as? ElixirAst.Call ?: return emptyList()

            return call.arguments.orEmpty().flatMap(::argumentReads)
        }

        private fun argumentReads(argument: ElixirAst): List<ElixirAst> {
            val arguments = (argument as? ElixirAst.Call)?.arguments.orEmpty()

            return when {
                isCall(argument, "\\\\", 2) -> argumentReads(arguments[0]) + prewalkReads(arguments[1])
                isNamedCall(argument, "@") -> listOf(argument)
                isCall(argument, "%", 2) && isNamedCall(arguments[0], "@") -> listOf(arguments[0])
                else -> emptyList()
            }
        }

        private fun prewalkReads(node: ElixirAst): List<ElixirAst> =
            if (isNamedCall(node, "@")) {
                listOf(node)
            } else {
                when (node) {
                    is ElixirAst.Call -> listOf(node.callee) + node.arguments.orEmpty()
                    is ElixirAst.Alias -> node.segments
                    is ElixirAst.ListNode -> node.elements
                    is ElixirAst.Tuple -> node.elements
                    is ElixirAst.Block -> node.expressions
                    is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
                }.flatMap(::prewalkReads)
            }

        fun hasPlaceholder(node: ElixirAst): Boolean =
            when (node) {
                is ElixirAst.Placeholder -> true
                is ElixirAst.Call -> hasPlaceholder(node.callee) || node.arguments.orEmpty().any(::hasPlaceholder)
                is ElixirAst.Alias -> node.segments.any(::hasPlaceholder)
                is ElixirAst.Tuple -> node.elements.any(::hasPlaceholder)
                is ElixirAst.ListNode -> node.elements.any(::hasPlaceholder)
                is ElixirAst.Block -> node.expressions.any(::hasPlaceholder)
                is ElixirAst.Literal -> false
            }

        /**
         * The nodes of [node] to wrap in an identity probe: in a pattern, each variable and `_` outside a `^`, a map
         * key and a bitstring spec, each `^` outside a map key, and each non-literal bitstring size; and each variable
         * of a clause's guard outside a `Kernel` macro's arguments. A `rescue` head's only site is the variable left
         * of an `in` other than `in _`: wrapped, a variable is a call, which neither a bare `rescue` nor `in _` takes.
         * Nothing inside a capture is a site: Elixir names its parameters apart from source variables, and from 1.17
         * by the module's counter, which the expander doesn't keep. Of a `quote`, only its options and unquoted
         * expressions hold sites. The `_` of `_ = for` isn't one: wrapped, a block would expand it, not discard it.
         */
        fun identitySites(node: ElixirAst, pattern: Boolean): List<ElixirAst> =
            when {
                isCall(node, "&", 1) -> emptyList()
                isCall(node, "=", 2) -> {
                    val (left, right) = (node as ElixirAst.Call).arguments!!
                    val leftSites = if (isUnderscore(left) && isNamedCall(right, "for")) emptyList() else identitySites(left, true)

                    leftSites + identitySites(right, pattern)
                }
                !pattern ->
                    parts(node)?.flatMap { (kind, value) ->
                        when (kind) {
                            Part.EXPRESSION, Part.BODY -> identitySites(value, false)
                            Part.HEAD -> definitionHeadSites(value)
                            Part.GENERATOR -> generatorSites(value)
                            else -> clauses(value).flatMap { (args, body) ->
                                headSites(kind, args) + identitySites(body, false)
                            }
                        }
                    } ?: children(node).flatMap { identitySites(it, false) }
                isVariable(node) || isCall(node, "^", 1) -> listOf(node)
                isMap(node) ->
                    (node as ElixirAst.Call).arguments!!.flatMap { pair ->
                        if (pair is ElixirAst.Tuple && pair.elements.size == 2) identitySites(pair.elements[1], true) else emptyList()
                    }
                isBitstring(node) ->
                    (node as ElixirAst.Call).arguments!!.flatMap { segment ->
                        if (isCall(segment, "::", 2)) {
                            val (value, spec) = (segment as ElixirAst.Call).arguments!!

                            identitySites(value, true) + sizeSites(spec)
                        } else {
                            identitySites(segment, true)
                        }
                    }
                isCall(node, "|", 2) -> (node as ElixirAst.Call).arguments!!.flatMap { identitySites(it, true) }
                node is ElixirAst.Tuple || node is ElixirAst.ListNode || node is ElixirAst.Block ->
                    children(node).flatMap { identitySites(it, true) }
                else -> emptyList()
            }

        /** The arguments of `size(...)` and the `Size` of `Size*Unit` in [spec] that aren't integer or atom literals. */
        fun sizeSites(spec: ElixirAst): List<ElixirAst> {
            fun nonLiteral(size: ElixirAst) =
                listOf(size).filter { it !is ElixirAst.Literal.Integer && it !is ElixirAst.Literal.Atom }

            return when {
                isCall(spec, "-", 2) -> (spec as ElixirAst.Call).arguments!!.flatMap(::sizeSites)
                isCall(spec, "*", 2) -> {
                    val size = (spec as ElixirAst.Call).arguments!![0]

                    if (isUnderscore(size)) emptyList() else nonLiteral(size)
                }
                isCall(spec, "size", 1) -> nonLiteral((spec as ElixirAst.Call).arguments!!.single())
                else -> emptyList()
            }
        }

        /** The `def*` calls in [node], outermost first. */
        fun definitions(node: ElixirAst): List<ElixirAst> {
            val definer = node is ElixirAst.Call &&
                (node.callee as? ElixirAst.Literal.Atom)?.name in DEFINERS &&
                node.arguments?.size in 1..2

            return listOfNotNull(node.takeIf { definer }) + children(node).flatMap(::definitions)
        }

        val DEFINERS = setOf("def", "defp", "defmacro", "defmacrop")

        fun children(node: ElixirAst): List<ElixirAst> =
            when (node) {
                is ElixirAst.Call -> node.arguments.orEmpty()
                is ElixirAst.Tuple -> node.elements
                is ElixirAst.ListNode -> node.elements
                is ElixirAst.Block -> node.expressions
                is ElixirAst.Alias, is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
            }

        /** How a part of a `case`, `cond`, `receive`, `try`, `fn`, `with`, `for`, `def*` or `defmodule` is expanded. */
        enum class Part {
            EXPRESSION,
            BODY,

            /** A definition's head: its arguments are patterns, their defaults expressions, then its guard. */
            HEAD,

            /** A `<-` clause, or a bitstring whose last segment is one: a pattern, with an optional guard, from an expression. */
            GENERATOR,

            /** `->` clauses whose heads are patterns, with an optional guard. */
            PATTERN_CLAUSES,

            /** `->` clauses whose heads are expressions. */
            EXPRESSION_CLAUSES,
            RESCUE_CLAUSES,
        }

        /** [node]'s parts, when it is one of the constructs whose clauses are expanded, in source order. */
        fun parts(node: ElixirAst): List<Pair<Part, ElixirAst>>? {
            if (node !is ElixirAst.Call || node.arguments == null) return null

            val arguments = node.arguments

            fun keyword(
                options: List<ElixirAst>? = (arguments.lastOrNull() as? ElixirAst.ListNode)?.elements,
                each: (String) -> Part,
            ): List<Pair<Part, ElixirAst>>? =
                options?.map { option ->
                    val key = keyOf(option) ?: return null
                    val value = (option as ElixirAst.Tuple).elements[1]
                    val kind = each(key)

                    (if (kind != Part.EXPRESSION && kind != Part.BODY && !isClauses(value)) Part.EXPRESSION else kind) to value
                }

            return when ((node.callee as? ElixirAst.Literal.Atom)?.name) {
                "fn" -> listOf(Part.PATTERN_CLAUSES to ElixirAst.ListNode(node.meta, arguments))
                "case" ->
                    if (arguments.size == 2) {
                        keyword { if (it == "do") Part.PATTERN_CLAUSES else Part.EXPRESSION }
                            ?.let { listOf(Part.EXPRESSION to arguments[0]) + it }
                    } else {
                        null
                    }
                "cond" -> if (arguments.size == 1) keyword { if (it == "do") Part.EXPRESSION_CLAUSES else Part.EXPRESSION } else null
                "receive" ->
                    if (arguments.size == 1) {
                        keyword {
                            when (it) {
                                "do" -> Part.PATTERN_CLAUSES
                                "after" -> Part.EXPRESSION_CLAUSES
                                else -> Part.EXPRESSION
                            }
                        }
                    } else {
                        null
                    }
                "try" ->
                    if (arguments.size == 1) {
                        keyword {
                            when (it) {
                                "do", "after" -> Part.BODY
                                "else", "catch" -> Part.PATTERN_CLAUSES
                                "rescue" -> Part.RESCUE_CLAUSES
                                else -> Part.EXPRESSION
                            }
                        }
                    } else {
                        null
                    }
                // A bodiless head defines no clause, so nothing in it is expanded.
                "def", "defp", "defmacro", "defmacrop" ->
                    if (arguments.size == 2) {
                        keyword {
                            when (it) {
                                "do", "after" -> Part.BODY
                                "else", "catch" -> Part.PATTERN_CLAUSES
                                "rescue" -> Part.RESCUE_CLAUSES
                                else -> Part.EXPRESSION
                            }
                        }?.let { listOf(Part.HEAD to arguments[0]) + it }
                    } else {
                        null
                    }
                "defmodule" ->
                    if (arguments.size == 2) {
                        keyword { if (it == "do") Part.BODY else Part.EXPRESSION }
                            ?.let { listOf(Part.EXPRESSION to arguments[0]) + it }
                    } else {
                        null
                    }
                "with", "for" -> {
                    // `elixir_utils:split_opts/1`, which `with` takes from 1.15: the parts are the same either way.
                    val lists = arguments.takeLastWhile { it is ElixirAst.ListNode }.takeLast(2)
                    // Only `for` has bitstring generators.
                    val generator = if ((node.callee as ElixirAst.Literal.Atom).name == "for") ::isGenerator else { it: ElixirAst -> isCall(it, "<-", 2) }
                    val clauses = arguments.dropLast(lists.size).map { (if (generator(it)) Part.GENERATOR else Part.EXPRESSION) to it }
                    val options = lists.flatMap { (it as ElixirAst.ListNode).elements }
                    val reduce = options.any { ((it as? ElixirAst.Tuple)?.elements?.firstOrNull() as? ElixirAst.Literal.Atom)?.name == "reduce" }

                    keyword(options) {
                        when {
                            it == "do" && reduce -> Part.PATTERN_CLAUSES
                            it == "do" -> Part.BODY
                            it == "else" -> Part.PATTERN_CLAUSES
                            else -> Part.EXPRESSION
                        }
                    }?.let { clauses + it }
                }
                // Only the options and the unquoted expressions are expanded.
                "quote" -> {
                    val options = arguments.flatMap { (it as? ElixirAst.ListNode)?.elements.orEmpty() }
                    val body = options.firstOrNull { keyOf(it) == "do" }?.let(::valueOf)
                    val unquotes = options.none {
                        keyOf(it) == "bind_quoted" ||
                            keyOf(it) == "unquote" && (valueOf(it) as? ElixirAst.Literal.Atom)?.name == "false"
                    }

                    options.filter { keyOf(it) != null && keyOf(it) != "do" }.map { Part.EXPRESSION to valueOf(it) } +
                        body?.takeIf { unquotes }?.let(::unquoted).orEmpty().map { Part.EXPRESSION to it }
                }
                else -> null
            }
        }

        /** The expressions [node] unquotes, outside any `quote` nested in it. */
        private fun unquoted(node: ElixirAst): List<ElixirAst> =
            when {
                isNamedCall(node, "quote") -> emptyList()
                isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1) -> (node as ElixirAst.Call).arguments!!
                else -> children(node).flatMap(::unquoted)
            }

        fun hasReceiveWithoutAfterZero(node: ElixirAst): Boolean {
            val options = (node as? ElixirAst.Call)?.arguments?.singleOrNull() as? ElixirAst.ListNode
            val isReceive = isNamedCall(node, "receive") &&
                !options?.elements.isNullOrEmpty() &&
                options.elements.none { keyOf(it) == "after" && isZeroTimeout((it as ElixirAst.Tuple).elements[1]) }

            return isReceive || children(node).any(::hasReceiveWithoutAfterZero)
        }

        /** Whether the first `after` clause of [clauses] waits for `0`, or for `pattern = 0`. */
        private fun isZeroTimeout(clauses: ElixirAst): Boolean {
            val first = (clauses as? ElixirAst.ListNode)?.elements?.firstOrNull()?.takeIf { isCall(it, "->", 2) }
            val timeout = ((first as ElixirAst.Call?)?.arguments?.first() as? ElixirAst.ListNode)?.elements?.firstOrNull()
                ?.let(::expandedShape)
            val value = timeout?.takeIf { isCall(it, "=", 2) }?.let { (it as ElixirAst.Call).arguments!![1] } ?: timeout

            return (value as? ElixirAst.Literal.Integer)?.value?.signum() == 0
        }

        /** Whether [node] is a non-empty list of `->` clauses. */
        fun isClauses(node: ElixirAst): Boolean =
            node is ElixirAst.ListNode && node.elements.isNotEmpty() && node.elements.all { isCall(it, "->", 2) }

        /** Each `->` clause of [clauses] as its arguments' list and its body. */
        fun clauses(clauses: ElixirAst): List<Pair<ElixirAst, ElixirAst>> =
            (clauses as ElixirAst.ListNode).elements.map { clause ->
                val (args, body) = (clause as ElixirAst.Call).arguments!!

                args to body
            }

        fun headSites(kind: Part, args: ElixirAst): List<ElixirAst> {
            val elements = (args as? ElixirAst.ListNode)?.elements ?: return identitySites(args, false)
            val guarded = elements.singleOrNull()?.takeIf { isNamedCall(it, "when") } as ElixirAst.Call?

            return when {
                kind == Part.EXPRESSION_CLAUSES -> identitySites(args, false)
                kind == Part.RESCUE_CLAUSES -> elements.singleOrNull()?.let(::rescueSites) ?: emptyList()
                guarded != null ->
                    guarded.arguments!!.dropLast(1).flatMap { identitySites(it, true) } +
                        guardSites(guarded.arguments.last())
                else -> elements.flatMap { identitySites(it, true) }
            }
        }

        /** The expression a generator takes its elements from. */
        fun generatorRight(generator: ElixirAst): ElixirAst {
            val arrow = if (isBitstring(generator)) (generator as ElixirAst.Call).arguments!!.last() else generator

            return (arrow as ElixirAst.Call).arguments!![1]
        }

        /** A generator's pattern sites, and those of its expression. */
        fun generatorSites(generator: ElixirAst): List<ElixirAst> {
            val call = generator as ElixirAst.Call

            return if (isBitstring(call)) {
                val segments = call.arguments!!
                val left = (segments.last() as ElixirAst.Call).arguments!![0]

                identitySites(ElixirAst.Call(call.meta, call.callee, segments.dropLast(1) + left), true) +
                    identitySites(generatorRight(call), false)
            } else {
                val (left, right) = call.arguments!!

                headSites(Part.PATTERN_CLAUSES, ElixirAst.ListNode(left.meta, listOf(left))) + identitySites(right, false)
            }
        }

        fun rescueSites(head: ElixirAst): List<ElixirAst> =
            if (isCall(head, "in", 2)) {
                val (left, right) = (head as ElixirAst.Call).arguments!!

                listOf(left).filter { isVariable(it) && !isUnderscore(right) } + identitySites(right, false)
            } else {
                emptyList()
            }

        /** A definition head's sites: each argument's as a pattern, its default's as an expression, then the guard's. */
        fun definitionHeadSites(head: ElixirAst): List<ElixirAst> {
            val guarded = head.takeIf { isCall(it, "when", 2) } as ElixirAst.Call?
            val call = guarded?.arguments?.first() ?: head

            return (call as? ElixirAst.Call)?.arguments.orEmpty().flatMap { argument ->
                if (isCall(argument, "\\\\", 2)) {
                    val (pattern, default) = (argument as ElixirAst.Call).arguments!!

                    identitySites(pattern, true) + identitySites(default, false)
                } else {
                    identitySites(argument, true)
                }
            } + guarded?.arguments?.last()?.let(::guardSites).orEmpty()
        }

        /**
         * The variables of [guard] outside a `Kernel` macro's arguments: wrapped, a variable is a call, which the
         * macro may reject, copy, drop or expand at macro time, so Elixir would deliver its probe where the macro's
         * output says.
         */
        fun guardSites(guard: ElixirAst): List<ElixirAst> =
            when {
                isVariable(guard) -> listOf(guard)
                isKernelMacro(guard) -> emptyList()
                else -> children(guard).flatMap(::guardSites)
            }

        /** A call of one of the leg's `Kernel` macros, imported or through `Kernel.`. */
        fun isKernelMacro(node: ElixirAst): Boolean {
            val call = node as? ElixirAst.Call ?: return false
            val arguments = call.arguments ?: return false
            val dot = (call.callee as? ElixirAst.Call)?.takeIf { isCall(it, ".", 2) }
            val name = when {
                dot == null -> call.callee
                ((dot.arguments!![0] as? ElixirAst.Alias)?.segments?.singleOrNull() as? ElixirAst.Literal.Atom)?.name ==
                    "Kernel" -> dot.arguments[1]
                else -> null
            }

            return (name as? ElixirAst.Literal.Atom)?.let { NameArity(it.name, arguments.size) in legKernel.macros } == true
        }

        /**
         * The bodies nested in [node]'s clauses and `try` parts, each as its statements, outermost first, outside any
         * capture, as [identitySites] takes them.
         */
        fun nestedBodies(node: ElixirAst): List<List<ElixirAst>> {
            if (isCall(node, "&", 1)) return emptyList()

            val parts = parts(node) ?: return children(node).flatMap(::nestedBodies)

            return parts.flatMap { (kind, value) ->
                when (kind) {
                    Part.EXPRESSION -> nestedBodies(value)
                    // A pattern or guard holds no body, whatever a call in it is named.
                    Part.HEAD -> emptyList()
                    Part.BODY -> bodies(value)
                    Part.GENERATOR -> nestedBodies(generatorRight(value))
                    else -> clauses(value).flatMap { (args, body) -> nestedBodies(args) + bodies(body) }
                }
            }
        }

        /** [body]'s statements, unless it has none, and the bodies nested in them. */
        fun bodies(body: ElixirAst): List<List<ElixirAst>> {
            val statements = when {
                body is ElixirAst.Block -> body.expressions
                // A `->` without a body lowers to `nil` at the arrow.
                body is ElixirAst.Literal.Atom && body.name == "nil" && body.meta.origin.length == 2 -> emptyList()
                else -> listOf(body)
            }

            return listOfNotNull(statements.takeIf { it.isNotEmpty() }) + statements.flatMap(::nestedBodies)
        }
    }
}
