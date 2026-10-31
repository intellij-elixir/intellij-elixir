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
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.NameArity
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.admits
import org.elixir_lang.expander.Annotation
import org.elixir_lang.expander.AppEnvValue
import org.elixir_lang.expander.AttributeTable
import org.elixir_lang.expander.AttributeValue
import org.elixir_lang.expander.Compiling
import org.elixir_lang.expander.ConfigRead
import org.elixir_lang.expander.DEFINITION_ARITIES
import org.elixir_lang.expander.DefinitionTable
import org.elixir_lang.expander.EffectOutcome
import org.elixir_lang.expander.Env
import org.elixir_lang.expander.EscapeOp
import org.elixir_lang.expander.EscapeView
import org.elixir_lang.expander.EscapeResult
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.ModuleAttributes
import org.elixir_lang.expander.ModuleEffects
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.QuoteEscape
import org.elixir_lang.expander.Shape
import org.elixir_lang.expander.TermEscapeBuilder
import org.elixir_lang.expander.TermEscapeView
import org.elixir_lang.expander.annotation
import org.elixir_lang.expander.defines
import org.elixir_lang.expander.specialFormArity
import org.elixir_lang.expander.write
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.BINARY_PART_REJECTS_BITSTRINGS
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.language_level.ElixirLanguageFeature.ERLANG_ERROR_3
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_FOR_REGISTERED
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_ONLY_UNSET
import org.elixir_lang.language_level.ElixirLanguageFeature.GRAPHEME_CLUSTER_KEEPS_EMPTY_TAIL
import org.elixir_lang.language_level.ElixirLanguageFeature.INTEGERS_UP_TO_4_MBIT
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_CHECK_THE_FUN_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_SORT_KEEPS_EQUAL_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.RAISE_FRAME_TAKES_A_CAPTURE
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_ATTRIBUTES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_GET_LAST_ATTRIBUTE
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_OVERRIDABLES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.OVERRIDABLE_GUARDS_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.SPLICE_EMPTY_BEFORE_TAIL_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.STATIC_OPTIONS_IN_PERSISTENT_TERM
import org.elixir_lang.language_level.ElixirLanguageFeature.STRING_TO_ATOM_REQUIRES_BINARY
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.lowering.fitsAnAtom
import org.elixir_lang.model.psi.config.ConfigFiles
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.unicode_util.Graphemes
import org.elixir_lang.unicode_util.isScalarValue
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.math.abs

/** The frames a stacktrace `erlang:raise/3` stores holds at most: the default `backtrace_depth`, which Elixir keeps. */
private const val BACKTRACE_DEPTH = 8

/**
 * Each native that can return, or return a part of, fewer than all its arguments, by receiver, name and arity, to the
 * indices of those it can; any other can return any of them. An app-env read returns the environment's value, which
 * came from no module, or a default: `get_env/3`'s and `compile_env/4`'s where the key isn't set (`App:724`, `:595` at
 * v1.20.4), and `__compile_env__/4`'s before it (`App:516` at v1.11.4). `Module`'s attribute reads, and
 * `delete_attribute/2`, return a value of the module their first argument names, or the default they are given; its
 * predicates and other writers a boolean or `:ok`, and its listings names. `IO.warn/1,2` returns `:ok`, as do
 * `application`'s env writers. `binary:split/3` and `filename:basename/2` return parts of their first argument alone.
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
    Triple("Elixir.Module", "get_last_attribute", 2) to listOf(0),
    Triple("Elixir.Module", "get_last_attribute", 3) to listOf(0, 2),
    Triple("Elixir.Module", "open?", 1) to emptyList(),
    Triple("Elixir.Module", "definitions_in", 1) to emptyList(),
    Triple("Elixir.Module", "definitions_in", 2) to emptyList(),
    Triple("Elixir.Module", "overridable?", 2) to emptyList(),
    Triple("Elixir.Module", "overridables_in", 1) to emptyList(),
    Triple("Elixir.Module", "attributes_in", 1) to emptyList(),
    Triple("Elixir.Module", "put_attribute", 3) to emptyList(),
    Triple("Elixir.Module", "__put_attribute__", 4) to emptyList(),
    Triple("Elixir.Module", "__put_attribute__", 5) to emptyList(),
    Triple("Elixir.Module", "register_attribute", 3) to emptyList(),
    Triple("Elixir.Module", "delete_attribute", 2) to listOf(0),
    Triple("Elixir.Module", "make_overridable", 2) to emptyList(),
    Triple("Elixir.Module", "delete_definition", 2) to emptyList(),
    Triple("elixir_def", "take_definition", 2) to emptyList(),
    Triple("Elixir.IO", "warn", 1) to emptyList(),
    Triple("Elixir.IO", "warn", 2) to emptyList(),
    Triple("erlang", "whereis", 1) to emptyList(),
    Triple("application", "set_env", 1) to emptyList(),
    Triple("application", "set_env", 2) to emptyList(),
    Triple("application", "set_env", 3) to emptyList(),
    Triple("application", "set_env", 4) to emptyList(),
    Triple("application", "unset_env", 2) to emptyList(),
    Triple("application", "unset_env", 3) to emptyList(),
    Triple("binary", "split", 3) to listOf(0),
    Triple("filename", "basename", 2) to listOf(0),
)

/** The `filename` functions answered here, by name and arity. */
private val FILENAME_NATIVES = setOf("dirname/1", "basename/1", "basename/2", "split/1")

/**
 * Each native that never returns, whatever it is given, by receiver, name and arity: those `raise`, `throw` and `exit`
 * lower to. `erlang:raise/3` returns `badarg` where its class or stacktrace is invalid.
 */
