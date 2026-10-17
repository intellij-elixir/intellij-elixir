package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangPort
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_BINARY_SPEC_IS_A_NAME
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_QUOTED_APART
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_SORTED
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPE_HEADS_NEED_LIST_META
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPE_PRUNE_KEEPS_DELIMITER
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_EXTENSION_META_ESCAPED
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_META_DROPS_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTED_DEF_CONTEXT_SKIPS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_CALL_META_KEPT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/** What a term is, as the heads of `elixir_quote`'s escape match on it, with its parts as the input [I] holds them. */
internal sealed interface Shape<out I> {
    data class Atom(val name: String) : Shape<Nothing>

    data class Integer(val value: BigInteger) : Shape<Nothing>

    data class Float(val value: Double) : Shape<Nothing>

    /** [bytes], the last of which holds only its leading `8 - padBits` bits. */
    class Bitstring(val bytes: ByteArray, val padBits: Int) : Shape<Nothing>

    /** @property at the node the tuple is, where the escape's output for it is placed; `null` for a term */
    class Tuple<I>(val elements: kotlin.collections.List<I>, val at: Meta? = null) : Shape<I>

    /** @property tail what an improper list ends in, `null` for `[]` */
    class List<I>(val elements: kotlin.collections.List<I>, val tail: I? = null) : Shape<I>

    class Map<I>(val entries: kotlin.collections.List<MapEntry<I>>) : Shape<I>

    class Pid(val pid: OtpErlangPid) : Shape<Nothing>

    data class ExternalFun(val module: String, val function: String, val arity: Long) : Shape<Nothing>

    /** A closure, a reference or a port, which `bad_escape/1` refuses. */
    data object Unescapable : Shape<Nothing>

    /** A value only a compiler builds, put in the output as it is. */
    data object Verbatim : Shape<Nothing>

    /** A term the input doesn't know, at the node [at] where it has one. */
    class Unknown(val at: ElixirAst? = null) : Shape<Nothing>
}

/** A map's entry, with the terms its key and value are, which order it. */
internal class MapEntry<I>(val key: I, val value: I, val keyTerm: OtpErlangObject, val valueTerm: OtpErlangObject)

/** Reads an input as the [Shape] of the term it stands for. */
internal fun interface EscapeView<I> {
    fun shape(input: I): Shape<I>
}

/** Builds the escape's output, the code that evaluates to the escaped term. A builder decides nothing. */
internal interface EscapeBuilder<O> {
    fun atom(name: String): O

    fun integer(value: BigInteger): O

    fun float(value: Double): O

    fun binary(bytes: ByteArray): O

    fun list(elements: List<O>): O

    /** A tuple of [elements]: a pair is itself, any other size `{'{}', [], Elements}`, placed at [at]. */
    fun tuple(elements: List<O>, at: Meta? = null): O

    /** `{'{}', [], Elements}`, where [elements] builds the list; `null` where the output can't hold it. */
    fun tupleOf(elements: O): O?

    /** `{Callee, Keys, Args}`, or `{Callee, Keys, nil}` for `null` [args], each key's value an atom. */
    fun call(callee: O, args: List<O>?, keys: List<Pair<String, String>> = emptyList()): O

    /**
     * `{{'.', Meta, [Module, Function]}, Meta, Args}`, where `Meta` is the term [meta], or the metadata of the node
     * [at]; `[]` for neither.
     */
    fun remoteCall(module: String, function: String, args: List<O>, meta: O? = null, at: Meta? = null): O

    /** [pid] itself; `null` where the output can't hold it. */
    fun pid(pid: OtpErlangPid): O?
}

/** What [QuoteEscape.escape] gives. */
internal sealed interface EscapeResult<out O> {
    data class Built<O>(val value: O) : EscapeResult<O>

    /** Elixir raises [exception], the text of its module's atom, such as `Elixir.ArgumentError`. */
    data class Raised(val exception: String) : EscapeResult<Nothing>

    /** The input holds a term the escape doesn't know, at the node [at] where it has one. */
    data class Declined(val at: ElixirAst?) : EscapeResult<Nothing>
}

/** `#elixir_quote.op` for an escape. */
internal sealed interface EscapeOp {
    data object Escape : EscapeOp

    /** `escape_and_prune`, which `Macro.escape(_, prune_metadata: true)` gives. */
    data object EscapeAndPrune : EscapeOp
}

/** Metadata as a keyword list, each value built. */
internal typealias Keywords<O> = List<Pair<String, O>>

/**
 * `#elixir_quote{op = quote}`: the fields and hygiene that `do_quote/2` reads only for `quote`, over the input [I] a
 * `quote`'s body is.
 */
internal interface QuoteOp<I, O> {
    /** `Q#elixir_quote.context`. */
    fun context(): O

    /** [output] as an input, for what `do_quote/2` quotes after building it. */
    fun input(output: O): I

    /** The metadata of the tuple [input]: what `annotate_def/2` gave it ahead of its own quoting, or its own. */
    fun metaOf(input: I): Keywords<O>

    /** `annotate_def/2`: `context:` stored in the metadata of [head], for when it is quoted. */
    fun annotateDefinition(head: I)

    /** `{unquote, Meta, [Expr]}` at the node [at]: [expr] as `validate` puts it in. */
    fun unquoted(at: Meta?, expr: I): O

    /** `elixir_aliases:expand/4` of the alias [input]: the module it names, or `false`. */
    fun aliased(input: I): String

    /** `import_meta/5` of [input], a call of [name] and [arity] with [meta]. */
    fun importMeta(input: I, meta: Keywords<O>, name: String, arity: Int): Keywords<O>

    /** The import head of [input], the capture `&name/arity` with [meta]: its metadata after `find_import/4`. */
    fun captureMeta(input: I, meta: Keywords<O>, name: String, arity: Int): Keywords<O>

    /** `generated/2` of `keep/2` of [meta], built. */
    fun kept(meta: Keywords<O>): O
}

/** `annotate/2`'s clause for a tuple. */
internal sealed interface Annotation<out I> {
    /** A definition's: `annotate_def/2` of its head, which gives [head] `context:`, or nothing where it is `null`. */
    class Definition<I>(val head: I?) : Annotation<I>

