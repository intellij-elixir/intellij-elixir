package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_EXTENSION_META_ESCAPED
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEPS_IMPORTS_META
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEP_READS_LINE_OPTION
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEP_STORES_FULL_PATH
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_OPTIONS_EXPANDED_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_VALIDATED_BY_UNQUOTE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * The `quote` clauses of `elixir_expand`, and the part of `elixir_quote` they reach: the quoted expression is built as
 * the escaped expression Elixir builds, an [ElixirAst] of tuples, lists and literals, and then expanded.
 */
internal object Quote {
    /** `{quote, Meta, [Opts]}`: the `do:` taken out of [call]'s only argument, a list, into a second one. */
    fun expandKeywords(call: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
        withDoArgument(call)?.let { expand(it, state, env, run, written = call) } ?: Expansion.Error("missing_option", call)

    /**
     * `{quote, Meta, [Opts, Do]}` where `Do` is a list, which is [written] in the source. In a definition's body, the
     * escaped block is recorded for [written].
     */
    fun expand(call: ElixirAst.Call, state: ExState, env: Env, run: Run, written: ElixirAst.Call = call): Expansion =
        when (val built = build(call, state, env, run, 0)) {
            is Built.Stopped -> built.expansion
            is Built.Escaped -> {
                if (built.known) {
                    val evaluated = if (built.bindings.isEmpty()) {
                        built.block
                    } else {
                        ElixirAst.Block(call.meta, built.bindings + built.block)
                    }

                    run.quotesAt(env)?.put(written, evaluated)
                }

                Expander.expand(built.quoted, built.state, built.env, run).let {
                    // `{'__block__', [], Prelude ++ [Quoted]}`, the bindings' escaped `__block__`, or from 1.20 outside
                    // a pattern or guard, either in `:elixir_quote.validate_quote/1`.
                    val validated = UNQUOTE_VALIDATED_BY_UNQUOTE.isSufficient(run.level) && env.context == Env.Context.NONE

                    if (built.bindings.isNotEmpty() || built.block !== built.quoted || validated) it.withValue(NODE) else it
                }
            }
        }

    /**
     * The escaped expression [call], a `quote` of either form, builds from [state] and [env], with its `bind_quoted`
     * bindings: `null` where building it raises or reaches what isn't ported, or where a run-time option's prelude
     * comes first. [lineOffset] is added to each line read from a node's metadata.
     */
    fun escaped(call: ElixirAst.Call, state: ExState, env: Env, run: Run, lineOffset: Int = 0): ElixirAst? {
        val arguments = call.arguments ?: return null
        val body = when (arguments.size) {
            1 if arguments.single() is ElixirAst.ListNode -> withDoArgument(call) ?: return null
            2 if arguments[1] is ElixirAst.ListNode -> call
            else -> return null
        }

        return (build(body, state, env, run, lineOffset) as? Built.Escaped)?.takeIf { it.known && it.bindings.isEmpty() }?.block
    }

    /**
     * What [escape] gives: the expression that builds the escaped node, the node the port can't read, where building
     * stops, or what it raises.
     */
    sealed interface Escaped {
        /** [readAsCode] is the code each `unquote` in a macro's term became, which [node] holds as it is. */
        data class Built(val node: ElixirAst, val readAsCode: Set<ElixirAst> = emptySet()) : Escaped

        /** The node holds code the port can't read, which Elixir escapes as it is; [expansion] is the stop at it. */
        data class Unread(val expansion: Expansion) : Escaped

        /** The escape builds what the port doesn't, so what Elixir does from here isn't known. */
        data class Stopped(val expansion: Expansion) : Escaped

        /** Elixir raises [exception], the text of its module's atom. */
        data class Raised(val exception: String) : Escaped
    }

    /**
     * `elixir_quote:escape(Expr, escape, true)`, which `Macro.escape(expr, unquote: true)` calls: the expression that
     * builds [node], with no hygiene, its metadata as it is, and each `unquote` its expression. The nodes it builds are
     * at [at].
     */
    fun escape(node: ElixirAst, at: Meta, run: Run): Escaped {
        val s = Synthetic(at)
        val view = AstEscapeView(s, ParsedMeta(COMPILER_PARSES_COLUMNS.isSufficient(run.level)))
        val escape = QuoteEscape(view, AstEscapeBuilder(s), view::inserted, run.level, run.exports, unquote = true)

        return when (val escaped = escape.escape(AstTerm.Node(node))) {
            is EscapeResult.Built -> Escaped.Built(escaped.value, view.readAsCode)
            is EscapeResult.Unread -> Escaped.Unread(Expansion.Unported(escaped.at ?: node))
            EscapeResult.Declined -> Escaped.Stopped(Expansion.Unported(node))
            is EscapeResult.Raised -> Escaped.Raised(escaped.exception)
        }
    }

    /** `lists:keytake(do, 1, Opts)`, as the keyword clause expands `{quote, Meta, [DoOpts, [{do, Do}]]}`. */
    private fun withDoArgument(call: ElixirAst.Call): ElixirAst.Call? {
        val opts = call.arguments!!.single() as ElixirAst.ListNode
        val index = opts.elements.indexOfFirst { keyOf(it) == "do" }.takeIf { it >= 0 } ?: return null
        val rest = opts.elements.filterIndexed { i, _ -> i != index }

        val meta = call.meta

        return ElixirAst.Call(
            Meta(meta.origin, meta.start, meta.end, meta.keys, built = true),
            call.callee,
            listOf(ElixirAst.ListNode(opts.meta, rest), ElixirAst.ListNode(opts.meta, listOf(opts.elements[index]))),
        )
    }

    private sealed interface Built {
        class Stopped(val expansion: Expansion) : Built

        /**
         * @property quoted what `elixir_quote:quote/2` gives, which is expanded from [state] and [env], those after the
         * prelude
         * @property block [quoted] after the `bind_quoted` bindings, without the prelude
         * @property known whether [block] is the tree Elixir builds: not where a `file:` path's bytes aren't known,
         *   though [quoted] still expands as Elixir's does, since a binary expands alike whatever it holds
         * @property bindings the prelude: each run-time option's variable bound ahead of [quoted]
         */
        class Escaped(
            val quoted: ElixirAst,
            val block: ElixirAst,
            val state: ExState,
            val env: Env,
            val known: Boolean,
            val bindings: List<ElixirAst>,
        ) : Built
    }

    /** Thrown where building stops: an error Elixir raises, or a node that isn't ported. */
    private class Stop(val expansion: Expansion) : RuntimeException(null, null, false, false)

    private fun build(call: ElixirAst.Call, state: ExState, env: Env, run: Run, lineOffset: Int): Built =
        try {
            buildOrStop(call, state, env, run, lineOffset)
        } catch (stop: Stop) {
            Built.Stopped(stop.expansion)
        }

    private fun buildOrStop(call: ElixirAst.Call, state: ExState, env: Env, run: Run, lineOffset: Int): Built.Escaped {
        val (opts, doList) = call.arguments!!
        val level = run.level
        val exprs = (doList as ElixirAst.ListNode).elements.firstOrNull { keyOf(it) == "do" }
            ?.let { (it as ElixirAst.Tuple).elements[1] }
            ?: throw Stop(Expansion.Error("missing_option", call))

        val expandedOpts = Expander.expand(opts, state, env, run)
        if (expandedOpts !is Expansion.Expanded) throw Stop(expandedOpts)

        val options = options(call, opts)
        val values = expandedOpts.value

        fun optionValue(key: String): Term? =
            values.keyfind(key)?.also { term -> unknownStop(term, options.getValue(key))?.let { throw Stop(it) } }

        val bindQuoted = options["bind_quoted"]?.let { bindings(call, it, env, level) }
        val unquote = options["unquote"]?.let { boolean(it, optionValue("unquote")) }
            ?: Option.Static(bindQuoted == null)
        val generated = options["generated"]?.let { boolean(it, optionValue("generated")) } ?: Option.Static(false)

        if (env.context != Env.Context.NONE && QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES.isSufficient(level)) {
            when (unquote) {
                is Option.Static ->
                    if (unquote.value && hasUnquotes(exprs, call, level)) {
                        throw Stop(Expansion.Error("quote_in_pattern_with_unquote", call))
                    }
                // `andalso` raises `badarg` on a value that isn't a boolean.
                is Option.Dynamic -> throw Stop(Expansion.Unported(call))
            }
        }

        val kept = options["location"]?.let { keptFile(call, optionValue("location"), env, run) }
        val line = if (kept != null) {
            Option.Static(if (QUOTE_KEEP_READS_LINE_OPTION.isSufficient(level)) Line.Keep else Line.Drop)
        } else {
            options["line"]?.let { value ->
                when (val term = optionValue("line")) {
                    is Term.Integer -> Option.Static(Line.At(term.value))
                    TRUE -> Option.Static(Line.Keep)
                    FALSE -> Option.Static(Line.Drop)
                    else -> Option.Dynamic(value)
                }
            } ?: Option.Static(Line.Drop)
        }
        val file = kept?.let { Option.Static(it) } ?: options["file"]?.let { value ->
            when (val term = optionValue("file")) {
                is Term.Binary -> Option.Static(term)
                NIL -> Option.Static(null)
                else -> Option.Dynamic(value)
            }
        } ?: Option.Static(null)
        val context = options["context"]?.let { value ->
            when (val term = optionValue("context")) {
                is Term.Atom if term != NIL -> Option.Static(term.name)
                else -> Option.Dynamic(value)
            }
        } ?: Option.Static(env.module ?: "Elixir")

        if (unquote is Option.Dynamic || generated is Option.Dynamic) {
            throw Stop(Expansion.Error("quote_invalid_runtime_option", call))
        }

        // The prelude is built line, file, context, each in front, so its first binding is the context's.
        val prelude = listOf("context" to context, "file" to file, "line" to line).mapNotNull { (key, option) ->
            (option as? Option.Dynamic)?.let { key to it.value }
        }

        val path = (file as? Option.Static)?.value
        val q = Quoting(
            line = when (line) {
                is Option.Static -> line.value
                is Option.Dynamic -> Line.Var
            },
            // A path whose bytes aren't known is built empty, and `known` keeps that tree from `escaped`.
            file = when (file) {
                is Option.Static -> Option.Static(path?.let { it.bytes ?: ByteArray(0) })
                is Option.Dynamic -> file
            },
            context = context,
            unquote = (unquote as Option.Static).value,
            generated = (generated as Option.Static).value,
            env = expandedOpts.env,
            level = level,
            synthetic = Synthetic(call.meta),
            lineOffset = lineOffset,
            observer = run.observer,
            exports = run.exports,
        )

        fun doQuote(): ElixirAst {
            if (q.unquote && isCall(exprs, "unquote_splicing", 1)) {
                throw Stop(Expansion.Error("quote_unquote_splicing", call))
            }

            return q.doQuote(exprs)
        }

        val s = q.synthetic
        var expanded: Expansion.Expanded = expandedOpts
        val expandedFirst = QUOTE_OPTIONS_EXPANDED_FIRST.isSufficient(level)
        // Before 1.17 the prelude and the bindings are expanded with the quoted body, after it is built.
        val quotedFirst = if (expandedFirst) null else doQuote()

        val bindings = prelude.map { (key, value) ->
            s.match(
                s.variable(key),
                s.remoteCall(call.meta, "elixir_quote", "validate_runtime", listOf(s.atom(key), builtCopy(value))),
            )
        }

        // The prelude is given each value as expanded, so it is expanded a second time.
        prelude.zip(bindings).forEach { (option, binding) ->
            expanded = expandAgain(option.second, state, env, expanded, run) {
                Expander.expand(binding, it.state, it.env, run)
            }
        }

        if (!expandedFirst) {
            bindQuoted.orEmpty().forEach { (_, value) ->
                expanded = expandAgain(value, state, env, expanded, run) {
                    Expander.expand(builtCopy(value), it.state, it.env, run)
                }
            }
        }

        // From 1.17 the context is expanded on its own before the body is quoted: a variable's read, binding nothing.
        val quoted = quotedFirst ?: doQuote()
        val block = if (bindQuoted.isNullOrEmpty()) {
            quoted
        } else {
            val bindingMeta = s.keywords(q.sourceMeta(call.meta, QUOTE_EXTENSION_META_ESCAPED.isSufficient(level)))
            val bindings = bindQuoted.map { (key, value) ->
                s.tuple(
                    s.atom("="),
                    s.list(),
                    s.list(s.tuple(s.atom(key), bindingMeta, q.contextNode()), expandedValue(value, env, level)),
                )
            }

            s.tuple(s.atom("__block__"), s.list(), s.list(bindings + quoted))
        }

        val known = path == null || path.bytes != null

        return Built.Escaped(quoted, block, expanded.state, expanded.env, known, bindings)
    }

    /**
     * [expansion] of [value], first expanded from [state] and [env], which Elixir expands a second time as the first
     * time's result: as [value] again when it holds no call, whose result would dispatch differently.
     */
    private inline fun expandAgain(
        value: ElixirAst,
        state: ExState,
        env: Env,
        from: Expansion.Expanded,
        run: Run,
        expansion: (Expansion.Expanded) -> Expansion,
    ): Expansion.Expanded {
        if (holdsCall(value, state, env, run.level, inPattern = false)) throw Stop(Expansion.Unported(value))

        return when (val again = expansion(from)) {
            is Expansion.Expanded -> again
            is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> throw Stop(again)
        }
    }

    /**
     * Whether [node], expanded from [state] and [env], holds a call other than `=`, a variable read as a call included.
     */
    private fun holdsCall(
        node: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        inPattern: Boolean,
    ): Boolean {
        ProgressManager.checkCanceled()

        fun holds(child: ElixirAst) = holdsCall(child, state, env, level, inPattern)

        return when (node) {
            is ElixirAst.Call ->
                when {
                    node.arguments == null -> !inPattern && isExpandedAsCall(node, state, env, level)
                    isCall(node, "=", 2) ->
                        holdsCall(node.arguments[0], state, env, level, inPattern = true) || holds(node.arguments[1])
                    else -> true
                }
            is ElixirAst.Alias -> node.segments.any(::holds)
            is ElixirAst.Block -> node.expressions.any(::holds)
            is ElixirAst.Tuple -> node.elements.any(::holds)
            is ElixirAst.ListNode -> node.elements.any(::holds)
            is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> false
        }
    }

    /** Whether [node], a variable, is expanded as the local call `{Name, Meta, []}`. */
    private fun isExpandedAsCall(node: ElixirAst.Call, state: ExState, env: Env, level: ElixirLanguageLevel): Boolean =
        Clause.entries.firstOrNull { it.matches(node, state, env, level) } == Clause.VARIABLE &&
            variableOutcome(node, state, level) == VariableOutcome.LocalCall

    /**
     * [node] with each node in it marked [Meta.built]: an expansion the observer is told of once already, or code the
     * expander made from a value.
     */
    fun builtCopy(node: ElixirAst): ElixirAst {
        ProgressManager.checkCanceled()

        val meta = node.meta.let { Meta(it.origin, it.start, it.end, it.keys, built = true) }

        return when (node) {
            is ElixirAst.Call ->
                ElixirAst.Call(meta, builtCopy(node.callee), node.arguments?.map(::builtCopy), node.context)
            is ElixirAst.Alias -> ElixirAst.Alias(meta, node.segments.map(::builtCopy))
            is ElixirAst.Block -> ElixirAst.Block(meta, node.expressions.map(::builtCopy))
            is ElixirAst.Tuple -> ElixirAst.Tuple(meta, node.elements.map(::builtCopy))
            is ElixirAst.ListNode -> ElixirAst.ListNode(meta, node.elements.map(::builtCopy))
            is ElixirAst.Placeholder -> ElixirAst.Placeholder(meta, node.reason)
            is ElixirAst.Literal.Atom -> ElixirAst.Literal.Atom(meta, node.name)
            is ElixirAst.Literal.Integer -> ElixirAst.Literal.Integer(meta, node.value)
            is ElixirAst.Literal.Float -> ElixirAst.Literal.Float(meta, node.value)
            is ElixirAst.Literal.Binary -> ElixirAst.Literal.Binary(meta, node.bytes)
            is ElixirAst.Value -> ElixirAst.Value(meta, node.term)
        }
    }

    /** An option's value: known while expanding, or only at run time. */
    private sealed interface Option<out T> {
        data class Static<T>(val value: T) : Option<T>

        /** @property value the option's expression, which `elixir_quote:validate_runtime/2` is given */
        data class Dynamic(val value: ElixirAst) : Option<Nothing>
    }

    /**
     * `line:`: `true` keeps each node's own line, `false` drops it, and an integer, or the variable the prelude binds,
     * replaces it.
     */
    private sealed interface Line {
        data object Keep : Line

        data object Drop : Line

        data class At(val line: BigInteger) : Line

        data object Var : Line
    }

    /** An option's [term] as a boolean, or else the option's expression, [value]. */
    private fun boolean(value: ElixirAst, term: Term?): Option<Boolean> =
        when (term) {
            TRUE -> Option.Static(true)
            FALSE -> Option.Static(false)
            else -> Option.Dynamic(value)
        }

    /**
     * The first value of each pair in [opts], the options as expanded, by key, after `validate_opts/5`. Elixir reads
     * them with `proplists:get_value/3` and `lists:keyfind/3`, which also read an option's name as an atom or as the
     * first element of a larger tuple: such an element, or one whose expansion isn't known here, isn't ported.
     */
    private fun options(call: ElixirAst.Call, opts: ElixirAst): Map<String, ElixirAst> {
        val list = expandedShape(opts) as? ElixirAst.ListNode
        val shapes = list?.elements?.map(::expandedShape).orEmpty()
        val pairs = shapes.filterIsInstance<ElixirAst.Tuple>().filter { it.elements.size == 2 }
        val keys = pairs.map { (expandedShape(it.elements[0]) as? ElixirAst.Literal.Atom)?.name }
        val keyed = list?.let { Term.List(keys.map { key -> Term.Pair(key?.let(Term::Atom) ?: NODE, NODE) }) }

        Import.optionsError(keyed ?: NODE, QUOTE_OPTIONS)?.let { throw Stop(Expansion.Error(it, call)) }

        val options = mutableMapOf<String, ElixirAst>()

        pairs.zip(keys).forEach { (pair, key) -> options.putIfAbsent(key!!, pair.elements[1]) }

        val readAsOption = shapes.any { shape ->
            val name = when (shape) {
                is ElixirAst.Literal.Atom -> shape.name
                is ElixirAst.Call -> (shape.callee as? ElixirAst.Literal.Atom)?.name
                else -> null
            }

            name in QUOTE_OPTIONS
        }

        if (readAsOption) throw Stop(Expansion.Unported(call))

        return options
    }

    /**
     * The file `location: :keep` stores, [run]'s, which before [QUOTE_KEEP_STORES_FULL_PATH] is relative to the
     * directory it was compiled in. A definition in another file isn't ported. Any other [location] is a `case_clause`
     * crash.
     */
    private fun keptFile(call: ElixirAst.Call, location: Term?, env: Env, run: Run): Term.Binary {
        val file = run.file
        val moved = env.function != null && run.compiling[env.module]?.definitionMoved == true
        if ((location as? Term.Atom)?.name != "keep" || file == null || moved) throw Stop(Expansion.Unported(call))
        if (QUOTE_KEEP_STORES_FULL_PATH.isSufficient(run.level)) return Term.Binary(file.toByteArray())

        val directory = run.directories.of(file) ?: throw Stop(Expansion.Unported(call))

        return Term.Binary(relativeTo(file, directory).toByteArray())
    }

    /** `Path.relative_to/2`: [path] relative to [directory] when below it, otherwise [path] itself. */
    private fun relativeTo(path: String, directory: String): String {
        val segments = splitPath(path)
        val base = splitPath(directory)

        return if (segments.size > base.size && segments.subList(0, base.size) == base) {
            segments.drop(base.size).joinToString("/")
        } else {
            path
        }
    }

    /** `bind_quoted:`'s `[{Key, Value}]`, validated as `elixir_expand` validates it. */
    private fun bindings(
        call: ElixirAst.Call,
        value: ElixirAst,
        env: Env,
        level: ElixirLanguageLevel,
    ): List<Pair<String, ElixirAst>> {
        val invalid = Stop(Expansion.Error("invalid_bind_quoted_for_quote", call))
        val list = expandedShape(value) as? ElixirAst.ListNode ?: throw invalid

        return list.elements.map { element ->
            ProgressManager.checkCanceled()

            val pair = (expandedShape(element) as? ElixirAst.Tuple)?.takeIf { it.elements.size == 2 } ?: throw invalid
            val key = when (val key = pair.elements[0]) {
                is ElixirAst.Alias -> atomValue(key, env, level) ?: throw Stop(Expansion.Unported(key))
                else -> (expandedShape(key) as? ElixirAst.Literal.Atom)?.name ?: throw invalid
            }

            key to pair.elements[1]
        }
    }

    /** The atom [value] expands to, or `null` when it isn't an atom or isn't known here. */
    private fun atomValue(value: ElixirAst, env: Env, level: ElixirLanguageLevel): String? =
        when (val shape = value as? ElixirAst.Alias ?: expandedShape(value)) {
            is ElixirAst.Alias -> aliasesModule(shape, env, level)
            is ElixirAst.Literal.Atom -> shape.name
            else -> null
        }

    /** [value] as it expands, as far as [quotedValue] reads it: an alias is the atom it names. */
    private fun expandedValue(value: ElixirAst, env: Env, level: ElixirLanguageLevel): ElixirAst =
        (value as? ElixirAst.Alias)?.let { aliasesModule(it, env, level) }?.let { ElixirAst.Literal.Atom(value.meta, it) }
            ?: value

    /** `elixir_quote:has_unquotes/1` over [exprs], `quote`'s body, raising where it raises. */
    private fun hasUnquotes(exprs: ElixirAst, call: ElixirAst.Call, level: ElixirLanguageLevel): Boolean =
        try {
            HasUnquotes(AstEscapeView(Synthetic(call.meta), ParsedMeta(columns = false)), level).has(AstTerm.Node(exprs))
        } catch (_: ImproperList) {
            throw Stop(Expansion.Error("Elixir.FunctionClauseError", call))
        }

    private val QUOTE_OPTIONS = listOf("context", "location", "line", "file", "unquote", "bind_quoted", "generated")

    /**
     * `#elixir_quote{op = quote}`, with `aliases_hygiene` and `imports_hygiene` both [env]: the [ElixirAst] view and
     * builder [QuoteEscape] quotes a body through, and the fields only `quote` has. A dynamic [file] or [context] is
     * the variable the prelude binds; [observer] is told of the imports quoted.
     */
    private class Quoting(
        val line: Line,
        val file: Option<ByteArray?>,
        val context: Option<String>,
        val unquote: Boolean,
        val generated: Boolean,
        val env: Env,
        val level: ElixirLanguageLevel,
        val synthetic: Synthetic,
        val lineOffset: Int,
        val observer: ExpansionObserver,
        val exports: Exports,
    ) : QuoteOp<AstTerm, ElixirAst> {
        private val s = synthetic

        /** The metadata `annotate_def/2` gave a node ahead of its own quoting. */
        private val annotated = IdentityHashMap<ElixirAst, Keywords<ElixirAst>>()

        private val view = AstEscapeView(s, ParsedMeta(columns = false, lineOffset))

        /** `do_quote/2` of [node]. */
        fun doQuote(node: ElixirAst): ElixirAst =
            when (val built = QuoteEscape(view, AstEscapeBuilder(s), view::inserted, level, exports, this, unquote).escape(AstTerm.Node(node))) {
                is EscapeResult.Built -> built.value
                is EscapeResult.Unread -> throw Stop(Expansion.Unported(built.at ?: node))
                EscapeResult.Declined, is EscapeResult.Raised -> throw Stop(Expansion.Unported(node))
            }

        /** `Q#elixir_quote.context`. */
        fun contextNode(): ElixirAst =
            when (context) {
                is Option.Static -> s.atom(context.value)
                is Option.Dynamic -> s.variable("context")
            }

        /** [meta]'s keys as `Code.compile_string` parses them at [level], with [lineOffset] added to each line. */
        fun sourceMeta(meta: Meta, dropColumn: Boolean = false): Keywords<ElixirAst> {
            val parsed = ParsedMeta(COMPILER_PARSES_COLUMNS.isSufficient(level) && !dropColumn, lineOffset)

            return parsed.pairs(meta.keys).map { (key, value) -> key to parsed.code(s, value) }
        }

        override fun context(): ElixirAst = contextNode()

        override fun input(output: ElixirAst): AstTerm = AstTerm.Node(output)

        override fun metaOf(input: AstTerm): Keywords<ElixirAst> = node(input).let { annotated.remove(it) ?: sourceMeta(it.meta) }

        override fun annotateDefinition(head: AstTerm) {
            annotated[node(head)] = keystore(metaOf(head), "context", contextNode())
        }

        /** The unquote head of `do_quote/2`: [expr] itself, or from 1.18 passed to a validation call. */
        override fun unquoted(at: Meta?, expr: AstTerm): ElixirAst {
            val node = view.inserted(expr)
            val keys = at?.keys.orEmpty()

            return when {
                UNQUOTE_VALIDATED_BY_UNQUOTE.isSufficient(level) -> s.remoteCall("elixir_quote", "unquote", listOf(node), keys)
                UNQUOTE_SHALLOW_VALIDATED.isSufficient(level) -> s.remoteCall("elixir_quote", "shallow_validate_ast", listOf(node), keys)
                else -> node
            }
        }

        /** The `alias` annotation `elixir_aliases:expand/4` gives [input]: the module it names, or `false`. */
        override fun aliased(input: AstTerm): String {
            // A call or a value can have the alias head's shape.
            val alias = node(input) as? ElixirAst.Alias ?: throw Stop(Expansion.Unported(node(input)))

            return when (val expansion = aliasExpansion(alias, env, level)) {
                is AliasExpansion.Aliased -> expansion.module
                is AliasExpansion.Unaliased -> "false"
                null -> throw Stop(Expansion.Unported(alias))
            }
        }

        /** `import_meta/5`. */
        override fun importMeta(input: AstTerm, meta: Keywords<ElixirAst>, name: String, arity: Int): Keywords<ElixirAst> {
            val node = node(input)
            val everyArity = QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level)
            val keepsImports = QUOTE_KEEPS_IMPORTS_META.isSufficient(level)
            val named = meta.any { it.first == if (keepsImports) "imports" else "import" }

            // Until `imports:` is the key checked, a node with `import:` has the check's `false` written as its imports.
            if (named && everyArity && !keepsImports) {
                return keystore(keystore(meta, "context", contextNode()), "imports", s.atom("false"))
            }

            val imports = when {
                // Metadata that already names its imports keeps them.
                named -> emptyList()
                everyArity ->
                    when (val found = findImports(name, env)) {
                        is NameImports.Ambiguous -> throw Stop(Expansion.Error("ambiguous_call", node))
                        is NameImports.Found -> found.imports.also { if (it.isNotEmpty()) traceImportQuoted(node, name, it) }
                    }
                else -> listOfNotNull(findImport(node, name, arity)?.let { arity to it })
            }

            if (imports.isNotEmpty()) {
                val key = importKey(level, imports)

                return keystore(keystore(meta, "context", contextNode()), key.name, ParsedMeta(columns = false, lineOffset).code(s, key.value))
            }

            val ambiguousOp = meta.firstOrNull { it.first == "ambiguous_op" }?.second

            return if (arity == 1 && (ambiguousOp as? ElixirAst.Literal.Atom)?.name == "nil") {
                keystore(meta, "ambiguous_op", contextNode())
            } else {
                meta
            }
        }

        override fun captureMeta(input: AstTerm, meta: Keywords<ElixirAst>, name: String, arity: Int): Keywords<ElixirAst> {
            val receiver = findImport(node(input), name, arity)

            return when {
                receiver == null -> meta
                QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level) ->
                    keystore(
                        keystore(meta, "imports", s.list(s.tuple(s.integer(arity.toBigInteger()), s.atom(receiver)))),
                        "context",
                        contextNode(),
                    )
                else -> keystore(keystore(meta, "import", s.atom(receiver)), "context", contextNode())
            }
        }

