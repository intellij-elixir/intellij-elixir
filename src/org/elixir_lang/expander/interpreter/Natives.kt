package org.elixir_lang.expander.interpreter

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
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.Annotation
import org.elixir_lang.expander.AttributeTable
import org.elixir_lang.expander.AttributeValue
import org.elixir_lang.expander.DefinitionTable
import org.elixir_lang.expander.EscapeOp
import org.elixir_lang.expander.EscapeView
import org.elixir_lang.expander.EscapeResult
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.QuoteEscape
import org.elixir_lang.expander.Shape
import org.elixir_lang.expander.TermEscapeBuilder
import org.elixir_lang.expander.TermEscapeView
import org.elixir_lang.expander.annotation
import org.elixir_lang.expander.defines
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.ERLANG_ERROR_3
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_FOR_REGISTERED
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_ONLY_UNSET
import org.elixir_lang.language_level.ElixirLanguageFeature.INTEGERS_UP_TO_4_MBIT
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_CHECK_THE_FUN_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_SORT_KEEPS_EQUAL_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.RAISE_FRAME_TAKES_A_CAPTURE
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ESCAPE_MARKS_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.SPLICE_EMPTY_BEFORE_TAIL_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.STRING_TO_ATOM_REQUIRES_BINARY
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.lowering.fitsAnAtom
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlin.math.abs

/** The frames a stacktrace `erlang:raise/3` stores holds at most: the default `backtrace_depth`, which Elixir keeps. */
private const val BACKTRACE_DEPTH = 8

/**
 * Each native that can return, or return a part of, fewer than all its arguments, by receiver, name and arity, to the
 * indices of those it can; any other can return any of them. An app-env read returns the environment's value, which
 * came from no module, or a default: `get_env/3`'s and `compile_env/4`'s where the key isn't set (`App:724`, `:595` at
 * v1.20.4), and `__compile_env__/4`'s before it (`App:516` at v1.11.4). `Module`'s attribute reads return a value of
 * the module their first argument names, or `get_attribute/3`'s default, and its predicates a boolean.
 */
private val RETURNABLE = mapOf(
    Triple("Elixir.Application", "get_env", 2) to emptyList(),
    Triple("Elixir.Application", "get_env", 3) to listOf(2),
    Triple("Elixir.Application", "fetch_env", 2) to emptyList(),
    Triple("Elixir.Application", "fetch_env!", 2) to emptyList(),
    Triple("Elixir.Application", "compile_env", 4) to listOf(3),
    Triple("Elixir.Application", "compile_env!", 3) to emptyList(),
    Triple("Elixir.Application", "__compile_env__", 4) to listOf(2),
    Triple("Elixir.Application", "__compile_env__!", 3) to emptyList(),
    Triple("Elixir.Module", "__get_attribute__", 3) to listOf(0),
    Triple("Elixir.Module", "__get_attribute__", 4) to listOf(0),
    Triple("Elixir.Module", "get_attribute", 2) to listOf(0),
    Triple("Elixir.Module", "get_attribute", 3) to listOf(0, 2),
    Triple("Elixir.Module", "has_attribute?", 2) to emptyList(),
    Triple("Elixir.Module", "defines?", 2) to emptyList(),
    Triple("Elixir.Module", "defines?", 3) to emptyList(),
)

/**
 * Each native that runs a function its arguments name, by receiver, name and arity, to the indices of the arguments that
 * name it: `apply/3`'s module and name, and each other's function. The native reads them from here ([Natives.running]),
 * as [Natives.runs] does.
 */
private val RUNS = mapOf(
    Triple("erlang", "apply", 2) to listOf(0),
    Triple("erlang", "apply", 3) to listOf(0, 1),
    Triple("lists", "map", 2) to listOf(0),
    Triple("lists", "filter", 2) to listOf(0),
    Triple("lists", "foldl", 3) to listOf(0),
    Triple("lists", "mapfoldl", 3) to listOf(0),
    Triple("lists", "all", 2) to listOf(0),
    Triple("Elixir.Enum", "map", 2) to listOf(1),
    Triple("Elixir.Enum", "flat_map", 2) to listOf(1),
    Triple("Elixir.Enum", "filter", 2) to listOf(1),
    Triple("Elixir.Enum", "reject", 2) to listOf(1),
    Triple("Elixir.Enum", "reduce", 3) to listOf(2),
    Triple("Elixir.Enum", "any?", 2) to listOf(1),
    Triple("Elixir.Enum", "all?", 2) to listOf(1),
)

/** The functions a macro body calls that are answered as their clauses would answer, without reading them. */
internal class Natives(private val evaluation: Evaluation) {
    companion object {
        /** The Erlang modules whose functions are answered here or not at all: their clauses are never read. */
        val CLAUSES_UNREAD = setOf("erlang", "lists", "maps", "elixir_quote")
    }

    private val level get() = evaluation.run.level

    /** [module]'s [function] applied to [args], or `null` where it isn't one of these. */
    fun call(module: String, function: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val key = "$function/${args.size}"

        return when (module) {
            "erlang" -> erlang(key, args)
            "lists" -> lists(key, args)
            "maps" -> maps(key, args)
            "elixir_quote" -> quote(key, args)
            "Elixir.Keyword" -> keyword(key, args)
            "Elixir.Enum" -> enum(key, args)
            "Elixir.Access" -> access(key, args)
            "Elixir.Macro.Env" -> env(key, args)
            "Elixir.Module" -> module(key, args)
            "Elixir.Application" -> application(key, args)
            "Elixir.Atom" -> if (key == "to_string/1") erlang("atom_to_binary/1", args) else null
            "Elixir.String" -> string(key, args)
            "Elixir.String.Chars" -> if (key == "to_string/1") toString(args[0]) else null
            "Elixir.Kernel" -> kernel(key, args)
            "Elixir.Macro" -> macro(key, args)
            else -> null
        }
    }