    /** `import`, `alias` or `require` with arguments: `counter` removed and `context` stored. */
    data object Lexical : Annotation<Nothing>

    data object None : Annotation<Nothing>
}

/** `annotate/2`'s clause for [tuple], `{Target, Meta, Args}`, as [view] reads it. */
internal fun <I> annotation(view: EscapeView<I>, tuple: Shape.Tuple<I>, level: ElixirLanguageLevel): Annotation<I> {
    val (target, _, args) = tuple.elements
    val first = (view.shape(args) as? Shape.List)?.elements?.firstOrNull() ?: return Annotation.None
    val name = (view.shape(target) as? Shape.Atom)?.name
    val dotted = (view.shape(target) as? Shape.Tuple)
        ?.takeIf { it.elements.size == 3 && (view.shape(it.elements[0]) as? Shape.Atom)?.name == "." }
        ?.let { properList(view, it.elements[2]) }
        ?.takeIf { it.size == 2 }
        ?.let { (view.shape(it[1]) as? Shape.Atom)?.name }

    return when {
        name in DEFINITIONS || dotted in DEFINITIONS -> Annotation.Definition(definitionHead(view, first, level))
        name in LEXICAL -> Annotation.Lexical
        else -> Annotation.None
    }
}

/**
 * The node of a definition's [head] that `annotate_def/2` gives `context:`: the head under a guard's `when` from
 * [QUOTED_DEF_CONTEXT_SKIPS_GUARD], before it the first `{_, _, _}`, the `when` included, or `null` where that isn't
 * one.
 */
internal fun <I> definitionHead(view: EscapeView<I>, head: I, level: ElixirLanguageLevel): I? {
    ProgressManager.checkCanceled()

    val tuple = (view.shape(head) as? Shape.Tuple)?.takeIf { it.elements.size == 3 } ?: return null
    val left = properList(view, tuple.elements[2])?.takeIf { it.size == 2 }?.first()
        ?.takeIf { QUOTED_DEF_CONTEXT_SKIPS_GUARD.isSufficient(level) && (view.shape(tuple.elements[0]) as? Shape.Atom)?.name == "when" }

    return if (left != null) definitionHead(view, left, level) else head
}

private val DEFINITIONS = setOf("def", "defp", "defmacro", "defmacrop", "@")

/** `lists:keystore/4`, as `elixir_quote`'s `keystore/3` calls it with a value that isn't `nil`. */
internal fun <O> keystore(meta: Keywords<O>, key: String, value: O): Keywords<O> {
    val index = meta.indexOfFirst { it.first == key }

    return if (index < 0) meta + (key to value) else meta.toMutableList().apply { set(index, key to value) }
}

/** `lists:keydelete/3`: [meta] without its first element whose key, as [keyOf] reads it, is [key]. */
internal fun <T> keydelete(meta: List<T>, key: String, keyOf: (T) -> String?): List<T> {
    val index = meta.indexOfFirst { keyOf(it) == key }

    return if (index < 0) meta else meta.filterIndexed { i, _ -> i != index }
}

internal fun <O> keydelete(meta: Keywords<O>, key: String): Keywords<O> = keydelete(meta, key) { it.first }

/** A list's quoted elements: the list itself, or an expression that builds it at run time. */
internal sealed interface Listed<out O> {
    class Plain<O>(val elements: List<O>) : Listed<O>

    class Expression<O>(val value: O) : Listed<O>
}

/**
 * `do_quote_tail/2`, `do_quote_splice/4` and `do_list_concat/2`, which `quote` and an escape with `unquote` share:
 * the `quote` each method is given quotes an element that isn't spliced, and [insert] puts an unquoted expression or metadata in as it is.
 */
internal class SplicedList<I, O>(
    private val view: EscapeView<I>,
    private val build: EscapeBuilder<O>,
    private val insert: (I) -> O,
) {
    /** `do_quote_tail/2` over [reversed], a list's elements last first. */
    fun tail(reversed: List<I>, quote: (I) -> O): Listed<O> {
        val bar = (view.shape(reversed.first()) as? Shape.Tuple)?.takeIf { atomOf(it, "|") }
        val barArgs = bar?.let { properList(view, it.elements[2]) }?.takeIf { it.size == 2 }
        val left = barArgs?.let { splicedExpr(it[0]) } ?: return splice(reversed, quote)
        val tt = splice(reversed.drop(1), quote)
        val tr = quote(barArgs[1])

        return Listed.Expression(runtime(bar, "tail_list", listOf(insert(left), tr, built(tt))))
    }

    /** `do_quote_splice/4` over [reversed], a list's elements last first. */
    fun splice(reversed: List<I>, quote: (I) -> O): Listed<O> {
        var buffer = ArrayDeque<O>()
        var acc: O? = null

        for (element in reversed) {
            val splicing = view.shape(element) as? Shape.Tuple
            val expr = splicing?.let(::splicedExpr)

            if (expr != null) {
                acc = runtime(splicing, "list", listOf(insert(expr), built(concat(buffer, acc))))
                buffer = ArrayDeque()
            } else {
                buffer.addFirst(quote(element))
            }
        }

        return concat(buffer, acc)
    }

    fun built(listed: Listed<O>): O =
        when (listed) {
            is Listed.Plain -> build.list(listed.elements)
            is Listed.Expression -> listed.value
        }

    /** `do_list_concat/2`, where a `null` [right] is `[]`. */
    private fun concat(left: List<O>, right: O?): Listed<O> =
        when {
            right == null -> Listed.Plain(left.toList())
            left.isEmpty() -> Listed.Expression(right)
            else -> Listed.Expression(build.remoteCall("erlang", "++", listOf(build.list(left.toList()), right)))
        }

    /** `Expr` of `{unquote_splicing, _, [Expr]}`. */
    private fun splicedExpr(input: I): I? = (view.shape(input) as? Shape.Tuple)?.let(::splicedExpr)

    private fun splicedExpr(tuple: Shape.Tuple<I>): I? =
        tuple.takeIf { atomOf(it, "unquote_splicing") }?.let { properList(view, it.elements[2]) }?.singleOrNull()

    /** `do_runtime_list/3`: a call of `elixir_quote`'s [function] with [tuple]'s metadata. */
    private fun runtime(tuple: Shape.Tuple<I>, function: String, args: List<O>): O =
        build.remoteCall("elixir_quote", function, args, insert(tuple.elements[1]), tuple.at)

    /** Whether [tuple] is `{Name, _, _}`. */
    private fun atomOf(tuple: Shape.Tuple<I>, name: String): Boolean =
        tuple.elements.size == 3 && (view.shape(tuple.elements[0]) as? Shape.Atom)?.name == name
}