private val NEVER_RETURNS = setOf(
    Triple("erlang", "error", 1),
    Triple("erlang", "error", 2),
    Triple("erlang", "error", 3),
    Triple("erlang", "throw", 1),
    Triple("erlang", "exit", 1),
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
    Triple("lists", "foreach", 2) to listOf(0),
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

        /**
         * The modules whose writers, the functions [ModuleEffects] has a row for, are answered here by that row
         * ([write]). Another's writer, `Kernel.Typespec.deftypespec/6`, runs from its clauses.
         */
        val WRITES_BY_ROW = setOf("Elixir.Module", "elixir_def")
    }

    private val level get() = evaluation.run.level

    /** [module]'s [function] applied to [args], or `null` where it isn't one of these. */
    fun call(module: String, function: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val key = "$function/${args.size}"

        if (key == "__info__/1") info(module, args[0])?.let { return it }

        return when (module) {
            "erlang" -> erlang(key, args)
            "lists" -> lists(key, args)
            "maps" -> maps(key, args)
            "elixir_quote" -> quote(key, args)
            "elixir_module" -> elixirModule(key, args)
            "code" -> code(key, args)
            "application" -> envWrite(key, args)
            "unicode" -> unicode(key, args)
            "unicode_util" -> if (key == "gc/1") gc(seen(args[0])) else null
            "Elixir.Keyword" -> keyword(key, args)
            "Elixir.Enum" -> enum(key, args)
            "Elixir.Access" -> access(key, args)
            "Elixir.Macro.Env" -> env(key, args)
            "Elixir.IO" -> io(key, args)
            "Elixir.Module" -> module(key, args)
            "Elixir.Application" -> application(key, args)
            "Elixir.Atom" -> if (key == "to_string/1") erlang("atom_to_binary/1", args) else null
            "Elixir.String" -> string(key, args)
            "Elixir.String.Chars" -> if (key == "to_string/1") toString(args[0]) else null
            "Elixir.Kernel" -> kernel(key, args)
            "Elixir.Macro" -> macro(key, args)
            "ordsets" -> if (key == "is_element/2") isElement(args[0], args[1]) else null
            "binary" -> binaries(key, args)
            "os" -> if (key == "type/0") osType() else null
            "file" -> if (key == "get_cwd/0") workingDirectory() else null
            "filename" -> filename(key, args)
            "elixir_config" -> config(key, args)
            "Elixir.Mix" -> mix(key)
            "Elixir.Config" -> configure(key, args)
            "Elixir.Mix.Config" -> if (key == "__import__!/2") mixConfigImport(args[0], args[1]) else null
            "Elixir.File" -> if (key == "exists?/1" || key == "exists?/2") fileExists(args[0]) else null
            "elixir_import" -> if (key == "special_form/2") isSpecialForm(args[0], args[1]) else null
            else -> null
        } ?: if (module in WRITES_BY_ROW) write(module, key, args) else null
    }

    /**
     * The arguments of [module]'s [function], given [args], that a native [call] may return, or return a part of, where
     * its result isn't known or it declines ([RETURNABLE]). An argument it only reads, such as `compile_env/4`'s env, is
     * none of them.
     */
    fun <T> returnable(module: String, function: String, args: List<T>): List<T> =
        RETURNABLE[Triple(module, function, args.size)]?.map(args::get) ?: args

    /**
     * What [module]'s [function], given [args], runs, given what: every argument where it runs a function its arguments
     * name ([RUNS]), known or not, or where a function is held in any ([anyHeld]), which the others may flow into, and
     * none otherwise.
     */
    fun runs(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        args.takeIf {
            runsAFunction(module, function, args.size) ||
                anyHeld(it) { term -> term is Closure || term is OtpErlangExternalFun }
        }.orEmpty()

    /** Whether [module]'s [function] of [arity] runs a function it is given ([RUNS]). */
    fun runsAFunction(module: String, function: String, arity: Int): Boolean = Triple(module, function, arity) in RUNS

    /**
     * The argument of [module]'s [function], given [args], that names the module it writes: the one its [ModuleEffects]
     * row names, none where it has no row. Where a native [call] of it declines, it may have written that module.
     */
    fun writes(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        listOfNotNull(ModuleEffects.target(module, function, args.size, level)?.let(args::get))

    /** Whether [module]'s [function] of [arity] is a `Module` native that only reads its tables. */
    fun onlyReads(module: String, function: String, arity: Int): Boolean =
        module == "Elixir.Module" && Triple(module, function, arity) in RETURNABLE && ModuleEffects.target(module, function, arity, level) == null

    /** The value of [module]'s attribute [name] here, `null` where it isn't known. */
    fun attributeAt(module: String, name: String): OtpErlangObject? =
        try {
            getAttribute(listOf(atom(module), atom(name)), NIL)
        } catch (_: Declined) {
            null
        }

    /** Whether a delete of [module]'s attribute [name] is known to change nothing here. */
    fun deleteChangesNothing(module: String, name: String): Boolean =
        try {
            attributesOf(atom(module), atom(module)).deleteChanges(name) == false
        } catch (_: Declined) {
            false
        }

    /** Whether [module]'s [function] of [arity] never returns ([NEVER_RETURNS]). */
    fun neverReturns(module: String, function: String, arity: Int): Boolean =
        Triple(module, function, arity) in NEVER_RETURNS

    /** `erlang:map_get/2`, as a guard's `map.key` reads it. */
    fun mapGet(key: OtpErlangObject, map: OtpErlangObject): OtpErlangObject = erlang("map_get/2", listOf(key, map))!!

    /**
     * The elements a comprehension's generator takes from [enumerable], each after a cancellation check: a list's, a
     * range's, or a map's entries as `Enumerable.Map` takes them, from `maps:to_list/1` ([inOrder]). Another struct
     * enumerates through its own implementation.
     */
    fun enumerate(enumerable: OtpErlangObject): Sequence<OtpErlangObject> =
        properElements(enumerable)?.asSequence()?.onEach { ProgressManager.checkCanceled() } ?: range(enumerable)?.elements()
            ?: (enumerable as? OtpErlangMap)?.takeIf { it.get(atom("__struct__")) == null }?.let { map ->
                inOrder(map).asSequence().map { (key, value) ->
                    ProgressManager.checkCanceled()
                    tuple(key, value)
                }
            }
            ?: decline("enumerates %s", enumerable)

    /**
     * `Module.__info__(:attributes)` (`elixir_erl.erl:364-366`) of a module that has `__info__/1`, from the attributes its
     * `.beam` persists, declining where they can't be read; `null`, as its clauses answer, for any other key or module.
     */
    private fun info(module: String, key: OtpErlangObject): OtpErlangObject? {
        if (seen(key) != atom("attributes")) return null

        val exports = evaluation.run.exports.of(module) as? ModuleExports.Present ?: return null

        if (!exports.hasInfo) return null

        return when (val attributes = exports.attributes) {
            is ModuleAttributes.Known -> attributes.keyword
            ModuleAttributes.Unreadable -> decline("$module's attributes aren't known")
        }
    }

    /**
     * `elixir_module:next_counter/1` (`elixir_module.erl:64-71`), from the [Counters][org.elixir_lang.expander.Counters]
     * the run's expansions take theirs from: `{Module, N}` for a module being compiled, whose table holds its counter, and
     * a unique integer for `nil` or any other term, as Elixir's `catch` gives.
     */
    private fun elixirModule(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "next_counter/1" -> {
                val module = (seen(args[0]) as? OtpErlangAtom)?.atomValue()?.takeIf { it in evaluation.run.compiling }

                when (val counter = evaluation.run.counters.next(module)) {
                    is Env.Counter.InModule -> tuple(atom(counter.module), integer(counter.n))
                    is Env.Counter.Unique -> integer(counter.n)
                }
            }
            else -> null
        }

    /**
     * `code:ensure_loaded/1` (`code.erl:581-604`), which `Code.ensure_loaded/1`, `ensure_loaded?/1` and
     * `ensure_compiled/1` reach, from the run's exports, as the code server answers in interactive mode: `{module, M}`
     * for a module that loads, `{error, nofile}` for one that doesn't, and a decline where which isn't known.
     */
    private fun code(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "ensure_loaded/1" -> {
                val module = seen(args[0]) as? OtpErlangAtom ?: functionClause("code", "ensure_loaded", args)

                when (evaluation.run.exports.of(module.atomValue())) {
                    is ModuleExports.Present -> tuple(atom("module"), module)
                    ModuleExports.Absent -> tuple(atom("error"), atom("nofile"))
                    ModuleExports.Unreadable -> decline("whether ${module.atomValue()} loads isn't known")
                }
            }
            else -> null
        }

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
            // The processes the build runs, which aren't known.
            "whereis/1" -> if (isAtom(args[0])) Unknown else badarg()
            "make_ref/0" -> OtpErlangRef(LOCAL_NODE, intArrayOf(evaluation.run.nextRef(), 0, 0), 0)
            "</2" -> bool(compare(args[0], args[1], level) < 0)
            ">/2" -> bool(compare(args[0], args[1], level) > 0)
            "=</2" -> bool(compare(args[0], args[1], level) <= 0)
            ">=/2" -> bool(compare(args[0], args[1], level) >= 0)
            "min/2" -> if (compare(args[0], args[1], level) > 0) args[1] else args[0]
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
            "binary_part/3" -> {
                val bitstring = seen(args[0]) as? OtpErlangBitstr ?: badarg()

                if (!isBinary(bitstring) && BINARY_PART_REJECTS_BITSTRINGS.isSufficient(level)) badarg()

                // Before OTP 27 a bitstring's part is taken from its whole bytes.
                val bytes = bitstring.binaryValue().copyOf(bitSize(bitstring) / Byte.SIZE_BITS)
                val (start, length) = args.drop(1).map {
                    (seen(it) as? OtpErlangLong)?.takeIf { long -> long.bitLength() < Int.SIZE_BITS }?.longValue() ?: badarg()
                }
                val (from, to) = if (length < 0) start + length to start else start to start + length

                if (from < 0 || to > bytes.size) badarg()

                OtpErlangBinary(bytes.copyOfRange(from.toInt(), to.toInt()))
            }
            "byte_size/1" -> integer(((bitSize(seen(args[0]) as? OtpErlangBitstr ?: badarg()) + 7) / 8).toLong())
            "iolist_to_binary/1" -> OtpErlangBinary(iodataBytes(args[0], badarg))
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
                val (left, right) = args.map(::bigInteger)

                if (right.signum() == 0) badarith()

                integer(if (key == "div/2") left.divide(right) else left.rem(right))
            }
            "band/2", "bor/2", "bxor/2" -> {
                val (left, right) = args.map(::bigInteger)

                integer(
                    when (key) {
                        "band/2" -> left.and(right)
                        "bor/2" -> left.or(right)
                        else -> left.xor(right)
                    }
                )
            }
            "bnot/1" -> integer(bigInteger(args[0]).not())
            "bsl/2", "bsr/2" -> {
                val (value, by) = args.map(::bigInteger)

                shifted(value, if (key == "bsl/2") by else by.negate())
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
            "atom_to_list/1" ->
                list((seen(args[0]) as? OtpErlangAtom ?: badarg()).atomValue().codePoints().toArray().map { integer(it.toLong()) })
            "md5/1" -> OtpErlangBinary(MessageDigest.getInstance("MD5").digest(iodataBytes(args[0], badarg)))
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
            "foreach/2" -> {
                val function = function(ran, 1) ?: listsError(atom("function_clause"), frame("lists", "foreach", list(args)))
                val helper = { list: OtpErlangObject -> frame("lists", "foreach_1", list(listOf(args[0], list))) }

                forEach(args[1], { listsError(atom("function_clause"), helper(args[1])) }, helper) { function.call(it) }

                atom("ok")
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

        keyFound(elements, n, ::seen) { equal(it, args[0], false, level) }?.let { return it }

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
    ): List<OtpErlangObject> = mutableListOf<OtpErlangObject>().also { results -> forEach(term, notList, helper) { results += transform(it) } }

    /**
     * [action] on each element of the list [term], raising [notList] where it isn't one, and `function_clause` in the
     * helper whose [helper] frame meets an improper tail. What [action] gives is dropped, as `lists:foreach/2` drops it.
     */
    private fun forEach(
        term: OtpErlangObject,
        notList: () -> Nothing,
        helper: (OtpErlangObject) -> OtpErlangTuple,
        action: (OtpErlangObject) -> Unit,
    ) {
        val (elements, tail) = cells(term) ?: notList()

        elements.forEach(action)

        if (tail != null) listsError(atom("function_clause"), helper(tail))
    }

    /** An error a `lists` function raises on its arguments, which before OTP 25 the VM gave otherwise. */
    private fun listsError(reason: OtpErlangObject, top: OtpErlangTuple? = null): Nothing {
        if (!LISTS_CHECK_THE_FUN_FIRST.isSufficient(level)) decline("an error of a lists function before OTP 25")

        evaluation.error(reason, top)
    }

    /**
     * `lists:do_flatten/2`: [term] flattened onto the list [reversed] holds back to front. Each cell's rest is flattened
     * before the list at its head, so an improper tail raises with what follows it already flattened. It declines once
     * it has met more than [COPY_NODES] elements, nested lists included, [met] of them before [term], and gives how many
     * it has met.
     */
    private fun flatten(term: OtpErlangObject, reversed: MutableList<OtpErlangObject>, met: Int = 0): Int {
        val (elements, tail) = cells(term)!!

        if (tail != null) {
            evaluation.error(atom("function_clause"), frame("lists", "do_flatten", list(listOf(tail, list(reversed.reversed())))))
        }

        var count = met

        for (element in elements.asReversed()) {
            ProgressManager.checkCanceled()

            if (++count > COPY_NODES) decline("lists:flatten/1 of a list too large to copy")
            if (isList(element)) count = flatten(element, reversed, count) else reversed += element
        }

        return count
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
            "to_list/1" -> list(inOrder(map(args[0])).map { tuple(it.first, it.second) })
            "keys/1" -> list(inOrder(map(args[0])).map { it.first })
            else -> null
        }

    /**
     * [map]'s entries in the order `maps:to_list/1` gives them: term order, declining over 32 keys.
     */
    private fun inOrder(map: OtpErlangMap): List<Pair<OtpErlangObject, OtpErlangObject>> {
        val entries = entries(map)

        if (entries.size > 32) decline("the order of a map of more than 32 keys")

        return entries.sortedWith { left, right -> compare(left.first, right.first, level, true) }
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

        if (hasOwnImplementation(enumerable)) return null

        val elements = { enumerate(enumerable) }
        val ran by lazy { running("Elixir.Enum", key, args).single() }

        return when (key) {
            "map/2" ->
                if (isList(enumerable)) {
                    lists("map/2", listOf(ran, enumerable))
                } else {
                    list(elements().map { evaluation.apply(ran, listOf(it)) }.toList())
                }
            "flat_map/2" ->
                list(
                    elements().flatMap { element ->
                        val returned = evaluation.apply(ran, listOf(element))

                        enumerate(
                            if (hasOwnImplementation(returned)) {
                                evaluation.remote(atom("Elixir.Enum"), "to_list", listOf(returned))
                            } else {
                                returned
                            },
                        )
                    }.toList(),
                )
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
                if (isList(args[0]) && isList(args[1])) erlang("++/2", args) else null
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

    /** Whether [term] is a struct or a function, which enumerates through its own implementation, called by `Enum`'s clauses. */
    private fun hasOwnImplementation(term: OtpErlangObject): Boolean =
        term is Closure || term is OtpErlangExternalFun ||
            term is OtpErlangMap && term.get(atom("__struct__")) != null && range(term) == null

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
            "stacktrace/1" -> return stacktrace(macroEnv(args[0]) ?: functionClause("Elixir.Macro.Env", "stacktrace", args))
            else -> return null
        }
        val env = macroEnv(args[0])?.get(atom("context"))
            ?: functionClause("Elixir.Macro.Env", key.substringBefore('/'), args)

        return bool(seen(env) == atom(context))
    }

    /**
     * `IO.warn/1,2` (`io.ex`), recorded as the macro call's warning, with its stacktrace as given: `IO.warn/1`'s is the
     * caller's, which isn't known. A message that isn't a binary of UTF-8, and the location an env or options give,
     * decline; a second argument that is none of those, `[]` or a stacktrace raises `function_clause`.
     */
    private fun io(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        if (key != "warn/1" && key != "warn/2") return null

        val message = utf8(args[0]) ?: decline("IO.warn/${args.size} of %s", args[0])
        val stacktrace = args.getOrNull(1)?.also { given ->
            if (macroEnv(given) != null) decline("IO.warn/2 at a Macro.Env")

            val (elements, _) = cells(seen(given)) ?: functionClause("Elixir.IO", "warn", args)

            when ((elements.firstOrNull()?.let(::seen) as? OtpErlangTuple)?.arity()) {
                null -> if (elements.isNotEmpty()) functionClause("Elixir.IO", "warn", args)
                4 -> {}
                2 -> decline("IO.warn/2 with options %s", given)
                else -> functionClause("Elixir.IO", "warn", args)
            }
        }

        evaluation.warn(message, stacktrace)

        return atom("ok")
    }

    /** [term] where it is a `%Macro.Env{}`, `null` where it is something else. */
    private fun macroEnv(term: OtpErlangObject): OtpErlangMap? =
        (seen(term) as? OtpErlangMap)?.takeIf { it.get(atom("__struct__"))?.let(::seen) == atom("Elixir.Macro.Env") }

    /**
     * `Macro.Env.stacktrace/1` (`env.ex`): [env]'s one frame, the compiler's outside a module and the module body's
     * outside a function, at its line and its file relative to where Elixir compiled it, which is unknown where that
     * directory is (`Run.workingDirectory`).
     */
    private fun stacktrace(env: OtpErlangMap): OtpErlangObject {
        val field = { name: String -> env.get(atom(name))?.let(::seen) ?: decline("a Macro.Env without %s", atom(name)) }
        val file = utf8(field("file")) ?: decline("Macro.Env.stacktrace/1 of a file that isn't UTF-8 text")
        val os = evaluation.run.os ?: decline("Path.relative_to_cwd/1 of a file whose SDK's OS isn't known")
        val relative = evaluation.run.relativeToCwd(file, os)
        val location = list(
            listOf(
                tuple(atom("file"), relative?.let { list(it.codePoints().toArray().map { point -> integer(point.toLong()) }) } ?: Unknown),
                tuple(atom("line"), field("line")),
            ),
        )
        val module = field("module")
        val frame = when {
            module == NIL -> tuple(atom("elixir_compiler"), atom("__FILE__"), integer(1), location)
            else -> when (val function = field("function")) {
                NIL -> tuple(module, atom("__MODULE__"), integer(0), location)
                else -> {
                    val (name, arity) = (function as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements()
                        ?: decline("Macro.Env.stacktrace/1 of the function %s", function)

                    tuple(module, name, arity, location)
                }
            }
        }

        return list(listOf(frame))
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
            "get_last_attribute/2" ->
                if (MODULE_GET_LAST_ATTRIBUTE.isSufficient(level)) getAttribute(args + NIL, NIL, last = true) else null
            "get_last_attribute/3" ->
                if (MODULE_GET_LAST_ATTRIBUTE.isSufficient(level)) getAttribute(args, args[2], last = true) else null
            "open?/1" -> open(args)
            "definitions_in/1", "definitions_in/2" -> definitionsIn(args)
            "overridable?/2" -> overridable(args)
            "overridables_in/1" -> if (MODULE_OVERRIDABLES_IN.isSufficient(level)) overridablesIn(args) else null
            "attributes_in/1" -> if (MODULE_ATTRIBUTES_IN.isSufficient(level)) attributesIn(args) else null
            else -> null
        }

    /**
     * [receiver]'s writer [key], one [ModuleEffects] has a row for at this level, given [args]: its guards, then that
     * row, the one the walk applies the call by where it is its own unit, applied at once for the module whose code runs
     * it, as a statement where that code runs once ([certainHere][org.elixir_lang.expander.BodyValues.certainHere]).
     * `null` where it has no row. A write to a module this run isn't compiling declines, as does one whose row raises,
     * or may, as a raise there isn't followed; one whose row stops the module stops it at the unit running this code.
     * The writes made before a decline stay.
     */
    private fun write(receiver: String, key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val function = key.substringBefore('/')

        val written = args[ModuleEffects.target(receiver, function, args.size, level) ?: return null]

        if (receiver == "Elixir.Module") guard(function, args)

        val run = evaluation.run
        val target = (seen(written) as? OtpErlangAtom)?.atomValue()?.takeIf { it in run.compiling }
            ?: decline("writes %s, which this run isn't compiling", written)
        val module = evaluation.module
        val compiling = module?.let(run.compiling::get) ?: decline("writes %s from code no module being compiled runs", written)
        // What it gives, read before it writes: the value `delete_attribute/2` removes, and whether `delete_definition/2`
        // removes a definition, which its row removes only where that is known.
        val result = when (function) {
            "delete_attribute" -> deleted(args)
            "delete_definition" -> bool(definitionOf(args[1])?.let { run.compiling.getValue(target).defines(it, null) } == true)
            else -> atom("ok")
        }
        val change = ModuleEffects.of(Term.Atom(receiver), function, args.map(::termOf), emptyList(), module, level, args)
            ?: decline("$receiver:$key changes no module")
        val at = evaluation.call
        val statement = compiling.values.certainHere()

        when (change) {
            is ModuleEffects.Change.Attributes ->
                when (val outcome = compiling.write(change.effect, at, statement, run)) {
                    is EffectOutcome.Raises -> decline("$receiver:$key raises ${outcome.kind}")
                    EffectOutcome.Unchecked -> decline("whether $receiver:$key raises isn't known")
                    EffectOutcome.Stored -> {}
                }
            is ModuleEffects.Change.Definitions ->
                when (val applied = change.apply(compiling, at, run, statement)) {
                    null -> {}
                    is Expansion.Unported -> throw Declined("$receiver:$key stops the module", stops = true)
                    else -> decline("$receiver:$key gives ${applied::class.simpleName}")
                }
        }

        return result
    }

    /**
     * The guards of `Module`'s writer [function] given [args] (`Mod:1478–1798` and `:2132` at v1.20.4): atoms for its
     * module and attribute, a list of options for `register_attribute/3`, a list or a behaviour for
     * `make_overridable/2`, and an atom name and an integer arity for `delete_definition/2`, whose arity outside
     * `0..255` declines, as its guards refuse it only from v1.20.0-rc.4.
     */
    private fun guard(function: String, args: List<OtpErlangObject>) {
        val refused = when (function) {
            "put_attribute", "delete_attribute" -> !isAtom(args[0]) || !isAtom(args[1])
            "__put_attribute__" -> !isAtom(args[1])
            "register_attribute" -> !isAtom(args[0]) || !isAtom(args[1]) || !isList(args[2])
            "make_overridable" -> !isAtom(args[0]) || !isList(args[1]) && !isAtom(args[1])
            else -> !isAtom(args[0]) || !isDefinition(args[1])
        }

        if (refused) functionClause("Elixir.Module", function, args)
        if (function == "delete_definition" && definitionOf(args[1]) == null) {
            decline("deletes %s, whose arity Elixir's guards refuse from v1.20.0-rc.4", args[1])
        }
    }

    /** Whether [term] is a `{name, arity}` of an atom and an integer, which `delete_definition/2`'s guards take. */
    private fun isDefinition(term: OtpErlangObject): Boolean {
        val definition = (seen(term) as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: return false

        return isAtom(definition.elementAt(0)) && typed(definition.elementAt(1)) is OtpErlangLong
    }

    /** `delete_attribute/2`'s value: the row [args] name, as `get_attribute/2` finds it, `nil` where there is none. */
    private fun deleted(args: List<OtpErlangObject>): OtpErlangObject =
        when (val row = lookup("delete_attribute", args, guardsModule = true)) {
            AttributeTable.Lookup.Absent, AttributeTable.Lookup.Unset -> NIL
            is AttributeTable.Lookup.Holds -> valueOf(row, args)
        }

    /**
     * `Module.get_attribute/2,3` (`Mod:1637–1639` at v1.20.4, `Mod:1232–1237` at v1.11.4): [default] for an attribute
     * that isn't set, or, before [GET_ATTRIBUTE_DEFAULT_ONLY_UNSET], for any `nil`. As `get_last_attribute/2,3`, the
     * [last] value of an accumulating attribute, or [default] where it has none (`Mod:1545` and `:1953–1962` at v1.15.8,
     * `:1672` and `:2076–2085` at v1.20.4).
     */
    private fun getAttribute(args: List<OtpErlangObject>, default: OtpErlangObject, last: Boolean = false): OtpErlangObject {
        val onlyUnset = GET_ATTRIBUTE_DEFAULT_ONLY_UNSET.isSufficient(level)

        return when (val row = lookup(if (last) "get_last_attribute" else "get_attribute", args, guardsModule = true)) {
            AttributeTable.Lookup.Absent -> default
            AttributeTable.Lookup.Unset ->
                if (!onlyUnset || GET_ATTRIBUTE_DEFAULT_FOR_REGISTERED.isSufficient(level)) default else NIL
            is AttributeTable.Lookup.Holds -> {
                val value = valueOf(row, args)

                when {
                    last && accumulates(args) -> cells(value)!!.first.firstOrNull() ?: default
                    !onlyUnset && value == NIL -> default
                    else -> value
                }
            }
        }
    }

    /** Whether the attribute [args] name, in the module they name, accumulates; their guards have passed. */
    private fun accumulates(args: List<OtpErlangObject>): Boolean =
        (seen(args[1]) as OtpErlangAtom).atomValue() in attributesOf(seen(args[0]) as OtpErlangAtom, args[0]).accumulating

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
        compilingOf(module, arg).attributes

    /** [module], being compiled, where its tables are Elixir's here ([Evaluation.compilingKnown]). */
    private fun compilingOf(module: OtpErlangAtom?, arg: OtpErlangObject): Compiling =
        module?.let { evaluation.compilingKnown(it.atomValue()) }
            ?: decline("reads the tables of %s, which aren't known there", arg)

    /**
     * `Application.compile_env/4` and `compile_env!/3` from [COMPILE_ENV_TAKES_ENV_FIRST] (`App:595–646` at v1.20.4),
     * `__compile_env__/4` and `__compile_env__!/3` before it (`App:516–552` at v1.11.4): their env match, then
     * `fetch_compile_env`'s key and `fetch_env/2`'s app guards, then the application's environment as a default build
     * sees it ([fetched]), and `traverse_env/2` down the rest of the path through `Access.fetch/2`. Where it isn't
     * known, nor is whether `compile_env!` finds it, and its raise is assumed away. An env that isn't known is taken to
     * be one, which assumes away the raise where it isn't; before [COMPILE_ENV_TAKES_ENV_FIRST] it is read only for its
     * tracers, after the guards, where a binary raises. A tracer the env names records the read, and writes no module.
     * `get_env/2,3`, `fetch_env/2` and `fetch_env!/2` (`App:724–783` at v1.20.4, `App:620–668` at v1.11.4) read the
     * same environment after their `is_atom(app)` guard; `get_env/2` is `get_env/3`'s default.
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
            val fetched = fetched(args[0], args[1])
            val value = (fetched as? OtpErlangTuple)?.elementAt(1)

            return when (key) {
                "fetch_env/2" -> fetched
                "fetch_env!/2" -> value.also { if (it == null) evaluation.assumeNoRaise() }
                else -> if (fetched == atom("error")) args.getOrElse(2) { NIL } else value
            } ?: Unknown
        }

        val env = if (envFirst) args[0] else args.last()
        val (app, path) = if (envFirst) args[1] to args[2] else args[0] to args[1]

        if (envFirst) {
            when {
                env is UnknownValue -> evaluation.assumeNoRaise()
                (env as? OtpErlangMap)?.get(atom("__struct__"))?.let(::seen) != atom("Elixir.Macro.Env") ->
                    functionClause("Elixir.Application", key.substringBefore('/'), args)
            }
        }

        val keys = seen(path)
        val (elements, tail) =
            if (keys is OtpErlangAtom) listOf(keys) to null
            else cells(keys) ?: (emptyList<OtpErlangObject>() to null)
        val first = elements.firstOrNull()?.let(::seen)
        val rest = elements.drop(1)

        if (first !is OtpErlangAtom) {
            functionClause("Elixir.Application", "fetch_compile_env", listOf(app, path, env))
        }

        if (!isAtom(app)) functionClause("Elixir.Application", "fetch_env", listOf(app, first))

        if (!envFirst) {
            when {
                env is UnknownValue -> evaluation.assumeNoRaise()
                typed(env) is OtpErlangBinary -> argumentError()
                (env as? OtpErlangMap)?.get(atom("tracers")) == null -> decline("reads the tracers of %s", env)
            }
        }

        var traversed = fetched(app, first)
        for (next in rest) {
            val value = (traversed as? OtpErlangTuple)?.elementAt(1) ?: break
            traversed = if (isUnknown(value) || isUnknown(next)) {
                null
            } else {
                evaluation.remote(atom("Elixir.Access"), "fetch", listOf(value, next))
            }
        }
        // `traverse_env/2` takes the path's tail as a list: one that isn't raises where a value was fetched before it.
        if (tail != null && traversed is OtpErlangTuple) {
            traversed = if (isUnknown(tail)) null
            else functionClause("Elixir.Application", "traverse_env", listOf(traversed, tail))
        }
        val default = when {
            key.endsWith("!/3") -> null
            envFirst -> args[3]
            else -> args[2]
        }

        return when (traversed) {
            is OtpErlangTuple if seen(traversed.elementAt(0)) == atom("ok") -> traversed.elementAt(1)
            atom("error") if default != null -> default
            else -> {
                // `traverse_env/2` fetches each key after the first from the value before it, which may not be a
                // container.
                if (default == null || traversed == null && (rest.isNotEmpty() || tail != null)) {
                    evaluation.assumeNoRaise()
                }
                Unknown
            }
        }
    }

    /**
     * `fetch_env/2`'s answer for [app]'s [key] in the environment a default build sees, the run's `appEnv`:
     * `{:ok, value}` where the `.app` declares it, `:error` where nothing sets it, and unknown where a config file may
     * set it, its `.app` can't be read, the run wrote the app's env
     * ([Run.wroteEnvOf][org.elixir_lang.expander.Run.wroteEnvOf]) or [key] isn't an atom. Each answer it knows is an
     * assumption: the environment may change before the read.
     */
    private fun fetched(app: OtpErlangObject, key: OtpErlangObject): OtpErlangObject? {
        if (key !is OtpErlangAtom) return null
        val name = (seen(app) as OtpErlangAtom).atomValue()
        if (evaluation.run.wroteEnvOf(name)) return null

        val answer = when (val value = evaluation.run.appEnv.of(name, key)) {
            is AppEnvValue.Declared -> tuple(atom("ok"), value.term)
            AppEnvValue.Unset -> atom("error")
            AppEnvValue.Configured, AppEnvValue.Unreadable -> return null
        }
        evaluation.assume()

        return answer
    }

    /**
     * `application:set_env/1,2,3,4` and `unset_env/2,3` (`application.erl:767–894` at OTP 29.0.6, `:324–377` at OTP
     * 24.3.4.6), which `Application`'s writers call. Each runs its guards, then records on the run the apps it writes
     * ([Run.wroteEnv][org.elixir_lang.expander.Run.wroteEnv]), every app where one isn't known, and gives `:ok`.
     * Whether `set_env/1,2`'s config is one the controller takes isn't known.
     */
    private fun envWrite(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        when (key) {
            "set_env/1", "set_env/2" -> {
                if (args.any { !isUnknown(it) && cells(it) == null }) functionClause("application", "set_env", args)
                val config = args[0]
                when (val elements = if (isUnknown(config)) null else properElements(config)) {
                    null -> evaluation.run.wroteEnv(null)
                    else -> elements.forEach { element ->
                        when {
                            isUnknown(element) -> evaluation.run.wroteEnv(null)
                            element is OtpErlangTuple && element.arity() == 2 -> wroteEnv(element.elementAt(0))
                        }
                    }
                }

                decline("whether %s is a config the application controller takes isn't known", config)
            }
            "set_env/3", "unset_env/2" -> wroteEnv(args[0])
            "set_env/4", "unset_env/3" -> {
                val opts = args.last()
                when {
                    isUnknown(opts) -> evaluation.assumeNoRaise()
                    opts == atom("infinity") || opts is OtpErlangList || opts is OtpErlangString -> {}
                    opts is OtpErlangLong && opts.bigIntegerValue().signum() >= 0 -> {}
                    else -> functionClause("application", key.substringBefore('/'), args)
                }
                wroteEnv(args[0])
            }
            else -> return null
        }

        return atom("ok")
    }

    /** Records that [app]'s env was written, every app's where [app] isn't known; another term names no app read. */
    private fun wroteEnv(app: OtpErlangObject) {
        when {
            isUnknown(app) -> evaluation.run.wroteEnv(null)
            app is OtpErlangAtom -> evaluation.run.wroteEnv(app.atomValue())
        }
    }

    /** Whether [term] is an atom, declining where that isn't known. */
    private fun isAtom(term: OtpErlangObject): Boolean = typed(term) is OtpErlangAtom

    private fun valueOf(row: AttributeTable.Lookup.Holds, args: List<OtpErlangObject>): OtpErlangObject =
        when (val value = row.value) {
            is AttributeValue.Known -> evaluation.otpOf(value.term)
            is AttributeValue.Held -> evaluation.held(value.value)
            AttributeValue.Unknown -> decline("the value of %s's %s isn't known", args[0], args[1])
        }

    /**
     * `Module.defines?/2,3` (`Mod:949–987` at v1.11.4, `:1253–1290` at v1.20.4): its guards, then the table of the
     * module being compiled, declining where Elixir's may differ from it.
     */
    private fun defines(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom
        val nameArity = definitionOf(args[1])
        val kind = args.getOrNull(2)?.let(::kindOf)

        if (module == null || nameArity == null || args.size == 3 && kind == null) {
            functionClause("Elixir.Module", "defines?", args)
        }

        return evaluation.compilingKnown(module.atomValue())?.defines(nameArity, kind)?.let(::bool)
            ?: decline("whether ${module.atomValue()} defines ${nameArity.name}/${nameArity.arity} isn't known")
    }

    /** `Module.open?/1` (`Mod:553` at v1.11.4, `:863` at v1.20.4), declining where another file may be compiling it. */
    private fun open(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom ?: functionClause("Elixir.Module", "open?", args)

        return evaluation.run.isOpen(module.atomValue())?.let(::bool)
            ?: decline("whether %s is open isn't known", args[0])
    }

    /**
     * `Module.definitions_in/1,2` (`Mod:1033–1064` at v1.11.4, `:1394–1421` at v1.20.4): its guards, then each
     * definition of the module being compiled, of the kind where one is given, declining where Elixir's table may
     * differ.
     */
    private fun definitionsIn(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom
        val kind = args.getOrNull(1)?.let(::kindOf)

        if (module == null || args.size == 2 && kind == null) functionClause("Elixir.Module", "definitions_in", args)

        val compiling = compilingOf(module, args[0])
        val table = compiling.table

        if (!table.everyNameKnown || table.entries.values.any { !it.ordered } || compiling.overridable.values.any { !it.ordered }) {
            decline("the definitions of %s aren't known", args[0])
        }

        return list(table.entries.filterValues { kind == null || it.kind == kind }.keys.map(::definitionTerm))
    }

    /**
     * `Module.overridable?/2` (`Mod:1174–1177` at v1.11.4, `:1578–1582` at v1.20.4): its guards, then whether
     * `make_overridable/2` recorded the definition, which stays recorded once overridden. Before
     * [OVERRIDABLE_GUARDS_MODULE] a module that isn't an atom passes the guards and declines.
     */
    private fun overridable(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom
        val definition = definitionOf(args[1])

        if (definition == null || module == null && OVERRIDABLE_GUARDS_MODULE.isSufficient(level)) {
            functionClause("Elixir.Module", "overridable?", args)
        }

        val record = compilingOf(module, args[0]).overridable[definition]

        if (record?.ordered == false) decline("whether %s is overridable isn't known", args[1])

        return bool(record != null)
    }

    /**
     * `Module.overridables_in/1` (`Mod:1170–1173` at v1.13.4, `:1369–1372` at v1.20.4): each definition
     * `make_overridable/2` recorded, in the order it first did.
     */
    private fun overridablesIn(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom ?: functionClause("Elixir.Module", "overridables_in", args)
        val overridable = compilingOf(module, args[0]).overridable

        if (overridable.values.any { !it.ordered }) decline("the overridable definitions of %s aren't known", args[0])

        return list(overridable.keys.map(::definitionTerm))
    }

    /** `Module.attributes_in/1` (`Mod:1141–1145` at v1.13.4, `:1341–1344` at v1.20.4). */
    private fun attributesIn(args: List<OtpErlangObject>): OtpErlangObject {
        val module = seen(args[0]) as? OtpErlangAtom ?: functionClause("Elixir.Module", "attributes_in", args)
        val names = compilingOf(module, args[0]).attributes.names()
            ?: decline("the attributes of %s aren't known", args[0])

        return list(names.map(::atom))
    }

    /** [term] as the `{name, arity}` `Module`'s guards take, or `null` where they refuse it. */
    private fun definitionOf(term: OtpErlangObject): NameArity? {
        val definition = (seen(term) as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: return null
        val name = seen(definition.elementAt(0)) as? OtpErlangAtom ?: return null
        val arity = (seen(definition.elementAt(1)) as? OtpErlangLong)?.bigIntegerValue()
            ?.takeIf { it in DEFINITION_ARITIES } ?: return null

        return NameArity(name.atomValue(), arity.toInt())
    }

    private fun definitionTerm(nameArity: NameArity): OtpErlangObject =
        tuple(atom(nameArity.name), integer(nameArity.arity.toLong()))

    /** [term] as the definition kind `Module`'s guards take, or `null` where they refuse it. */
    private fun kindOf(term: OtpErlangObject): DefinitionTable.Kind? =
        (seen(term) as? OtpErlangAtom)?.atomValue()
            ?.let { kind -> DefinitionTable.Kind.entries.firstOrNull { it.name.lowercase() == kind } }

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

        val escape = QuoteEscape(TermEscapeView, TermEscapeBuilder, { it }, level, evaluation.run.exports, op, unquote) {
            val generated = option("generated")
            val marks: () -> Boolean = { seen(generated) == TRUE }

            marks
        }

        return when (val escaped = escape.escape(args[0])) {
            is EscapeResult.Built -> escaped.value
            is EscapeResult.Raised -> evaluation.raise(escaped.exception)
            is EscapeResult.Unread, EscapeResult.Declined -> decline("Macro.escape/2 of %s", args[0])
        }
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

    /** [term]'s integer, or `badarith` where it isn't one, as Erlang's integer operators raise. */
    private fun bigInteger(term: OtpErlangObject): BigInteger = (seen(term) as? OtpErlangLong)?.bigIntegerValue() ?: badarith()

    /**
     * [value] shifted left [by] bits, or right where [by] is negative, as `bsl/2` gives it: a shift right past every bit
     * leaves `0` or `-1`, and a nonzero [value] shifted left past the VM's largest integer raises `system_limit`.
     */
    private fun shifted(value: BigInteger, by: BigInteger): OtpErlangObject =
        when {
            value.signum() == 0 -> integer(0)
            by.signum() < 0 ->
                if (-by > value.bitLength().toBigInteger()) integer(value.signum().coerceAtMost(0).toLong()) else integer(value.shiftRight(-by.toInt()))
            by > OLD_INTEGER_WORDS.toBigInteger() * Long.SIZE_BITS.toBigInteger() -> evaluation.error(atom("system_limit"))
            else -> bounded(value.shiftLeft(by.toInt()))
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

    private fun binaries(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "at/2" -> byteAt(args[0], args[1])
            "split/3" -> binarySplit(args[0], args[1], args[2])
            else -> null
        }

    /**
     * `binary:split/3` of a binary at a binary or a list of them, none empty, with `[]` or `[global]`; any other
     * argument, such as the `trim` options, declines.
     */
    private fun binarySplit(subject: OtpErlangObject, pattern: OtpErlangObject, options: OtpErlangObject): OtpErlangObject {
        val patterns = (properElements(seen(pattern))?.takeIf { it.isNotEmpty() } ?: listOf(pattern)).map {
            nameBytes("binary:split/3", it).takeIf { bytes -> bytes.isNotEmpty() } ?: decline("binary:split/3 of an empty pattern")
        }
        val flags = properElements(seen(options)) ?: decline("binary:split/3 of %s, which is no list of options", options)
        val global = when {
            flags.isEmpty() -> false
            flags.size == 1 && seen(flags[0]) == atom("global") -> true
            else -> decline("binary:split/3 with the options %s", options)
        }

        return list(Filename.binarySplit(nameBytes("binary:split/3", subject), patterns, global).map(::OtpErlangBinary))
    }

    /** `os:type/0`: the OS family and name the run's SDK runs as. */
    private fun osType(): OtpErlangObject {
        val (family, name) = evaluation.run.os?.let(Filename::osType) ?: decline("os:type/0 of a run whose OS isn't known")

        return tuple(atom(family), atom(name))
    }

    /**
     * `Mix.env/0` and `Mix.target/0`: the build the run's file is compiled in. Each answer is an assumption, as an app env
     * read's is: it is the default build's. In a config read it is no assumption: the read is of that env.
     */
    private fun mix(key: String): OtpErlangObject? {
        val build = evaluation.run.buildTarget ?: return null
        val answer = when (key) {
            "env/0" -> build.mixEnv ?: return null
            "target/0" -> build.target
            else -> return null
        }
        if (evaluation.run.config == null) evaluation.assume()

        return atom(answer)
    }

    /**
     * `Config`'s functions over the run's config read ([ConfigRead]), which holds what `Config.__eval__!/3` keeps in the
     * process dictionary. Outside a read, where Elixir raises, they decline: a config file's own expansion isn't Mix's.
     */
    private fun configure(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        when (key) {
            "__merge__/2" -> return configMerge(args[0], args[1])
            "config/2", "config/3", "__env__!/0", "__target__!/0", "__import__!/1" -> {}
            "read_config/1" -> if (!CONFIG_READ_CONFIG.isSufficient(level)) return null
            else -> return null
        }

        val read = evaluation.run.config ?: decline("Config.$key outside a config read")

        return when (key) {
            "__env__!/0" -> atom(read.env)
            "__target__!/0" -> atom(read.target)
            "config/2" -> {
                val (root, opts) = args

                if (!isAtom(root) || !isList(opts)) functionClause("Elixir.Config", "config", args)
                if (!isKeyword(opts)) argumentError()

                configured(read, list(listOf(tuple(root, opts))))
            }
            "config/3" -> {
                val (root, configKey, opts) = args

                if (!isAtom(root) || !isAtom(configKey)) functionClause("Elixir.Config", "config", args)

                configured(read, list(listOf(tuple(root, list(listOf(tuple(configKey, opts)))))))
            }
            "read_config/1" -> {
                if (!isAtom(args[0])) functionClause("Elixir.Config", "read_config", args)

                keyword("get/2", listOf(read.config, args[0]))
            }
            else -> configImport(read, args[0])
        }
    }

    /** [config] merged into [read]'s, returning the config before, as `Process.put/2` does. */
    private fun configured(read: ConfigRead, config: OtpErlangObject): OtpErlangObject {
        val before = read.config

        read.config = configMerge(before, config)

        return before
    }

    /**
     * `Config.__import__!/1`: the file at [path] read and evaluated in the same read, with its own `__DIR__`, an empty
     * binding and a fresh env, after `import_config!/3`'s check that the read hasn't imported it already. A raise in the
     * file raises here; where the file's run stopped, Elixir may have raised there, which is assumed not to happen.
     */
    private fun configImport(read: ConfigRead, path: OtpErlangObject): OtpErlangObject {
        if (!isBinary(path)) functionClause("Elixir.Config", "__import__!", listOf(path))

        val text = String(nameBytes("Config.__import__!/1", path), Charsets.UTF_8)

        when (evaluation.run.files.exists(text)) {
            null -> decline("Config.__import__!/1 of %s, whose file isn't known", path)
            false -> evaluation.raise(
                "Elixir.File.Error",
                "reason" to atom("enoent"),
                "path" to path,
                "action" to OtpErlangBinary("read file".toByteArray()),
            )
            true -> {}
        }
        if (text in read.imports) argumentError()

        read.imports.add(0, text)

        val expansion = evaluation.run.files.imported(text, read) ?: decline("Config.__import__!/1 of %s, which can't be read", path)

        when (val top = expansion.top) {
            is Expansion.Error -> when (val kind = top.kind) {
                null -> decline("Config.__import__!/1 of %s, whose raise isn't known", path)
                else -> {
                    // Only its type is known, so a `rescue` can't read the rest.
                    evaluation.mayRaise()
                    // An expansion error, which `Code.eval_string/3` raises as a `CompileError`.
                    evaluation.raise(if (kind.startsWith("Elixir.")) kind else "Elixir.CompileError")
                }
            }
            is Expansion.Expanded -> {}
            else -> {
                read.unitDeclined()
                evaluation.assumeNoRaise()
            }
        }

        return Unknown
    }

    /**
     * `Mix.Config.__import__!/2`: [path] expanded against [dir], and where it holds a wildcard, each file it matches,
     * each imported as `Config.__import__!/1` imports it.
     */
    private fun mixConfigImport(path: OtpErlangObject, dir: OtpErlangObject): OtpErlangObject {
        val read = evaluation.run.config ?: decline("Mix.Config.__import__!/2 outside a config read")
        val expanded = evaluation.remote(atom("Elixir.Path"), "expand", listOf(path, dir))
        val text = String(nameBytes("Mix.Config.__import__!/2", expanded), Charsets.UTF_8)
        val paths =
            if (text.any { it in ConfigFiles.WILDCARD }) {
                evaluation.run.files.wildcard(text)?.map { OtpErlangBinary(it.toByteArray()) }
                    ?: decline("Mix.Config.__import__!/2 of the wildcard %s", expanded)
            } else {
                listOf(expanded)
            }

        paths.forEach { configImport(read, it) }

        return atom("ok")
    }

    /**
     * `File.exists?/1,2`: whether a file or directory is at [path], relative to `Run.workingDirectory` where it is relative,
     * read through the run's files.
     */
    private fun fileExists(path: OtpErlangObject): OtpErlangObject {
        val text = String(nameBytes("File.exists?/1", path), Charsets.UTF_8)
        val absolute =
            if (FileUtil.isAbsolutePlatformIndependent(text)) {
                text
            } else {
                val file = evaluation.run.file() ?: decline("File.exists?/1 of %s, relative to a file with no path", path)
                val directory = evaluation.run.workingDirectory(file) ?: decline("File.exists?/1 of %s, relative to an unknown directory", path)

                "$directory/$text"
            }

        return bool(evaluation.run.files.exists(absolute) ?: decline("File.exists?/1 of %s isn't known", path))
    }

    /** `Config.__merge__/2`: [config2]'s apps merged into [config1]'s, each app's keys merged by `deep_merge/3`. */
    private fun configMerge(config1: OtpErlangObject, config2: OtpErlangObject): OtpErlangObject {
        if (!isList(config1) || !isList(config2)) functionClause("Elixir.Config", "__merge__", listOf(config1, config2))

        val merged = IdentityHashMap<OtpErlangObject, IdentityHashMap<OtpErlangObject, OtpErlangObject>>()

        return keywordMerge(config1, config2) { app1, app2 ->
            keywordMerge(app1, app2) { value1, value2 -> deepMerge(value1, value2, merged) }
        }
    }

    /** `Config.deep_merge/3`: two keyword lists merged, keys by key, else the later value; each pair of parts merged once, in [merged]. */
    private fun deepMerge(
        value1: OtpErlangObject,
        value2: OtpErlangObject,
        merged: IdentityHashMap<OtpErlangObject, IdentityHashMap<OtpErlangObject, OtpErlangObject>>,
    ): OtpErlangObject =
        when {
            isUnknown(value2) -> Unknown
            isUnknown(value1) -> if (isKeyword(value2)) Unknown else value2
            isKeyword(value1) && isKeyword(value2) ->
                merged.getOrPut(value1) { IdentityHashMap() }.getOrPut(value2) {
                    keywordMerge(value1, value2) { part1, part2 -> deepMerge(part1, part2, merged) }
                }
            else -> value2
        }

    /**
     * `Keyword.merge/3`: [keywords1] without the keys of [keywords2], then [keywords2]'s pairs in turn, each merged by
     * [merge] with the first pair under its key in [keywords1], which that takes.
     */
    private fun keywordMerge(
        keywords1: OtpErlangObject,
        keywords2: OtpErlangObject,
        merge: (OtpErlangObject, OtpErlangObject) -> OtpErlangObject,
    ): OtpErlangObject {
        if (isUnknown(keywords1) || isUnknown(keywords2)) return Unknown
        if (!isList(keywords1) || !isList(keywords2)) functionClause("Elixir.Keyword", "merge", listOf(keywords1, keywords2))
        if (!isKeyword(keywords1)) argumentError()

        val pair = { term: OtpErlangObject -> seen(term) as OtpErlangTuple }
        val original = properElements(seen(keywords1))!!.map(pair).toMutableList()
        val rest = original.toMutableList()
        val merged = ArrayList<OtpErlangObject>()

        for (element in properElements(seen(keywords2)) ?: argumentError()) {
            ProgressManager.checkCanceled()

            val (key, value2) = (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements() ?: argumentError()
            val name = seen(key) as? OtpErlangAtom ?: argumentError()
            val index = original.indexOfFirst { seen(it.elementAt(0)) == name }

            if (index >= 0) {
                merged += tuple(name, merge(original[index].elementAt(1), value2))
                original.removeAt(index)
            } else {
                merged += tuple(name, value2)
            }
            rest.removeAll { seen(it.elementAt(0)) == name }
        }

        return list(rest + merged)
    }

    /** `file:get_cwd/0`: `{ok, Directory}`, the run's working directory (`Run.workingDirectory`). */
    private fun workingDirectory(): OtpErlangObject {
        val file = evaluation.run.file() ?: decline("file:get_cwd/0 of a file with no path")
        val directory = evaluation.run.workingDirectory(file) ?: decline("file:get_cwd/0 where the compile directory isn't known")

        return tuple(atom("ok"), list(directory.codePoints().toArray().map { integer(it.toLong()) }))
    }

    /**
     * `filename:dirname/1`, `basename/1,2` and `split/1` of binaries, by the syntax of the OS the run's SDK runs as; a
     * list or atom name declines.
     */
    private fun filename(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        if (key !in FILENAME_NATIVES) return null

        val os = evaluation.run.os ?: decline("filename:$key of a run whose OS isn't known")
        val name = nameBytes("filename:$key", args[0])

        return when (key) {
            "dirname/1" -> OtpErlangBinary(Filename.dirname(name, os))
            "basename/1" -> OtpErlangBinary(Filename.basename(name, os))
            "basename/2" -> OtpErlangBinary(Filename.basename(name, extensionBytes(args[1]), os))
            else -> list(Filename.split(name, os).map(::OtpErlangBinary))
        }
    }

    /**
     * The extension `filename:basename/2` is given: a binary, or a list of code points, which `filename_string_to_binary/1`
     * encodes (`filename.erl:1109`). A list holding anything else declines.
     */
    private fun extensionBytes(term: OtpErlangObject): ByteArray {
        val elements = properElements(seen(term)) ?: return nameBytes("filename:basename/2", term)

        if (elements.any { small(it) == null }) decline("filename:basename/2 of the extension %s, which is no list of code points", term)

        return (unicode("characters_to_binary/1", listOf(term)) as? OtpErlangBitstr)?.binaryValue()
            ?: decline("filename:basename/2 of the extension %s, which is no text", term)
    }

    /** The bytes of the binary [term], declining where it is not one, as a list or atom name is not. */
    private fun nameBytes(function: String, term: OtpErlangObject): ByteArray =
        (seen(term) as? OtpErlangBitstr)?.takeIf(::isBinary)?.binaryValue() ?: decline("$function of %s, which is no binary", term)

    /** `binary:at/2`: the byte of the binary [subject] at the zero-based [position]. */
    private fun byteAt(subject: OtpErlangObject, position: OtpErlangObject): OtpErlangObject {
        val badarg = { evaluation.error(atom("badarg"), frame("binary", "at", list(listOf(subject, position)))) }
        val bytes = (seen(subject) as? OtpErlangBitstr)?.takeIf(::isBinary)?.binaryValue() ?: badarg()
        val index = (seen(position) as? OtpErlangLong)?.takeIf { it.bitLength() < Int.SIZE_BITS }?.intValue() ?: badarg()

        return integer((bytes.getOrNull(index) ?: badarg()).toLong() and 0xFF)
    }

    /**
     * `ordsets:is_element/2`, by its clauses: the first element of [set] not below [element] decides, and a tail that
     * isn't `[]` has no clause.
     */
    private fun isElement(element: OtpErlangObject, set: OtpErlangObject): OtpErlangObject {
        val functionClause = { rest: OtpErlangObject ->
            evaluation.error(atom("function_clause"), frame("ordsets", "is_element", list(listOf(element, rest))))
        }
        val (elements, tail) = cells(set) ?: functionClause(set)

        for (each in elements) {
            ProgressManager.checkCanceled()

            val order = compare(element, each, level)

            if (order < 0) return FALSE
            if (order == 0) return TRUE
        }

        return if (tail == null) FALSE else functionClause(tail)
    }

    /**
     * `elixir_config`'s readers of Elixir's static options ([STATIC_OPTIONS]): its own functions from
     * [STATIC_OPTIONS_IN_PERSISTENT_TERM], `get/1,2` before it. Every other option varies with how Elixir is run.
     */
    private fun config(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val persistent = STATIC_OPTIONS_IN_PERSISTENT_TERM.isSufficient(level)

        return when (key) {
            "is_bootstrap/0" -> STATIC_OPTIONS.getValue("bootstrap").takeIf { persistent }
            "identifier_tokenizer/0" -> STATIC_OPTIONS.getValue("identifier_tokenizer").takeIf { persistent }
            "get/1", "get/2" ->
                STATIC_OPTIONS[(seen(args[0]) as? OtpErlangAtom)?.atomValue()].takeUnless { persistent }
                    ?: decline("Elixir's option %s", args[0])
            "static/1" -> decline("sets Elixir's static options")
            else -> null
        }
    }

    /**
     * `elixir_import:special_form/2`: whether [name]/[arity] is a special form ([specialFormArity]). A name that is one
     * at any arity is one whatever [arity] is, and any other term `false`, as its clauses match them.
     */
    private fun isSpecialForm(name: OtpErlangObject, arity: OtpErlangObject): OtpErlangObject {
        val arities = (seen(name) as? OtpErlangAtom)?.let { specialFormArity(it.atomValue(), level) } ?: return FALSE

        if (arities == ArityKnowledge.Open(0)) return TRUE

        val int = (seen(arity) as? OtpErlangLong)?.takeIf { it.isLong }?.longValue()

        return bool(int != null && int in Int.MIN_VALUE..Int.MAX_VALUE && arities.admits(int.toInt()))
    }

    /** The text of the binary [term], `null` where it isn't a binary of UTF-8. */
    private fun utf8(term: OtpErlangObject): String? {
        if (!isBinary(term)) return null

        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap((seen(term) as OtpErlangBitstr).binaryValue())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * `unicode:characters_to_binary/1` (`unicode.erl:305-311`): chardata as a UTF-8 binary, raising `badarg` for a term
     * that isn't chardata, which OTP checks before it encodes any of the term, up to the first integer too large to be
     * small. Where a binary isn't UTF-8 or an integer isn't a code point, it gives `{error, ...}` or
     * `{incomplete, ...}`, which decline.
     */
    private fun unicode(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "characters_to_binary/1" -> {
                // One binary's bytes run on into the next's, so a character may be split between them.
                val bytes = Built()
                var codePoints = true
                val chardata = leaves(args[0], { bytes.write(it.binaryValue()) }) { integer ->
                    if (small(integer) == null) decline("characters_to_binary/1 of %s, which is no code point", integer)

                    codePoint(integer)?.let { bytes.write(Character.toString(it).toByteArray()) } ?: run { codePoints = false }
                }

                if (!chardata) evaluation.error(atom("badarg"), frame("unicode", "characters_to_binary", list(args)))
                if (!codePoints) decline("characters_to_binary/1 of %s, which holds no code point", args[0])

                OtpErlangBinary(bytes.toByteArray()).takeIf { utf8(it) != null }
                    ?: decline("characters_to_binary/1 of %s, which isn't UTF-8", args[0])
            }
            else -> null
        }

    /** [term] where it is a Unicode code point, which a surrogate isn't, `null` where it is something else. */
    private fun codePoint(term: OtpErlangObject): Int? =
        (term as? OtpErlangLong)?.takeIf { it.bitLength() < Int.SIZE_BITS }?.intValue()?.takeIf { isScalarValue(it.toLong()) }

    /**
     * `unicode_util:gc/1`: the first grapheme cluster of a binary or a flat list of code points, then the rest. Where
     * the cluster ends, and whether a code point is a control or a Prepend, come from [Graphemes]; this builds OTP's
     * term from them. A cluster is its one code point, or a list of them. A binary whose first code point isn't UTF-8
     * gives `{error, Bin}`; one whose first cluster is followed directly by bytes that aren't, a list that holds
     * anything but code points, and any other term decline.
     */
    private fun gc(term: OtpErlangObject): OtpErlangObject {
        val graphemes = Graphemes.of(level)
        val cluster = { codePoints: List<Int> -> codePoints.singleOrNull()?.let { integer(it.toLong()) } ?: list(codePoints.map { integer(it.toLong()) }) }

        if (isBinary(term)) {
            val bytes = (term as OtpErlangBitstr).binaryValue()

            if (bytes.isEmpty()) return list(emptyList())

            // `String`'s walks call this once per cluster, so it decodes only as far as the first cluster is seen to end.
            var decoded = 0
            var text: String
            var end: Int
            do {
                decoded = maxOf(decoded * 2, 64).coerceAtMost(bytes.size)
                text = utf8Prefix(bytes, decoded)
                if (text.isEmpty()) return tuple(atom("error"), term)
                end = graphemes.clusterEnd(text, 0)
            } while (end == text.length && decoded < bytes.size)

            if (end == text.length && text.toByteArray(Charsets.UTF_8).size < bytes.size) {
                decline("unicode_util:gc/1 of %s, whose first cluster is followed by bytes that aren't UTF-8", term)
            }

            val codePoints = text.substring(0, end).codePoints().toArray().toList()
            val tail = bytes.copyOfRange(text.substring(0, end).toByteArray(Charsets.UTF_8).size, bytes.size)
            val last = codePoints.last()
            val keepsEmptyTail = graphemes.isControl(last) ||
                !graphemes.isPrepend(last) && GRAPHEME_CLUSTER_KEEPS_EMPTY_TAIL.isSufficient(level)

            return if (tail.isEmpty() && !keepsEmptyTail) {
                list(listOf(cluster(codePoints)))
            } else {
                cons(listOf(cluster(codePoints)), OtpErlangBinary(tail))
            }
        }

        val (elements, tail) = cells(term) ?: decline("unicode_util:gc/1 of %s", term)
        val codePoints = elements.map { codePoint(seen(it)) ?: decline("unicode_util:gc/1 of a list holding %s", it) }

        if (tail != null) decline("unicode_util:gc/1 of a list whose tail is %s", tail)
        if (codePoints.isEmpty()) return list(emptyList())

        val text = buildString { codePoints.forEach(::appendCodePoint) }
        val count = text.codePointCount(0, graphemes.clusterEnd(text, 0))

        return list(listOf(cluster(codePoints.take(count))) + elements.drop(count))
    }

    /** The UTF-8 text of the first [size] of [bytes], up to the first byte that isn't UTF-8 or a code point they cut. */
    private fun utf8Prefix(bytes: ByteArray, size: Int): String {
        val decoded = CharBuffer.allocate(size)
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, size), decoded, size == bytes.size)

        return decoded.flip().toString()
    }

    /**
     * Gives [binary] each binary and [integer] each integer of the iodata or chardata [term], in order, and answers whether
     * [term] is such: a binary, or a list of integers, binaries and such lists whose tail is `[]` or a binary. It stops at
     * the first term that isn't. Past [COPY_NODES] elements, nested lists' included, it declines, as their parts may be
     * shared many times over.
     */
    private fun leaves(term: OtpErlangObject, binary: (OtpErlangBitstr) -> Unit, integer: (OtpErlangLong) -> Unit): Boolean {
        var met = 0

        fun walk(term: OtpErlangObject): Boolean {
            ProgressManager.checkCanceled()

            val known = seen(term)

            if (known is OtpErlangBitstr) return (known.pad_bits() == 0).also { if (it) binary(known) }

            val (elements, tail) = cells(known) ?: return false

            for (element in elements) {
                if (++met > COPY_NODES) decline("iodata or chardata of over $COPY_NODES elements")

                val part = seen(element)

                if (part is OtpErlangLong) integer(part) else if (!walk(part)) return false
            }

            return tail == null || seen(tail) is OtpErlangBitstr && walk(tail)
        }

        return walk(term)
    }

    /** The bytes of the iodata [term], as `iolist_to_binary/1` gives them; [badarg] where [term] isn't iodata. */
    private fun iodataBytes(term: OtpErlangObject, badarg: () -> Nothing): ByteArray {
        val bytes = Built()
        val iodata = leaves(term, { bytes.write(it.binaryValue()) }) { byte ->
            bytes.write(byte.bigIntegerValue().takeIf { it.signum() >= 0 && it.bitLength() <= 8 }?.toInt() ?: badarg())
        }

        if (!iodata) badarg()

        return bytes.toByteArray()
    }

    /** The bytes a conversion writes, which decline past the size of a bitstring the interpreter builds. */
    private class Built : ByteArrayOutputStream() {
        override fun write(b: Int) {
            reserveBits(count * 8L, 8)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            reserveBits(count * 8L, len * 8L)
            super.write(b, off, len)
        }
    }

    /** [TermEscapeView], declining on a value it doesn't know. */
    private val view = EscapeView<OtpErlangObject> { TermEscapeView.shape(seen(it)) }

}

/**
 * Elixir's static options, as its boot sets them: `elixir:start/2`'s config before [STATIC_OPTIONS_IN_PERSISTENT_TERM],
 * `elixir_config`'s defaults from it.
 */
private val STATIC_OPTIONS = mapOf("bootstrap" to FALSE, "identifier_tokenizer" to atom("Elixir.String.Tokenizer"))

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
