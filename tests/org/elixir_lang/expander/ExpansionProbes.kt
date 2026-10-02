package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import org.elixir_lang.expander.ProbeHarness.Tag
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDEFINED_VARIABLE_RAISES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.expressionNodes
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Expands case module bodies statement by statement, as `expand_block` threads them, and compares the expander with
 * Elixir on the leg's Elixir through a [ProbeHarness]:
 *
 * - a case the expander expands is compiled in a batch, and if the batch fails, alone, where it must compile or raise
 *   only when run;
 * - a case the expander reports an error for is compiled alone, and must fail at expansion, at the error's line and
 *   for its reason;
 *
 * and at each probe either delivers, the variables fall into the same classes and the env's other fields are equal. A
 * case the expander doesn't cover is not compiled.
 *
 * Each variable and `_` of a pattern, each `^` and each non-literal bitstring size in one, and each variable of a
 * clause's guard, is wrapped in an identity probe, which the expander matches when it enters that node. Each statement
 * of a body nested in a clause, such as a `->` clause's, is followed by a probe, which the expander matches when it
 * leaves that statement.
 */
internal class ExpansionProbes(private val harness: ProbeHarness, private val parse: (String) -> ElixirFile) {
    /** What the expander saw at the probe [tag], whose `case` is always 0. */
    class Step(val tag: Tag, val read: Map<Variable, Int>, val env: Env, val stacktrace: Boolean)

    /**
     * [case] expanded from the start of an empty module body.
     *
     * @property steps the statement probes, and the identity probes the expander entered, in its order, up to [outcome]
     * @property outcome the last statement's expansion, or the first that isn't [Expansion.Expanded]
     * @property statements the top-level statements, lowered
     * @property starts the state and env each top-level statement the expander reached was expanded from
     */
    class CaseExpansion(
        val case: ProbeHarness.Case,
        val steps: List<Step>,
        val outcome: Expansion,
        val statements: List<ElixirAst>,
        val starts: List<Pair<ExState, Env>>,
    )

    /** [body] expanded from the start of an empty module body, which is in [module] when one is given. */
    fun expand(body: String, module: String? = null): CaseExpansion {
        val level = legLevel()
        val file = parse(body)
        val statements = ReadAction.computeBlocking<List<ElixirAst>, Throwable> {
            val lowering = Lowering.of(file, level)

            expressionNodes(file).map { lowering.lower(it.psi) }
        }

        check(statements.none(::hasPlaceholder)) { "a case body must lower without placeholders: $body" }
        // The module body runs when it compiles, and a `receive` waits for its timeout, or for ever without one.
        check(statements.none(::hasReceiveWithoutAfterZero)) { "a case's receive must wait after 0: $body" }

        val sites = statements.flatMap { identitySites(it, false) }.sortedBy { it.meta.origin.startOffset }
        val identities = sites.withIndex().associate { (index, site) -> site.meta.origin to index + 1 }
        val bodies = statements.flatMap(::nestedBodies).sortedBy { it.first().meta.origin.startOffset }
        val nested = bodies.withIndex()
            .flatMap { (block, body) ->
                body.withIndex().map { (statement, node) -> node.meta.origin to Tag(0, block + 1, statement + 1) }
            }
            .toMap()
        var state = ExState.empty(level)
        var env = Env.empty(level, legKernel).copy(module = module)
        val steps = mutableListOf(Step(Tag(0, 0, 0), state.read, env, state.stacktrace))
        val starts = mutableListOf<Pair<ExState, Env>>()
        var outcome: Expansion = Expansion.Expanded(state, env)

        for ((index, statement) in statements.withIndex()) {
            starts.add(state to env)
            val entered = mutableSetOf<TextRange>()
            val left = mutableSetOf<TextRange>()
            val observer = object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {
                    identities[node.meta.origin]?.let { identity ->
                        if (entered.add(node.meta.origin)) {
                            steps.add(Step(Tag(0, 0, index + 1, identity), state.read, env, state.stacktrace))
                        }
                    }
                }

                // Nodes that share an origin, such as a block of one expression and that expression, leave with one state.
                override fun left(node: ElixirAst, expansion: Expansion) {
                    val tag = nested[node.meta.origin]

                    if (tag != null && expansion is Expansion.Expanded && left.add(node.meta.origin)) {
                        steps.add(Step(tag, expansion.state.read, expansion.env, expansion.state.stacktrace))
                    }
                }
            }

            outcome = Expander.expand(statement, state, env, level, legExports, observer)

            when (val expansion = outcome) {
                is Expansion.Expanded -> {
                    state = expansion.state
                    env = expansion.env
                    steps.add(Step(Tag(0, 0, index + 1), state.read, env, state.stacktrace))
                }
                is Expansion.Error, is Expansion.Unported -> break
            }
        }

