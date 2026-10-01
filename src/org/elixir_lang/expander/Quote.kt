package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTED_DEF_CONTEXT_SKIPS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_BINDING_META_DROPS_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEP_READS_LINE_OPTION
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_META_DROPS_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_VALIDATED_BY_UNQUOTE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger
import java.util.IdentityHashMap

/** Metadata as a keyword list, each value an expression. */
private typealias Keywords = List<Pair<String, ElixirAst>>

/**
 * The `quote` clauses of `elixir_expand`, and the part of `elixir_quote` they reach: the quoted expression is built as
 * the escaped expression Elixir builds, an [ElixirAst] of tuples, lists and literals, and then expanded.
 */
internal object Quote {
    /** `{quote, Meta, [Opts]}`: the `do:` taken out of [call]'s only argument, a list, into a second one. */
    fun expandKeywords(call: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
        withDoArgument(call)?.let { Expander.expand(it, state, env, run) } ?: Expansion.Error("missing_option", call)

    /** `{quote, Meta, [Opts, Do]}` where `Do` is a list. */
    fun expand(call: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
        when (val built = build(call, state, env, run, 0)) {
            is Built.Stopped -> built.expansion
            is Built.Escaped -> Expander.expand(built.quoted, built.state, built.env, run)
        }

    /**
     * The escaped expression [call], a `quote` of either form, builds from [state] and [env], with its `bind_quoted`
     * bindings: `null` where building it raises or reaches what isn't ported. [lineOffset] is added to each line read
     * from a node's metadata.
     */
    fun escaped(call: ElixirAst.Call, state: ExState, env: Env, run: Run, lineOffset: Int = 0): ElixirAst? {
        val arguments = call.arguments ?: return null
        val body = when (arguments.size) {
            1 if arguments.single() is ElixirAst.ListNode -> withDoArgument(call) ?: return null
            2 if arguments[1] is ElixirAst.ListNode -> call
            else -> return null
        }

        return (build(body, state, env, run, lineOffset) as? Built.Escaped)?.block
    }

    /** `lists:keytake(do, 1, Opts)`, as the keyword clause expands `{quote, Meta, [DoOpts, [{do, Do}]]}`. */
    private fun withDoArgument(call: ElixirAst.Call): ElixirAst.Call? {
        val opts = call.arguments!!.single() as ElixirAst.ListNode
        val index = opts.elements.indexOfFirst { keyOf(it) == "do" }.takeIf { it >= 0 } ?: return null
        val rest = opts.elements.filterIndexed { i, _ -> i != index }

        return ElixirAst.Call(
            call.meta,
            call.callee,
            listOf(ElixirAst.ListNode(opts.meta, rest), ElixirAst.ListNode(opts.meta, listOf(opts.elements[index]))),
        )
    }

    private sealed interface Built {
        class Stopped(val expansion: Expansion) : Built

        /**
         * @property quoted what `elixir_quote:quote/2` gives, which is expanded
         * @property block [quoted] after the `bind_quoted` bindings
         */
        class Escaped(val quoted: ElixirAst, val block: ElixirAst, val state: ExState, val env: Env) : Built
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
        val bindQuoted = options["bind_quoted"]?.let { bindings(call, it, env, level) }
        val unquote = options["unquote"]?.let(::boolean) ?: Option.Static(bindQuoted == null)
        val generated = options["generated"]?.let(::boolean) ?: Option.Static(false)

        if (env.context != Env.Context.NONE && QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES.isSufficient(level)) {
            when (unquote) {
                is Option.Static ->
                    if (unquote.value && hasUnquotes(exprs, 0)) {
                        throw Stop(Expansion.Error("quote_in_pattern_with_unquote", call))
                    }
                // `andalso` raises `badarg` on a value that isn't a boolean.
                is Option.Dynamic -> throw Stop(Expansion.Unported(call))
            }
        }

        val line = options["line"]?.let { value ->
            when (val shape = expandedShape(value)) {
                is ElixirAst.Literal.Integer -> Option.Static(Line.At(shape.value))
                is ElixirAst.Literal.Atom if shape.name == "true" -> Option.Static(Line.Keep)
                is ElixirAst.Literal.Atom if shape.name == "false" -> Option.Static(Line.Drop)
                else -> Option.Dynamic(value)
            }
        } ?: Option.Static(Line.Drop)
        val file = options["file"]?.let { value ->
            when (val shape = expandedShape(value)) {
                is ElixirAst.Literal.Binary -> Option.Static(shape.bytes)
                is ElixirAst.Literal.Atom if shape.name == "nil" -> Option.Static(null)
                else -> Option.Dynamic(value)
            }
        } ?: Option.Static(null)
        val context = options["context"]?.let { value ->
            when (val atom = atomValue(value, env, level)) {
                null, "nil" -> Option.Dynamic(value)
                else -> Option.Static(atom)
            }
        } ?: Option.Static(env.module ?: "Elixir")

        if (unquote is Option.Dynamic || generated is Option.Dynamic) {
            throw Stop(Expansion.Error("quote_invalid_runtime_option", call))
        }

        // The prelude is built line, file, context, each in front, so its first call validates the last of them.
        listOf("context" to context, "file" to file, "line" to line).firstOrNull { it.second is Option.Dynamic }?.let {
            (key, option) ->
            val synthetic = Synthetic(call.meta)

            throw Stop(
                Expansion.Unported(
                    synthetic.remoteCall(
                        call.meta,
                        "elixir_quote",
                        "validate_runtime",
                        listOf(synthetic.atom(key), (option as Option.Dynamic).value),
                    )
                )
            )
        }

        val q = Quoting(
            line = (line as Option.Static).value,
            file = (file as Option.Static).value,
            context = (context as Option.Static).value,
            unquote = (unquote as Option.Static).value,
            generated = (generated as Option.Static).value,
            env = expandedOpts.env,
            level = level,
            synthetic = Synthetic(call.meta),
            lineOffset = lineOffset,
        )

        if (q.unquote && isCall(exprs, "unquote_splicing", 1)) {
            throw Stop(Expansion.Error("quote_unquote_splicing", call))
        }

        val quoted = q.doQuote(exprs)
        val block = if (bindQuoted.isNullOrEmpty()) {
            quoted
        } else {
            val s = q.synthetic
            val bindingMeta = s.keywords(q.sourceMeta(call.meta, QUOTE_BINDING_META_DROPS_COLUMN.isSufficient(level)))
            val bindings = bindQuoted.map { (key, value) ->
                s.tuple(
                    s.atom("="),
                    s.list(),
                    s.list(s.tuple(s.atom(key), bindingMeta, s.atom(q.context)), expandedValue(value, env, level)),
                )
            }

            s.tuple(s.atom("__block__"), s.list(), s.list(bindings + quoted))
        }

        return Built.Escaped(quoted, block, expandedOpts.state, expandedOpts.env)
    }

    /** An option's value: known while expanding, or only at run time. */
    private sealed interface Option<out T> {
        data class Static<T>(val value: T) : Option<T>

        /** @property value the option's expression, which `elixir_quote:validate_runtime/2` is given */
        data class Dynamic(val value: ElixirAst) : Option<Nothing>
    }

    /** `line:`: `true` keeps each node's own line, `false` drops it, and an integer replaces it. */
    private sealed interface Line {
        data object Keep : Line

        data object Drop : Line

        data class At(val line: BigInteger) : Line
    }

    private fun boolean(value: ElixirAst): Option<Boolean> =
        when ((expandedShape(value) as? ElixirAst.Literal.Atom)?.name) {
            "true" -> Option.Static(true)
            "false" -> Option.Static(false)
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
        val keyed = list?.let { Term.List(keys.map { key -> Term.Pair(key?.let(Term::Atom) ?: Term.Other, Term.Other) }) }

        Import.optionsError(keyed ?: Term.Other, QUOTE_OPTIONS)?.let { throw Stop(Expansion.Error(it, call)) }

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

        // Any `location:` is `:keep`, which reads `E.file`, or a `case_clause` crash.
        if (readAsOption || "location" in options) throw Stop(Expansion.Unported(call))

        return options
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

    /** `elixir_quote:has_unquotes/2`. */
    private fun hasUnquotes(node: ElixirAst, level: Int): Boolean =
        when {
            isCall(node, "quote", 1) -> hasUnquotes((node as ElixirAst.Call).arguments!!.single(), level + 1)
            isCall(node, "quote", 2) -> {
                val (opts, child) = (node as ElixirAst.Call).arguments!!

                !disablesUnquote(opts) && hasUnquotes(child, level + 1)
            }
            isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1) ->
                level == 0 || hasUnquotes((node as ElixirAst.Call).arguments!!.single(), level - 1)
            node is ElixirAst.Call && node.arguments?.size == 1 && isUnquoteDot(node.callee) -> true
            node is ElixirAst.Call ->
                node.arguments != null &&
                    (hasUnquotes(node.callee, level) || node.arguments.any { hasUnquotes(it, level) })
            node is ElixirAst.Alias -> node.segments.any { hasUnquotes(it, level) }
            node is ElixirAst.Block -> node.expressions.any { hasUnquotes(it, level) }
            node is ElixirAst.Tuple -> node.elements.any { hasUnquotes(it, level) }
            node is ElixirAst.ListNode -> node.elements.any { hasUnquotes(it, level) }
            else -> false
        }

    /** `disables_unquote/1`: `unquote: false` or any `bind_quoted:` in a list of options. */
    private fun disablesUnquote(opts: ElixirAst): Boolean =
        (opts as? ElixirAst.ListNode)?.elements.orEmpty().any { element ->
            val pair = (element as? ElixirAst.Tuple)?.takeIf { it.elements.size == 2 }
            val key = (pair?.elements?.get(0) as? ElixirAst.Literal.Atom)?.name

            key == "bind_quoted" || key == "unquote" && (pair.elements[1] as? ElixirAst.Literal.Atom)?.name == "false"
        }

    /** `{'.', _, [_, unquote]}`. */
    private fun isUnquoteDot(node: ElixirAst): Boolean =
        isCall(node, ".", 2) && ((node as ElixirAst.Call).arguments!![1] as? ElixirAst.Literal.Atom)?.name == "unquote"

    /** The atom a tuple's first element is, for [node]'s term, or `null` when its term isn't such a tuple. */
    private fun firstAtom(node: ElixirAst): String? =
        when (node) {
            is ElixirAst.Call -> (node.callee as? ElixirAst.Literal.Atom)?.name
            is ElixirAst.Alias -> "__aliases__"
            is ElixirAst.Block -> "__block__"
            is ElixirAst.Tuple -> if (node.elements.size == 2) (node.elements[0] as? ElixirAst.Literal.Atom)?.name else "{}"
            is ElixirAst.Literal, is ElixirAst.ListNode, is ElixirAst.Placeholder -> null
        }

    /** The nodes Elixir builds, all at the `quote`'s position. */
    private class Synthetic(private val at: Meta) {
        fun meta(keys: List<Meta.Key> = emptyList()) = Meta(at.origin, at.start, at.end, keys)

        fun atom(name: String) = ElixirAst.Literal.Atom(meta(), name)

        fun integer(value: BigInteger) = ElixirAst.Literal.Integer(meta(), value)

        fun list(vararg elements: ElixirAst) = list(elements.toList())

        fun list(elements: List<ElixirAst>) = ElixirAst.ListNode(meta(), elements)

        fun tuple(vararg elements: ElixirAst) = ElixirAst.Tuple(meta(), elements.toList())

        fun keywords(pairs: List<Pair<String, ElixirAst>>) = list(pairs.map { (key, value) -> tuple(atom(key), value) })

        /** `{{'.', Meta, [Module, Function]}, Meta, Args}`, with [source]'s keys. */
        fun remoteCall(source: Meta, module: String, function: String, args: List<ElixirAst>) =
            ElixirAst.Call(
                meta(source.keys),
                ElixirAst.Call(meta(source.keys), atom("."), listOf(atom(module), atom(function))),
                args,
            )

        /** [node], a literal, as a node of its own. */
        fun literal(node: ElixirAst.Literal): ElixirAst =
            when (node) {
                is ElixirAst.Literal.Atom -> atom(node.name)
                is ElixirAst.Literal.Integer -> integer(node.value)
                is ElixirAst.Literal.Float -> ElixirAst.Literal.Float(meta(), node.value)
                is ElixirAst.Literal.Binary -> ElixirAst.Literal.Binary(meta(), node.bytes)
            }
    }

    private val QUOTE_OPTIONS = listOf("context", "location", "line", "file", "unquote", "bind_quoted", "generated")

    private val DEFINITIONS = setOf("def", "defp", "defmacro", "defmacrop", "@")

    private val DIRECTIVES = setOf("import", "alias", "require")

    /** `#elixir_quote{op = quote}`, with `aliases_hygiene` and `imports_hygiene` both [env]. */
    private class Quoting(
        val line: Line,
        val file: ByteArray?,
        val context: String,
        val unquote: Boolean,
        val generated: Boolean,
        val env: Env,
        val level: ElixirLanguageLevel,
        val synthetic: Synthetic,
        val lineOffset: Int,
        /** The metadata `annotate/2` gave a node ahead of its own quoting. */
        private val annotated: IdentityHashMap<ElixirAst, Keywords> = IdentityHashMap(),
    ) {
        private val s = synthetic

        private fun withoutUnquote() =
            Quoting(line, file, context, false, generated, env, level, synthetic, lineOffset, annotated)

        /** [meta]'s keys as `Code.compile_string` parses them at [level], with [lineOffset] added to each line. */
        fun sourceMeta(meta: Meta, dropColumn: Boolean = false): Keywords =
            keywords(meta.keys, COMPILER_PARSES_COLUMNS.isSufficient(level) && !dropColumn)

        private fun keywords(keys: List<Meta.Key>, columns: Boolean): Keywords =
            keys.flatMap { key ->
                when (key) {
                    is Meta.Key.Location -> listOfNotNull(
                        "line" to s.integer((key.position.line + lineOffset).toBigInteger()),
                        ("column" to s.integer(key.position.column.toBigInteger())).takeIf { columns },
                    )
                    is Meta.Key.Entry -> if (key.tokenMetadata) emptyList() else listOf(key.name to value(key.value, columns))
                }
            }

        private fun value(value: Meta.Value, columns: Boolean): ElixirAst =
            when (value) {
                is Meta.Value.Atom -> s.atom(value.name)
                is Meta.Value.Integer -> s.integer(value.value.toBigInteger())
                is Meta.Value.Binary -> ElixirAst.Literal.Binary(s.meta(), value.text.toByteArray(Charsets.UTF_8))
                is Meta.Value.Keywords -> s.keywords(keywords(value.keys, columns))
            }

        private fun metaOf(node: ElixirAst): Keywords = annotated.remove(node) ?: sourceMeta(node.meta)

        /** `do_quote/2`. */
        fun doQuote(node: ElixirAst): ElixirAst =
            when {
                isCall(node, "quote", 1) || isCall(node, "quote", 2) -> {
                    val call = node as ElixirAst.Call
                    val args = call.arguments!!
                    val tOpts = if (args.size == 2) doQuote(args[0]) else null
                    val tArg = withoutUnquote().doQuote(args.last())
                    val meta = keystore(metaOf(call), "context", s.atom(context))

                    s.tuple(s.atom("quote"), meta(meta), s.list(listOfNotNull(tOpts, tArg)))
                }
                unquote && isCall(node, "unquote", 1) -> unquoted(node.meta, (node as ElixirAst.Call).arguments!!.single())
                node is ElixirAst.Alias && (node.segments.first() as? ElixirAst.Literal.Atom)?.name.let {
                    it != null && it != "Elixir"
                } -> {
                    val annotation = aliasedModule(node) ?: "false"
                    val meta = keystore(keydelete(metaOf(node), "counter"), "alias", s.atom(annotation))

                    s.tuple(s.atom("__aliases__"), meta(meta), doQuoteList(node.segments))
                }
                node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom && node.arguments == null -> {
                    val name = node.callee.name

                    s.tuple(s.atom(name), meta(importMeta(node, metaOf(node), name, 0)), s.atom(context))
                }
                unquote && node is ElixirAst.Call && node.callee is ElixirAst.Call &&
                    node.callee.arguments?.size == 1 &&
                    isUnquoteDot(node.callee.callee) -> {
                    val unquoteCall = node.callee
                    val dot = unquoteCall.callee as ElixirAst.Call

                    doQuoteCall(dot, unquoteCall.arguments.single(), node.arguments)
                }
                unquote && node is ElixirAst.Call && node.arguments?.size == 1 && isUnquoteDot(node.callee) ->
                    doQuoteCall(node.callee as ElixirAst.Call, node.arguments.single(), null)
                isImportedCapture(node) -> {
                    val capture = node as ElixirAst.Call
                    val (function, arity) = (capture.arguments!!.single() as ElixirAst.Call).arguments!!
                    val name = ((function as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name
                    val receiver = findImport(capture, name, (arity as ElixirAst.Literal.Integer).value.toInt())
                    val meta = metaOf(capture).let { meta ->
                        when {
                            receiver == null -> meta
                            QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level) ->
                                keystore(
                                    keystore(meta, "imports", s.list(s.tuple(s.integer(arity.value), s.atom(receiver)))),
                                    "context",
                                    s.atom(context),
                                )
                            else -> keystore(keystore(meta, "import", s.atom(receiver)), "context", s.atom(context))
                        }
                    }

                    s.tuple(s.atom("&"), meta(meta), doQuoteList(capture.arguments))
                }
                node is ElixirAst.Tuple && node.elements.size == 2 -> {
                    val (left, right) = node.elements

                    if (unquote && (firstAtom(left) == "unquote_splicing" || firstAtom(right) == "unquote_splicing")) {
                        namedTuple(node, "{}", emptyList(), node.elements)
                    } else {
                        val tLeft = doQuote(left)

                        s.tuple(tLeft, doQuote(right))
                    }
                }
                node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom ->
                    namedTuple(node, node.callee.name, metaOf(node), node.arguments!!)
                node is ElixirAst.Alias -> namedTuple(node, "__aliases__", metaOf(node), node.segments)
                node is ElixirAst.Block -> namedTuple(node, "__block__", metaOf(node), node.expressions)
                node is ElixirAst.Tuple -> namedTuple(node, "{}", metaOf(node), node.elements)
                node is ElixirAst.Placeholder -> throw Stop(Expansion.Unported(node))
                node is ElixirAst.Call -> {
                    val args = node.arguments ?: error("a call whose callee isn't an atom always has arguments")
                    val meta = annotate(node.callee, metaOf(node), args)
                    val tLeft = doQuote(node.callee)

                    s.tuple(tLeft, meta(meta), doQuoteList(args))
                }
                node is ElixirAst.ListNode -> doQuoteList(node.elements)
                else -> s.literal(node as ElixirAst.Literal)
            }

        /** The import head of `do_quote/2`: `{Name, Meta, Args}`. */
        private fun namedTuple(node: ElixirAst, name: String, meta: Keywords, args: List<ElixirAst>): ElixirAst {
            val importMeta = importMeta(node, meta, name, args.size)
            val annotatedMeta = annotate(s.atom(name), importMeta, args)

            return s.tuple(s.atom(name), meta(annotatedMeta), doQuoteList(args))
        }

        /**
         * `annotate/2` for a node whose first element is [target] and whose arguments are [args]: its own metadata, and
         * its first argument's, recorded for when that is quoted.
         */
        private fun annotate(target: ElixirAst, meta: Keywords, args: List<ElixirAst>): Keywords {
            val name = (target as? ElixirAst.Literal.Atom)?.name
                ?: (target as? ElixirAst.Call)?.takeIf { isCall(it, ".", 2) }
                    ?.let { (it.arguments!![1] as? ElixirAst.Literal.Atom)?.name }
                    ?.takeIf { it in DEFINITIONS }

            return when {
                name in DEFINITIONS && args.isNotEmpty() -> {
                    annotateDefinition(args.first())
                    meta
                }
                target is ElixirAst.Literal.Atom && name in DIRECTIVES && args.isNotEmpty() ->
                    keystore(keydelete(meta, "counter"), "context", s.atom(context))
                else -> meta
            }
        }

        /** `annotate_def/2`, and before it the head that took any tuple, a guard's `when` included. */
        private fun annotateDefinition(head: ElixirAst) {
            if (QUOTED_DEF_CONTEXT_SKIPS_GUARD.isSufficient(level) && isCall(head, "when", 2)) {
                annotateDefinition((head as ElixirAst.Call).arguments!![0])
            } else if (head.hasMetadata()) {
                annotated[head] = keystore(metaOf(head), "context", s.atom(context))
            }
        }

        /** The unquote head of `do_quote/2`: [expr] itself, or from 1.18 passed to a validation call. */
        private fun unquoted(meta: Meta, expr: ElixirAst): ElixirAst =
            when {
                UNQUOTE_VALIDATED_BY_UNQUOTE.isSufficient(level) -> s.remoteCall(meta, "elixir_quote", "unquote", listOf(expr))
                UNQUOTE_SHALLOW_VALIDATED.isSufficient(level) ->
                    s.remoteCall(meta, "elixir_quote", "shallow_validate_ast", listOf(expr))
                else -> expr
            }

        /** `do_quote_call/5` for `Left.unquote(Expr)`, with [args] when it is called. */
        private fun doQuoteCall(dot: ElixirAst.Call, expr: ElixirAst, args: List<ElixirAst>?): ElixirAst {
            val left = dot.arguments!![0]
            val tLeft = doQuote(left)
            val tUnquote = unquoted(dot.meta, expr)
            val tArgs = args?.let(::doQuoteList) ?: s.atom("nil")

            return s.remoteCall(
                dot.meta,
                "elixir_quote",
                "dot",
                listOf(meta(sourceMeta(dot.meta)), tLeft, tUnquote, tArgs, s.atom(context)),
            )
        }

        /** The list heads of `do_quote/2`. */
        private fun doQuoteList(elements: List<ElixirAst>): ElixirAst =
            when {
                elements.isEmpty() -> s.list()
                !unquote -> s.list(elements.map(::doQuote))
                else -> doQuoteTail(elements.asReversed())
            }

        /** `do_quote_tail/2` over the elements, last first. */
        private fun doQuoteTail(reversed: List<ElixirAst>): ElixirAst {
            val last = reversed.first()
            val spliced = (last as? ElixirAst.Call)?.takeIf { isCall(it, "|", 2) }?.arguments
                ?.takeIf { isCall(it[0], "unquote_splicing", 1) }

            return if (spliced != null) {
                val tt = doQuoteSplice(reversed.drop(1))
                val tr = doQuote(spliced[1])
                val left = (spliced[0] as ElixirAst.Call).arguments!!.single()

                s.remoteCall(last.meta, "elixir_quote", "tail_list", listOf(left, tr, tt))
            } else {
                doQuoteSplice(reversed)
            }
        }

        /** `do_quote_splice/4` over the elements, last first. */
        private fun doQuoteSplice(reversed: List<ElixirAst>): ElixirAst {
            var buffer = ArrayDeque<ElixirAst>()
            var acc: ElixirAst? = null

            for (element in reversed) {
                if (isCall(element, "unquote_splicing", 1)) {
                    val expr = (element as ElixirAst.Call).arguments!!.single()

                    acc = s.remoteCall(element.meta, "elixir_quote", "list", listOf(expr, listConcat(buffer, acc)))
                    buffer = ArrayDeque()
                } else {
                    buffer.addFirst(doQuote(element))
                }
            }

            return listConcat(buffer, acc)
        }

        /** `do_list_concat/2`, where a `null` [right] is `[]`. */
        private fun listConcat(left: List<ElixirAst>, right: ElixirAst?): ElixirAst =
            when {
                right == null -> s.list(left)
                left.isEmpty() -> right
                else -> s.remoteCall(s.meta(), "erlang", "++", listOf(s.list(left), right))
            }

        /** `import_meta/5`. */
        private fun importMeta(node: ElixirAst, meta: Keywords, name: String, arity: Int): Keywords {
            if (QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level)) {
                when (val imports = findImports(name, env)) {
                    is NameImports.Ambiguous -> throw Stop(Expansion.Error("ambiguous_call", node))
                    is NameImports.Found ->
                        if (imports.imports.isNotEmpty()) {
                            val list = s.list(imports.imports.map { (a, module) -> s.tuple(s.integer(a.toBigInteger()), s.atom(module)) })

                            return keystore(keystore(meta, "context", s.atom(context)), "imports", list)
                        }
                }
            } else {
                findImport(node, name, arity)?.let { receiver ->
                    return keystore(keystore(meta, "context", s.atom(context)), "import", s.atom(receiver))
                }
            }

            val ambiguousOp = meta.firstOrNull { it.first == "ambiguous_op" }?.second

            return if (arity == 1 && (ambiguousOp as? ElixirAst.Literal.Atom)?.name == "nil") {
                keystore(meta, "ambiguous_op", s.atom(context))
            } else {
                meta
            }
        }

        /** `elixir_dispatch:find_import/4`, raising at [node] for an ambiguous import. */
        private fun findImport(node: ElixirAst, name: String, arity: Int): String? =
            when (val match = findImportByNameArity(name, arity, emptyList(), env)) {
                is ImportMatch.Function -> match.receiver
                is ImportMatch.Macro -> match.receiver
                is ImportMatch.Ambiguous -> throw Stop(Expansion.Error("ambiguous_call", node))
                ImportMatch.None -> null
            }

        /** `elixir_aliases:expand/4` of [node]: the module an alias names, or `null` when no alias applies. */
        private fun aliasedModule(node: ElixirAst.Alias): String? {
            val head = (node.segments.first() as ElixirAst.Literal.Atom).name

            if (env.aliases.none { it.alias == "Elixir.$head" }) return null

            return aliasesModule(node, env, level) ?: throw Stop(Expansion.Unported(node))
        }

        /** `meta/2`. */
        private fun meta(meta: Keywords): ElixirAst {
            val kept = keep(if (QUOTE_META_DROPS_COLUMN.isSufficient(level)) keydelete(meta, "column") else meta)

            return s.keywords(if (generated) listOf("generated" to s.atom("true")) + kept else kept)
        }

        /** `keep/2`, and `line/2` without a file. */
        private fun keep(meta: Keywords): Keywords {
            val file = file ?: return when (line) {
                Line.Keep -> meta
                Line.Drop -> keydelete(meta, "line")
                is Line.At -> keystore(meta, "line", s.integer(line.line))
            }
            val path = ElixirAst.Literal.Binary(s.meta(), file)
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
                else -> listOf(kept(s.integer((line as Line.At).line))) + keydelete(meta, "line")
            }
        }
    }

    /** `{'&', Meta, [{'/', _, [{F, _, C}, A]}]}` with `F` and `C` atoms and `A` an integer. */
    private fun isImportedCapture(node: ElixirAst): Boolean {
        val slash = (node as? ElixirAst.Call)?.takeIf { isCall(it, "&", 1) }?.arguments?.single() ?: return false
        if (!isCall(slash, "/", 2)) return false

        val (function, arity) = (slash as ElixirAst.Call).arguments!!

        return isVariable(function) && arity is ElixirAst.Literal.Integer
    }

    /** `lists:keystore/4`, as `elixir_quote`'s `keystore/3` calls it with a value that isn't `nil`. */
    private fun keystore(meta: Keywords, key: String, value: ElixirAst): Keywords {
        val index = meta.indexOfFirst { it.first == key }

        return if (index < 0) meta + (key to value) else meta.toMutableList().apply { set(index, key to value) }
    }

    /** `lists:keydelete/3`. */
    private fun keydelete(meta: Keywords, key: String): Keywords {
        val index = meta.indexOfFirst { it.first == key }

        return if (index < 0) meta else meta.filterIndexed { i, _ -> i != index }
    }
}