        /** `generated/2` of `keep/2`, and `line/2` without a file. */
        override fun kept(meta: Keywords<ElixirAst>): ElixirAst {
            val kept = keep(meta)

            return s.keywords(if (generated) listOf("generated" to s.atom("true")) + kept else kept)
        }

        /** `trace_import_quoted/4`: one event for each module, in the order [imports] first name it. */
        private fun traceImportQuoted(node: ElixirAst, name: String, imports: List<Pair<Int, String>>) =
            imports.groupBy({ it.second }, { it.first }).forEach { (module, arities) ->
                observer.quotedImport(node, QuotedImportKind.IMPORTED_QUOTED, module, name, arities)
            }

        /** `elixir_dispatch:find_import/4`, raising at [node] for an ambiguous import. */
        private fun findImport(node: ElixirAst, name: String, arity: Int): String? =
            when (val match = findImportByNameArity(node.meta, name, arity, emptyList(), env, level)) {
                is ImportMatch.Function -> match.receiver.also { receiver ->
                    val (module, inlined) = inline(receiver, name, arity, level) ?: (receiver to name)

                    observer.quotedImport(node, QuotedImportKind.IMPORTED_FUNCTION, module, inlined, listOf(arity))
                }
                is ImportMatch.Macro -> match.receiver.also { receiver ->
                    observer.quotedImport(node, QuotedImportKind.IMPORTED_MACRO, receiver, name, listOf(arity))
                }
                is ImportMatch.Ambiguous -> throw Stop(Expansion.Error("ambiguous_call", node))
                // `find_import/4` answers `false` for a recorded import.
                ImportMatch.None, is ImportMatch.Quoted -> null
                ImportMatch.Unreadable -> throw Stop(Expansion.Unported(node))
            }