        return CaseExpansion(
            ProbeHarness.Case(body, sites.map { it.meta.origin }, bodies.map { it.map { node -> node.meta.origin } }),
            steps,
            outcome,
            statements,
            starts,
        )
    }

    /**
     * Compares each of [cases] the expander covers with Elixir, and returns how long the compiles of the erroring
     * ones took.
     */
    fun assertMatchesElixir(cases: Map<String, CaseExpansion>): Duration {
        assertDefaultCompilerOptions()

        val expanded = cases.filterValues { it.outcome is Expansion.Expanded }
        val erroring = cases.filterValues { it.outcome is Expansion.Error }
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        compareExpanded(expanded, expected, actual)

        val start = TimeSource.Monotonic.markNow()
        erroring.forEach { (name, expansion) -> compareError(name, expansion, expected, actual) }
        val elapsed = start.elapsedNow()

        println("${expanded.size} expanded and ${erroring.size} erroring cases; erroring compiles took $elapsed")

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))

        return elapsed
    }

    private fun compareExpanded(
        cases: Map<String, CaseExpansion>,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        if (cases.isEmpty()) return

        val names = cases.keys.toList()
        val attempt = harness.attempt(names.map { cases.getValue(it).case })

        if (attempt.compiled.status == OtpErlangAtom("ok")) {
            assertEquals("probes that reported", attempt.tags.toSet(), attempt.batch.observations.map { it.tag }.toSet())

            val byCase = attempt.batch.observations.groupBy { it.tag.case }

            names.forEachIndexed { index, name ->
                expected.add(render(name, cases.getValue(name).steps))
                actual.add(render(name, byCase[index].orEmpty(), attempt.batch.probeModule))
            }
        } else {
            names.forEach { name ->
                val expansion = cases.getValue(name)
                val alone = harness.attempt(listOf(expansion.case))
                val compiled = alone.compiled
                val status = compiled.status
                val runTimeRaise = status is OtpErlangTuple &&
                    status.elementAt(0) == OtpErlangAtom("raise") &&
                    status.elementAt(1) != OtpErlangAtom(COMPILE_ERROR) &&
                    errors(compiled.diagnostics).isEmpty() &&
                    alone.tags.toSet() == alone.batch.observations.map { it.tag }.toSet()

                expected.add(render(name, expansion.steps))
                actual.add(
                    if (status == OtpErlangAtom("ok") || runTimeRaise) {
                        render(name, alone.batch.observations, alone.batch.probeModule)
                    } else {
                        "== $name\ncompile failed: ${inspect(compiled.status)} ${compiled.diagnostics.map(::inspect)}"
                    }
                )
            }
        }
    }

    private fun compareError(
        name: String,
        expansion: CaseExpansion,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        val error = expansion.outcome as Expansion.Error
        val alone = harness.attempt(listOf(expansion.case))
        val compiled = alone.compiled
        val bodyLine = alone.bodyLines.single()
        val line = error.at.meta.keys.filterIsInstance<Meta.Key.Location>().singleOrNull()?.position?.line
        val location = if (ErrorKinds.hasLine(error.kind)) " at line ${line?.let { it + bodyLine - 1 }}" else ""
        val raising = Tag(0, 0, expansion.starts.size)

        expected.add(render(name, expansion.steps) + "\nerror ${error.kind}$location")
        actual.add(
            render(name, alone.batch.observations, alone.batch.probeModule) + "\n" +
                elixirError(error, compiled, alone.batch.observations.any { it.tag == raising })
        )
    }

    /**
     * The error [compiled] failed with, as `error <kind> at line <n>` when its message is [error]'s kind, or as `error
     * <kind>` for a kind with no line. A raise failed at expansion if and only if the probe after the raising statement
     * wasn't [delivered]: the module body is expanded whole before it runs.
     */
    private fun elixirError(
        error: Expansion.Error,
        compiled: org.elixir_lang.intellij_elixir.Quoter.Compiled,
        delivered: Boolean,
    ): String {
        val status = compiled.status as? OtpErlangTuple
        val raised = status != null && status.elementAt(0) == OtpErlangAtom("raise")

        if (!raised || delivered) {
            return "not an expansion error: ${inspect(compiled.status)} ${compiled.diagnostics.map(::inspect)}"
        }

        if (status.elementAt(1) != OtpErlangAtom(COMPILE_ERROR)) {
            val message = utf8(status.elementAt(2))

            return if (!ErrorKinds.hasLine(error.kind) && ErrorKinds.pattern(error.kind).containsMatchIn(message)) {
                "error ${error.kind}"
            } else {
                "error ${inspect(status.elementAt(1))}: $message"
            }
        }

        val (line, message) = if (legLevel().elixir >= DIAGNOSTICS_SINCE.elixir) {
            val errors = errors(compiled.diagnostics)

            if (errors.size != 1) return "not one :error diagnostic: ${compiled.diagnostics.map(::inspect)}"

            errors.single().line to errors.single().message
        } else {
            val match = PREFIXED_MESSAGE.find(utf8(status.elementAt(2)))
                ?: return "no <file>:<line>: prefix: ${inspect(status.elementAt(2))}"

            match.groupValues[1].toInt() to match.groupValues[2]
        }
        val pattern = ErrorKinds.pattern(error.kind)

        return if (pattern.containsMatchIn(message)) {
            "error ${error.kind} at line $line"
        } else {
            "error at line $line: $message"
        }
    }

    /** One line per probe: its tag and the variables' classes, numbered across [steps]; then its env's fields. */
    private fun render(name: String, steps: List<Step>): String =
        render(
            name,
            steps.map { it.tag },
            VariableClasses.canonical(steps.map { it.read }),
            steps.map { ProbedEnvNormaliser.render(ProbedEnvNormaliser.projected(it.env, it.stacktrace, legLevel())) }
        )

    private fun render(name: String, observations: List<ProbeHarness.Observation>, probeModule: String): String =
        render(
            name,
            observations.map { it.tag.copy(case = 0) },
            VariableClasses.canonical(observations.map { VariableClasses.observed(it.env) }),
            observations.map { ProbedEnvNormaliser.render(ProbedEnvNormaliser.observed(it.env, probeModule)) }
        )

    private fun render(name: String, tags: List<Tag>, classes: List<String>, envs: List<String>): String =
        "== $name\n" +
            tags.indices.joinToString("\n") { "$it ${tags[it]}: ${classes[it]}" } + "\n" +
            tags.indices.joinToString("\n") { "${tags[it]}\n${envs[it].prependIndent("  ")}" }

    private fun errors(diagnostics: List<com.ericsson.otp.erlang.OtpErlangObject>) =
        diagnostics.map(Diagnostic::of).filter { it.severity == "error" }

    private fun assertDefaultCompilerOptions() {
        if (!UNDEFINED_VARIABLE_RAISES.isSufficient(legLevel()) || optionsAsserted) return

        // The expander assumes the default, which makes an undefined variable an error.
        assertEquals(OtpErlangAtom("raise"), harness.compilerOption("on_undefined_variable"))
        optionsAsserted = true
    }

    private companion object {
        const val COMPILE_ERROR = "Elixir.CompileError"

        /** From 1.15, `Code.with_diagnostics` reports the error, and the exception says only that compiling failed. */
        val DIAGNOSTICS_SINCE: ElixirLanguageLevel = ElixirLanguageLevel.of("1.15.0-rc.0")

        val PREFIXED_MESSAGE = Regex("""^[^:\n]*:(\d+): (.*)""", RegexOption.DOT_MATCHES_ALL)

        @Volatile
        var optionsAsserted = false

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
         * of a clause's guard. A `rescue` head's only site is the variable left of an `in` other than `in _`: wrapped, a
         * variable is a call, which neither a bare `rescue` nor `in _` takes. Nothing inside a capture is a site: Elixir
         * names its parameters apart from source variables, and from 1.17 by the module's counter, which the expander
         * doesn't keep. The `_` of `_ = for` isn't one: wrapped, a block would expand it, not discard it.
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

        fun children(node: ElixirAst): List<ElixirAst> =
            when (node) {
                is ElixirAst.Call -> node.arguments.orEmpty()
                is ElixirAst.Tuple -> node.elements
                is ElixirAst.ListNode -> node.elements
                is ElixirAst.Block -> node.expressions
                is ElixirAst.Alias, is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
            }

        /** How a part of a `case`, `cond`, `receive`, `try`, `fn`, `with` or `for` is expanded. */
        enum class Part {
            EXPRESSION,
            BODY,

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
                else -> null
            }
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

        fun guardSites(guard: ElixirAst): List<ElixirAst> =
            if (isVariable(guard)) listOf(guard) else children(guard).flatMap(::guardSites)

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