    /**
     * The arguments of [module]'s [function], given [args], that a native [call] may return, or return a part of, where
     * its result isn't known or it declines ([RETURNABLE]). An argument it only reads, such as `compile_env/4`'s env, is
     * none of them.
     */
    fun returnable(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        RETURNABLE[Triple(module, function, args.size)]?.map(args::get) ?: args

    /**
     * What [module]'s [function], given [args], runs, given what: every argument where it runs a function its arguments
     * name ([RUNS]), known or not, or where a function is held in any ([anyHeld]), which the others may flow into, and
     * none otherwise. A native writes no module itself: the `Module` writes it serves are effects the walk applies.
     */
    fun runs(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        args.takeIf {
            Triple(module, function, args.size) in RUNS ||
                anyHeld(it) { term -> term is Closure || term is OtpErlangExternalFun }
        }.orEmpty()

    /** `erlang:map_get/2`, as a guard's `map.key` reads it. */
    fun mapGet(key: OtpErlangObject, map: OtpErlangObject): OtpErlangObject = erlang("map_get/2", listOf(key, map))!!

    /** The elements a comprehension's generator takes from [enumerable], a list's or a range's, each after a cancellation check. */
    fun enumerate(enumerable: OtpErlangObject): Sequence<OtpErlangObject> =
        properElements(enumerable)?.asSequence()?.onEach { ProgressManager.checkCanceled() } ?: range(enumerable)?.elements()
            ?: decline("enumerates %s", enumerable)

    private fun erlang(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val badarg = { evaluation.error(atom("badarg"), frame("erlang", key.substringBefore('/'), list(args))) }

        return when (key) {
            "++/2" -> cons(properElements(args[0]) ?: badarg(), args[1])
            "--/2" -> {
                val left = properElements(args[0])?.toMutableList() ?: badarg()

                for (element in properElements(args[1]) ?: badarg()) {
                    ProgressManager.checkCanceled()

                    val index = left.indexOfFirst { equal(it, element, true, level) }

                    if (index >= 0) left.removeAt(index)
                }

                list(left)
            }
            "==/2" -> bool(equal(args[0], args[1], false, level))
            "/=/2" -> bool(!equal(args[0], args[1], false, level))
            "=:=/2" -> bool(equal(args[0], args[1], true, level))
            "=/=/2" -> bool(!equal(args[0], args[1], true, level))
            "self/0" -> SELF
            "make_ref/0" -> OtpErlangRef(LOCAL_NODE, intArrayOf(evaluation.run.nextRef(), 0, 0), 0)
            "</2" -> bool(compare(args[0], args[1], level) < 0)
            ">/2" -> bool(compare(args[0], args[1], level) > 0)
            "=</2" -> bool(compare(args[0], args[1], level) <= 0)
            ">=/2" -> bool(compare(args[0], args[1], level) >= 0)
            "not/1" -> bool(!(boolean(args[0]) ?: badarg()))
            "and/2" -> bool((boolean(args[0]) ?: badarg()) and (boolean(args[1]) ?: badarg()))
            "or/2" -> bool((boolean(args[0]) ?: badarg()) or (boolean(args[1]) ?: badarg()))
            "xor/2" -> bool((boolean(args[0]) ?: badarg()) xor (boolean(args[1]) ?: badarg()))
            "is_atom/1" -> bool(isAtom(args[0]))
            "is_list/1" -> bool(isList(args[0]))
            "is_binary/1" -> bool(isBinary(args[0]))
            "is_bitstring/1" -> bool(typed(args[0]) is OtpErlangBitstr)
            "is_integer/1" -> bool(typed(args[0]) is OtpErlangLong)
            "is_float/1" -> bool(typed(args[0]) is OtpErlangDouble)
            "is_number/1" -> bool(isNumber(typed(args[0])))
            "is_tuple/1" -> bool(typed(args[0]) is OtpErlangTuple)
            "is_map/1" -> bool(typed(args[0]) is OtpErlangMap)
            "is_boolean/1" -> bool(boolean(args[0]) != null)
            "is_pid/1" -> bool(typed(args[0]) is OtpErlangPid)
            "is_function/1" -> bool(arity(args[0]) != null)
            "is_function/2" -> {
                val arity = (seen(args[1]) as? OtpErlangLong)?.takeIf { it.signum() >= 0 } ?: badarg()

                bool(arity(args[0])?.toBigInteger() == arity.bigIntegerValue())
            }
            "element/2" -> {
                val tuple = seen(args[1]) as? OtpErlangTuple ?: badarg()

                tuple.elementAt((index(args[0], tuple.arity()) ?: badarg()) - 1)
            }
            "tuple_size/1" -> integer((seen(args[0]) as? OtpErlangTuple ?: badarg()).arity().toLong())
            "bit_size/1" -> integer(bitSize(seen(args[0]) as? OtpErlangBitstr ?: badarg()).toLong())
            "byte_size/1" -> integer(((bitSize(seen(args[0]) as? OtpErlangBitstr ?: badarg()) + 7) / 8).toLong())
            "setelement/3" -> {
                val tuple = seen(args[1]) as? OtpErlangTuple ?: badarg()
                val elements = tuple.elements()

                elements[(index(args[0], tuple.arity()) ?: badarg()) - 1] = args[2]
                OtpErlangTuple(elements)
            }
            "length/1" -> integer((properElements(args[0]) ?: badarg()).size.toLong())
            "hd/1" -> cells(args[0])?.first?.firstOrNull() ?: badarg()
            "tl/1" -> {
                val (elements, tail) = cells(args[0])?.takeIf { it.first.isNotEmpty() } ?: badarg()

                cons(elements.drop(1), tail ?: EMPTY)
            }
            "map_size/1" -> integer(map(args[0]).arity().toLong())
            "map_get/2" -> valueAt(map(args[1]), args[0], level) ?: evaluation.error(tuple(atom("badkey"), args[0]))
            "is_map_key/2" -> bool(valueAt(map(args[1]), args[0], level) != null)
            "+/2", "-/2", "*/2" -> arithmetic(key[0], args[0], args[1])
            "+/1" -> number(args[0]) ?: badarith()
            "-/1" -> when (val number = number(args[0])) {
                is OtpErlangLong -> integer(number.bigIntegerValue().negate())
                is OtpErlangDouble -> OtpErlangDouble(-number.doubleValue())
                else -> badarith()
            }
            "//2" -> {
                val (left, right) = args.map { (number(it) ?: badarith()).let(::double) }

                if (right == 0.0) badarith()

                finite(left / right)
            }
            "div/2", "rem/2" -> {
                val (left, right) = args.map { (seen(it) as? OtpErlangLong)?.bigIntegerValue() ?: badarith() }

                if (right.signum() == 0) badarith()

                integer(if (key == "div/2") left.divide(right) else left.rem(right))
            }
            "abs/1" -> when (val number = number(args[0])) {
                is OtpErlangLong -> integer(number.bigIntegerValue().abs())
                is OtpErlangDouble -> OtpErlangDouble(abs(number.doubleValue()))
                else -> badarg()
            }
            "atom_to_binary/1", "atom_to_binary/2" -> {
                val text = (seen(args[0]) as? OtpErlangAtom ?: badarg()).atomValue()

                when (encoding(args)) {
                    Encoding.LATIN1 -> OtpErlangBinary(latin1(text) ?: badarg())
                    Encoding.UTF8 -> binary(text)
                    null -> badarg()
                }
            }
            "binary_to_atom/1", "binary_to_atom/2" -> {
                val text = atomText(args) ?: badarg()

                if (!fitsAnAtom(text)) evaluation.error(atom("system_limit"))

                atom(text).also { evaluation.atoms += text }
            }
            "binary_to_existing_atom/1", "binary_to_existing_atom/2" -> {
                val atom = atom(atomText(args) ?: badarg())

                // Every atom the VM has loaded exists, where the evaluation knows only those it has seen.
                if (atom.atomValue() !in evaluation.atoms) decline("whether the atom %s exists", atom)

                atom
            }
            "list_to_tuple/1" -> OtpErlangTuple((properElements(args[0]) ?: badarg()).toTypedArray())
            "tuple_to_list/1" -> list((seen(args[0]) as? OtpErlangTuple ?: badarg()).elements().asList())
            "function_exported/3" -> {
                val module = seen(args[0]) as? OtpErlangAtom ?: badarg()
                val function = seen(args[1]) as? OtpErlangAtom ?: badarg()
                val arity = small(args[2]) ?: badarg()

                when (val exports = evaluation.run.exports.of(module.atomValue())) {
                    // The VM reads an arity by its low 32 bits.
                    is ModuleExports.Present -> bool(NameArity(function.atomValue(), arity.toInt()) in exports.exported)
                    ModuleExports.Absent -> FALSE
                    ModuleExports.Unreadable -> decline("${module.atomValue()}'s exports aren't known")
                }
            }
            "apply/2" -> evaluation.apply(running("erlang", key, args).single(), properElements(args[1]) ?: badarg())
            "apply/3" -> {
                val (module, name) = running("erlang", key, args)
                val function = seen(name) as? OtpErlangAtom ?: badarg()

                evaluation.remote(module, function.atomValue(), properElements(args[2]) ?: badarg())
            }
            "error/1" -> evaluation.raiseAs("error", args[0])
            "error/2" -> evaluation.error(args[0], args[1], null)
            "error/3" if ERLANG_ERROR_3.isSufficient(level) -> evaluation.error(args[0], args[1], errorInfo(args[2]))
            "throw/1" -> evaluation.raiseAs("throw", args[0])
            "exit/1" -> evaluation.raiseAs("exit", args[0])
            "raise/3" -> {
                val kind = (seen(args[0]) as? OtpErlangAtom)?.atomValue()?.takeIf { it in setOf("error", "exit", "throw") }

                // `erlang:raise/3` returns `badarg` where its class or stacktrace is invalid.
                if (kind == null || !isStacktrace(args[2])) return atom("badarg")

                evaluation.raiseAs(kind, args[1], stored(args[2]))
            }
            else -> null
        }
    }

    /**
     * The `error_info` map `error_3` takes from [options]: the last, where every option is `{error_info, Map}` and the
     * list is proper; `null` otherwise, and `error/3` raises as `error/2` does.
     */
    private fun errorInfo(options: OtpErlangObject): OtpErlangMap? {
        val (elements, tail) = cells(options) ?: return null
        var info: OtpErlangMap? = null

        for (element in elements) {
            val option = seen(element) as? OtpErlangTuple

            if (option?.arity() != 2 || seen(option.elementAt(0)) != atom("error_info")) return null
            info = seen(option.elementAt(1)) as? OtpErlangMap ?: return null
        }

        return info.takeIf { tail?.let(::seen) == null }
    }

    private enum class Encoding { LATIN1, UTF8 }

    /** The encoding `erlang`'s atom conversions take as their second argument, `utf8` where there is none. */
    private fun encoding(args: List<OtpErlangObject>): Encoding? =
        when ((args.getOrNull(1)?.let(::seen) as? OtpErlangAtom)?.atomValue() ?: if (args.size == 1) "utf8" else null) {
            "latin1" -> Encoding.LATIN1
            "utf8", "unicode" -> Encoding.UTF8
            else -> null
        }

    /** The atom text a binary is in its encoding, or `null` where either is invalid. */
    private fun atomText(args: List<OtpErlangObject>): String? =
        when (encoding(args)) {
            Encoding.LATIN1 -> (seen(args[0]) as? OtpErlangBinary)?.binaryValue()?.let { String(it, Charsets.ISO_8859_1) }
            Encoding.UTF8 -> utf8(args[0])
            null -> null
        }

    /** [text]'s bytes in Latin-1, or `null` where a character is past it. */
    private fun latin1(text: String): ByteArray? =
        text.codePoints().toArray().takeIf { codes -> codes.all { it < 256 } }?.let { codes ->
            ByteArray(codes.size) { codes[it].toByte() }
        }

    /** `raise_3`'s check of [stacktrace]: a proper list of the frames [isStackFrame] takes. */
    private fun isStacktrace(stacktrace: OtpErlangObject): Boolean {
        val (frames, tail) = cells(stacktrace) ?: return false

        return frames.all(::isStackFrame) && tail?.let(::seen) == null
    }

    /**
     * The stacktrace `raise_3` stores for [stacktrace], one it takes: its first [BACKTRACE_DEPTH] frames, each
     * `{Fun, Args}` and `{M, F, A}` given an empty location.
     */
    private fun stored(stacktrace: OtpErlangObject): OtpErlangObject =
        list(
            cells(stacktrace)!!.first.take(BACKTRACE_DEPTH).map { frame ->
                val elements = (seen(frame) as OtpErlangTuple).elements()
                val short = elements.size == 2 || elements.size == 3 && isAtom(elements[0])

                if (short) OtpErlangTuple(elements + EMPTY) else frame
            }
        )

    /**
     * A frame `raise_3` takes, by its size: `{Fun, Args}`; `{Fun, Args, Location}` or `{M, F, A}`; `{M, F, A, Location}`.
     * It never reads `Args` or `A`, and takes any list as `Location`.
     */
    private fun isStackFrame(frame: OtpErlangObject): Boolean {
        val elements = (seen(frame) as? OtpErlangTuple)?.elements() ?: return false
        val isFun = { term: OtpErlangObject ->
            when (typed(term)) {
                is OtpErlangFun -> true
                is OtpErlangExternalFun -> RAISE_FRAME_TAKES_A_CAPTURE.isSufficient(level)
                else -> false
            }
        }

        return when (elements.size) {
            2 -> isFun(elements[0])
            3 -> if (isFun(elements[0])) isList(elements[2]) else isAtom(elements[0]) && isAtom(elements[1])
            4 -> isAtom(elements[0]) && isAtom(elements[1]) && isList(elements[3])
            else -> false
        }
    }

    private fun lists(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val functionClause = { function: String, clauseArgs: List<OtpErlangObject> ->
            evaluation.error(atom("function_clause"), frame("lists", function, list(clauseArgs)))
        }
        val badarg = { function: String -> evaluation.error(atom("badarg"), frame("lists", function, list(args))) }
        val ran by lazy { running("lists", key, args).single() }

        return when (key) {
            "reverse/1" -> {
                val (elements, tail) = cells(args[0]) ?: functionClause("reverse", args)

                when {
                    tail == null && elements.size <= 1 -> args[0]
                    tail == null && elements.size == 2 -> list(elements.reversed())
                    elements.size >= 2 ->
                        lists("reverse/2", listOf(cons(elements.drop(2), tail ?: EMPTY), list(elements.take(2).reversed())))
                    else -> functionClause("reverse", args)
                }
            }
            "reverse/2" -> cons((properElements(args[0]) ?: badarg("reverse")).reversed(), args[1])
            "member/2" -> {
                val (elements, tail) = cells(args[1]) ?: badarg("member")

                when {
                    elements.any { equal(it, args[0], true, level) } -> TRUE
                    tail != null -> badarg("member")
                    else -> FALSE
                }
            }
            "keyfind/3" -> keyfind(args) ?: FALSE
            "keymember/3" -> bool(keyfind(args) != null)
            "keydelete/3" -> {
                val n = (seen(args[1]) as? OtpErlangLong)?.bigIntegerValue()?.takeIf { it.signum() > 0 }
                    ?: functionClause("keydelete", args)
                val (elements, tail) = cells(args[2]) ?: functionClause("keydelete3", args)
                val index = elements.indexOfFirst { element ->
                    (seen(element) as? OtpErlangTuple)?.takeIf { BigInteger.valueOf(it.arity().toLong()) >= n }
                        ?.let { equal(it.elementAt(n.toInt() - 1), args[0], false, level) } == true
                }

                when {
                    index >= 0 -> cons(elements.take(index), cons(elements.drop(index + 1), tail ?: EMPTY))
                    tail != null -> functionClause("keydelete3", listOf(args[0], args[1], tail))
                    else -> args[2]
                }
            }
            "append/1" -> {
                val (lists, tail) = cells(args[0]) ?: functionClause("append", args)

                append(lists, tail)
            }
            "append/2" -> erlang("++/2", args)
            "flatten/1" -> {
                if (!isList(args[0])) functionClause("flatten", args)

                list(mutableListOf<OtpErlangObject>().also { flatten(args[0], it) }.asReversed())
            }
            "map/2" -> {
                val function = function(ran, 1) ?: listsError(atom("function_clause"), frame("lists", "map", list(args)))
                val caseClause = { listsError(tuple(atom("case_clause"), args[1])) }

                list(each(args[1], caseClause, { tail -> frame("lists", "map_1", list(listOf(args[0], tail))) }) { function.call(it) })
            }
            "filter/2" -> {
                val predicate = function(ran, 1) ?: functionClause("filter", args)

                filter(args[1]) { element ->
                    when (val keep = predicate.call(element)) {
                        TRUE -> true
                        FALSE -> false
                        else -> evaluation.error(tuple(atom("bad_filter"), seen(keep)))
                    }
                }
            }
            "foldl/3" -> {
                val function = function(ran, 2) ?: listsError(atom("function_clause"), frame("lists", "foldl", list(args)))
                val caseClause = { listsError(tuple(atom("case_clause"), args[2])) }
                var acc = args[1]

                each(args[2], caseClause, { tail -> frame("lists", "foldl_1", list(listOf(args[0], acc, tail))) }) { element ->
                    function.call(element, acc).also { acc = it }
                }

                acc
            }
            "mapfoldl/3" -> {
                val function = function(ran, 2) ?: listsError(atom("function_clause"), frame("lists", "mapfoldl", list(args)))
                var acc = args[1]
                val helper = { list: OtpErlangObject -> frame("lists", "mapfoldl_1", list(listOf(args[0], acc, list))) }
                val notList = { listsError(atom("function_clause"), helper(args[2])) }
                val mapped = each(args[2], notList, helper) { element ->
                    val result = function.call(element, acc)
                    val pair = (seen(result) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }
                        ?: listsError(tuple(atom("badmatch"), result))

                    acc = pair.elementAt(1)
                    pair.elementAt(0)
                }

                tuple(list(mapped), acc)
            }
            "all/2" -> {
                val predicate = function(ran, 1) ?: listsError(atom("function_clause"), frame("lists", "all", list(args)))
                val (elements, tail) = cells(args[1]) ?: listsError(tuple(atom("case_clause"), args[1]))

                for (element in elements) {
                    when (val result = predicate.call(element)) {
                        TRUE -> {}
                        FALSE -> return FALSE
                        else -> listsError(tuple(atom("case_clause"), seen(result)))
                    }
                }

                if (tail != null) listsError(atom("function_clause"), frame("lists", "all_1", list(listOf(args[0], tail))))

                TRUE
            }
            "sort/1" -> sort(args[0], functionClause)
            else -> null
        }
    }

    /**
     * `lists:sort/1` (`lists.erl:1084–1124`, `:3006–3050` at OTP-29.0.6; `:484–515` at OTP-24.3.4.6): a proper list is the
     * stable sort of its elements in term order, which its merge gives. An improper list raises `function_clause` in the
     * helper whose walk meets the tail, with the runs split so far, so that walk is ported clause by clause. Before
     * [LISTS_SORT_KEEPS_EQUAL_ORDER], elements that compare equal but differ, such as `1` and `1.0`, are in the old
     * split's order, and the walk's clauses are others, so both decline.
     */
    private fun sort(term: OtpErlangObject, functionClause: (String, List<OtpErlangObject>) -> Nothing): OtpErlangObject {
        val (elements, tail) = cells(term) ?: functionClause("sort", listOf(term))

        if (tail == null) {
            val sorted = elements.sortedWith { left, right ->
                ProgressManager.checkCanceled()

                compare(left, right, level)
            }

            if (!LISTS_SORT_KEEPS_EQUAL_ORDER.isSufficient(level)) {
                for (index in 1 until sorted.size) {
                    ProgressManager.checkCanceled()

                    val (left, right) = sorted[index - 1] to sorted[index]

                    if (compare(left, right, level) == 0 && !equal(left, right, true, level)) {
                        decline("lists:sort/1 of elements that compare equal before OTP 29")
                    }
                }
            }

            return list(sorted)
        }

        if (elements.size < 2) functionClause("sort", listOf(term))
        if (!LISTS_SORT_KEEPS_EQUAL_ORDER.isSufficient(level)) decline("lists:sort/1 of an improper list before OTP 29")

        val split = SortSplit(elements[0], elements[1])

        for (z in elements.drop(2)) {
            ProgressManager.checkCanceled()

            split.next(z)
        }

        functionClause(split.helper, split.arguments(tail))
    }

    /**
     * The state of `lists:sort/1`'s split walk: [helper], the function the walk is in, with its `X`, `Y`, `R`, `Rs` and
     * `S`, from `sort/1`'s first two clauses of [first] and [second].
     */
    private inner class SortSplit(first: OtpErlangObject, second: OtpErlangObject) {
        var helper: String
        private var x = first
        private var y = second
        private var s: OtpErlangObject = EMPTY

        // `R` and `Rs`, each with its head last.
        private val r = mutableListOf<OtpErlangObject>()
        private val rs = mutableListOf<OtpErlangObject>()

        init {
            val order = compare(x, y, level)

            helper = when {
                order < 0 -> "split_1"
                order > 0 -> "split_2"
                else -> {
                    r += x
                    x = y
                    "sort_1"
                }
            }
        }

        /** The walk's clause for the next element, [z]. */
        fun next(z: OtpErlangObject) {
            when (helper) {
                "sort_1" -> {
                    val order = compare(x, z, level)

                    when {
                        order == 0 -> push(z)
                        order < 0 -> {
                            y = z
                            helper = "split_1"
                        }
                        else -> {
                            rs.add(list(r.toList()))
                            r.clear()
                            y = z
                            helper = "split_2"
                        }
                    }
                }
                "split_1", "split_1_1" -> when {
                    compare(z, y, level) >= 0 -> {
                        push(y)
                        y = z
                    }
                    compare(z, x, level) >= 0 -> push(z)
                    helper == "split_1" && r.isEmpty() -> r += z
                    helper == "split_1" -> {
                        s = z
                        helper = "split_1_1"
                    }
                    compare(z, s, level) >= 0 -> split("split_1", s, z)
                    else -> split("split_1", z, s)
                }
                else -> when {
                    compare(y, z, level) > 0 -> {
                        push(y)
                        y = z
                    }
                    compare(x, z, level) > 0 -> push(z)
                    helper == "split_2" && r.isEmpty() -> r += z
                    helper == "split_2" -> {
                        s = z
                        helper = "split_2_1"
                    }
                    compare(s, z, level) > 0 -> split("split_2", s, z)
                    else -> split("split_2", z, s)
                }
            }
        }

        /** The helper's arguments where its list is [tail]. */
        fun arguments(tail: OtpErlangObject): List<OtpErlangObject> {
            val rTerm = list(r.asReversed())
            val rsTerm = list(rs.asReversed())

            return when (helper) {
                "sort_1" -> listOf(x, tail, rTerm)
                "split_1", "split_2" -> listOf(x, y, tail, rTerm, rsTerm)
                else -> listOf(x, y, tail, rTerm, rsTerm, s)
            }
        }

        /** `X` onto `R`, and [next] as `X`. */
        private fun push(next: OtpErlangObject) {
            r += x
            x = next
        }

        /** `[Y, X | R]` onto `Rs`, and [helper] from [x] and [y] with an empty `R`. */
        private fun split(helper: String, x: OtpErlangObject, y: OtpErlangObject) {
            rs.add(cons(listOf(this.y, this.x), list(r.asReversed())))
            r.clear()
            this.helper = helper
            this.x = x
            this.y = y
        }
    }

    /** `lists:append/1` of the list of [lists] and [tail]. */
    private fun append(lists: List<OtpErlangObject>, tail: OtpErlangObject?): OtpErlangObject {
        // The innermost `append/1` raises before any `++` runs.
        if (tail != null) functionClause("lists", "append", listOf(tail))

        return lists.dropLast(1).foldRight(lists.lastOrNull() ?: EMPTY) { list, acc ->
            ProgressManager.checkCanceled()

            erlang("++/2", listOf(list, acc))!!
        }
    }

    /** `lists:keyfind/3`: the first tuple of the list whose element [args]`[1]` is `==` to [args]`[0]`. */
    private fun keyfind(args: List<OtpErlangObject>): OtpErlangTuple? {
        val badarg = { evaluation.error(atom("badarg"), frame("lists", "keyfind", list(args))) }
        val n = small(args[1])?.takeIf { it > 0 } ?: badarg()
        val (elements, tail) = cells(args[2]) ?: badarg()

        elements.forEach { element ->
            val tuple = seen(element) as? OtpErlangTuple

            if (tuple != null && tuple.arity() >= n && equal(tuple.elementAt(n.toInt() - 1), args[0], false, level)) return tuple
        }

        if (tail != null) badarg()

        return null
    }

    /**
     * [transform] of each element of the list [term], raising [notList] where it isn't one, and `function_clause` in the
     * helper whose [helper] frame meets an improper tail.
     */
    private fun each(
        term: OtpErlangObject,
        notList: () -> Nothing,
        helper: (OtpErlangObject) -> OtpErlangTuple,
        transform: (OtpErlangObject) -> OtpErlangObject,
    ): List<OtpErlangObject> {
        val (elements, tail) = cells(term) ?: notList()
        val results = elements.map(transform)

        if (tail != null) listsError(atom("function_clause"), helper(tail))

        return results
    }

    /** An error `lists:map/2`, `foldl/3`, `mapfoldl/3` or `all/2` raises, which before OTP 25 the VM gave otherwise. */
    private fun listsError(reason: OtpErlangObject, top: OtpErlangTuple? = null): Nothing {
        if (!LISTS_CHECK_THE_FUN_FIRST.isSufficient(level)) decline("an error of a lists function before OTP 25")

        evaluation.error(reason, top)
    }

    /**
     * `lists:do_flatten/2`: [term] flattened onto the list [reversed] holds back to front. Each cell's rest is flattened
     * before the list at its head, so an improper tail raises with what follows it already flattened.
     */
    private fun flatten(term: OtpErlangObject, reversed: MutableList<OtpErlangObject>) {
        val (elements, tail) = cells(term)!!

        if (tail != null) {
            evaluation.error(atom("function_clause"), frame("lists", "do_flatten", list(listOf(tail, list(reversed.reversed())))))
        }

        for (element in elements.asReversed()) {
            ProgressManager.checkCanceled()

            if (isList(element)) flatten(element, reversed) else reversed += element
        }
    }

    private fun maps(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "get/2" -> valueAt(map(args[1]), args[0], level) ?: evaluation.error(tuple(atom("badkey"), args[0]))
            "get/3" -> valueAt(map(args[1]), args[0], level) ?: args[2]
            "find/2" -> valueAt(map(args[1]), args[0], level)?.let { tuple(atom("ok"), it) } ?: atom("error")
            "is_key/2" -> bool(valueAt(map(args[1]), args[0], level) != null)
            "put/3" -> mapOf(entries(map(args[2])) + (args[0] to args[1]), level)
            "update/3" -> {
                val map = map(args[2])

                if (valueAt(map, args[0], level) == null) evaluation.error(tuple(atom("badkey"), args[0]))

                mapOf(entries(map) + (args[0] to args[1]), level)
            }
            "merge/2" -> {
                val left = map(args[0])

                mapOf(entries(left) + entries(map(args[1])), level)
            }
            "from_list/1" -> {
                val badarg = { evaluation.error(atom("badarg"), frame("maps", "from_list", list(args))) }

                mapOf(
                    (properElements(args[0]) ?: badarg()).map { element ->
                        val pair = (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: badarg()

                        pair.elementAt(0) to pair.elementAt(1)
                    },
                    level,
                )
            }
            "to_list/1" -> {
                val entries = entries(map(args[0]))

                if (entries.size > 32) decline("the order of a map of more than 32 keys")
                if (MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(level) && entries.count { it.first is OtpErlangAtom } > 1) {
                    decline("the order of a map's atom keys")
                }

                list(entries.sortedWith { left, right -> compare(left.first, right.first, level, true) }.map { tuple(it.first, it.second) })
            }
            else -> null
        }

    /** `elixir_quote`'s run-time helpers, which an expanded `quote` calls. */
    private fun quote(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val functionClause = { function: String ->
            evaluation.error(atom("function_clause"), frame("elixir_quote", function, list(args)))
        }

        return when (key) {
            "validate_quote/1" -> args[0]
            "validate_runtime/2" -> {
                val value = typed(args[1])
                val valid = when ((seen(args[0]) as? OtpErlangAtom)?.atomValue()) {
                    "line" -> value is OtpErlangLong
                    "file" -> value is OtpErlangBitstr && value.pad_bits() == 0
                    "context" -> value is OtpErlangAtom && value != NIL
                    "generated", "unquote" -> boolean(value) != null
                    else -> functionClause("is_valid")
                }

                if (valid) args[1] else argumentError()
            }
            "dot/5" -> annotate(dot(args[0], args[1], args[2], args[3]), args[4])
            "dot/4" -> dot(args[0], args[1], args[2], args[3])
            "list/2" -> {
                if (!isList(args[1])) functionClause("list")

                validateList(args[0])
                erlang("++/2", args)!!
            }
            "tail_list/3" -> {
                val (left, right, tail) = args

                when {
                    isList(right) && isList(tail) -> {
                        validateList(left)
                        erlang("++/2", listOf(tail, erlang("++/2", listOf(left, right))!!))!!
                    }
                    isList(left) -> {
                        validateList(left)

                        val reversed = properElements(lists("reverse/1", listOf(erlang("++/2", listOf(tail, left))!!))!!)!!

                        if (reversed.isEmpty()) {
                            if (SPLICE_EMPTY_BEFORE_TAIL_RAISES.isSufficient(level)) argumentError()

                            evaluation.error(tuple(atom("badmatch"), EMPTY))
                        }

                        list((listOf(tuple(atom("|"), EMPTY, list(listOf(reversed[0], right)))) + reversed.drop(1)).reversed())
                    }
                    else -> functionClause("tail_list")
                }
            }
            "unquote/1", "shallow_validate_ast/1" -> if (shallowValidAst(args[0])) args[0] else argumentError()
            else -> null
        }
    }

    /** `elixir_quote:dot/4`. */
    private fun dot(meta: OtpErlangObject, left: OtpErlangObject, right: OtpErlangObject, args: OtpErlangObject): OtpErlangObject {
        val call = { name: OtpErlangObject, callMeta: OtpErlangObject, callArgs: OtpErlangObject ->
            tuple(tuple(atom("."), meta, list(listOf(left, name))), callMeta, callArgs)
        }
        val noParens = { cons(listOf(tuple(atom("no_parens"), TRUE)), meta) }
        val triple = (seen(right) as? OtpErlangTuple)?.takeIf { it.arity() == 3 }
        val name = triple?.elementAt(0)?.let(::seen) as? OtpErlangAtom
        val context = triple?.elementAt(2)

        if (seen(args) == NIL) {
            return when {
                name?.atomValue() == "__aliases__" -> tuple(atom("__aliases__"), meta, cons(listOf(left), context!!))
                right is OtpErlangAtom ->
                    if (right.atomValue().startsWith("Elixir.")) {
                        tuple(atom("__aliases__"), meta, list(listOf(left, right)))
                    } else {
                        call(right, noParens(), EMPTY)
                    }
                name != null && isAtom(context!!) -> call(name, noParens(), EMPTY)
                name != null -> call(name, meta, context!!)
                else -> argumentError()
            }
        }

        return when {
            right is OtpErlangAtom -> call(right, meta, args)
            name != null && isAtom(context!!) -> call(name, meta, args)
            else -> argumentError()
        }
    }

    /** `elixir_quote:annotate/2`. */
    private fun annotate(tree: OtpErlangObject, context: OtpErlangObject): OtpErlangObject {
        val (head, meta, args) = (seen(tree) as? OtpErlangTuple)?.takeIf { it.arity() == 3 }?.elements() ?: return tree

        return when (val annotation = annotation(view, Shape.Tuple(listOf(head, meta, args)), level)) {
            is Annotation.Definition -> {
                val target = annotation.head ?: return tree
                val (elements, tail) = cells(args)!!

                tuple(head, meta, cons(listOf(annotateHead(elements[0], target, context)) + elements.drop(1), tail ?: EMPTY))
            }
            Annotation.Lexical -> tuple(head, storeContext(deleteCounter(meta), context), args)
            Annotation.None -> tree
        }
    }

    /** [head] with `context:` stored in the metadata of [target], which [head] is or holds as a `when`'s left. */
    private fun annotateHead(head: OtpErlangObject, target: OtpErlangObject, context: OtpErlangObject): OtpErlangObject {
        ProgressManager.checkCanceled()

        val (name, meta, args) = (head as OtpErlangTuple).elements()

        if (head === target) return tuple(name, storeContext(meta, context), args)

        val (left, right) = properElements(args)!!

        return tuple(name, meta, list(listOf(annotateHead(left, target, context), right)))
    }

    /** `elixir_quote:keystore(context, Meta, Value)`. */
    private fun storeContext(meta: OtpErlangObject, value: OtpErlangObject): OtpErlangObject {
        if (seen(value) == NIL) return meta

        val key = atom("context")
        val elements = properElements(meta) ?: decline("metadata that isn't a list")
        val index = elements.indexOfFirst { (seen(it) as? OtpErlangTuple)?.takeIf { pair -> pair.arity() >= 1 }?.elementAt(0)?.let(::seen) == key }
        val entry = tuple(key, value)

        return list(if (index < 0) elements + entry else elements.toMutableList().also { it[index] = entry })
    }

    /** `lists:keydelete(counter, 1, Meta)`. */
    private fun deleteCounter(meta: OtpErlangObject): OtpErlangObject =
        lists("keydelete/3", listOf(atom("counter"), integer(1), meta))!!

    /** `elixir_quote:validate_list/1`. */
    private fun validateList(term: OtpErlangObject) {
        val valid = if (UNQUOTE_SHALLOW_VALIDATED.isSufficient(level)) shallowValidList(term) else isList(term)

        if (!valid) argumentError()
    }

    private fun shallowValidAst(term: OtpErlangObject): Boolean = if (isList(term)) shallowValidList(term) else shallowValidElem(term)

    private fun shallowValidList(term: OtpErlangObject): Boolean {
        val (elements, tail) = cells(term) ?: return false

        return elements.all(::shallowValidElem) && tail == null
    }

    private fun shallowValidElem(term: OtpErlangObject): Boolean {
        ProgressManager.checkCanceled()

        when (val typed = typed(term)) {
            is OtpErlangList, is OtpErlangString, is OtpErlangAtom, is OtpErlangLong, is OtpErlangDouble, is OtpErlangPid,
            is OtpErlangFun, is OtpErlangExternalFun -> return true
            is OtpErlangBitstr -> return typed.pad_bits() == 0
        }

        val tuple = term as? OtpErlangTuple ?: return false

        return when (tuple.arity()) {
            2 -> shallowValidElem(tuple.elementAt(0)) && shallowValidElem(tuple.elementAt(1))
            3 -> {
                val (call, meta, args) = tuple.elements()

                when {
                    !isList(meta) -> false
                    (isAtom(args) || isList(args)) && isAtom(call) -> true
                    isList(args) -> shallowValidAst(call)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun keyword(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        // `get/2` and `pop/2` are `get/3` and `pop/3` with their `nil` default, as a `FunctionClauseError` names them.
        if (key == "get/2" || key == "pop/2") return keyword(key.replace("/2", "/3"), args + NIL)

        val functionClause = { function: String ->
            evaluation.error(atom("function_clause"), frame("Elixir.Keyword", function, list(args)))
        }
        val guarded = { function: String ->
            if (!isList(args[0]) || !isAtom(args[1])) functionClause(function)
        }
        val found = { function: String ->
            guarded(function)

            when (val found = lists("keyfind/3", listOf(args[1], integer(1), args[0]))) {
                FALSE -> null
                else -> (found as OtpErlangTuple).takeIf { it.arity() == 2 }?.elementAt(1)
                    ?: evaluation.error(tuple(atom("case_clause"), found))
            }
        }

        return when (key) {
            "keyword?/1" -> bool(isKeyword(args[0]))
            "get/3" -> found("get") ?: args[2]
            "fetch/2" -> found("fetch")?.let { tuple(atom("ok"), it) } ?: atom("error")
            "fetch!/2" -> found("fetch!") ?: evaluation.raise("Elixir.KeyError", "key" to args[1], "term" to args[0])
            "has_key?/2" -> {
                guarded("has_key?")
                lists("keymember/3", listOf(args[1], integer(1), args[0]))
            }
            "delete/2" -> {
                guarded("delete")
                delete(args[0], args[1])
            }
            "put/3" -> {
                guarded("put")
                cons(listOf(tuple(args[1], args[2])), delete(args[0], args[1]))
            }
            "put_new/3" -> if (found("put_new") != null) args[0] else cons(listOf(tuple(args[1], args[2])), args[0])
            "merge/2" -> {
                val (left, right) = args

                when {
                    isList(left) && properElements(right)?.isEmpty() == true -> left
                    properElements(left)?.isEmpty() == true && isList(right) -> right
                    isList(left) && isList(right) -> {
                        if (!isKeyword(right)) argumentError()

                        val kept = filter(left) { pair ->
                            val name = (seen(pair) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elementAt(0)?.let(::seen) as? OtpErlangAtom
                                ?: argumentError()

                            lists("keymember/3", listOf(name, integer(1), right)) == FALSE
                        }

                        erlang("++/2", listOf(kept, right))
                    }
                    else -> functionClause("merge")
                }
            }
            "take/2", "drop/2" -> {
                val (keywords, keys) = args
                val take = key == "take/2"

                if (!isList(keywords) || !isList(keys)) functionClause(key.substringBefore('/'))

                filter(keywords) { pair ->
                    val name = (seen(pair) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elementAt(0)
                        ?: evaluation.error(atom("function_clause"), frame("Elixir.Keyword", "-${key.substringBefore('/')}/2-fun-0-", list(listOf(pair))))

                    (lists("member/2", listOf(name, keys)) == TRUE) == take
                }
            }
            "pop/3" -> {
                val value = found("pop")

                if (value != null) tuple(value, delete(args[0], args[1])) else tuple(args[2], args[0])
            }
            else -> null
        }
    }

    private fun isKeyword(term: OtpErlangObject): Boolean {
        val (elements, tail) = cells(term) ?: return false

        return tail == null && elements.all { element ->
            (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elementAt(0)?.let(::seen) is OtpErlangAtom
        }
    }

    /** `Keyword.delete/2`'s body, past its guard. */
    private fun delete(keywords: OtpErlangObject, key: OtpErlangObject): OtpErlangObject {
        if (lists("keymember/3", listOf(key, integer(1), keywords)) != TRUE) return keywords

        val (elements, tail) = cells(keywords)!!
        val kept = elements.withIndex().mapNotNull { (index, element) ->
            // `delete_key/2` is called on the rest of the list, from this cell.
            val pair = (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }
                ?: evaluation.error(
                    atom("function_clause"),
                    frame("Elixir.Keyword", "delete_key", list(listOf(cons(elements.subList(index, elements.size), tail ?: EMPTY), key))),
                )

            element.takeUnless { equal(pair.elementAt(0), key, true, level) }
        }

        if (tail != null) evaluation.error(atom("function_clause"), frame("Elixir.Keyword", "delete_key", list(listOf(tail, key))))

        return list(kept)
    }

    /** `:lists.filter/2` of [keep] over [term]: `[E || E <- List, Pred(E)]`, which calls `Pred` on each cell before the tail. */
    private fun filter(term: OtpErlangObject, keep: (OtpErlangObject) -> Boolean): OtpErlangObject {
        val (elements, tail) = cells(term) ?: evaluation.error(tuple(atom("bad_generator"), term))
        val kept = elements.filter {
            ProgressManager.checkCanceled()
            keep(it)
        }

        if (tail != null) evaluation.error(tuple(atom("bad_generator"), tail))

        return list(kept)
    }

    private fun enum(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val enumerable = args.getOrNull(0) ?: return null
        val elements = { enumerate(enumerable) }
        val ran by lazy { running("Elixir.Enum", key, args).single() }

        return when (key) {
            "map/2" ->
                if (isList(enumerable)) {
                    lists("map/2", listOf(ran, enumerable))
                } else {
                    list(elements().map { evaluation.apply(ran, listOf(it)) }.toList())
                }
            "flat_map/2" -> list(elements().flatMap { enumerate(evaluation.apply(ran, listOf(it))) }.toList())
            "filter/2", "reject/2" -> {
                val keep = key == "filter/2"

                list(elements().filter { truthy(evaluation.apply(ran, listOf(it))) == keep }.toList())
            }
            "reduce/3" ->
                if (isList(enumerable)) {
                    lists("foldl/3", listOf(ran, args[1], enumerable))
                } else {
                    elements().fold(args[1]) { acc, element -> evaluation.apply(ran, listOf(element, acc)) }
                }
            "any?/1", "all?/1" -> {
                val any = key == "any?/1"

                bool(if (any) elements().any(::truthy) else elements().all(::truthy))
            }
            "any?/2", "all?/2" -> {
                val initial = key == "all?/2"

                bool(elements().all { truthy(evaluation.apply(ran, listOf(it))) == initial } == initial)
            }
            "member?/2" -> {
                val range = range(enumerable)

                when {
                    isList(enumerable) -> lists("member/2", listOf(args[1], enumerable))
                    range != null -> bool(range.has(args[1]))
                    else -> bool(elements().any { equal(it, args[1], true, level) })
                }
            }
            "concat/2" ->
                if (isList(args[0]) && isList(args[1])) erlang("++/2", args) else decline("concatenates enumerables")
            "reverse/1" ->
                if (isList(enumerable)) {
                    val (heads, tail) = cells(enumerable)!!

                    if (tail == null || heads.size >= 2) {
                        lists("reverse/1", args)
                    } else {
                        lists("foldl/3", listOf(Closure(2) { (element, acc) -> cons(listOf(element), acc) }, EMPTY, enumerable))
                    }
                } else {
                    list(elements().toList().reversed())
                }
            else -> null
        }
    }

    /** [term] where it is a `Range`. */
    private fun range(term: OtpErlangObject): Range? {
        val map = term as? OtpErlangMap ?: return null

        if (map.get(atom("__struct__")) != atom("Elixir.Range")) return null

        val first = (map.get(atom("first")) as? OtpErlangLong)?.bigIntegerValue() ?: decline("a range's first")
        val last = (map.get(atom("last")) as? OtpErlangLong)?.bigIntegerValue() ?: decline("a range's last")
        val step = map.get(atom("step"))?.let { (it as? OtpErlangLong)?.bigIntegerValue() ?: decline("a range's step") }
            ?: if (first <= last) BigInteger.ONE else BigInteger.ONE.negate()

        if (step.signum() == 0) decline("a range's step")

        return Range(first, last, step)
    }

    /** A range's integers, from [first] to [last] by [step], taken as they are read, as Elixir's `Enumerable` does. */
    private class Range(val first: BigInteger, val last: BigInteger, val step: BigInteger) {
        fun elements(): Sequence<OtpErlangObject> =
            generateSequence(first) { it + step }
                .takeWhile { holds(it) }
                .map {
                    ProgressManager.checkCanceled()
                    integer(it)
                }

        /** `Enumerable.Range.member?/2`: an integer within the bounds, a whole number of steps from [first]. */
        fun has(term: OtpErlangObject): Boolean {
            val value = (seen(term) as? OtpErlangLong)?.bigIntegerValue() ?: return false

            return holds(value) && (value - first).mod(step.abs()) == BigInteger.ZERO
        }

        private fun holds(value: BigInteger) = if (step.signum() > 0) value in first..last else value in last..first
    }

    private fun access(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        if (key != "get/2" && key != "get/3") return null

        val (container, accessKey) = args
        val default = args.getOrElse(2) { NIL }

        return when (val value = seen(container)) {
            is OtpErlangMap -> {
                val struct = value.get(atom("__struct__"))?.let(::seen) as? OtpErlangAtom

                if (struct != null) {
                    when (val fetched = evaluation.remote(struct, "fetch", listOf(container, accessKey))) {
                        is OtpErlangTuple if fetched.arity() == 2 && seen(fetched.elementAt(0)) == atom("ok") ->
                            fetched.elementAt(1)
                        atom("error") -> default
                        else -> evaluation.error(tuple(atom("try_clause"), seen(fetched)))
                    }
                } else {
                    valueAt(value, accessKey, level) ?: default
                }
            }
            is OtpErlangList, is OtpErlangString ->
                if (isAtom(accessKey)) {
                    when (val found = lists("keyfind/3", listOf(accessKey, integer(1), container))) {
                        FALSE -> default
                        else -> (found as OtpErlangTuple).takeIf { it.arity() == 2 }?.elementAt(1)
                            ?: evaluation.error(tuple(atom("case_clause"), found))
                    }
                } else {
                    argumentError()
                }
            NIL -> default
            else -> evaluation.error(atom("function_clause"), frame("Elixir.Access", "get", list(listOf(container, accessKey, default))))
        }
    }

    private fun env(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val context = when (key) {
            "in_guard?/1" -> "guard"
            "in_match?/1" -> "match"
            else -> return null
        }
        val env = (seen(args[0]) as? OtpErlangMap)
            ?.takeIf { it.get(atom("__struct__"))?.let(::seen) == atom("Elixir.Macro.Env") }
            ?.get(atom("context"))
            ?: evaluation.error(atom("function_clause"), frame("Elixir.Macro.Env", key.substringBefore('/'), list(args)))

        return bool(seen(env) == atom(context))
    }

    private fun module(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "concat/1" -> concat(args[0].takeIf(::isList) ?: functionClause("Elixir.Module", "concat", args))
            "concat/2" -> {
                if (!args.all { isAtom(it) || isBinary(it) }) functionClause("Elixir.Module", "concat", args)

                concat(list(args))
            }
            "defines?/2", "defines?/3" -> defines(args)
            "has_attribute?/2" -> hasAttribute(args)
            "__get_attribute__/3", "__get_attribute__/4" ->
                if ((args.size == 4) == ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level)) {
                    when (val row = lookup("__get_attribute__", args, guardsModule = false)) {
                        AttributeTable.Lookup.Absent, AttributeTable.Lookup.Unset -> NIL
                        is AttributeTable.Lookup.Holds -> valueOf(row, args)
                    }
                } else {
                    null
                }
            // `get_attribute/2` is the default-argument clause of `get_attribute/3`, which raises with `nil` appended.
            "get_attribute/2" -> getAttribute(args + NIL, NIL)
            "get_attribute/3" -> getAttribute(args, args[2])
            else -> null
        }

    /**
     * `Module.get_attribute/2,3` (`Mod:1637–1639` at v1.20.4, `Mod:1232–1237` at v1.11.4): [default] for an attribute
     * that isn't set, or, before [GET_ATTRIBUTE_DEFAULT_ONLY_UNSET], for any `nil`.
     */
    private fun getAttribute(args: List<OtpErlangObject>, default: OtpErlangObject): OtpErlangObject {
        val onlyUnset = GET_ATTRIBUTE_DEFAULT_ONLY_UNSET.isSufficient(level)

        return when (val row = lookup("get_attribute", args, guardsModule = true)) {
            AttributeTable.Lookup.Absent -> default
            AttributeTable.Lookup.Unset ->
                if (!onlyUnset || GET_ATTRIBUTE_DEFAULT_FOR_REGISTERED.isSufficient(level)) default else NIL
            is AttributeTable.Lookup.Holds -> valueOf(row, args).let { if (!onlyUnset && it == NIL) default else it }
        }
    }

    /**
     * The row of [args]' attribute, as `Module`'s `get_attribute` finds it in the module being compiled, its body run
     * to where the walk is, declining where Elixir's may differ ([Evaluation.compilingKnown]).
     */
    private fun lookup(function: String, args: List<OtpErlangObject>, guardsModule: Boolean): AttributeTable.Lookup {
        val module = seen(args[0]) as? OtpErlangAtom
        val key = seen(args[1]) as? OtpErlangAtom

        if (key == null || guardsModule && module == null) functionClause("Elixir.Module", function, args)

        return attributesOf(module, args[0]).lookup(key.atomValue())
    }

    /**
     * `Module.has_attribute?/2` (`Mod:1268–1273` at v1.11.4, `:1705–1708` at v1.20.4): its guards, then whether the
     * module being compiled has a row for the key, declining where Elixir's may differ.
     */
    private fun hasAttribute(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom
        val key = seen(args[1]) as? OtpErlangAtom

        if (module == null || key == null) functionClause("Elixir.Module", "has_attribute?", args)

        return attributesOf(module, args[0]).has(key.atomValue())?.let(::bool)
            ?: decline("whether %s has %s isn't known", args[0], args[1])
    }

    /** The attributes of [module], being compiled, where they are Elixir's here ([Evaluation.compilingKnown]). */
    private fun attributesOf(module: OtpErlangAtom?, arg: OtpErlangObject): AttributeTable =
        module?.let { evaluation.compilingKnown(it.atomValue()) }?.attributes
            ?: decline("reads an attribute of %s, which isn't known there", arg)

    /**
     * `Application.compile_env/4` and `compile_env!/3` from [COMPILE_ENV_TAKES_ENV_FIRST] (`App:595–646` at v1.20.4),
     * `__compile_env__/4` and `__compile_env__!/3` before it (`App:516–552` at v1.11.4): their env match, then
     * `fetch_compile_env`'s key and `fetch_env/2`'s app guards, then the application's environment as the build
     * configures it, which isn't known, so whether `compile_env!` finds it isn't either and its raise is assumed away.
     * An env that isn't known is taken to be one, which assumes away the raise where it isn't; before
     * [COMPILE_ENV_TAKES_ENV_FIRST] it is read only for its tracers, after the guards, where a binary raises. A tracer
     * the env names records the read, and writes no module. `get_env/2,3`, `fetch_env/2` and `fetch_env!/2`
     * (`App:724–783` at v1.20.4, `App:620–668` at v1.11.4) read the same environment after their `is_atom(app)` guard;
     * `get_env/2` is `get_env/3`'s default.
     */
    private fun application(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val envFirst = COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(level)

        when (key) {
            "get_env/2" -> if (!isAtom(args[0])) functionClause("Elixir.Application", "get_env", args + NIL)
            "get_env/3", "fetch_env/2", "fetch_env!/2" ->
                if (!isAtom(args[0])) functionClause("Elixir.Application", key.substringBefore('/'), args)
            "compile_env/4", "compile_env!/3" -> if (!envFirst) return null
            "__compile_env__/4", "__compile_env__!/3" -> if (envFirst) return null
            else -> return null
        }

        if (key.startsWith("get_env/") || key.startsWith("fetch_env")) {
            if (key == "fetch_env!/2") assumeNoRaise()

            return Unknown
        }

        val env = if (envFirst) args[0] else args.last()
        val (app, path) = if (envFirst) args[1] to args[2] else args[0] to args[1]

        if (envFirst) {
            when {
                env is UnknownValue -> assumeNoRaise()
                (env as? OtpErlangMap)?.get(atom("__struct__"))?.let(::seen) != atom("Elixir.Macro.Env") ->
                    functionClause("Elixir.Application", key.substringBefore('/'), args)
            }
        }

        val keys = seen(path)
        val first = when (keys) {
            is OtpErlangAtom -> keys
            else -> cells(keys)?.let { (elements, _) -> elements.firstOrNull() }?.let(::seen)
        }

        if (first !is OtpErlangAtom) {
            functionClause("Elixir.Application", "fetch_compile_env", listOf(app, path, env))
        }

        if (!isAtom(app)) functionClause("Elixir.Application", "fetch_env", listOf(app, first))

        if (!envFirst) {
            when {
                env is UnknownValue -> assumeNoRaise()
                typed(env) is OtpErlangBinary -> argumentError()
                (env as? OtpErlangMap)?.get(atom("tracers")) == null -> decline("reads the tracers of %s", env)
            }
        }

        // `traverse_env/2` fetches each key after the first from the value before it, which may not be a container.
        if (key.endsWith("!/3") || keys !is OtpErlangAtom && properElements(keys)?.size != 1) assumeNoRaise()

        return Unknown
    }

    /** A raise whose inputs aren't known is taken not to happen: Elixir may have raised there (`Run.assumptions`). */
    private fun assumeNoRaise() {
        evaluation.run.assumptions++
    }

    /** Whether [term] is an atom, declining where that isn't known. */
    private fun isAtom(term: OtpErlangObject): Boolean = typed(term) is OtpErlangAtom

    private fun valueOf(row: AttributeTable.Lookup.Holds, args: List<OtpErlangObject>): OtpErlangObject =
        when (val value = row.value) {
            is AttributeValue.Known -> evaluation.otpOf(value.term)
            AttributeValue.Unknown -> decline("the value of %s's %s isn't known", args[0], args[1])
        }

    /**
     * `Module.defines?/2,3` (`Mod:949–987` at v1.11.4, `:1253–1290` at v1.20.4): its guards, then the table of the
     * module being compiled, declining where Elixir's may differ from it.
     */
    private fun defines(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom
        val definition = (seen(args[1]) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }
        val name = definition?.let { seen(it.elementAt(0)) as? OtpErlangAtom }
        val arity = definition?.let { seen(it.elementAt(1)) as? OtpErlangLong }
            ?.takeIf { it.isLong && it.longValue() in 0..255 }
        val kind = args.getOrNull(2)?.let { (seen(it) as? OtpErlangAtom)?.atomValue() }
            ?.let { kind -> DefinitionTable.Kind.entries.firstOrNull { it.name.lowercase() == kind } }

        if (module == null || name == null || arity == null || args.size == 3 && kind == null) {
            functionClause("Elixir.Module", "defines?", args)
        }

        val nameArity = NameArity(name.atomValue(), arity.longValue().toInt())

        return evaluation.compilingKnown(module.atomValue())?.defines(nameArity, kind)?.let(::bool)
            ?: decline("whether ${module.atomValue()} defines ${nameArity.name}/${nameArity.arity} isn't known")
    }

    /** `elixir_aliases:concat/1` of the list [parts]. */
    private fun concat(parts: OtpErlangObject): OtpErlangObject {
        val (elements, tail) = cells(parts)!!
        val bytes = { part: OtpErlangObject ->
            when (val seen = seen(part)) {
                is OtpErlangAtom -> binary(seen.atomValue()).binaryValue()
                else -> seen.takeIf(::isBinary)?.let { (it as OtpErlangBitstr).binaryValue() }
            }
        }
        val elixir = "Elixir".toByteArray()
        val first = elements.firstOrNull()?.takeIf { it != NIL }?.let(bytes)
        // `do_concat/1`: a first part that is already a module name is where the name starts.
        val start = first?.takeIf { startsWith(it, "Elixir.".toByteArray()) || it.contentEquals(elixir) }
        val acc = ByteArrayOutputStream().apply { write(start ?: elixir) }
        val rest = if (start != null) elements.drop(1) else elements
        val doConcat = { remaining: OtpErlangObject ->
            functionClause("elixir_aliases", "do_concat", listOf(remaining, OtpErlangBinary(acc.toByteArray())))
        }

        for ((index, part) in rest.withIndex()) {
            ProgressManager.checkCanceled()

            if (part == NIL) continue

            val partial = bytes(part) ?: doConcat(cons(rest.drop(index), tail ?: EMPTY))
            val prefix = listOf("Elixir.", ".").map { it.toByteArray() }.firstOrNull { startsWith(partial, it) }?.size ?: 0

            reserveBits(acc.size() * 8L, (1L + partial.size - prefix) * 8)
            acc.write('.'.code)
            acc.write(partial, prefix, partial.size - prefix)
        }

        if (tail != null) doConcat(tail)

        return erlang("binary_to_atom/2", listOf(OtpErlangBinary(acc.toByteArray()), atom("utf8")))!!
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray) =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun string(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val function = when (key) {
            "to_atom/1" -> "binary_to_atom/2"
            "to_existing_atom/1" -> "binary_to_existing_atom/2"
            else -> return null
        }

        if (STRING_TO_ATOM_REQUIRES_BINARY.isSufficient(level) && !isBinary(args[0])) {
            functionClause("Elixir.String", key.substringBefore('/'), args)
        }

        return erlang(function, listOf(args[0], atom("utf8")))
    }

    /** `String.Chars.to_string/1` by its `BitString`, `Atom` and `Integer` implementations; the others aren't read. */
    private fun toString(term: OtpErlangObject): OtpErlangObject =
        when {
            isBinary(term) -> term
            term == NIL -> binary("")
            term is OtpErlangAtom -> binary(term.atomValue())
            term is OtpErlangLong -> binary(term.bigIntegerValue().toString())
            else -> decline("String.Chars of %s", term)
        }

    private fun kernel(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "elem/2" -> erlang("element/2", listOf(erlang("+/2", listOf(args[1], integer(1)))!!, args[0]))
            "put_elem/3" -> erlang("setelement/3", listOf(erlang("+/2", listOf(args[1], integer(1)))!!, args[0], args[2]))
            "inspect/1" -> UnknownBinary
            "inspect/2" -> inspect(args[1], args)
            else -> null
        }

    /**
     * `Kernel.inspect/2`, `when is_list(opts)`: with no options, a binary whose bytes aren't known, as `inspect/1` gives.
     * Options decline: which of them raise is `Inspect.Opts`' and `Inspect.Algebra`'s to say, and those aren't read.
     */
    private fun inspect(options: OtpErlangObject, args: List<OtpErlangObject>): OtpErlangObject =
        when {
            options == EMPTY -> UnknownBinary
            !isUnknown(options) && !isList(options) -> functionClause("Elixir.Kernel", "inspect", args)
            else -> decline("Kernel.inspect/2 with options %s", options)
        }

    private fun macro(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        when (key) {
            "expand/2" -> return evaluation.macroExpand(args[0], args[1], once = false)
            "expand_once/2" -> return evaluation.macroExpand(args[0], args[1], once = true)
            "escape/1", "escape/2" -> {}
            else -> return null
        }

        val options = args.getOrElse(1) { EMPTY }
        val option = { name: String -> keyword("get/3", listOf(options, atom(name), FALSE))!! }
        val unquote = seen(option("unquote")) == TRUE
        val op = if (truthy(option("prune_metadata"))) EscapeOp.EscapeAndPrune else EscapeOp.Escape

        val generated = if (MACRO_ESCAPE_MARKS_GENERATED.isSufficient(level)) option("generated") else FALSE
        val escape = QuoteEscape(TermEscapeView, TermEscapeBuilder, { it }, level, evaluation.run.exports, op, unquote)
        val escaped = when (val escaped = escape.escape(args[0])) {
            is EscapeResult.Built -> escaped.value
            is EscapeResult.Raised -> evaluation.raise(escaped.exception)
            is EscapeResult.Declined -> decline("Macro.escape/2 of %s", args[0])
        }
        // `{caller, meta, args} when generated and is_list(meta)`.
        val node = (escaped as? OtpErlangTuple)?.takeIf { it.arity() == 3 && seen(generated) == TRUE && isList(it.elementAt(1)) }
            ?: return escaped

        return tuple(node.elementAt(0), cons(listOf(tuple(atom("generated"), TRUE)), node.elementAt(1)), node.elementAt(2))
    }

    /** [term] as a type test reads it: a binary whose bytes aren't known is a binary, as only reading them declines. */
    private fun typed(term: OtpErlangObject): OtpErlangObject = if (term === UnknownBinary) SOME_BINARY else seen(term)

    private fun functionClause(module: String, function: String, args: List<OtpErlangObject>): Nothing =
        evaluation.error(atom("function_clause"), frame(module, function, list(args)))

    /** An `ArgumentError`, whose message `inspect` builds. */
    private fun argumentError(): Nothing = evaluation.raise("Elixir.ArgumentError", "message" to UnknownBinary)

    private fun badarith(): Nothing = evaluation.error(atom("badarith"))

    private fun arithmetic(operator: Char, left: OtpErlangObject, right: OtpErlangObject): OtpErlangObject {
        val l = number(left) ?: badarith()
        val r = number(right) ?: badarith()

        if (l is OtpErlangLong && r is OtpErlangLong) {
            val (a, b) = l.bigIntegerValue() to r.bigIntegerValue()

            return bounded(
                when (operator) {
                    '+' -> a + b
                    '-' -> a - b
                    else -> a * b
                }
            )
        }

        val (a, b) = double(l) to double(r)

        return finite(
            when (operator) {
                '+' -> a + b
                '-' -> a - b
                else -> a * b
            }
        )
    }

    /**
     * [value], or `system_limit` past the VM's largest integer: 65,535 words from [INTEGERS_UP_TO_4_MBIT], 524,287
     * before. The lower bound came in a patch release of each OTP from 24, so between the two it depends on the patch.
     */
    private fun bounded(value: BigInteger): OtpErlangObject {
        val bits = value.abs().bitLength()

        return when {
            fitsEveryVmInteger(bits) -> integer(value)
            bits > OLD_INTEGER_WORDS * Long.SIZE_BITS || INTEGERS_UP_TO_4_MBIT.isSufficient(level) ->
                evaluation.error(atom("system_limit"))
            else -> decline("an integer whose limit depends on the OTP patch release")
        }
    }

    private fun finite(value: Double): OtpErlangObject =
        if (value.isNaN() || value.isInfinite()) badarith() else OtpErlangDouble(value)

    /** An integer past the float range raises, as Erlang's conversion does. */
    private fun double(number: OtpErlangObject): Double =
        when (number) {
            is OtpErlangLong -> number.bigIntegerValue().toDouble().also { if (it.isInfinite()) badarith() }
            else -> (number as OtpErlangDouble).doubleValue()
        }

    private fun number(term: OtpErlangObject): OtpErlangObject? = typed(term).takeIf(::isNumber)

    private fun boolean(term: OtpErlangObject): Boolean? =
        when (typed(term)) {
            TRUE -> true
            FALSE -> false
            else -> null
        }

    /** The arity of the `fun` [term], `null` for any other term. */
    private fun arity(term: OtpErlangObject): Int? =
        when (val function = typed(term)) {
            is Closure -> function.arity
            is OtpErlangExternalFun -> function.arity
            is OtpErlangFun -> decline("a fun the evaluator didn't make")
            else -> null
        }

    private fun interface Function {
        fun call(vararg values: OtpErlangObject): OtpErlangObject
    }

    /** The arguments of [module]'s function [key], given [args], that name the function it runs ([RUNS]). */
    private fun running(module: String, key: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        RUNS.getValue(Triple(module, key.substringBeforeLast('/'), args.size)).map(args::get)

    /** [term] as a function of [arity] arguments, `null` where it isn't one. */
    private fun function(term: OtpErlangObject, arity: Int): Function? =
        if (arity(term) == arity) Function { values -> evaluation.apply(term, values.toList()) } else null

    /** [term] as a small integer, the only integer a BIF takes as a position or an arity. */
    private fun small(term: OtpErlangObject): Long? =
        (seen(term) as? OtpErlangLong)?.takeIf { it.isLong }?.longValue()?.takeIf { it in SMALL_INTEGERS }

    /** [term] as a 1-based index of a tuple of [size] elements. */
    private fun index(term: OtpErlangObject, size: Int): Int? =
        (seen(term) as? OtpErlangLong)?.takeIf { it.isLong }?.longValue()?.takeIf { it in 1..size }?.toInt()

    private fun map(term: OtpErlangObject): OtpErlangMap =
        seen(term) as? OtpErlangMap ?: evaluation.error(tuple(atom("badmap"), term))

    private fun isList(term: OtpErlangObject) = typed(term).let { it is OtpErlangList || it is OtpErlangString }

    private fun bitSize(bitstring: OtpErlangBitstr) = bitstring.binaryValue().size * 8 - bitstring.pad_bits()

    private fun isBinary(term: OtpErlangObject) = typed(term).let { it is OtpErlangBitstr && it.pad_bits() == 0 }

    /** The text of the binary [term], `null` where it isn't a binary of UTF-8. */
    private fun utf8(term: OtpErlangObject): String? {
        if (!isBinary(term)) return null

        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap((seen(term) as OtpErlangBitstr).binaryValue())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** [TermEscapeView], declining on a value it doesn't know. */
    private val view = EscapeView<OtpErlangObject> { TermEscapeView.shape(seen(it)) }

}

/** The node of a process or reference the evaluator makes. */
private const val LOCAL_NODE = "nonode@nohost"

/** `self()`: the one process a macro body runs in. */
private val SELF = OtpErlangPid(LOCAL_NODE, 0, 0, 0)

/** A binary that stands for [UnknownBinary] in a type test. */
private val SOME_BINARY = OtpErlangBinary(ByteArray(0))

/** The small integers of a 64-bit VM. */
private val SMALL_INTEGERS = -(1L shl 59) until (1L shl 59)

/** The words in the VM's largest integer, `BIG_ARITY_MAX` on a 64-bit VM ([INTEGERS_UP_TO_4_MBIT]). */
private const val INTEGER_WORDS = (1 shl 16) - 1

/** Whether an integer of [bits] bits, its sign apart, is within the VM's largest on every OTP ([INTEGER_WORDS]). */
internal fun fitsEveryVmInteger(bits: Int) = bits <= INTEGER_WORDS * Long.SIZE_BITS

/** [INTEGER_WORDS] before [INTEGERS_UP_TO_4_MBIT]. */
private const val OLD_INTEGER_WORDS = (1 shl 19) - 1