        /** `keep/2`, and `line/2` without a file. */
        private fun keep(meta: Keywords<ElixirAst>): Keywords<ElixirAst> {
            val path = when (file) {
                is Option.Static -> file.value?.let { ElixirAst.Literal.Binary(s.meta(), it) }
                is Option.Dynamic -> s.variable("file")
            } ?: return when (line) {
                Line.Keep -> meta
                Line.Drop -> keydelete(meta, "line")
                is Line.At, Line.Var -> keystore(meta, "line", lineNode())
            }
            val readsLine = QUOTE_KEEP_READS_LINE_OPTION.isSufficient(level)

            fun kept(line: ElixirAst) = "keep" to s.tuple(path, line)

            return when {
                !readsLine || line == Line.Keep -> {
                    val index = meta.indexOfFirst { it.first == "line" }

                    if (index >= 0) {
                        listOf(kept(meta[index].second)) + meta.filterIndexed { i, _ -> i != index }
                    } else {
                        listOf(kept(s.integer(BigInteger.ZERO))) + meta
                    }
                }
                line == Line.Drop -> listOf(kept(s.integer(BigInteger.ZERO))) + keydelete(meta, "line")
                else -> listOf(kept(lineNode())) + keydelete(meta, "line")
            }
        }

        /** The integer or the prelude's variable that [line] puts in place of each node's line. */
        private fun lineNode(): ElixirAst =
            when (line) {
                is Line.At -> s.integer(line.line)
                Line.Var -> s.variable("line")
                Line.Keep, Line.Drop -> error("$line puts no line in place")
            }

        /** The node [input] is: a `quote`'s body holds nodes only. */
        private fun node(input: AstTerm): ElixirAst = (input as AstTerm.Node).node
    }

    /** The node of a `def` [head] that `annotate_def/2` marks with `context:`, as [definitionHead] names it. */
    fun annotatedDefinitionHead(head: ElixirAst, level: ElixirLanguageLevel): ElixirAst? {
        val view = AstEscapeView(Synthetic(head.meta), ParsedMeta(columns = false))

        return definitionHead(view, AstTerm.Node(head), level)?.let { (it as AstTerm.Node).node }
    }
}

/** `Path.split/1`: an absolute path's root, then each name. */
private fun splitPath(path: String): List<String> =
    listOfNotNull("/".takeIf { path.startsWith("/") }) + path.split("/").filter(String::isNotEmpty)