/** The elements of [input] where it is a proper list. */
private fun <I> properList(view: EscapeView<I>, input: I): List<I>? =
    (view.shape(input) as? Shape.List)?.takeIf { it.tail == null }?.elements

/**
 * `elixir_quote:do_quote/2` from `escape/3`, or from `quote/2` where [quote] is given: [view] reads each input, [build]
 * writes the output, and [insert] puts an input in it as it is, as `unquote` puts its expression, or gives `null` where
 * the output can't hold it.
 */
internal class QuoteEscape<I, O> private constructor(
    private val view: EscapeView<I>,
    private val build: EscapeBuilder<O>,
    private val insert: (I) -> O?,
    private val level: ElixirLanguageLevel,
    private val exports: Exports,
    private val op: EscapeOp,
    private val quote: QuoteOp<I, O>?,
    private val unquote: Boolean,
) {
    /** `escape/3` with [op]. */
    constructor(
        view: EscapeView<I>,
        build: EscapeBuilder<O>,
        insert: (I) -> O?,
        level: ElixirLanguageLevel,
        exports: Exports,
        op: EscapeOp = EscapeOp.Escape,
        unquote: Boolean = false,
    ) : this(view, build, insert, level, exports, op, null, unquote)

    /** `quote/2`, with [quote]'s fields. */
    constructor(
        view: EscapeView<I>,
        build: EscapeBuilder<O>,
        insert: (I) -> O?,
        level: ElixirLanguageLevel,
        exports: Exports,
        quote: QuoteOp<I, O>,
        unquote: Boolean,
    ) : this(view, build, insert, level, exports, EscapeOp.Escape, quote, unquote)

    private val lists = SplicedList(view, build, ::inserted)

    fun escape(input: I): EscapeResult<O> =
        try {
            EscapeResult.Built(doQuote(input, unquote))
        } catch (raise: Raise) {
            EscapeResult.Raised(raise.exception)
        } catch (decline: Decline) {
            EscapeResult.Declined(decline.at)
        }

    private class Raise(val exception: String) : RuntimeException(null, null, false, false)

    private class Decline(val at: ElixirAst?) : RuntimeException(null, null, false, false)

    /** `do_quote/2`, its heads in Elixir's order. */
    private fun doQuote(input: I, unquote: Boolean): O {
        ProgressManager.checkCanceled()

        val shape = view.shape(input)
        val tuple = shape as? Shape.Tuple
        val q = quote

        if (tuple != null && tuple.elements.size == 3) {
            quoteHead(input, tuple, unquote)?.let { return it }
            if (unquote) unquoteHead(tuple)?.let { return it }
            if (q != null) aliasOrVariableHead(q, input, tuple, unquote)?.let { return it }
            if (unquote) unquotedCall(tuple)?.let { return it }
            if (q != null) importHead(q, input, tuple, unquote)?.let { return it }
        }

        if (tuple != null && tuple.elements.size == 2) return pair(tuple.elements[0], tuple.elements[1], tuple.at, unquote, input)

        return when {
            q == null -> doEscape(input, shape, unquote)
            tuple != null && tuple.elements.size == 3 -> quoteTuple(q, tuple, annotated(q, tuple, q.metaOf(input)), unquote)
            shape is Shape.List -> lists.built(escapeList(shape.elements, shape.tail, unquote))
            // `do_quote(Other, _) -> Other`: a literal built as it is, and a term no source writes put in as it is.
            shape is Shape.Atom || shape is Shape.Integer || shape is Shape.Float ||
                shape is Shape.Bitstring && shape.padBits == 0 || shape is Shape.Unknown -> doEscape(input, shape, unquote)
            else -> inserted(input)
        }
    }

    /** `{quote, Meta, [Arg]}` and `{quote, Meta, [Opts, Arg]}`. */
    private fun quoteHead(input: I, tuple: Shape.Tuple<I>, unquote: Boolean): O? {
        val (name, meta, args) = tuple.elements
        if (atomOf(name) != "quote" || !isHeadMeta(meta)) return null

        val arguments = properList(view, args)?.takeIf { it.size in 1..2 } ?: return null
        val tOpts = if (arguments.size == 2) doQuote(arguments[0], unquote) else null
        val tArg = doQuote(arguments.last(), unquote = false)
        val tMeta = quote?.let { q -> meta(q, keystore(q.metaOf(input), "context", q.context())) } ?: meta(meta, unquote)

        return build.tuple(listOf(build.atom("quote"), tMeta, build.list(listOfNotNull(tOpts, tArg))), tuple.at)
    }

    /** `{unquote, Meta, [Expr]}`: `Expr` as `validate` puts it in. */
    private fun unquoteHead(tuple: Shape.Tuple<I>): O? {
        val (name, meta, args) = tuple.elements
        if (atomOf(name) != "unquote" || !isHeadMeta(meta)) return null

        return properList(view, args)?.singleOrNull()?.let { unquoted(tuple.at, it) }
    }

    private fun unquoted(at: Meta?, expr: I): O = quote?.unquoted(at, expr) ?: inserted(expr)

    /**
     * The alias head, `{'__aliases__', Meta, [H | T]}` with `H` an atom other than `Elixir`, and the variable head,
     * `{Name, Meta, nil}`.
     */
    private fun aliasOrVariableHead(q: QuoteOp<I, O>, input: I, tuple: Shape.Tuple<I>, unquote: Boolean): O? {
        val (left, meta, right) = tuple.elements
        val name = atomOf(left) ?: return null
        if (view.shape(meta) !is Shape.List) return null

        val first = properList(view, right)?.firstOrNull()?.let(::atomOf)

        return when {
            name == "__aliases__" && first != null && first != "Elixir" -> {
                val keys = keystore(keydelete(q.metaOf(input), "counter"), "alias", build.atom(q.aliased(input)))

                quoteTuple(q, tuple, keys, unquote)
            }
            atomOf(right) == "nil" ->
                build.tuple(listOf(build.atom(name), meta(q, q.importMeta(input, q.metaOf(input), name, 0)), q.context()), tuple.at)
            else -> null
        }
    }

    /**
     * The import heads: `{'&', Meta, [{'/', _, [{F, _, C}, A]}]}` with `F` and `C` atoms and `A` an integer, and
     * `{Name, Meta, ArgsOrContext}` with `Name` an atom, annotated.
     */
    private fun importHead(q: QuoteOp<I, O>, input: I, tuple: Shape.Tuple<I>, unquote: Boolean): O? {
        val (left, meta, right) = tuple.elements
        val name = atomOf(left) ?: return null
        if (view.shape(meta) !is Shape.List) return null

        val args = view.shape(right)
        val arity = when (args) {
            is Shape.List -> args.elements.size.takeIf { args.tail == null } ?: return null
            is Shape.Atom -> 0
            else -> return null
        }

        captured(name, args)?.let { (function, functionArity) ->
            val meta = q.metaOf(input)

            return quoteTuple(q, tuple, functionArity?.let { q.captureMeta(input, meta, function, it) } ?: meta, unquote)
        }

        return quoteTuple(q, tuple, annotated(q, tuple, q.importMeta(input, q.metaOf(input), name, arity)), unquote)
    }

    /**
     * `F` and `A` of the capture `{'&', _, [{'/', _, [{F, _, C}, A]}]}`, [name] and [args] its name and arguments. `A`
     * is `null` past an `Int`, an arity no function has.
     */
    private fun captured(name: String, args: Shape<I>): Pair<String, Int?>? {
        if (name != "&") return null

        val slash = (args as? Shape.List)?.takeIf { it.tail == null }?.elements?.singleOrNull()
            ?.let { view.shape(it) as? Shape.Tuple }
            ?.takeIf { it.elements.size == 3 && atomOf(it.elements[0]) == "/" }
            ?: return null
        val (function, arity) = properList(view, slash.elements[2])?.takeIf { it.size == 2 } ?: return null
        val variable = (view.shape(function) as? Shape.Tuple)?.elements?.takeIf { it.size == 3 } ?: return null
        val functionName = atomOf(variable[0]) ?: return null
        if (atomOf(variable[2]) == null) return null

        val value = (view.shape(arity) as? Shape.Integer)?.value ?: return null

        return functionName to value.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()
    }

    /** `annotate/2` of [tuple], its metadata [meta]. */
    private fun annotated(q: QuoteOp<I, O>, tuple: Shape.Tuple<I>, meta: Keywords<O>): Keywords<O> =
        when (val annotation = annotation(view, tuple, level)) {
            is Annotation.Definition -> meta.also { annotation.head?.let(q::annotateDefinition) }
            Annotation.Lexical -> keystore(keydelete(meta, "counter"), "context", q.context())
            Annotation.None -> meta
        }

    /** `do_quote_tuple/4` of [tuple] with [meta]. */
    private fun quoteTuple(q: QuoteOp<I, O>, tuple: Shape.Tuple<I>, meta: Keywords<O>, unquote: Boolean): O {
        val (left, _, right) = tuple.elements
        val tLeft = doQuote(left, unquote)
        val tMeta = meta(q, meta)

        return build.tuple(listOf(tLeft, tMeta, doQuote(right, unquote)), tuple.at)
    }

    /** `{{{'.', Meta, [Left, unquote]}, _, [Expr]}, _, Args}` and `{{'.', Meta, [Left, unquote]}, _, [Expr]}`. */
    private fun unquotedCall(tuple: Shape.Tuple<I>): O? {
        val callee = tuple.elements[0]

        if ((view.shape(callee) as? Shape.Tuple)?.elements?.size == 3) {
            unquotedName(callee)?.let { (dot, left, expr) -> return quoteCall(dot, left, expr, tuple.elements[2]) }
        }

        return unquotedName(tuple)?.let { (dot, left, expr) -> quoteCall(dot, left, expr, null) }
    }

    /** The dot, `Left` and `Expr` of [input], `{{'.', Meta, [Left, unquote]}, _, [Expr]}`. */
    private fun unquotedName(input: I): Triple<I, I, I>? = (view.shape(input) as? Shape.Tuple)?.let(::unquotedName)

    private fun unquotedName(tuple: Shape.Tuple<I>): Triple<I, I, I>? {
        val (callee, _, args) = tuple.elements
        val dot = (view.shape(callee) as? Shape.Tuple)
            ?.takeIf { it.elements.size == 3 && atomOf(it.elements[0]) == "." && isHeadMeta(it.elements[1]) }
            ?: return null
        val (left, name) = properList(view, dot.elements[2])?.takeIf { it.size == 2 } ?: return null
        if (atomOf(name) != "unquote") return null

        val expr = properList(view, args)?.singleOrNull() ?: return null

        return Triple(callee, left, expr)
    }

    /** `do_quote_call/5` of the dot [input]. */
    private fun quoteCall(input: I, left: I, expr: I, args: I?): O {
        val dot = view.shape(input) as Shape.Tuple
        val dotMeta = dot.elements[1]
        val q = quote

        fun builtMeta(): O = if (q != null) meta(q, q.metaOf(input)) else meta(dotMeta, unquote = true)

        val quotedMeta = when {
            UNQUOTE_CALL_META_KEPT.isSufficient(level) -> null
            q != null -> doQuote(q.input(builtMeta()), unquote = true)
            else -> doQuote(dotMeta, unquote = true)
        }
        val tLeft = doQuote(left, unquote = true)
        val tExpr = unquoted(dot.at, expr)
        val tArgs = args?.let { doQuote(it, unquote = true) } ?: build.atom("nil")
        val tContext = q?.let { doQuote(it.input(it.context()), unquote = true) } ?: build.atom("nil")

        return build.remoteCall(
            "elixir_quote",
            "dot",
            listOf(quotedMeta ?: builtMeta(), tLeft, tExpr, tArgs, tContext),
            inserted(dotMeta),
            dot.at,
        )
    }

    /** The two-element tuple heads of `do_quote/2`, [input] the pair where it is an input. */
    private fun pair(left: I, right: I, at: Meta?, unquote: Boolean, input: I? = null): O {
        if (unquote && (isSplicing(left) || isSplicing(right))) {
            // `do_quote({'{}', [], [Left, Right]}, Q)`, which `quote` reaches through the import head.
            val meta = quote?.let { q -> meta(q, q.importMeta(checkNotNull(input), emptyList(), "{}", 2)) } ?: build.list(emptyList())
            val quoted = lists.built(escapeList(listOf(left, right), null, unquote = true))

            return build.tuple(listOf(build.atom("{}"), meta, quoted), at)
        }

        val tLeft = doQuote(left, unquote)

        return build.tuple(listOf(tLeft, doQuote(right, unquote)))
    }

    /** Whether [input] is a tuple whose first element is `unquote_splicing`. */
    private fun isSplicing(input: I): Boolean =
        (view.shape(input) as? Shape.Tuple)?.elements?.firstOrNull()?.let(::atomOf) == "unquote_splicing"

    /** `do_escape/2`. */
    private fun doEscape(input: I, shape: Shape<I>, unquote: Boolean): O =
        when (shape) {
            is Shape.Tuple -> {
                val elements = shape.elements

                when {
                    op == EscapeOp.EscapeAndPrune && elements.size == 3 && isHeadMeta(elements[1]) -> pruned(shape, unquote)
                    elements.size == 2 -> {
                        val tLeft = doQuote(elements[0], unquote)

                        build.tuple(listOf(tLeft, doQuote(elements[1], unquote)))
                    }
                    else -> when (val listed = escapeList(elements, null, unquote)) {
                        is Listed.Plain -> build.tuple(listed.elements, shape.at)
                        is Listed.Expression -> build.tupleOf(listed.value) ?: throw Decline(null)
                    }
                }
            }
            is Shape.Bitstring -> if (shape.padBits == 0) build.binary(shape.bytes) else partBits(shape)
            is Shape.Map -> map(shape, unquote)
            is Shape.List -> lists.built(escapeList(shape.elements, shape.tail, unquote))
            is Shape.Atom -> build.atom(shape.name)
            is Shape.Integer -> build.integer(shape.value)
            is Shape.Float -> build.float(shape.value)
            is Shape.Pid -> build.pid(shape.pid) ?: throw Decline(null)
            is Shape.ExternalFun -> funToQuoted(shape)
            Shape.Unescapable -> throw Raise(ARGUMENT_ERROR)
            Shape.Verbatim -> inserted(input)
            is Shape.Unknown -> throw Decline(shape.at)
        }

    /** The prune clause: only the `line`, `no_parens` and `delimiter` pairs of the metadata, as they are. */
    private fun pruned(tuple: Shape.Tuple<I>, unquote: Boolean): O {
        val (left, meta, right) = tuple.elements
        // A comprehension over what isn't a proper list raises `bad_generator`.
        val metaList = (view.shape(meta) as? Shape.List)?.takeIf { it.tail == null } ?: throw Raise(ERLANG_ERROR)
        val keys = if (ESCAPE_PRUNE_KEEPS_DELIMITER.isSufficient(level)) PRUNED_KEYS + "delimiter" else PRUNED_KEYS
        val kept = metaList.elements.filter { element ->
            (view.shape(element) as? Shape.Tuple)?.elements?.takeIf { it.size == 2 }?.let { atomOf(it[0]) in keys } == true
        }
        val tm = build.list(kept.map(::inserted))
        val tl = doQuote(left, unquote)

        return build.tuple(listOf(tl, tm, doQuote(right, unquote)), tuple.at)
    }

    /** `<<Bits:Size, Bytes/binary>>`, a bitstring whose last byte holds only its leading `Size` bits. */
    private fun partBits(shape: Shape.Bitstring): O {
        val size = 8 - shape.padBits
        val bytes = shape.bytes.map { it.toInt() and 0xFF }
        val bits = bytes[0] ushr (8 - size)
        val rest = ByteArray(bytes.size - 1) { i -> ((bytes[i] shl size) or (bytes[i + 1] ushr (8 - size))).toByte() }
        val binaryArgs = if (ESCAPED_BINARY_SPEC_IS_A_NAME.isSufficient(level)) null else emptyList<O>()

        return build.call(
            build.atom("<<>>"),
            listOf(
                build.call(
                    build.atom("::"),
                    listOf(build.integer(bits.toBigInteger()), build.call(build.atom("size"), listOf(build.integer(size.toBigInteger())))),
                ),
                build.call(build.atom("::"), listOf(build.binary(rest), build.call(build.atom("binary"), binaryArgs))),
            ),
        )
    }

    private fun map(shape: Shape.Map<I>, unquote: Boolean): O {
        if (REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level)) structEscape(shape)
        val entries = listed(shape.entries, level, MapEntry<I>::keyTerm, MapEntry<I>::valueTerm) ?: run {
            // Every key and value is escaped, in an order no term decides, so the escape raises only what each that
            // raises does, and only where every other is known not to.
            val raised = shape.entries.flatMap { listOf(it.key, it.value) }.mapNotNull { raised(it, unquote) }

            throw raised.distinctBy(Raise::exception).singleOrNull() ?: Decline(null)
        }
        val quoted = if (ESCAPED_MAP_ENTRIES_QUOTED_APART.isSufficient(level)) {
            entries.map { entry ->
                val tKey = doQuote(entry.key, unquote)

                build.tuple(listOf(tKey, doQuote(entry.value, unquote)))
            }
        } else {
            // The entries are quoted as one list, which is quoted last first.
            entries.asReversed().map { pair(it.key, it.value, null, unquote) }.asReversed()
        }

        return build.call(build.atom("%{}"), quoted)
    }

    /** What escaping [input] raises, `null` where it doesn't. Throws [Decline] where that isn't known. */
    private fun raised(input: I, unquote: Boolean): Raise? =
        try {
            doQuote(input, unquote)
            null
        } catch (raise: Raise) {
            raise
        }

    /** A struct whose module exports `__escape__/1`, which escapes it with code the escape doesn't run. */
    private fun structEscape(shape: Shape.Map<I>) {
        // `__escape__` is looked for before any entry is escaped, so where the module isn't known, nor is the escape.
        val struct = shape.entries.firstOrNull { (it.keyTerm as? OtpErlangAtom)?.atomValue() == "__struct__" }
        val unknown = if (struct == null) shape.entries.map(MapEntry<I>::key) else listOf(struct.value)

        unknown.firstNotNullOfOrNull { view.shape(it) as? Shape.Unknown }?.let { throw Decline(it.at) }

        val module = (struct?.valueTerm as? OtpErlangAtom)?.atomValue() ?: return

        val escapes = when (val exported = exports.of(module)) {
            is ModuleExports.Present -> ESCAPE in exported.exported
            ModuleExports.Absent -> false
            ModuleExports.Unreadable -> throw Decline(null)
        }

        if (escapes) throw Decline(null)
    }

    /** The list clauses of `do_escape/2`, over [elements] ending in [tail] where the list is improper. */
    private fun escapeList(elements: List<I>, tail: I?, unquote: Boolean): Listed<O> =
        when {
            elements.isEmpty() -> Listed.Plain(emptyList())
            !unquote -> Listed.Plain(simpleList(elements, tail))
            tail == null -> lists.tail(elements.asReversed()) { doQuote(it, unquote = true) }
            else -> {
                val tl = lists.splice(elements.asReversed()) { doQuote(it, unquote = true) }
                val tr = doQuote(tail, unquote = true)

                when (tl) {
                    is Listed.Plain -> Listed.Plain(tl.elements.dropLast(1) + build.call(build.atom("|"), listOf(tl.elements.last(), tr)))
                    // `update_last/2` of what isn't a list.
                    is Listed.Expression -> throw Raise(FUNCTION_CLAUSE_ERROR)
                }
            }
        }

    /** `do_quote_simple_list/3`, from the list's head as `do_escape/2`, or for `quote` `do_quote/2`, quotes it. */
    private fun simpleList(elements: List<I>, tail: I?): List<O> {
        val head = elements.first()
        val escapesHead = quote == null && QUOTE_EXTENSION_META_ESCAPED.isSufficient(level)
        val quoted = mutableListOf(if (escapesHead) doEscape(head, view.shape(head), unquote = false) else doQuote(head, unquote = false))

        for (element in elements.drop(1)) quoted += doQuote(element, unquote = false)

        tail?.let { quoted[quoted.lastIndex] = build.call(build.atom("|"), listOf(quoted.last(), doQuote(it, unquote = false))) }

        return quoted
    }

    /** `meta/2` for `quote`: `keep/2` and `generated/2` of [meta], without its column as [withoutColumn] says. */
    private fun meta(q: QuoteOp<I, O>, meta: Keywords<O>): O = q.kept(withoutColumn(meta) { it.first } ?: meta)

    /**
     * `meta/2` for any other op: [meta] quoted as any other term, before that without its column as [withoutColumn]
     * says, and as it is before both.
     */
    private fun meta(meta: I, unquote: Boolean): O =
        if (QUOTE_EXTENSION_META_ESCAPED.isSufficient(level)) {
            doQuote(meta, unquote)
        } else {
            withoutColumn(properList(view, meta)) { (view.shape(it) as? Shape.Tuple)?.elements?.firstOrNull()?.let(::atomOf) }
                ?.let { build.list(it.map(::inserted)) }
                ?: inserted(meta)
        }

    /**
     * `keydelete(column, Meta)`, which `meta/2` applies from [QUOTE_META_DROPS_COLUMN]: [meta] without it, or `null`
     * before it, which keeps the metadata as it is.
     */
    private fun <T> withoutColumn(meta: List<T>?, keyOf: (T) -> String?): List<T>? {
        if (!QUOTE_META_DROPS_COLUMN.isSufficient(level)) return null

        // `lists:keydelete/3` of what isn't a proper list.
        return keydelete(meta ?: throw Raise(FUNCTION_CLAUSE_ERROR), "column", keyOf)
    }

    /** `fun_to_quoted/1`. */
    private fun funToQuoted(shape: Shape.ExternalFun): O {
        val name = build.call(build.atom("."), listOf(build.atom(shape.module), build.atom(shape.function)))
        val call = build.call(name, emptyList(), listOf("no_parens" to "true"))

        return build.call(build.atom("&"), listOf(build.call(build.atom("/"), listOf(call, build.integer(shape.arity.toBigInteger())))))
    }

    /** Whether [meta] meets a head's `is_list(Meta)` guard, which [ESCAPE_HEADS_NEED_LIST_META] adds. */
    private fun isHeadMeta(meta: I): Boolean = !ESCAPE_HEADS_NEED_LIST_META.isSufficient(level) || view.shape(meta) is Shape.List

    private fun atomOf(input: I): String? = (view.shape(input) as? Shape.Atom)?.name

    private fun inserted(input: I): O = insert(input) ?: throw Decline((view.shape(input) as? Shape.Unknown)?.at)

    companion object {
        /** The escape of a map whose keys are the atoms [entries] name, each with its value already escaped. */
        fun <O> map(build: EscapeBuilder<O>, level: ElixirLanguageLevel, entries: List<Pair<String, O>>): O? =
            listed(entries, level, { OtpErlangAtom(it.first) })?.let { listed ->
                build.call(build.atom("%{}"), listed.map { (key, value) -> build.tuple(listOf(build.atom(key), value)) })
            }

        /**
         * [entries] in the order `do_escape/2` lists a map's: by [key], every integer before every float, and from
         * [ESCAPED_MAP_ENTRIES_SORTED] sorted as `{Key, Value}` pairs, where [value] is `null` for keys that order the
         * entries alone. `null` for an order no term decides: before it, a map of more than [SMALL_MAP] entries is in
         * the order of the keys' hashes, and a pid, port, reference or closure is wherever the VM puts it.
         */
        private fun <E> listed(
            entries: List<E>,
            level: ElixirLanguageLevel,
            key: (E) -> OtpErlangObject,
            value: ((E) -> OtpErlangObject)? = null,
        ): List<E>? =
            try {
                val byKey = entries.sortedWith(compareBy(mapKeyOrder(level), key))

                when {
                    ESCAPED_MAP_ENTRIES_SORTED.isSufficient(level) ->
                        termOrder(level).let { terms ->
                            byKey.sortedWith(compareBy(terms, key).let { order -> value?.let { order.thenBy(terms, it) } ?: order })
                        }
                    entries.size > SMALL_MAP -> null
                    else -> byKey
                }
            } catch (_: UnorderedTerms) {
                null
            }

        /** The most entries a map holds sorted by key, `MAP_SMALL_MAP_LIMIT` in `erl_map.h`. */
        private const val SMALL_MAP = 32

        private val PRUNED_KEYS = setOf("line", "no_parens")

        private val ESCAPE = NameArity("__escape__", 1)

        private const val ARGUMENT_ERROR = "Elixir.ArgumentError"

        private const val ERLANG_ERROR = "Elixir.ErlangError"
    }
}

/** An `Import` term, as `defstruct`'s defaults are read. */
internal object ImportTermEscapeView : EscapeView<Term> {
    override fun shape(input: Term): Shape<Term> =
        when (input) {
            is Term.Atom -> Shape.Atom(input.name)
            is Term.Integer -> Shape.Integer(input.value)
            is Term.Binary -> input.bytes?.let { Shape.Bitstring(it, 0) } ?: Shape.Unknown()
            is Term.List -> Shape.List(input.elements, input.tail)
            is Term.Pair -> Shape.Tuple(listOf(input.first, input.second))
            is Term.Tuple -> Shape.Tuple(input.elements)
            is Term.Node, Term.NonTuple, Term.Unexpanded -> Shape.Unknown()
        }
}

/** A term, as the interpreter's values are. */
internal object TermEscapeView : EscapeView<OtpErlangObject> {
    override fun shape(input: OtpErlangObject): Shape<OtpErlangObject> =
        when (input) {
            is OtpErlangAtom -> Shape.Atom(input.atomValue())
            is OtpErlangLong -> Shape.Integer(input.bigIntegerValue())
            is OtpErlangDouble -> Shape.Float(input.doubleValue())
            is OtpErlangBitstr -> Shape.Bitstring(input.binaryValue(), input.pad_bits())
            is OtpErlangString -> Shape.List(input.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) })
            is OtpErlangList -> Shape.List(input.elements().asList(), input.lastTail)
            is OtpErlangTuple -> Shape.Tuple(input.elements().asList())
            is OtpErlangMap -> Shape.Map(input.keys().zip(input.values()) { key, value -> MapEntry(key, value, key, value) })
            is OtpErlangPid -> Shape.Pid(input)
            is OtpErlangExternalFun -> Shape.ExternalFun(input.module, input.function, input.arity.toLong())
            is OtpErlangFun, is OtpErlangRef, is OtpErlangPort -> Shape.Unescapable
            else -> Shape.Unknown()
        }
}

/** An [ElixirAst] node, or a term in a node's metadata, as the escape reads them. */
internal sealed interface AstTerm {
    class Node(val node: ElixirAst) : AstTerm

    class Value(val value: Meta.Value) : AstTerm
}

/**
 * Metadata as `Code.compile_string` parses it: each line offset by [lineOffset], with [columns] each column, and no
 * token metadata.
 */
internal class ParsedMeta(private val columns: Boolean, private val lineOffset: Int = 0) {
    fun pairs(keys: List<Meta.Key>): List<Pair<String, Meta.Value>> {
        ProgressManager.checkCanceled()

        return keys.flatMap { key ->
            when (key) {
                is Meta.Key.Location -> listOfNotNull(
                    "line" to Meta.Value.Integer((key.position.line + lineOffset).toLong()),
                    ("column" to Meta.Value.Integer(key.position.column.toLong())).takeIf { columns },
                )
                is Meta.Key.Entry -> if (key.tokenMetadata) emptyList() else listOf(key.name to key.value)
            }
        }
    }

    /** The code that builds [value]. */
    fun code(s: Synthetic, value: Meta.Value): ElixirAst {
        ProgressManager.checkCanceled()

        return when (value) {
            is Meta.Value.Atom -> s.atom(value.name)
            is Meta.Value.Integer -> s.integer(value.value.toBigInteger())
            is Meta.Value.Binary -> ElixirAst.Literal.Binary(s.meta(), value.text.toByteArray(Charsets.UTF_8))
            is Meta.Value.Keywords -> s.keywords(pairs(value.keys).map { (key, entry) -> key to code(s, entry) })
            is Meta.Value.List -> s.list(value.elements.map { code(s, it) })
            is Meta.Value.Tuple -> s.tuple(*value.elements.map { code(s, it) }.toTypedArray())
        }
    }

    fun shape(value: Meta.Value): Shape<AstTerm> =
        when (value) {
            is Meta.Value.Atom -> Shape.Atom(value.name)
            is Meta.Value.Integer -> Shape.Integer(value.value.toBigInteger())
            is Meta.Value.Binary -> Shape.Bitstring(value.text.toByteArray(Charsets.UTF_8), 0)
            is Meta.Value.Keywords ->
                Shape.List(pairs(value.keys).map { (key, entry) -> AstTerm.Value(Meta.Value.Tuple(listOf(Meta.Value.Atom(key), entry))) })
            is Meta.Value.List -> Shape.List(value.elements.map(AstTerm::Value))
            is Meta.Value.Tuple -> Shape.Tuple(value.elements.map(AstTerm::Value))
        }
}

/**
 * An [ElixirAst] node as the term it quotes, its metadata as [parsed]. A node's tuple is placed at the node, but for a
 * variable or a call whose name isn't an atom.
 */
internal class AstEscapeView(private val s: Synthetic, private val parsed: ParsedMeta) : EscapeView<AstTerm> {
    override fun shape(input: AstTerm): Shape<AstTerm> =
        when (input) {
            is AstTerm.Node -> node(input.node)
            is AstTerm.Value -> parsed.shape(input.value)
        }

    /** [term] as it is, as `unquote` puts its expression in. */
    fun inserted(term: AstTerm): ElixirAst =
        when (term) {
            is AstTerm.Node -> term.node
            is AstTerm.Value -> parsed.code(s, term.value)
        }

    private fun node(node: ElixirAst): Shape<AstTerm> =
        when (node) {
            is ElixirAst.Literal.Atom -> Shape.Atom(node.name)
            is ElixirAst.Literal.Integer -> Shape.Integer(node.value)
            is ElixirAst.Literal.Float -> Shape.Float(node.value)
            is ElixirAst.Literal.Binary -> Shape.Bitstring(node.bytes, 0)
            is ElixirAst.ListNode -> Shape.List(node.elements.map(AstTerm::Node))
            is ElixirAst.Tuple ->
                if (node.elements.size == 2) Shape.Tuple(node.elements.map(AstTerm::Node), node.meta) else named(node, "{}", node.elements)
            is ElixirAst.Alias -> named(node, "__aliases__", node.segments)
            is ElixirAst.Block -> named(node, "__block__", node.expressions)
            is ElixirAst.Call -> {
                val meta = AstTerm.Value(Meta.Value.Keywords(node.meta.keys))

                when {
                    node.arguments == null -> {
                        val context = when (val context = node.context) {
                            ElixirAst.VariableContext.Nil -> "nil"
                            is ElixirAst.VariableContext.Atom -> context.name
                        }

                        Shape.Tuple(listOf(AstTerm.Node(node.callee), meta, AstTerm.Node(s.atom(context))))
                    }
                    else -> Shape.Tuple(
                        listOf(AstTerm.Node(node.callee), meta, AstTerm.Node(s.list(node.arguments))),
                        node.meta.takeIf { node.callee is ElixirAst.Literal.Atom },
                    )
                }
            }
            is ElixirAst.Placeholder -> if (node.stopsExpansion) Shape.Unknown(node) else Shape.Verbatim
            is ElixirAst.Value -> valueShape(node)
        }

    /** [value]'s term as [TermEscapeView] reads it, each of its parts a [ElixirAst.Value] of its own. */
    private fun valueShape(value: ElixirAst.Value): Shape<AstTerm> {
        val part = { term: OtpErlangObject -> AstTerm.Node(ElixirAst.Value(value.meta, term)) }

        return when (val shape = TermEscapeView.shape(value.term)) {
            is Shape.Tuple -> Shape.Tuple(shape.elements.map(part))
            is Shape.List -> Shape.List(shape.elements.map(part), shape.tail?.let(part))
            is Shape.Map ->
                Shape.Map(shape.entries.map { MapEntry(part(it.key), part(it.value), it.keyTerm, it.valueTerm) })
            is Shape.Atom -> shape
            is Shape.Integer -> shape
            is Shape.Float -> shape
            is Shape.Bitstring -> shape
            is Shape.Pid -> shape
            is Shape.ExternalFun -> shape
            is Shape.Unescapable -> shape
            is Shape.Verbatim -> shape
            is Shape.Unknown -> shape
        }
    }

    private fun named(node: ElixirAst, name: String, args: List<ElixirAst>): Shape<AstTerm> =
        Shape.Tuple(
            listOf(AstTerm.Node(s.atom(name)), AstTerm.Value(Meta.Value.Keywords(node.meta.keys)), AstTerm.Node(s.list(args))),
            node.meta,
        )
}

/** Builds the output as the term it is. */
internal object TermEscapeBuilder : EscapeBuilder<OtpErlangObject> {
    override fun atom(name: String): OtpErlangObject = OtpErlangAtom(name)

    override fun integer(value: BigInteger): OtpErlangObject = OtpErlangLong(value)

    override fun float(value: Double): OtpErlangObject = OtpErlangDouble(value)

    override fun binary(bytes: ByteArray): OtpErlangObject = OtpErlangBinary(bytes)

    override fun list(elements: List<OtpErlangObject>): OtpErlangObject = OtpErlangList(elements.toTypedArray())

    override fun tuple(elements: List<OtpErlangObject>, at: Meta?): OtpErlangObject =
        if (elements.size == 2) OtpErlangTuple(elements.toTypedArray()) else tupleOf(list(elements))

    override fun tupleOf(elements: OtpErlangObject): OtpErlangObject = OtpErlangTuple(arrayOf(atom("{}"), list(emptyList()), elements))

    override fun call(callee: OtpErlangObject, args: List<OtpErlangObject>?, keys: List<Pair<String, String>>): OtpErlangObject =
        OtpErlangTuple(arrayOf(callee, list(keys.map { (key, value) -> tuple(listOf(atom(key), atom(value))) }), args?.let(::list) ?: atom("nil")))

    override fun remoteCall(module: String, function: String, args: List<OtpErlangObject>, meta: OtpErlangObject?, at: Meta?): OtpErlangObject {
        val callMeta = meta ?: list(emptyList())

        return OtpErlangTuple(arrayOf(OtpErlangTuple(arrayOf(atom("."), callMeta, list(listOf(atom(module), atom(function))))), callMeta, list(args)))
    }

    override fun pid(pid: OtpErlangPid): OtpErlangObject = pid
}

/** Builds the output as code at [s]'s position, keeping where each node it builds a tuple for came from. */
internal class AstEscapeBuilder(private val s: Synthetic) : EscapeBuilder<ElixirAst> {
    override fun atom(name: String): ElixirAst = s.atom(name)

    override fun integer(value: BigInteger): ElixirAst = s.integer(value)

    override fun float(value: Double): ElixirAst = ElixirAst.Literal.Float(s.meta(), value)

    override fun binary(bytes: ByteArray): ElixirAst = ElixirAst.Literal.Binary(s.meta(), bytes)

    override fun list(elements: List<ElixirAst>): ElixirAst = s.list(elements)

    override fun tuple(elements: List<ElixirAst>, at: Meta?): ElixirAst {
        val meta = s.meta()

        return ElixirAst.Tuple(at?.let { Meta(it.origin, meta.start, meta.end, meta.keys, built = true) } ?: meta, elements)
    }

    override fun tupleOf(elements: ElixirAst): ElixirAst? = null

    override fun call(callee: ElixirAst, args: List<ElixirAst>?, keys: List<Pair<String, String>>): ElixirAst =
        ElixirAst.Call(s.meta(keys.map { (key, value) -> entry(key, value) }), callee, args)

    override fun remoteCall(module: String, function: String, args: List<ElixirAst>, meta: ElixirAst?, at: Meta?): ElixirAst =
        s.remoteCall(module, function, args, at?.keys.orEmpty())

    override fun pid(pid: OtpErlangPid): ElixirAst? = null
}
