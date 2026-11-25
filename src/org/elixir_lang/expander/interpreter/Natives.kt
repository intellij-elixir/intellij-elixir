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
import com.ericsson.otp.erlang.OtpExternal
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangPort
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.system.OS
import org.elixir_lang.NameArity
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.admits
import org.elixir_lang.expander.Annotation
import org.elixir_lang.expander.AppEnvValue
import org.elixir_lang.expander.AttributeTable
import org.elixir_lang.expander.AttributeValue
import org.elixir_lang.expander.BUILT_IN
import org.elixir_lang.expander.Compiling
import org.elixir_lang.expander.ConfigRead
import org.elixir_lang.expander.DEFINITION_ARITIES
import org.elixir_lang.expander.DefinitionTable
import org.elixir_lang.expander.DirectoryListing
import org.elixir_lang.expander.EffectOutcome
import org.elixir_lang.expander.Env
import org.elixir_lang.expander.EscapeOp
import org.elixir_lang.expander.EscapeView
import org.elixir_lang.expander.EscapeResult
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.EnvWrite
import org.elixir_lang.expander.FileEntry
import org.elixir_lang.expander.ModuleAttributes
import org.elixir_lang.expander.ModuleEffects
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.QuoteEscape
import org.elixir_lang.expander.implementationName
import org.elixir_lang.expander.Shape
import org.elixir_lang.expander.TermEscapeBuilder
import org.elixir_lang.expander.TermEscapeView
import org.elixir_lang.expander.annotation
import org.elixir_lang.expander.decodeUtf8
import org.elixir_lang.expander.defines
import org.elixir_lang.expander.keystore
import org.elixir_lang.expander.specialFormArity
import org.elixir_lang.expander.write
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.BINARY_COPY_CHECKS_WHOLE_BYTES_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.BINARY_PART_REJECTS_BITSTRINGS
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.language_level.ElixirLanguageFeature.ERLANG_ERROR_3
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_FOR_REGISTERED
import org.elixir_lang.language_level.ElixirLanguageFeature.GET_ATTRIBUTE_DEFAULT_ONLY_UNSET
import org.elixir_lang.language_level.ElixirLanguageFeature.GRAPHEME_CLUSTER_KEEPS_EMPTY_TAIL
import org.elixir_lang.language_level.ElixirLanguageFeature.INTEGERS_UP_TO_4_MBIT
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_OPTIONS_TAKE_A_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_READS_THE_MESSAGE_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_TAKES_AN_ENV_OR_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_CHECK_THE_FUN_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_SORT_KEEPS_EQUAL_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.MAKE_FUN_ARITY_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.RAISE_FRAME_TAKES_A_CAPTURE
import org.elixir_lang.language_level.ElixirLanguageFeature.READ_FILE_TAKES_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_ATTRIBUTES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_GET_LAST_ATTRIBUTE
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_OVERRIDABLES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.OVERRIDABLE_GUARDS_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.SPLICE_EMPTY_BEFORE_TAIL_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.STATIC_OPTIONS_IN_PERSISTENT_TERM
import org.elixir_lang.language_level.ElixirLanguageFeature.STRING_TO_ATOM_REQUIRES_BINARY
import org.elixir_lang.language_level.ElixirLanguageFeature.PATH_WILDCARD_READS_FILE_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.TERM_TO_BINARY_UTF8_ATOMS
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.fitsAnAtom
import org.elixir_lang.model.psi.config.ConfigFiles
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.unicode_util.Graphemes
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** The frames a stacktrace `erlang:raise/3` stores holds at most: the default `backtrace_depth`, which Elixir keeps. */
private const val BACKTRACE_DEPTH = 8

/**
 * Each native that can return, or return a part of, fewer than all its arguments, by receiver, name and arity, to the
 * indices of those it can; any other can return any of them. An app-env read returns the environment's value, which
 * came from no module, or a default: `get_env/3`'s and `compile_env/4`'s where the key isn't set (`App:724`, `:595` at
 * v1.20.4), and `__compile_env__/4`'s before it (`App:516` at v1.11.4). `Access.fetch/2` and `get/2,3` return a part of
 * their first argument, or `get/3`'s default, where they call no struct's `fetch/2`. The other readers of a container
 * by a key, in `Keyword`, `maps`, `lists` and `erlang`, return parts of their container, or the default they are given,
 * and their predicates a boolean, never the key, as the tests of membership in `lists`, `ordsets` and `Enum` do.
 * `Module`'s attribute reads, and `delete_attribute/2`, return a value of the module their first argument names, or the
 * default they are given; its predicates and other writers a boolean or `:ok`, and its listings names. `IO.warn/1,2`
 * returns `:ok`, as do `application`'s env writers. `binary:split/3`, `filename:basename/2` and `rootname/2` return
 * parts of their first argument alone. A read of the machine or the run given an argument, such as `System.get_env/1`
 * or a clock given its unit, returns what it read.
 */
private val RETURNABLE = mapOf(
    Triple("Elixir.Access", "fetch", 2) to listOf(0),
    Triple("Elixir.Access", "get", 2) to listOf(0),
    Triple("Elixir.Access", "get", 3) to listOf(0, 2),
    Triple("Elixir.Keyword", "get", 2) to listOf(0),
    Triple("Elixir.Keyword", "get", 3) to listOf(0, 2),
    Triple("Elixir.Keyword", "fetch", 2) to listOf(0),
    Triple("Elixir.Keyword", "fetch!", 2) to listOf(0),
    Triple("Elixir.Keyword", "pop", 2) to listOf(0),
    Triple("Elixir.Keyword", "pop", 3) to listOf(0, 2),
    Triple("Elixir.Keyword", "has_key?", 2) to emptyList(),
    Triple("Elixir.Keyword", "delete", 2) to listOf(0),
    Triple("Elixir.Keyword", "take", 2) to listOf(0),
    Triple("Elixir.Keyword", "drop", 2) to listOf(0),
    Triple("maps", "get", 2) to listOf(1),
    Triple("maps", "get", 3) to listOf(1, 2),
    Triple("maps", "find", 2) to listOf(1),
    Triple("maps", "is_key", 2) to emptyList(),
    Triple("lists", "keyfind", 3) to listOf(2),
    Triple("lists", "keymember", 3) to emptyList(),
    Triple("lists", "member", 2) to emptyList(),
    Triple("ordsets", "is_element", 2) to emptyList(),
    Triple("Elixir.Enum", "member?", 2) to emptyList(),
    Triple("lists", "keydelete", 3) to listOf(2),
    Triple("lists", "keytake", 3) to listOf(2),
    Triple("maps", "take", 2) to listOf(1),
    Triple("erlang", "map_get", 2) to listOf(1),
    Triple("erlang", "is_map_key", 2) to emptyList(),
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
    Triple("Elixir.System", "get_env", 1) to emptyList(),
    Triple("Elixir.System", "get_env", 2) to emptyList(),
    Triple("Elixir.System", "fetch_env", 1) to emptyList(),
    Triple("Elixir.System", "fetch_env!", 1) to emptyList(),
    Triple("erlang", "monotonic_time", 1) to emptyList(),
    Triple("erlang", "system_time", 1) to emptyList(),
    Triple("erlang", "time_offset", 1) to emptyList(),
    Triple("erlang", "unique_integer", 1) to emptyList(),
    Triple("erlang", "nodes", 1) to emptyList(),
    Triple("os", "system_time", 1) to emptyList(),
    Triple("rand", "uniform", 1) to emptyList(),
    Triple("crypto", "strong_rand_bytes", 1) to emptyList(),
    Triple("init", "get_argument", 1) to emptyList(),
    Triple("application", "set_env", 1) to emptyList(),
    Triple("application", "set_env", 2) to emptyList(),
    Triple("application", "set_env", 3) to emptyList(),
    Triple("application", "set_env", 4) to emptyList(),
    Triple("application", "unset_env", 2) to emptyList(),
    Triple("application", "unset_env", 3) to emptyList(),
    Triple("binary", "split", 3) to listOf(0),
    Triple("filename", "basename", 2) to listOf(0),
    Triple("filename", "rootname", 2) to listOf(0),
)

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
    Triple("lists", "sort", 2) to listOf(0),
    Triple("Elixir.Enum", "map", 2) to listOf(1),
    Triple("Elixir.Enum", "flat_map", 2) to listOf(1),
    Triple("Elixir.Enum", "filter", 2) to listOf(1),
    Triple("Elixir.Enum", "reject", 2) to listOf(1),
    Triple("Elixir.Enum", "reduce", 3) to listOf(2),
    Triple("Elixir.Enum", "any?", 2) to listOf(1),
    Triple("Elixir.Enum", "all?", 2) to listOf(1),
)

/**
 * Each native given options, by receiver, name and arity, to the index of its options and the option among them that
 * names a function it runs: `inspect/2`'s `:inspect_fun`. Elixir raises where those options are a list that isn't a
 * proper list of pairs.
 */
private val OPTIONS = mapOf(Triple("Elixir.Kernel", "inspect", 2) to (1 to atom("inspect_fun")))

/**
 * How a native calls the [code] of a struct's module, for the arguments at [arguments]: for each struct one holds where
 * it is [nested], as `inspect/1` does, else for the argument itself. A native that calls it only from a version does so
 * from [since] on.
 */
internal class StructDispatch(
    val arguments: List<Int>,
    val code: StructCode,
    val nested: Boolean = false,
    val since: ElixirLanguageFeature? = null,
)

/** Where `IO.warn`'s second argument says its warning is. */
private sealed interface WarnedAt {
    /** Nowhere: the warning's stacktrace is [stacktrace], `null` for the caller's. */
    class Nowhere(val stacktrace: OtpErlangObject?) : WarnedAt

    /** At a place, which declines once the message is made. */
    object Place : WarnedAt
}

/** The code of a struct's module a native calls ([Natives.runsCodeOf]). */
internal sealed interface StructCode {
    /**
     * The types, by the module `defimpl ... for:` names, Elixir implements this code's protocol for none of, where it is
     * one: a project may implement it for a term of one ([Natives.unimplementedTypes]).
     */
    val unimplemented: Set<String> get() = emptySet()

    /** `fetch/2`, which `Access` calls for every struct. */
    data object Fetch : StructCode

    /** The `Enumerable` implementation, which `Enum` calls for a struct it doesn't enumerate itself. */
    data object Enumerable : StructCode {
        override val unimplemented = builtInTypesBut("List", "Map", "Function")
    }

    /** `__escape__/1`, where the module exports it. */
    data object Escape : StructCode

    /**
     * [protocol]'s implementation for the struct, where one may exist. The native answers without running it, so a call
     * of it declines.
     */
    class Protocol(val protocol: String, override val unimplemented: Set<String> = emptySet()) : StructCode
}

/**
 * The types `defprotocol` tells apart ([BUILT_IN]) but [implemented], the ones Elixir implements a protocol for, by the
 * module `defimpl ... for:` names.
 */
private fun builtInTypesBut(vararg implemented: String): Set<String> =
    BUILT_IN.map { (type, _) -> type }.filterNot(implemented::contains).mapTo(LinkedHashSet()) { "Elixir.$it" }

private val STRING_CHARS = StructCode.Protocol("String.Chars", builtInTypesBut("Atom", "BitString", "Integer", "Float", "List"))
private val FETCH = StructDispatch(listOf(0), StructCode.Fetch)
private val TO_STRING = StructDispatch(listOf(0), STRING_CHARS)
private val ESCAPE = StructDispatch(listOf(0), StructCode.Escape, nested = true, since = REGEX_ESCAPED_IN_FIXED_FIELD_ORDER)
private val INSPECT = StructDispatch(listOf(0), StructCode.Protocol("Inspect"), nested = true)
internal val ENUMERABLE = StructDispatch(listOf(0), StructCode.Enumerable)

/** Each native that calls a struct's implementation, by receiver, name and arity, to how ([Natives.dispatches]). */
private val DISPATCHES = mapOf(
    Triple("Elixir.Access", "fetch", 2) to FETCH,
    Triple("Elixir.Access", "get", 2) to FETCH,
    Triple("Elixir.Access", "get", 3) to FETCH,
    Triple("Elixir.String.Chars", "to_string", 1) to TO_STRING,
    Triple("Elixir.IO", "warn", 1) to TO_STRING,
    Triple("Elixir.IO", "warn", 2) to TO_STRING,
    Triple("Elixir.Macro", "escape", 1) to ESCAPE,
    Triple("Elixir.Macro", "escape", 2) to ESCAPE,
    Triple("Elixir.Kernel", "inspect", 1) to INSPECT,
    Triple("Elixir.Kernel", "inspect", 2) to INSPECT,
    Triple("Elixir.Enum", "map", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "flat_map", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "filter", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "reject", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "reduce", 3) to ENUMERABLE,
    Triple("Elixir.Enum", "any?", 1) to ENUMERABLE,
    Triple("Elixir.Enum", "all?", 1) to ENUMERABLE,
    Triple("Elixir.Enum", "any?", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "all?", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "member?", 2) to ENUMERABLE,
    Triple("Elixir.Enum", "concat", 2) to StructDispatch(listOf(0, 1), StructCode.Enumerable),
    Triple("Elixir.Enum", "reverse", 1) to ENUMERABLE,
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

        /** The [Row.writes] of a row none of whose functions writes what later code reads. */
        private val READS: Natives.(function: String, arity: Int) -> Boolean = { _, _ -> false }

        /** Each module whose functions are answered here, to its [Row]. */
        private val ROWS = mapOf(
            "erlang" to Row(READS) { key, args, tail -> erlang(key, args, tail) },
            "lists" to reading(Natives::lists),
            "maps" to reading(Natives::maps),
            "counters" to Row({ function, _ -> function == "add" || function == "put" }) { key, args, _ -> counters(key, args) },
            "elixir_quote" to reading(Natives::quote),
            "elixir_module" to writing(Natives::elixirModule),
            "code" to reading(Natives::code),
            "application" to writing(Natives::envWrite),
            "unicode" to reading(Natives::unicode),
            "unicode_util" to reading { key, args -> if (key == "gc/1") gc(seen(args[0])) else null },
            "logger" to reading { key, args -> if (key == "compare_levels/2") compareLevels(args) else null },
            "Elixir.Keyword" to reading(Natives::keyword),
            "Elixir.Enum" to reading(Natives::enum),
            "Elixir.Access" to reading(Natives::access),
            "Elixir.Macro.Env" to reading(Natives::env),
            "Elixir.IO" to reading(Natives::io),
            "Elixir.Module" to Row({ function, arity -> !onlyReads("Elixir.Module", function, arity) }) { key, args, _ ->
                module(key, args)
            },
            "Elixir.Application" to reading(Natives::application),
            // A followed file's code may write anything.
            "Elixir.Code" to Row({ function, _ -> function !in CODE_READS }) { key, args, _ ->
                if (key == "can_await_module_compilation?/0") canAwaitModuleCompilation() else follow.call(key, args)
            },
            "Elixir.Atom" to reading { key, args -> if (key == "to_string/1") erlang("atom_to_binary/1", args) else null },
            "Elixir.String" to reading(Natives::string),
            "Elixir.String.Chars" to reading { key, args -> if (key == "to_string/1") toString(args[0]) else null },
            "Elixir.Kernel" to reading(Natives::kernel),
            // An expansion runs macros, which may write.
            "Elixir.Macro" to Row({ function, _ -> function == "expand" || function == "expand_once" }) { key, args, _ ->
                macro(key, args)
            },
            "ordsets" to reading { key, args -> if (key == "is_element/2") isElement(args[0], args[1]) else null },
            "binary" to reading(Natives::binaries),
            "sets" to reading(Natives::sets),
            "os" to reading(Natives::os),
            "Elixir.System" to reading(Natives::system),
            // More reads of the machine or the run, as `system`'s are, each where its argument is one Erlang takes.
            "rand" to reading { key, args ->
                when (key) {
                    "uniform/0", "uniform_real/0", "normal/0" -> Unknown
                    "uniform/1" -> Unknown.takeIf { small(args[0])?.let { it > 0 } == true }
                    else -> null
                }
            },
            "crypto" to reading { key, args ->
                if (key == "strong_rand_bytes/1") UnknownBinary.takeIf { small(args[0])?.let { it >= 0 } == true } else null
            },
            "inet" to reading { key, _ -> if (key == "gethostname/0") Unknown else null },
            "init" to reading { key, args ->
                when (key) {
                    "get_argument/1" -> Unknown.takeIf { isAtom(args[0]) }
                    "get_arguments/0" -> Unknown
                    else -> null
                }
            },
            "file" to reading { key, args ->
                when (key) {
                    "get_cwd/0" -> workingDirectory()
                    "read_file/1" -> readFile(args[0])
                    "read_file/2" -> if (READ_FILE_TAKES_OPTIONS.isSufficient(level)) readFile(args[0], args[1]) else null
                    else -> null
                }
            },
            "filelib" to reading { key, args -> if (key == "wildcard/1" || key == "wildcard/2") wildcard(args) else null },
            "filename" to reading(Natives::filename),
            "elixir_config" to Row({ function, _ -> function == "static" }) { key, args, _ -> config(key, args) },
            "Elixir.Mix" to reading { key, _ -> mix(key) },
            "Elixir.Mix.Project" to reading(Natives::mixProject),
            "Elixir.Config" to writing(Natives::configure),
            "Elixir.Mix.Config" to writing { key, args -> if (key == "__import__!/2") mixConfigImport(args[0], args[1]) else null },
            "Elixir.File" to reading { key, args -> if (key == "exists?/1" || key == "exists?/2") fileExists(args[0]) else null },
            "elixir_import" to reading { key, args -> if (key == "special_form/2") isSpecialForm(args[0], args[1]) else null },
            "elixir_tokenizer" to reading { key, args -> if (key == "tokenize/3") tokenize(args) else null },
        )

        /** A row none of whose functions writes what later code reads. */
        private fun reading(answer: Natives.(key: String, args: List<OtpErlangObject>) -> OtpErlangObject?) =
            Row(READS) { key, args, _ -> answer(key, args) }

        /** A row whose functions may write what later code reads. */
        private fun writing(answer: Natives.(key: String, args: List<OtpErlangObject>) -> OtpErlangObject?) =
            Row { key, args, _ -> answer(key, args) }
    }

    /**
     * A module whose functions are answered here: its [answer] for a function's `name/arity` key and arguments, `null`
     * where it has none, in tail position or not ([call]), and whether a function of it [writes] what later code reads.
     */
    private class Row(
        val writes: Natives.(function: String, arity: Int) -> Boolean = { _, _ -> true },
        val answer: Natives.(key: String, args: List<OtpErlangObject>, tail: Boolean) -> OtpErlangObject?,
    )

    private val level get() = evaluation.run.level

    private val follow = CodeFollow(evaluation)

    /**
     * [module]'s [function] applied to [args], or `null` where it isn't one of these. In [tail] position, a call
     * `apply/2,3` makes is made, not run ([Evaluation.apply]).
     */
    fun call(module: String, function: String, args: List<OtpErlangObject>, tail: Boolean = false): OtpErlangObject? {
        val key = "$function/${args.size}"

        if (key == "__info__/1") info(module, args[0])?.let { return it }

        return ROWS[module]?.answer?.invoke(this, key, args, tail) ?: if (module in WRITES_BY_ROW) write(module, key, args) else null
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
     * name or may ([runsAFunction]), known or not, or where a function is held in any ([holdsAFunction]), which the
     * others may flow into, and none otherwise.
     */
    fun runs(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        args.takeIf { runsAFunction(module, function, args) || holdsAFunction(it) }.orEmpty()

    /** How [module]'s [function] of [arity] calls a struct's implementation, where it does ([DISPATCHES]). */
    fun dispatches(module: String, function: String, arity: Int): StructDispatch? =
        DISPATCHES[Triple(module, function, arity)]?.takeIf { it.since?.isSufficient(level) != false }

    /**
     * Whether a native that calls [code] ([StructDispatch.code]) may call it for a struct of [module], or for a term of
     * the type [module] names ([unimplementedType]).
     */
    fun runsCodeOf(code: StructCode, module: String): Boolean =
        when (code) {
            StructCode.Fetch -> true
            StructCode.Enumerable -> if (module in code.unimplemented) mayImplement("Enumerable", module) else enumeratesItself(atom(module))
            StructCode.Escape -> QuoteEscape.escapesItself(evaluation.run.exports.of(module))
            is StructCode.Protocol -> mayImplement(code.protocol, module)
        }

    /** Whether [protocol] may have an implementation for [module]: the module that implementation is may exist. */
    private fun mayImplement(protocol: String, module: String): Boolean =
        implementationName(level, "Elixir.$protocol", module)?.let { evaluation.run.exports.of(it) != ModuleExports.Absent } == true

    /**
     * [term]'s type, by the module `defimpl ... for:` names, where it is one [code]'s protocol has no implementation in
     * Elixir for ([StructCode.unimplemented]); else `null`.
     */
    private fun unimplementedType(code: StructCode, term: OtpErlangObject): String? {
        val type = when (term) {
            is OtpErlangAtom -> "Elixir.Atom"
            is OtpErlangBitstr -> "Elixir.BitString"
            is OtpErlangDouble -> "Elixir.Float"
            is OtpErlangFun, is OtpErlangExternalFun -> "Elixir.Function"
            is OtpErlangLong -> "Elixir.Integer"
            is OtpErlangMap -> "Elixir.Map".takeIf { term.get(STRUCT) == null }
            is OtpErlangPid -> "Elixir.PID"
            is OtpErlangPort -> "Elixir.Port"
            is OtpErlangRef -> "Elixir.Reference"
            is OtpErlangTuple -> "Elixir.Tuple"
            else -> null
        }

        return type?.takeIf(code.unimplemented::contains)
    }

    /**
     * The types [term] may be of, by the module `defimpl ... for:` names, that [code]'s protocol has no implementation in
     * Elixir for: a known term's ([unimplementedType]), and each of them for a value not known that came from no module,
     * `BitString` only for a binary and `Atom` only for a boolean.
     */
    fun unimplementedTypes(code: StructCode, term: OtpErlangObject): Collection<String> =
        when {
            code.unimplemented.isEmpty() -> code.unimplemented
            term === UnknownBoolean -> listOfNotNull(unimplementedType(code, TRUE))
            term is UnknownValue && term.fromNoModule -> code.unimplemented
            else -> listOfNotNull(unimplementedType(code, if (term === UnknownBinary) SOME_BINARY else term))
        }

    /** Whether [module]'s [function] of [arity] runs a function it is given ([RUNS]). */
    fun runsAFunction(module: String, function: String, arity: Int): Boolean = Triple(module, function, arity) in RUNS

    /**
     * Whether [module]'s [function], given [args], runs a function they name ([RUNS]), or options that may name one
     * ([OPTIONS]): options not known, or holding that option, an element or a key not known.
     */
    fun runsAFunction(module: String, function: String, args: List<OtpErlangObject>): Boolean {
        val (options, option) = optionsOf(module, function, args) ?: return runsAFunction(module, function, args.size)
        if (isUnknown(options)) return true
        val elements = cells(options)?.first ?: return false

        return elements.any { element -> isUnknown(element) || pairKey(element)?.let { isUnknown(it) || it == option } == true }
    }

    /**
     * Whether Elixir raises on [args] whatever the values not known in them are: [module]'s [function] is given options
     * ([OPTIONS]) that are a list with a tail, which [cons] leaves not known only where it is a binary, or holding a known
     * element that isn't a pair.
     */
    fun alwaysRaises(module: String, function: String, args: List<OtpErlangObject>): Boolean {
        val options = optionsOf(module, function, args)?.first ?: return false
        val (elements, tail) = options.takeUnless(::isUnknown)?.let(::cells) ?: return false

        return tail != null || elements.any { !isUnknown(it) && pairKey(it) == null }
    }

    /**
     * The options [module]'s [function] is given in [args] ([OPTIONS]), with the option among them that names a
     * function it runs.
     */
    private fun optionsOf(module: String, function: String, args: List<OtpErlangObject>): Pair<OtpErlangObject, OtpErlangAtom>? =
        OPTIONS[Triple(module, function, args.size)]?.let { (index, option) -> args[index] to option }

    /**
     * The argument of [module]'s [function], given [args], that names the module it writes: the one its [ModuleEffects]
     * row names, none where it has no row. Where a native [call] of it declines, it may have written that module.
     */
    fun writes(module: String, function: String, args: List<OtpErlangObject>): List<OtpErlangObject> =
        listOfNotNull(ModuleEffects.target(module, function, args.size, level)?.let(args::get))

    /**
     * Whether [module]'s [function] of [arity] may write what later code reads ([Row.writes]), as one that isn't answered
     * here may.
     */
    fun writesTheRun(module: String, function: String, arity: Int): Boolean =
        ROWS[module]?.writes?.invoke(this, function, arity) ?: true

    /** Whether [module]'s [function] of [arity] is a `Module` native that only reads its tables. */
    fun onlyReads(module: String, function: String, arity: Int): Boolean =
        module == "Elixir.Module" && Triple(module, function, arity) in RETURNABLE && ModuleEffects.target(module, function, arity, level) == null

    /** The value of [module]'s attribute [name] here, `null` where it isn't known. */
    fun attributeAt(module: String, name: String): OtpErlangObject? =
        orOnDecline(null) { getAttribute(listOf(atom(module), atom(name)), NIL) }

    /** Whether a delete of [module]'s attribute [name] is known to change nothing here. */
    fun deleteChangesNothing(module: String, name: String): Boolean =
        orOnDecline(false) { attributesOf(atom(module), atom(module)).deleteChanges(name) == false }

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
            ?: (enumerable as? OtpErlangMap)?.takeIf { it.get(STRUCT) == null }?.let { map ->
                inOrder(map).asSequence().map { (key, value) ->
                    ProgressManager.checkCanceled()
                    tuple(key, value)
                }
            }
            ?: decline("enumerates %s", enumerable)

    /**
     * `Module.__info__(:module)`, and `(:attributes)` (`elixir_erl.erl:364-366`) from the attributes its `.beam` persists,
     * of a module that has `__info__/1`, declining where they can't be read; `null`, as its clauses answer, for any other
     * key or module.
     */
    private fun info(module: String, key: OtpErlangObject): OtpErlangObject? {
        val known = seen(key)

        if (known != atom("attributes") && known != atom("module")) return null

        val exports = evaluation.run.exports.of(module) as? ModuleExports.Present ?: return null

        if (!exports.hasInfo) return null
        if (known == atom("module")) return atom(module)

        return when (val attributes = exports.attributes) {
            is ModuleAttributes.Known -> attributes.keyword
            ModuleAttributes.Unreadable -> decline("$module's attributes aren't known")
        }
    }

    /**
     * `elixir_module:next_counter/1` (`elixir_module.erl:64-71`), from the [Counters][org.elixir_lang.expander.Counters]
     * the run's expansions take theirs from: `{Module, N}` for a module being compiled, whose table holds its counter, and
     * a unique integer for `nil` or any other term, as Elixir's `catch` gives. Drawing one may decline
     * ([drawingCounter][Evaluation.drawingCounter]).
     */
    private fun elixirModule(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "next_counter/1" -> {
                val name = (seen(args[0]) as? OtpErlangAtom)?.atomValue()
                evaluation.drawingCounter(name)

                when (val counter = evaluation.run.counters.next(name?.takeIf { it in evaluation.run.compiling })) {
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

    /**
     * [answer], which Elixir gives for any terms without raising: one not known where it reads a value not known
     * ([Declined.onValue]), as it can't raise there either.
     */
    private inline fun forAnyTerm(answer: () -> OtpErlangObject): OtpErlangObject = orOnUnknown(Unknown, answer)

    private fun erlang(key: String, args: List<OtpErlangObject>, tail: Boolean = false): OtpErlangObject? {
        val badarg = { badargOf("erlang", key.substringBefore('/'), args) }

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
            "==/2" -> forAnyTerm { bool(equal(args[0], args[1], false, level)) }
            "/=/2" -> forAnyTerm { bool(!equal(args[0], args[1], false, level)) }
            "=:=/2" -> forAnyTerm { bool(equal(args[0], args[1], true, level)) }
            "=/=/2" -> forAnyTerm { bool(!equal(args[0], args[1], true, level)) }
            "self/0" -> SELF
            // Of the processes the build runs, only the absence of one `mix compile` doesn't start is answered.
            "whereis/1" -> {
                val name = typed(args[0]) as? OtpErlangAtom ?: badarg()

                if (evaluation.run.unregistered(name.atomValue())) atom("undefined") else Unknown
            }
            // A hash the VM defines, which isn't computed here.
            "phash2/1" -> Unknown
            // The clocks `System`'s inlined functions call, and the node the build runs as, as `system`'s reads are.
            "monotonic_time/0", "system_time/0", "time_offset/0", "unique_integer/0", "node/0", "nodes/0", "is_alive/0" -> Unknown
            "monotonic_time/1", "system_time/1", "time_offset/1" -> readGiven(args[0], ::isTimeUnit, badarg)
            "unique_integer/1" -> readGiven(args[0], { isListOf(it, UNIQUE_MODIFIERS) }, badarg)
            "nodes/1" -> readGiven(args[0], { isOneOf(it, NODE_TYPES) || isListOf(it, NODE_TYPES) }, badarg)
            "make_ref/0" -> newRef()
            "</2" -> forAnyTerm { bool(compare(args[0], args[1], level) < 0) }
            ">/2" -> forAnyTerm { bool(compare(args[0], args[1], level) > 0) }
            "=</2" -> forAnyTerm { bool(compare(args[0], args[1], level) <= 0) }
            ">=/2" -> forAnyTerm { bool(compare(args[0], args[1], level) >= 0) }
            "min/2" -> forAnyTerm { if (compare(args[0], args[1], level) > 0) args[1] else args[0] }
            "max/2" -> forAnyTerm { if (compare(args[0], args[1], level) < 0) args[1] else args[0] }
            "not/1" -> bool(!(boolean(args[0]) ?: badarg()))
            "and/2" -> bool((boolean(args[0]) ?: badarg()) and (boolean(args[1]) ?: badarg()))
            "or/2" -> bool((boolean(args[0]) ?: badarg()) or (boolean(args[1]) ?: badarg()))
            "xor/2" -> bool((boolean(args[0]) ?: badarg()) xor (boolean(args[1]) ?: badarg()))
            "is_atom/1" -> forAnyTerm { bool(isAtom(args[0])) }
            "is_list/1" -> forAnyTerm { bool(isList(args[0])) }
            "is_binary/1" -> forAnyTerm { bool(isBinary(args[0])) }
            "is_bitstring/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangBitstr) }
            "is_integer/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangLong) }
            "is_float/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangDouble) }
            "is_number/1" -> forAnyTerm { bool(isNumber(typed(args[0]))) }
            "is_tuple/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangTuple) }
            "is_map/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangMap) }
            "is_boolean/1" -> forAnyTerm { bool(boolean(args[0]) != null) }
            "is_pid/1" -> forAnyTerm { bool(typed(args[0]) is OtpErlangPid) }
            "is_function/1" -> forAnyTerm { bool(arity(args[0]) != null) }
            "is_function/2" -> {
                val arity = (seen(args[1]) as? OtpErlangLong)?.takeIf { it.signum() >= 0 } ?: badarg()

                bool(arity(args[0])?.toBigInteger() == arity.bigIntegerValue())
            }
            "make_fun/3" -> {
                val (module, name) = args.take(2).map { seen(it) as? OtpErlangAtom ?: badarg() }
                val arity = (seen(args[2]) as? OtpErlangLong)?.let { makeFunArity(it.bigIntegerValue(), level) } ?: badarg()

                OtpErlangExternalFun(
                    module.atomValue(),
                    name.atomValue(),
                    arity.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: decline("a fun of an arity JInterface can't hold"),
                )
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
                    smallInt(seen(it))?.toLong() ?: badarg()
                }
                val (from, to) = if (length < 0) start + length to start else start to start + length

                if (from < 0 || to > bytes.size) badarg()

                OtpErlangBinary(bytes.copyOfRange(from.toInt(), to.toInt()))
            }
            "byte_size/1" -> integer(((bitSize(seen(args[0]) as? OtpErlangBitstr ?: badarg()) + 7) / 8).toLong())
            "iolist_to_binary/1" -> iolistToBinary(args[0], badarg)
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
            "integer_to_list/1" -> {
                val text = (seen(args[0]) as? OtpErlangLong ?: badarg()).bigIntegerValue().toString()

                if (text.length > COPY_NODES) decline("integer_to_list/1 of %s, past $COPY_NODES digits", args[0])

                charlist(text)
            }
            "integer_to_binary/1", "integer_to_binary/2" -> {
                val integer = seen(args[0]) as? OtpErlangLong ?: badarg()
                val base = radix(args) ?: badarg()

                binary(integer.bigIntegerValue().toString(base).uppercase())
            }
            "binary_to_integer/1", "binary_to_integer/2" -> {
                val text = utf8(args[0]) ?: badarg()
                val base = radix(args) ?: badarg()

                // One sign and the base's ASCII digits, where `BigInteger` takes any Unicode digit.
                if (!INTEGER_TEXT.matches(text)) badarg()

                integer(text.removePrefix("+").toBigIntegerOrNull(base) ?: badarg())
            }
            "binary_to_float/1" -> {
                // ERTS reads the text up to its first NUL (`bif.c`'s `binary_to_float`, `sys_chars_to_double`).
                val bytes = binaryBytes(args[0]) ?: badarg()
                val text = String(bytes, 0, bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size, Charsets.ISO_8859_1)

                if (!FLOAT_TEXT.matches(text)) badarg()

                OtpErlangDouble(text.replace(',', '.').toDouble().takeUnless { it.isInfinite() } ?: badarg())
            }
            "atom_to_list/1" ->
                charlist((seen(args[0]) as? OtpErlangAtom ?: badarg()).atomValue())
            "md5/1" -> OtpErlangBinary(MessageDigest.getInstance("MD5").digest(iodataBytes(args[0], badarg)))
            "term_to_binary/1" -> termToBinary(args[0])
            "binary_to_atom/1", "binary_to_atom/2" -> {
                val text = atomText(args) ?: badarg()

                if (!fitsAnAtom(text)) evaluation.error(atom("system_limit"))

                atom(text).also { with(evaluation.run) { (if (mayNotRun) atomsThatMayExist else atoms) += text } }
            }
            "binary_to_existing_atom/1", "binary_to_existing_atom/2" -> {
                val atom = atom(atomText(args) ?: badarg())

                when (atom.atomValue()) {
                    in evaluation.run.atoms -> atom
                    in evaluation.run.atomsThatMayExist -> evaluation.unlessRaised(atom)
                    // Every atom the VM has loaded exists, where the evaluation knows only those it has seen.
                    else -> decline("whether the atom %s exists", atom)
                }
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
            "apply/2" -> evaluation.apply(running("erlang", key, args).single(), properElements(args[1]) ?: badarg(), tail)
            "apply/3" -> {
                val (module, name) = running("erlang", key, args)
                val function = seen(name) as? OtpErlangAtom ?: badarg()

                evaluation.remote(module, function.atomValue(), properElements(args[2]) ?: badarg(), tail = tail)
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
            Encoding.LATIN1 -> binaryBytes(args[0])?.let { String(it, Charsets.ISO_8859_1) }
            Encoding.UTF8 -> utf8(args[0])
            null -> null
        }

    /** [text]'s bytes in Latin-1, or `null` where a character is past it. */
    private fun latin1(text: String): ByteArray? =
        text.takeIf { s -> s.all { it.code < 256 } }?.toByteArray(Charsets.ISO_8859_1)

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
        val noClause = { function: String, clauseArgs: List<OtpErlangObject> -> functionClause("lists", function, clauseArgs) }
        val badarg = { function: String -> badargOf("lists", function, args) }
        val ran by lazy { running("lists", key, args).single() }

        return when (key) {
            "reverse/1" -> {
                val (elements, tail) = cells(args[0]) ?: noClause("reverse", args)

                when {
                    tail == null && elements.size <= 1 -> args[0]
                    tail == null && elements.size == 2 -> list(elements.reversed())
                    elements.size >= 2 ->
                        lists("reverse/2", listOf(cons(elements.drop(2), tail ?: EMPTY), list(elements.take(2).reversed())))
                    else -> noClause("reverse", args)
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
                val n = positiveInteger(args[1]) ?: noClause("keydelete", args)
                val (elements, tail) = cells(args[2]) ?: noClause("keydelete3", args)
                val index = keyIndexOf(elements, n, args[0])

                when {
                    index >= 0 -> withoutAt(elements, index, tail)
                    tail != null -> noClause("keydelete3", listOf(args[0], args[1], tail))
                    else -> args[2]
                }
            }
            "keytake/3" -> {
                val n = positiveInteger(args[1]) ?: noClause("keytake", args)
                val (elements, tail) = cells(args[2]) ?: noClause("keytake", listOf(args[0], args[1], args[2], EMPTY))
                val index = keyIndexOf(elements, n, args[0])

                when {
                    index >= 0 -> tuple(atom("value"), elements[index], withoutAt(elements, index, tail))
                    tail != null -> noClause("keytake", listOf(args[0], args[1], tail, list(elements.reversed())))
                    else -> FALSE
                }
            }
            "append/1" -> {
                val (lists, tail) = cells(args[0]) ?: noClause("append", args)

                append(lists, tail)
            }
            "append/2" -> erlang("++/2", args)
            "unzip/1" -> {
                val (elements, tail) = cells(seen(args[0])) ?: noClause("unzip", listOf(args[0], EMPTY, EMPTY))
                val pairs = elements.asSequence().map(::seen).takeWhile { it is OtpErlangTuple && it.arity() == 2 }
                    .filterIsInstance<OtpErlangTuple>().toList()
                val xs = pairs.map { it.elementAt(0) }
                val ys = pairs.map { it.elementAt(1) }

                // `unzip/3`'s clauses take a pair or `[]`, its accumulators reversed.
                if (pairs.size < elements.size || tail != null) {
                    noClause("unzip", listOf(cons(elements.drop(pairs.size), tail ?: EMPTY), list(xs.reversed()), list(ys.reversed())))
                }

                tuple(list(xs), list(ys))
            }
            "duplicate/2" -> {
                val count = (seen(args[0]) as? OtpErlangLong)?.bigIntegerValue()?.takeIf { it.signum() >= 0 }
                    ?: noClause("duplicate", args)

                if (count > BigInteger.valueOf(COPY_NODES.toLong())) decline("lists:duplicate/2 of %s copies", args[0])

                OtpErlangList(Array(count.toInt()) { args[1] })
            }
            "flatten/1" -> {
                if (!isList(args[0])) noClause("flatten", args)

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
                val predicate = function(ran, 1) ?: noClause("filter", args)

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
            "sort/1" -> sort(args[0])
            "sort/2" -> sortWith(ran, args)
            "keysort/2" -> keysort(args)
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
    private fun sort(term: OtpErlangObject): OtpErlangObject {
        val (elements, tail) = cells(term) ?: functionClause("lists", "sort", listOf(term))

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

        if (elements.size < 2) functionClause("lists", "sort", listOf(term))
        if (!LISTS_SORT_KEEPS_EQUAL_ORDER.isSufficient(level)) decline("lists:sort/1 of an improper list before OTP 29")

        val split = SortSplit(elements[0], elements[1])

        for (z in elements.drop(2)) {
            ProgressManager.checkCanceled()

            split.next(z)
        }

        functionClause("lists", split.helper, split.arguments(tail))
    }

    /**
     * `lists:keysort/2`: a proper list's stable sort by each tuple's element, in term order. A list of one is returned
     * without reading it. A longer improper list raises in a helper whose runs aren't ported, so it declines, as does a
     * list OTP's own sort gives in a different order before and after its stability fix.
     */
    private fun keysort(args: List<OtpErlangObject>): OtpErlangObject {
        val position = positiveInteger(args[0]) ?: functionClause("lists", "keysort", args)
        val (elements, tail) = cells(args[1]) ?: evaluation.error(tuple(atom("case_clause"), args[1]))

        if (tail != null) {
            if (elements.size < 2) evaluation.error(tuple(atom("case_clause"), args[1]))

            decline("lists:keysort/2 of an improper list")
        }
        if (elements.size < 2) return args[1]

        val index = position.min(Int.MAX_VALUE.toBigInteger()).toInt()
        val keyed = elements.map { element ->
            val key = (seen(element) as? OtpErlangTuple)?.takeIf { index <= it.arity() }?.elementAt(index - 1) ?: badargOf("lists", "keysort", args)

            key to element
        }
        val run = keyed.takeWhile { (_, element) -> compare(element, keyed[0].second, level) == 0 }
        val next = keyed.getOrNull(run.size)

        if (next != null && run.size > 1 && run.any { (_, element) -> compare(element, keyed[0].second, level, exact = true) != 0 } &&
            compare(next.first, keyed[0].first, level) < 0
        ) {
            decline("lists:keysort/2 of tuples that compare equal, not exactly, before a lower key")
        }

        return list(keyed.sortedWith { (left, _), (right, _) ->
            ProgressManager.checkCanceled()

            compare(left, right, level)
        }.map { it.second })
    }

    /** `lists:sort/2`, which declines on an improper list or a value it can't call, whose raise is in a helper not ported. */
    private fun sortWith(ran: OtpErlangObject, args: List<OtpErlangObject>): OtpErlangObject {
        val function = function(ran, 2)
        val (elements, tail) = cells(args[1]) ?: functionClause("lists", "sort", args)

        if (tail != null) {
            if (elements.size < 2) functionClause("lists", "sort", args)

            decline("lists:sort/2 of an improper list")
        }
        if (elements.size < 2) return if (function != null) args[1] else functionClause("lists", "sort", args)
        if (function == null) decline("lists:sort/2 with %s", args[0])

        return list(FunSort { left, right ->
            when (val result = function.call(left, right)) {
                TRUE -> true
                FALSE -> false
                else -> evaluation.error(tuple(atom("case_clause"), seen(result)))
            }
        }.sorted(elements))
    }

    /**
     * `lists:sort/2`'s runs and merges (`fsplit_*`, `fmergel`, `rfmergel`, `fmerge2_*`, `rfmerge2_*`) as OTP calls [before]:
     * which pairs it is given, and so the result of one that isn't a total preorder, depend on them.
     */
    private class FunSort(private val before: (OtpErlangObject, OtpErlangObject) -> Boolean) {
        fun sorted(elements: List<OtpErlangObject>): List<OtpErlangObject> {
            val ascending = before(elements[0], elements[1])
            var x = elements[0]
            var y = elements[1]
            var r = ArrayList<OtpErlangObject>()
            var s: OtpErlangObject? = null
            val runs = ArrayList<List<OtpErlangObject>>()

            for (z in elements.drop(2)) {
                ProgressManager.checkCanceled()

                val pending = s

                when {
                    before(y, z) == ascending -> {
                        r += x
                        x = y
                        y = z
                    }
                    before(x, z) == ascending -> {
                        r += x
                        x = z
                    }
                    pending == null && r.isEmpty() -> r += z
                    pending == null -> s = z
                    else -> {
                        val inOrder = before(pending, z) == ascending

                        runs.add(0, listOf(y, x) + r.asReversed())
                        r = ArrayList()
                        x = if (inOrder) pending else z
                        y = if (inOrder) z else pending
                        s = null
                    }
                }
            }

            runs.add(0, listOf(y, x) + r.asReversed())
            s?.let { runs.add(0, listOf(it)) }

            return merged(runs, ascending, forward = !ascending)
        }

        /** `fmergel/4` where [forward], else `rfmergel/4`. */
        private tailrec fun merged(runs: List<List<OtpErlangObject>>, ascending: Boolean, forward: Boolean): List<OtpErlangObject> {
            val merges = ArrayList<List<OtpErlangObject>>()

            for (pair in runs.chunked(2).filter { it.size == 2 }) {
                val (first, second) = if (ascending == forward) pair[0] to pair[1] else pair[1] to pair[0]

                merges.add(0, merge(first, second, forward))
            }

            if (runs.size % 2 == 0) return merged(merges, ascending, !forward)

            val last = runs.last()

            return if (forward && merges.isEmpty()) last else merged(listOf(last.asReversed()) + merges, ascending, !forward)
        }

        /** `fmerge2_1/5` where [forward], taking from [first] where [before] holds, else `rfmerge2_1/5`, reversed as they give it. */
        private fun merge(first: List<OtpErlangObject>, second: List<OtpErlangObject>, forward: Boolean): List<OtpErlangObject> {
            val merged = ArrayList<OtpErlangObject>(first.size + second.size)
            var i = 0
            var j = 0

            while (i < first.size && j < second.size) {
                ProgressManager.checkCanceled()

                merged += if (before(first[i], second[j]) == forward) first[i++] else second[j++]
            }

            merged += first.subList(i, first.size)
            merged += second.subList(j, second.size)

            return merged.asReversed()
        }
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

    /** [term] where it is an integer above 0: `lists:keydelete/3`'s, `keytake/3`'s and `keysort/2`'s `N`, or a `counters` size. */
    private fun positiveInteger(term: OtpErlangObject): BigInteger? =
        (seen(term) as? OtpErlangLong)?.bigIntegerValue()?.takeIf { it.signum() > 0 }

    /** [keyIndex] of a tuple whose element [n] is `==` [key]; an [n] past a `Long` is no tuple's. */
    private fun keyIndexOf(elements: List<OtpErlangObject>, n: BigInteger, key: OtpErlangObject): Int =
        if (n.bitLength() >= Long.SIZE_BITS) -1 else keyIndex(elements, n.toLong(), ::seen) { equal(it, key, false, level) }

    /** The list of [elements] ending in [tail], `[]` where `null`, without the element at [index]. */
    private fun withoutAt(elements: List<OtpErlangObject>, index: Int, tail: OtpErlangObject?): OtpErlangObject =
        cons(elements.subList(0, index), cons(elements.subList(index + 1, elements.size), tail ?: EMPTY))

    /** `lists:keyfind/3`: the first tuple of the list whose element [args]`[1]` is `==` to [args]`[0]`. */
    private fun keyfind(args: List<OtpErlangObject>): OtpErlangTuple? {
        val badarg = { badargOf("lists", "keyfind", args) }
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

        if (tail != null) functionClause("lists", "do_flatten", listOf(tail, list(reversed.reversed())))

        var count = met

        for (element in elements.asReversed()) {
            ProgressManager.checkCanceled()

            if (++count > COPY_NODES) decline("lists:flatten/1 of a list too large to copy")
            if (isList(element)) count = flatten(element, reversed, count) else reversed += element
        }

        return count
    }

    /**
     * `sets` at version 2, the one `MapSet` asks for: a map from each element to `[]`, its keys compared exactly. A
     * version 1 set, or options that may ask for one, is left to `sets`' clauses; any other term has none.
     */
    private fun sets(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        fun version2(options: OtpErlangObject): Boolean =
            properElements(seen(options))?.singleOrNull()?.let { equal(it, VERSION_2, true, level) } == true
        // The set each of the other functions is given last; a version 1 set is a record, which only `sets`' clauses read.
        fun set(): OtpErlangMap? =
            when (val seen = seen(args.last())) {
                is OtpErlangMap -> seen
                is OtpErlangTuple -> null
                else -> functionClause("sets", key.substringBefore('/'), args)
            }

        return when (key) {
            "new/1" -> if (version2(args[0])) mapOf(emptyList(), level) else null
            "from_list/2" -> if (version2(args[1])) maps("from_keys/2", listOf(args[0], EMPTY)) else null
            "add_element/2" -> maps("put/3", listOf(args[0], EMPTY, set() ?: return null))
            "del_element/2" -> maps("remove/2", listOf(args[0], set() ?: return null))
            "is_element/2" -> maps("is_key/2", listOf(args[0], set() ?: return null))
            "size/1" -> integer((set() ?: return null).arity().toLong())
            "to_list/1" -> maps("keys/1", listOf(set() ?: return null))
            // Only two maps take the first clause; the other folds over the sets, and its failure is `size/1`'s.
            "union/2" -> maps("merge/2", args.map { seen(it) as? OtpErlangMap ?: return null })
            else -> null
        }
    }

    /** [map] without [key], as `maps:remove/2` gives it. */
    private fun without(map: OtpErlangMap, key: OtpErlangObject): OtpErlangMap =
        mapOf(entries(map).filterNot { equal(it.first, key, true, level) }, level)

    private fun maps(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "get/2" -> valueAt(map(args[1]), args[0], level) ?: evaluation.error(tuple(atom("badkey"), args[0]))
            "get/3" -> valueAt(map(args[1]), args[0], level) ?: args[2]
            "find/2" -> valueAt(map(args[1]), args[0], level)?.let { tuple(atom("ok"), it) } ?: atom("error")
            "is_key/2" -> bool(valueAt(map(args[1]), args[0], level) != null)
            "put/3" -> mapOf(entries(map(args[2])) + (args[0] to args[1]), level)
            "remove/2" -> without(map(args[1]), args[0])
            "take/2" -> {
                val map = map(args[1])

                valueAt(map, args[0], level)?.let { tuple(it, without(map, args[0])) } ?: atom("error")
            }
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
                val badarg = { badargOf("maps", "from_list", args) }

                mapOf(
                    (properElements(args[0]) ?: badarg()).map { element ->
                        val pair = (seen(element) as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: badarg()

                        pair.elementAt(0) to pair.elementAt(1)
                    },
                    level,
                )
            }
            "from_keys/2" -> mapOf((properElements(args[0]) ?: badargOf("maps", "from_keys", args)).map { it to args[1] }, level)
            "to_list/1" -> list(inOrder(map(args[0])).map { tuple(it.first, it.second) })
            "keys/1" -> list(inOrder(map(args[0])).map { it.first })
            else -> null
        }

    /**
     * `elixir_tokenizer:tokenize/3` of a charlist from a line, as `EEx` calls it: with `check_terminators: false`, and
     * maybe a binary `file:`, which changes nothing it gives. Any other text, line or option declines, and [Tokenizer]
     * declines where its map isn't exact.
     */
    private fun tokenize(args: List<OtpErlangObject>): OtpErlangObject {
        val text = charlistText(args[0]) ?: decline("elixir_tokenizer:tokenize/3 of %s", args[0])
        val line = smallInt(seen(args[1]))?.takeIf { it >= 0 } ?: decline("elixir_tokenizer:tokenize/3 from the line %s", args[1])
        val options = properElements(seen(args[2]))?.map(::seen) ?: decline("elixir_tokenizer:tokenize/3 given the options %s", args[2])
        var checksTerminators = true

        // `check_terminators: true` is read before 1.13 and passed over from it, so only `false` is ported.
        for (option in options) {
            val (key, value) = keywordPair(option)?.let { (key, value) -> key to seen(value) }
                ?: decline("elixir_tokenizer:tokenize/3 given the option %s", option)

            when (key) {
                "check_terminators" if value == FALSE -> checksTerminators = false
                "file" if value is OtpErlangBinary -> {}
                else -> decline("elixir_tokenizer:tokenize/3 given the option %s", option)
            }
        }

        if (checksTerminators) decline("elixir_tokenizer:tokenize/3, its terminators checked")

        return Tokenizer(level).tokenize(text, line)
    }

    /**
     * `counters`, over the arrays made in the runs that share the run's state
     * ([counterArrays][org.elixir_lang.expander.RunShared.counterArrays]). Code that may not run, or may run any
     * number of times ([joinsOnly][org.elixir_lang.expander.Run.joinsOnly]), reads a cell as not known and leaves one it
     * writes not known, as nothing joins it, and past code that declined no cell is known
     * ([declinedCodeRan][org.elixir_lang.expander.Run.declinedCodeRan]). `sub/3` and `info/1` aren't answered.
     */
    private fun counters(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "new/2" -> newCounters(args)
            "get/2" -> {
                val array = counterArray("get", args)
                val cell = array.cells[counterIndex(array, "get", args)]

                cell?.takeUnless { evaluation.run.joinsOnly }?.let(::integer) ?: Unknown
            }
            // An increment is any 64 bits, signed or not, and wraps.
            "add/3" -> writeCounter("add", args, { it.isLong || it.isULong }) { cell, n -> cell?.plus(n) }
            "put/3" -> writeCounter("put", args, { it.isLong }) { _, n -> n }
            else -> null
        }

    /**
     * `counters:new/2`: its options are read before its size, and a size that would take the cells of the arrays the run
     * shares past [COPY_NODES] declines.
     */
    private fun newCounters(args: List<OtpErlangObject>): OtpErlangObject {
        val options = properElements(seen(args[1]))?.map(::seen)
        val kind = if (options?.isEmpty() == true) {
            "atomics"
        } else {
            (options?.singleOrNull() as? OtpErlangAtom)?.atomValue()?.takeIf { it == "atomics" || it == "write_concurrency" }
                ?: countersError("badarg", "new", args, cause = "badopt")
        }
        val size = positiveInteger(args[0]) ?: countersError("badarg", "new", args)

        if (size.bitLength() > 64) countersError("system_limit", "new", args)
        val shared = evaluation.run.shared

        if (size > BigInteger.valueOf((COPY_NODES - shared.counterCells).toLong())) decline("a `counters` array of $size cells")

        val ref = newRef()

        shared.counterArrays[ref] = CounterArray(kind, Array(size.toInt()) { 0L })
        shared.counterCells += size.toInt()

        return tuple(atom(kind), ref)
    }

    /**
     * The array `counters:`[function]'s first argument names, raising `badarg` where it names none. A reference no
     * `counters:new/2` of the runs sharing the run's state made declines: it may be another run's.
     */
    private fun counterArray(function: String, args: List<OtpErlangObject>): CounterArray {
        val counter = (seen(args[0]) as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: countersError("badarg", function, args)
        val kind = (seen(counter.elementAt(0)) as? OtpErlangAtom)?.atomValue()
        val ref = seen(counter.elementAt(1)) as? OtpErlangRef ?: countersError("badarg", function, args)
        val array = evaluation.run.shared.counterArrays[ref] ?: decline("a `counters` reference no run sharing this one's state made")

        return array.takeIf { it.kind == kind } ?: countersError("badarg", function, args)
    }

    /** The 0-based index of [array] that `counters:`[function]'s second argument names, raising `badarg` where none. */
    private fun counterIndex(array: CounterArray, function: String, args: List<OtpErlangObject>): Int =
        index(args[1], array.cells.size)?.minus(1) ?: countersError("badarg", function, args)

    /**
     * `counters:add/3` or `put/3`: the cell becomes [write] of its value and the third argument, where that [fits], else
     * `badarg`.
     */
    private fun writeCounter(
        function: String,
        args: List<OtpErlangObject>,
        fits: (OtpErlangLong) -> Boolean,
        write: (cell: Long?, n: Long) -> Long?,
    ): OtpErlangObject {
        val array = counterArray(function, args)
        val index = counterIndex(array, function, args)
        val n = (seen(args[2]) as? OtpErlangLong)?.takeIf(fits) ?: countersError("badarg", function, args)

        array.cells[index] = if (evaluation.run.joinsOnly) null else write(array.cells[index], n.longValue())

        return atom("ok")
    }

    /** Raises [reason] from `counters:`[function] given [args], with the `error_info` `counters` gives it. */
    private fun countersError(reason: String, function: String, args: List<OtpErlangObject>, cause: String? = null): Nothing {
        val info = listOfNotNull(atom("module") to atom("erl_erts_errors"), cause?.let { atom("cause") to atom(it) })
        val location = list(listOf(tuple(atom("error_info"), mapOf(info, level))))

        evaluation.error(atom(reason), frame("counters", function, list(args), location))
    }

    /**
     * [map]'s entries in the order `maps:to_list/1` gives them: term order, declining over 32 keys. Where the order must
     * be [exact]ly the VM's, two atom keys decline from [MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER], which orders them by their
     * index in its atom table.
     */
    private fun inOrder(map: OtpErlangMap, exact: Boolean = false): List<Pair<OtpErlangObject, OtpErlangObject>> {
        val entries = entries(map)

        if (entries.size > 32) decline("the order of a map of more than 32 keys")
        if (exact && MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(level) && entries.count { seen(it.first) is OtpErlangAtom } > 1) {
            decline("the order of a map's atom keys, which is their index in the VM's atom table")
        }

        return entries.sortedWith { left, right -> compare(left.first, right.first, level, true) }
    }

    /** `elixir_quote`'s run-time helpers, which an expanded `quote` calls. */
    private fun quote(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val noClause = { function: String -> functionClause("elixir_quote", function, args) }

        return when (key) {
            "validate_quote/1" -> args[0]
            "validate_runtime/2" -> {
                val value = typed(args[1])
                val valid = when ((seen(args[0]) as? OtpErlangAtom)?.atomValue()) {
                    "line" -> value is OtpErlangLong
                    "file" -> value is OtpErlangBitstr && value.pad_bits() == 0
                    "context" -> value is OtpErlangAtom && value != NIL
                    "generated", "unquote" -> boolean(value) != null
                    else -> noClause("is_valid")
                }

                if (valid) args[1] else argumentError()
            }
            "dot/5" -> annotate(dot(args[0], args[1], args[2], args[3]), args[4])
            "dot/4" -> dot(args[0], args[1], args[2], args[3])
            "list/2" -> {
                if (!isList(args[1])) noClause("list")

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
                    else -> noClause("tail_list")
                }
            }
            "unquote/1", "shallow_validate_ast/1" ->
                if (shallowValidAst(args[0], WalkedParts())) args[0] else argumentError()
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

        val elements = properElements(meta) ?: decline("metadata that isn't a list")

        return list(
            keystore(elements, tuple(atom("context"), value)) {
                (seen(it) as? OtpErlangTuple)?.takeIf { pair -> pair.arity() >= 1 }?.elementAt(0)?.let(::seen)
            },
        )
    }

    /** `lists:keydelete(counter, 1, Meta)`. */
    private fun deleteCounter(meta: OtpErlangObject): OtpErlangObject =
        lists("keydelete/3", listOf(atom("counter"), integer(1), meta))!!

    /** `elixir_quote:validate_list/1`. */
    private fun validateList(term: OtpErlangObject) {
        val valid =
            if (UNQUOTE_SHALLOW_VALIDATED.isSufficient(level)) shallowValidList(term, WalkedParts()) else isList(term)

        if (!valid) argumentError()
    }

    private fun shallowValidAst(term: OtpErlangObject, walked: WalkedParts): Boolean =
        if (isList(term)) shallowValidList(term, walked) else shallowValidElem(term, walked)

    private fun shallowValidList(term: OtpErlangObject, walked: WalkedParts): Boolean {
        ProgressManager.checkCanceled()

        // A list walked before was valid, as an invalid one ends the walk.
        if (walked.again(term)) return true

        val (elements, tail) = cells(term) ?: return false

        return elements.all { shallowValidElem(it, walked) } && tail == null
    }

    private fun shallowValidElem(term: OtpErlangObject, walked: WalkedParts): Boolean {
        ProgressManager.checkCanceled()

        when (val typed = typed(term)) {
            is OtpErlangList, is OtpErlangString, is OtpErlangAtom, is OtpErlangLong, is OtpErlangDouble, is OtpErlangPid,
            is OtpErlangFun, is OtpErlangExternalFun -> return true
            is OtpErlangBitstr -> return typed.pad_bits() == 0
        }

        // A tuple walked before was valid, as an invalid one ends the walk.
        if (walked.again(term)) return true

        val tuple = term as? OtpErlangTuple ?: return false

        return when (tuple.arity()) {
            2 -> shallowValidElem(tuple.elementAt(0), walked) && shallowValidElem(tuple.elementAt(1), walked)
            3 -> {
                val (call, meta, args) = tuple.elements()

                when {
                    !isList(meta) -> false
                    (isAtom(args) || isList(args)) && isAtom(call) -> true
                    isList(args) -> shallowValidAst(call, walked)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun keyword(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        // `get/2` and `pop/2` are `get/3` and `pop/3` with their `nil` default, as a `FunctionClauseError` names them.
        if (key == "get/2" || key == "pop/2") return keyword(key.replace("/2", "/3"), args + NIL)

        val noClause = { function: String -> functionClause("Elixir.Keyword", function, args) }
        val guarded = { function: String ->
            if (!isList(args[0]) || !isAtom(args[1])) noClause(function)
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
            "keyword?/1" -> forAnyTerm { bool(isKeyword(args[0])) }
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
                            val name = pairKey(seen(pair))?.let(::seen) as? OtpErlangAtom
                                ?: argumentError()

                            lists("keymember/3", listOf(name, integer(1), right)) == FALSE
                        }

                        erlang("++/2", listOf(kept, right))
                    }
                    else -> noClause("merge")
                }
            }
            "take/2", "drop/2" -> {
                val (keywords, keys) = args
                val take = key == "take/2"

                if (!isList(keywords) || !isList(keys)) noClause(key.substringBefore('/'))

                filter(keywords) { pair ->
                    val name = pairKey(seen(pair))
                        ?: functionClause("Elixir.Keyword", "-${key.substringBefore('/')}/2-fun-0-", listOf(pair))

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
                ?: functionClause("Elixir.Keyword", "delete_key", listOf(cons(elements.subList(index, elements.size), tail ?: EMPTY), key))

            element.takeUnless { equal(pair.elementAt(0), key, true, level) }
        }

        if (tail != null) functionClause("Elixir.Keyword", "delete_key", listOf(tail, key))

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
        isFunction(term) || term is OtpErlangMap && term.get(STRUCT)?.let(::enumeratesItself) == true

    /** Whether a struct of [module] enumerates through its own implementation: any but a `Range`, which `Enum` enumerates. */
    private fun enumeratesItself(module: OtpErlangObject): Boolean = module != RANGE

    /** [term] where it is a `Range`. */
    private fun range(term: OtpErlangObject): Range? {
        val map = term as? OtpErlangMap ?: return null

        if (map.get(STRUCT) != RANGE) return null

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

    /**
     * `Access.fetch/2` and `get/2,3` (`Access:247–371` at v1.20.4): `fetch/2` gives what a struct's `fetch/2` gives,
     * else `{:ok, value}` or `:error` for a map's or a keyword list's key; `get` gives the value, or its default.
     */
    private fun access(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        // `get/2` is `get/3` with its `nil` default, as a `FunctionClauseError` names it.
        if (key == "get/2") return access("get/3", args + NIL)

        val fetch = key == "fetch/2"
        if (!fetch && key != "get/3") return null

        val (container, accessKey) = args

        val fetched = when (val value = seen(container)) {
            is OtpErlangMap -> {
                val struct = value.get(STRUCT) as? OtpErlangAtom

                if (struct != null) {
                    evaluation.remote(struct, "fetch", listOf(container, accessKey))
                } else {
                    maps("find/2", listOf(accessKey, value))!!
                }
            }
            is OtpErlangList, is OtpErlangString ->
                if (isAtom(accessKey)) keyword("fetch/2", listOf(container, accessKey))!! else argumentError()
            NIL -> atom("error")
            else -> functionClause("Elixir.Access", key.substringBefore('/'), args)
        }

        return when {
            fetch -> fetched
            isRecord(fetched, 2, "ok") -> (fetched as OtpErlangTuple).elementAt(1)
            fetched == atom("error") -> args[2]
            else -> evaluation.error(tuple(atom("try_clause"), seen(fetched)))
        }
    }

    private fun env(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val context = when (key) {
            "in_guard?/1" -> "guard"
            "in_match?/1" -> "match"
            "stacktrace/1" -> return stacktrace(macroEnvOf(args[0]) ?: functionClause("Elixir.Macro.Env", "stacktrace", args))
            else -> return null
        }
        val env = macroEnvOf(args[0])?.get(atom("context"))
            ?: functionClause("Elixir.Macro.Env", key.substringBefore('/'), args)

        return bool(seen(env) == atom(context))
    }

    /**
     * `IO.warn/1,2` (`io.ex`), recorded as the macro call's warning, with its stacktrace as given: `IO.warn/1`'s is the
     * caller's, which isn't known. The second argument is read first, as far as Elixir reads it before the message
     * ([located]). A message that isn't a list is then made a string as `to_string/1` makes one, and a list, or one that
     * isn't a binary of UTF-8, declines. A warning at a place declines.
     */
    private fun io(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        if (key != "warn/1" && key != "warn/2") return null

        val at = located(args)
        val message = args[0].takeUnless(::isList)?.let { utf8(toString(it)) } ?: decline("IO.warn/${args.size} of %s", args[0])

        when (at) {
            WarnedAt.Place -> decline("IO.warn/2 at %s", args[1])
            is WarnedAt.Nowhere -> evaluation.warn(message, at.stacktrace)
        }

        return atom("ok")
    }

    /**
     * Where the second of [args], `IO.warn`'s, says the warning is, read as far as Elixir reads it before the message:
     * nowhere where it is absent, `[]` or a stacktrace, and at a place where an env ([placedAt]) or options
     * ([optionsAt]) say. One that is none of them raises `function_clause`, as an env or options does before
     * `IO.warn/2` takes them ([IO_WARN_TAKES_AN_ENV_OR_OPTIONS]).
     */
    private fun located(args: List<OtpErlangObject>): WarnedAt {
        val given = args.getOrNull(1) ?: return WarnedAt.Nowhere(null)
        val noClause = { functionClause("Elixir.IO", "warn", args) }
        val takes = IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(level)

        macroEnvOf(given)?.let { return if (takes) placedAt(it, noClause) else noClause() }

        val (elements, _) = cells(seen(given)) ?: noClause()

        return when ((elements.firstOrNull()?.let(::seen) as? OtpErlangTuple)?.arity()) {
            null -> if (elements.isEmpty()) WarnedAt.Nowhere(given) else noClause()
            4 -> WarnedAt.Nowhere(given)
            2 -> if (takes) optionsAt(given, noClause) else noClause()
            else -> noClause()
        }
    }

    /**
     * The place at [env], as `IO.warn/2` takes it: before [IO_WARN_READS_THE_MESSAGE_FIRST] any env, whose stacktrace
     * is made before the message; from it, only one with a `:line` and a `:file`, and another raises `function_clause`
     * ([noClause]).
     */
    private fun placedAt(env: OtpErlangMap, noClause: () -> Nothing): WarnedAt.Place {
        when {
            !IO_WARN_READS_THE_MESSAGE_FIRST.isSufficient(level) -> stacktrace(env)
            env.get(atom("line")) == null || env.get(atom("file")) == null -> noClause()
        }

        return WarnedAt.Place
    }

    /**
     * Where [options], `IO.warn/2`'s, say a warning is: nowhere, as `[]` says, where they name no file (`:file` absent,
     * `nil` or `false`), else at a place. From [IO_WARN_OPTIONS_TAKE_A_COLUMN] their `:line` and `:column` are read
     * before the message; before it, their `:module`, `:function` and `:line` are read into the env `IO.warn/2` then
     * takes ([placedAt]).
     */
    private fun optionsAt(options: OtpErlangObject, noClause: () -> Nothing): WarnedAt {
        fun option(name: String) = access("get/2", listOf(options, atom(name)))!!

        val file = seen(option("file"))

        if (!truthy(file)) return WarnedAt.Nowhere(EMPTY)

        if (!IO_WARN_OPTIONS_TAKE_A_COLUMN.isSufficient(level)) {
            val fields = listOf("module", "function", "line").map { atom(it) to option(it) }

            val env = mapOf(listOf(STRUCT to MACRO_ENV, atom("file") to file) + fields, level)

            return placedAt(env, noClause)
        }

        option("line")
        option("column")

        return WarnedAt.Place
    }

    /**
     * `Macro.Env.stacktrace/1` (`env.ex`): [env]'s one frame, the compiler's outside a module and the module body's
     * outside a function, at its line and its file relative to where Elixir compiled it (`Run.relativeToCwd`).
     */
    private fun stacktrace(env: OtpErlangMap): OtpErlangObject {
        val file = nameText("Macro.Env.stacktrace/1 of a file", macroEnvField(env, "file"))
        val os = evaluation.run.os ?: decline("Path.relative_to_cwd/1 of a file whose SDK's OS isn't known")
        val relative = evaluation.run.relativeToCwd(file, os)
        val location = list(
            listOf(
                tuple(atom("file"), relative?.let(::charlist) ?: Unknown),
                tuple(atom("line"), macroEnvField(env, "line")),
            ),
        )
        val module = macroEnvField(env, "module")
        val frame = when {
            module == NIL -> tuple(atom("elixir_compiler"), atom("__FILE__"), integer(1), location)
            else -> when (val function = macroEnvField(env, "function")) {
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
            "create/3" -> evaluation.createModule(args)
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
     * `fetch_compile_env`'s key and `fetch_env/2`'s app guards ([clauseGuard]), then the application's environment as a
     * default build sees it ([fetched]), and `traverse_env/2` down the rest of the path through `Access.fetch/2`, a rest
     * not known included. Where it isn't known, nor is whether `compile_env!` finds it, and it may raise
     * ([Evaluation.raiseMayBeCaught]). An env that isn't known may not be one, and raise; before
     * [COMPILE_ENV_TAKES_ENV_FIRST] it is read only for its tracers, after the traversal, where a binary raises. A tracer
     * the env names records the read, and writes no module. `get_env/2,3`, `fetch_env/2` and `fetch_env!/2`
     * (`App:724–783` at v1.20.4, `App:620–668` at v1.11.4) read the same environment after their `is_atom(app)` guard;
     * `get_env/2` is `get_env/3`'s default, and `fetch_env!/2` raises where nothing sets the key and may raise where the
     * value isn't known.
     */
    private fun application(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        // `get_env/2` is `get_env/3` with its `nil` default, as a `FunctionClauseError` names it.
        if (key == "get_env/2") return application("get_env/3", args + NIL)

        val envFirst = COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(level)

        when (key) {
            "get_env/3", "fetch_env/2", "fetch_env!/2" -> {
                clauseGuard(args[0], key.substringBefore('/'), args)

                val fetched = fetched(args[0], args[1])

                return when (key) {
                    "fetch_env/2" -> fetched
                    "fetch_env!/2" -> fetchedValue(fetched, args[0], null)
                    else -> fetchedValue(fetched, args[0], args[2])
                }
            }
            "compile_env/4", "compile_env!/3" -> if (!envFirst) return null
            "__compile_env__/4", "__compile_env__!/3" -> if (envFirst) return null
            else -> return null
        }

        val env = if (envFirst) args[0] else args.last()
        val (app, path) = if (envFirst) args[1] to args[2] else args[0] to args[1]

        if (envFirst) clauseGuard(env, key.substringBefore('/'), args) { (it as? OtpErlangMap)?.let(::macroEnvOf) != null }

        // A path not known is a key not known, and a rest not known.
        val (elements, tail) = when (val keys = path.takeUnless(::isUnknown)) {
            null -> listOf(path) to path
            is OtpErlangAtom -> listOf(keys) to null
            else -> cells(keys) ?: (emptyList<OtpErlangObject>() to null)
        }
        // An empty path, or one that is no list, fails the first key's guard.
        val first = elements.firstOrNull() ?: path
        val rest = elements.drop(1)

        clauseGuard(first, "fetch_compile_env", listOf(app, path, env))
        clauseGuard(app, "fetch_env", listOf(app, first))

        // `{:ok, value}`, `:error`, or a value not known that may be either.
        var traversed = fetched(app, first)
        // A tail not known may hold more keys.
        for (next in rest + listOfNotNull(tail?.takeIf(::isUnknown))) {
            val value = (traversed as? OtpErlangTuple)?.elementAt(1) ?: traversed.takeIf(::isUnknown) ?: break
            traversed = evaluation.remote(atom("Elixir.Access"), "fetch", listOf(value, next))
        }
        // `traverse_env/2` takes the path's tail as a list: one that isn't raises where a value was fetched before it.
        if (tail != null && !isUnknown(tail) && traversed is OtpErlangTuple) {
            functionClause("Elixir.Application", "traverse_env", listOf(traversed, tail))
        }

        if (!envFirst) {
            when {
                env is UnknownValue -> evaluation.raiseMayBeCaught()
                typed(env) is OtpErlangBinary -> argumentError()
                (env as? OtpErlangMap)?.get(atom("tracers")) == null -> decline("reads the tracers of %s", env)
            }
        }
        val default = when {
            key.endsWith("!/3") -> null
            envFirst -> args[3]
            else -> args[2]
        }

        return fetchedValue(traversed, app, default, raisesOnUnknown = tail != null)
    }

    /**
     * [read], `fetch_env/2`'s `{:ok, value}` or `:error`, or a value not known that may be either, as a read with
     * [default] gives it: its value, else [default], else `ArgumentError`. Not known, it may raise where there is no
     * [default] or where [raisesOnUnknown] says so ([Evaluation.raiseMayBeCaught]), and holds what the env of [app] may
     * ([unfetched]).
     */
    private fun fetchedValue(
        read: OtpErlangObject,
        app: OtpErlangObject,
        default: OtpErlangObject?,
        raisesOnUnknown: Boolean = false,
    ): OtpErlangObject =
        when (read) {
            is OtpErlangTuple if seen(read.elementAt(0)) == atom("ok") -> read.elementAt(1)
            atom("error") -> default ?: argumentError()
            else -> {
                if (default == null || raisesOnUnknown) evaluation.raiseMayBeCaught()
                unfetched((app as? OtpErlangAtom)?.atomValue())
            }
        }

    /**
     * [module]'s [function]`(`[args]`)`'s guard on [term], `is_atom` unless [passes] says otherwise: one that fails it
     * raises `FunctionClauseError`, and one not known may ([Evaluation.raiseMayBeCaught]). A binary whose bytes aren't
     * known is a binary, which [passes] judges.
     */
    private fun clauseGuard(
        term: OtpErlangObject,
        function: String,
        args: List<OtpErlangObject>,
        module: String = "Elixir.Application",
        passes: (OtpErlangObject) -> Boolean = ::isAtom,
    ) {
        when {
            term is UnknownValue -> evaluation.raiseMayBeCaught()
            !passes(term) -> functionClause(module, function, args)
        }
    }

    /**
     * What the env of the app named [app], or, `null`, of an app not known, holds where [fetched] doesn't know it: where
     * the run wrote it, a value that may be or hold one of the modules being compiled ([ModuleUnknown]), as what the run
     * wrote may.
     */
    private fun unfetched(app: String?): UnknownValue = if (evaluation.run.wroteEnvOf(app)) ModuleUnknown else Unknown

    /**
     * `fetch_env/2`'s answer for [app]'s [key]: what the run wrote it as
     * ([Run.envWritten][org.elixir_lang.expander.Run.envWritten]), else what the environment a default build sees, the
     * run's `appEnv`, holds: `{:ok, value}` where the `.app` declares it, `:error` where nothing sets it, and unknown
     * ([unfetched]) where a config file may set it, its `.app` can't be read, or [app] or [key] isn't an atom. Each
     * answer `appEnv` gives is an assumption: the environment may change before the read. So is what the run wrote,
     * where it isn't alone ([Run.sequential][org.elixir_lang.expander.Run.sequential]): another module's compile may
     * write the key between.
     */
    private fun fetched(app: OtpErlangObject, key: OtpErlangObject): OtpErlangObject {
        val name = (app as? OtpErlangAtom)?.atomValue()
        if (name == null || key !is OtpErlangAtom) return unfetched(name)

        evaluation.run.envWritten(name, key)?.let { written ->
            val answer = when (written) {
                is EnvWrite.Value -> tuple(atom("ok"), written.term)
                EnvWrite.Unset -> atom("error")
                EnvWrite.Unknown -> return unfetched(name)
            }
            if (!evaluation.run.sequential) evaluation.assume()

            return answer
        }

        val answer = when (val value = evaluation.run.appEnv.of(name, key)) {
            is AppEnvValue.Declared -> tuple(atom("ok"), value.term)
            AppEnvValue.Unset -> atom("error")
            AppEnvValue.Configured, AppEnvValue.Unreadable -> return unfetched(name)
        }
        evaluation.assume()

        return answer
    }

    /**
     * `application:set_env/1,2,3,4` and `unset_env/2,3` (`application.erl:767–894` at OTP 29.0.6, `:324–377` at OTP
     * 24.3.4.6), which `Application`'s writers call. Each runs its guards, then records on the run what it writes
     * ([Run.wroteEnv][org.elixir_lang.expander.Run.wroteEnv]), every app where one isn't known, and gives `:ok`.
     * Whether `set_env/1,2`'s config is one the controller takes isn't known. `set_env/4` and `unset_env/3` exit before
     * they write where their timeout ([envTimeout]) isn't one `gen:call/4` takes, and may exit, their key written or not,
     * where it may run out before the reply ([waitsForTheReply]).
     */
    private fun envWrite(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        val run = evaluation.run

        when (key) {
            "set_env/1", "set_env/2" -> {
                if (args.any { !isUnknown(it) && cells(it) == null }) functionClause("application", "set_env", args)
                val config = args[0]
                when (val elements = if (isUnknown(config)) null else properElements(config)) {
                    null -> run.wroteEnv(Unknown)
                    else -> elements.forEach { element ->
                        (if (isUnknown(element)) element else pairKey(element))?.let { run.wroteEnv(it) }
                    }
                }

                decline("whether %s is a config the application controller takes isn't known", config)
            }
            "set_env/3", "unset_env/2" -> run.wroteEnv(args[0], args[1], writeOf(key, args))
            "set_env/4", "unset_env/3" -> {
                val opts = args.last()
                val function = key.substringBefore('/')
                clauseGuard(opts, function, args, module = "application") {
                    isTimeout(it) || it is OtpErlangList || it is OtpErlangString
                }
                val timeout = if (isTimeout(opts)) opts else envTimeout(opts)

                val write = when {
                    // `gen:call/4` has no clause for it, which `gen_server:call/3` exits with.
                    !isUnknown(timeout) && !isTimeout(timeout) -> {
                        val request = tuple(atom(function), *args.toTypedArray())
                        val call = list(listOf(atom("application_controller"), request, timeout))

                        evaluation.raiseAs("exit", tuple(tuple(atom("function_clause"), Unknown), tuple(atom("gen_server"), atom("call"), call)))
                    }
                    // A timeout that may run out before the controller replies, which it writes once it reaches.
                    !waitsForTheReply(timeout) -> {
                        evaluation.raiseMayBeCaught()
                        EnvWrite.Unknown
                    }
                    else -> writeOf(key, args)
                }
                run.wroteEnv(args[0], args[1], write)
            }
            else -> return null
        }

        return atom("ok")
    }

    /** Whether [term] is a timeout `gen:call/4` takes: `infinity` or a non-negative integer. */
    private fun isTimeout(term: OtpErlangObject): Boolean =
        term == INFINITY || term is OtpErlangLong && term.bigIntegerValue().signum() >= 0

    /**
     * Whether `gen:call/4` given [timeout] gets the controller's reply: its `receive` waits for it ([receiveTimeout]),
     * where `0` runs out at once and past 4294967295 raises, each once the request is sent.
     */
    private fun waitsForTheReply(timeout: OtpErlangObject): Boolean =
        receiveTimeout(timeout).let { it == ReceiveTimeout.NEVER || it == ReceiveTimeout.LATER }

    /**
     * `proplists:get_value(timeout, Opts, 5000)` of [opts], a list, which `application_controller` calls with: not known
     * where [opts] isn't, or an element before the first naming `timeout`, or the tail it reaches.
     */
    private fun envTimeout(opts: OtpErlangObject): OtpErlangObject {
        if (isUnknown(opts)) return Unknown
        val (elements, tail) = cells(opts) ?: return Unknown
        val timeout = atom("timeout")
        val default = integer(5000)

        for (element in elements) {
            if (isUnknown(element)) return Unknown
            if (element == timeout) return atom("true")
            if (element !is OtpErlangTuple || element.arity() == 0) continue

            val first = element.elementAt(0)
            if (isUnknown(first)) return Unknown
            if (first == timeout) return if (element.arity() == 2) element.elementAt(1) else default
        }

        return when {
            tail == null -> default
            isUnknown(tail) -> Unknown
            else -> functionClause("proplists", "get_value", listOf(timeout, tail, default))
        }
    }

    /** What `set_env` or `unset_env`, [key], writes with [args]. */
    private fun writeOf(key: String, args: List<OtpErlangObject>): EnvWrite =
        if (key.startsWith("set_env/")) EnvWrite.Value(args[2]) else EnvWrite.Unset

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
                else -> binaryBytes(seen)
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

    /**
     * `String.Chars.to_string/1` by its `BitString`, `Atom`, `Integer` and `List` implementations; the others aren't
     * read. A list that `List.to_string/1` raises on declines, as its exceptions aren't `unicode`'s `badarg`. A function,
     * a map that isn't a struct, a pid, a port, a reference or a tuple raises `Protocol.UndefinedError`, as Elixir
     * implements the protocol for none of them ([unimplementedType]) and it has no fallback. Where the project may
     * implement it for that type, the call declined before it got here ([Evaluation.givenAStruct]).
     */
    private fun toString(term: OtpErlangObject): OtpErlangObject =
        when {
            isBinary(term) -> term
            term == NIL -> binary("")
            term is OtpErlangAtom -> binary(term.atomValue())
            term is OtpErlangLong -> binary(term.bigIntegerValue().toString())
            isList(term) ->
                OtpErlangBinary(chardataBytes("characters_to_binary", listOf(term)) { decline("List.to_string/1 of %s, which is no chardata", term) })
            unimplementedType(STRING_CHARS, term) != null ->
                evaluation.raise("Elixir.Protocol.UndefinedError", "protocol" to atom("Elixir.${STRING_CHARS.protocol}"), "value" to term)
            else -> decline("String.Chars of %s", term)
        }

    private fun kernel(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "elem/2" -> erlang("element/2", listOf(erlang("+/2", listOf(args[1], integer(1)))!!, args[0]))
            "put_elem/3" -> erlang("setelement/3", listOf(erlang("+/2", listOf(args[1], integer(1)))!!, args[0], args[2]))
            "inspect/1" -> (args[0] as? OtpErlangAtom)?.let(::inspectAtom) ?: UnknownBinary
            "inspect/2" -> inspect(args[1], args)
            else -> null
        }

    /** `inspect/1` of [atom], as [InspectAtom.literal] writes it on the leg, declining where it can't. */
    private fun inspectAtom(atom: OtpErlangAtom): OtpErlangObject =
        binary(InspectAtom.literal(atom.atomValue(), level) ?: decline("inspect/1 of %s, which this release writes otherwise", atom))

    /**
     * `Kernel.inspect/2`, `when is_list(opts)`: with no options, or `:charlists` with a value that only chooses how a list
     * reads, a binary whose bytes aren't known. `:as_charlists` and other options decline: which of them raise is
     * `Inspect.Opts`' and `Inspect.Algebra`'s to say, and those aren't read.
     */
    private fun inspect(options: OtpErlangObject, args: List<OtpErlangObject>): OtpErlangObject =
        when {
            options == EMPTY || choosesOnlyCharlists(options) -> UnknownBinary
            !isUnknown(options) && !isList(options) -> functionClause("Elixir.Kernel", "inspect", args)
            else -> decline("Kernel.inspect/2 with options %s", options)
        }

    private fun choosesOnlyCharlists(options: OtpErlangObject): Boolean {
        val (elements, tail) = options.takeUnless(::isUnknown)?.let(::cells) ?: return false

        return tail == null && elements.all { element ->
            val option = (element as? OtpErlangTuple)?.takeIf { it.arity() == 2 && it.elementAt(0) == atom("charlists") }
                ?: return@all false

            !isUnknown(option.elementAt(1)) && option.elementAt(1) != atom("as_charlists")
        }
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
        evaluation.functionClause(module, function, args)

    private fun badargOf(module: String, function: String, args: List<OtpErlangObject>): Nothing = evaluation.badargOf(module, function, args)

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
        smallInt(seen(term))?.takeIf { it in 1..size }

    private fun map(term: OtpErlangObject): OtpErlangMap =
        seen(term) as? OtpErlangMap ?: evaluation.error(tuple(atom("badmap"), term))

    private fun isList(term: OtpErlangObject) = typed(term).let { it is OtpErlangList || it is OtpErlangString }

    private fun isBinary(term: OtpErlangObject) = typed(term).let { it is OtpErlangBitstr && it.pad_bits() == 0 }

    private fun binaries(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "at/2" -> byteAt(args[0], args[1])
            "compile_pattern/1" -> compilePattern(args[0])
            "last/1" -> lastByte(args[0])
            "copy/2" -> binaryCopy(args)
            "split/2", "split/3" -> binarySplit("binary:$key", args)
            "match/2" -> binaryMatches("binary:$key", args, global = false).firstOrNull()?.let(::part) ?: atom("nomatch")
            "matches/2" -> list(binaryMatches("binary:$key", args, global = true).map(::part))
            else -> null
        }

    private fun binaryMatches(function: String, args: List<OtpErlangObject>, global: Boolean) =
        Filename.binaryMatches(nameBytes(function, args[0]), patterns(function, args), global)

    private fun part(match: Pair<Int, Int>) = tuple(integer(match.first.toLong()), integer(match.second.toLong()))

    /**
     * The binaries the pattern of [function], the second of [args], is: one binary, a list of them, none empty, or what
     * [compilePattern] made of either; any other tuple raises `badarg`, as [args], the call's, give it, and anything else
     * declines.
     */
    private fun patterns(function: String, args: List<OtpErlangObject>): List<ByteArray> {
        val pattern = args[1]

        (seen(pattern) as? OtpErlangTuple)?.let { tuple ->
            val compiled = tuple.takeIf { it.arity() == 2 }?.let { seen(it.elementAt(1)) as? CompiledPattern }

            return compiled?.takeIf { seen(tuple.elementAt(0)) == it.tag }?.bytes
                ?: badargOf("binary", function.substringAfter(':').substringBefore('/'), args)
        }

        return (properElements(seen(pattern))?.takeIf { it.isNotEmpty() } ?: listOf(pattern)).map {
            nameBytes(function, it).takeIf { bytes -> bytes.isNotEmpty() } ?: decline("$function of an empty pattern")
        }
    }

    /**
     * `binary:split/2,3`, [function], of a binary at a binary, a list of them, none empty, or a [compiled][compilePattern]
     * pattern, with `global` and `trim_all` in any order; any other option, such as `trim` or `scope`, declines.
     */
    private fun binarySplit(function: String, args: List<OtpErlangObject>): OtpErlangObject {
        val patterns = patterns(function, args)
        val options = args.getOrElse(2) { EMPTY }
        val flags = (properElements(seen(options)) ?: decline("$function of %s, which is no list of options", options)).map(::seen)

        if (!flags.all { it == GLOBAL || it == TRIM_ALL }) decline("$function with the options %s", options)

        val parts = Filename.binarySplit(nameBytes(function, args[0]), patterns, GLOBAL in flags)

        return list((if (TRIM_ALL in flags) parts.filter { it.isNotEmpty() } else parts).map(::OtpErlangBinary))
    }

    /**
     * `binary:compile_pattern/1`: `{bm, pattern}` for one binary, `{ac, patterns}` for a list of several, as Erlang tags
     * them, where Erlang's second element is a reference to the compiled form, a [CompiledPattern]; `badarg` for any
     * other term.
     */
    private fun compilePattern(pattern: OtpErlangObject): OtpErlangObject {
        val bytes = (properElements(seen(pattern)) ?: listOf(pattern)).map { binaryBytes(it)?.takeIf(ByteArray::isNotEmpty) }

        if (bytes.isEmpty() || null in bytes) badargOf("binary", "compile_pattern", listOf(pattern))

        val tag = atom(if (bytes.size == 1) "bm" else "ac")

        return tuple(tag, CompiledPattern(tag, bytes.requireNoNulls(), newRefIds()))
    }

    /** `os:type/0`: the OS family and name the run's SDK runs as. */
    private fun osType(): OtpErlangObject {
        val (family, name) = evaluation.run.os?.let(Filename::osType) ?: decline("os:type/0 with no answer for the run's OS")

        return tuple(atom(family), atom(name))
    }

    /** `os`'s rows: `type/0` from the run's OS, and the clock `System.os_time/0,1` reads, as `system`'s reads are. */
    private fun os(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "type/0" -> osType()
            "system_time/0" -> Unknown
            "system_time/1" -> readGiven(args[0], ::isTimeUnit) { badargOf("os", "system_time", args) }
            else -> null
        }

    /**
     * A read of the machine given [arg], which it [refuses][refused] where it doesn't [take][takes] it: what it read, a
     * raise, or, where [arg] isn't known, what it read unless it raised.
     */
    private fun readGiven(arg: OtpErlangObject, takes: (OtpErlangObject) -> Boolean, refused: () -> Nothing): OtpErlangObject =
        when {
            holdsUnknown(arg) -> evaluation.unlessRaised(Unknown)
            takes(arg) -> Unknown
            else -> refused()
        }

    /**
     * `System`'s reads of the machine the build runs on, which another machine or run may answer differently: each is
     * [Unknown], or [UnknownBinary] where its spec gives a binary, and none reads the machine. One whose `!` raises where
     * the machine lacks what it reads says it may raise. Where a guard refuses the arguments there is no row, and the
     * clauses raise as Elixir's do. The clocks are `erlang`'s and `os`'s rows, which `System`'s clauses reach.
     */
    private fun system(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "get_env/0", "user_home/0", "tmp_dir/0", "schedulers/0", "schedulers_online/0", "endianness/0" -> Unknown
            "get_pid/0", "pid/0", "otp_release/0" -> UnknownBinary
            "user_home!/0", "tmp_dir!/0" -> evaluation.unlessRaised(UnknownBinary)
            "get_env/1", "fetch_env/1" -> Unknown.takeIf { isBinary(args[0]) }
            "get_env/2" -> when {
                !isBinary(args[0]) -> null
                isBinary(args[1]) -> UnknownBinary
                typed(args[1]) == NIL -> Unknown
                else -> null
            }
            "fetch_env!/1" -> if (isBinary(args[0])) evaluation.unlessRaised(UnknownBinary) else null
            else -> null
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
     * `Mix.Project`'s config in a config read, and its paths of that config: what the build machine and the project stack
     * make them, so not known, a path a binary that may raise. Given a config that is known, Elixir's own clauses run
     * over it. Outside a read, no row.
     */
    private fun mixProject(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        evaluation.run.config ?: return null
        if (args.any { !isUnknown(it) }) return null

        return when (key) {
            "config/0" -> Unknown
            "build_path/0", "build_path/1", "deps_path/0", "deps_path/1", "app_path/0", "app_path/1" -> {
                // Each reads keys of the config it is given (`config[key]`), as `Access.get/2` does, which may run a
                // struct's `fetch/2`. Which keys differs by version, so one read of a key not known stands for them.
                args.singleOrNull()?.let { evaluation.remote(atom("Elixir.Access"), "get", listOf(it, Unknown)) }
                evaluation.unlessRaised(UnknownBinary)
            }
            else -> null
        }
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

                keyword("get/2", listOf(read.config, args[0]))?.let { read.settingsAsRead(args[0], it) }
            }
            else -> configImport(read, args[0])
        }
    }

    /** [config] merged into [read]'s, returning the config before as code reads it, as `Process.put/2` does. */
    private fun configured(read: ConfigRead, config: OtpErlangObject): OtpErlangObject {
        writesRead("Config.config")
        val before = read.configAsRead()

        read.configured(configMerge(read.config, config), config)

        return before
    }

    /**
     * Declines [what], which writes the read, in a unit stepped unordered
     * ([steppedUnordered][org.elixir_lang.expander.Run.steppedUnordered]): Elixir may run it any number of times, or
     * never, and what it writes to the read isn't joined.
     */
    private fun writesRead(what: String) {
        if (evaluation.run.steppedUnordered) decline("$what in a unit stepped unordered")
    }

    /**
     * `Config.__import__!/1`: the file at [path] read and evaluated in the same read, with its own `__DIR__`, an empty
     * binding and a fresh env, after `import_config!/3`'s check that the read hasn't imported it already, which declines
     * where only code that may not have run did. A raise in the file raises here; where the file's run stopped, Elixir
     * may have raised there, which is assumed not to happen. It gives `{value, binding}`, neither known: the value may
     * hold the file's ([ConfigRead.result]), and the binding may hold a struct of a module being compiled.
     */
    private fun configImport(read: ConfigRead, path: OtpErlangObject): OtpErlangObject {
        if (!isBinary(path)) functionClause("Elixir.Config", "__import__!", listOf(path))
        writesRead("Config.__import__!/1")

        val text = nameText("Config.__import__!/1", path)

        readError(path, text)?.let { reason ->
            evaluation.raise("Elixir.File.Error", "reason" to reason, "path" to path, "action" to binary("read file"))
        }
        if (text in read.imports) {
            if (text in read.maybeImports) decline("Config.__import__!/1 of %s, which code that may not have run read", path)
            argumentError()
        }

        read.imports.add(0, text)

        val expansion = evaluation.importing(read) { evaluation.run.files.imported(text, read) }
            ?: decline("Config.__import__!/1 of %s, which can't be read", path)
        // The file runs in a run of its own, so code it doesn't follow ran before the importer's goes on.
        if (expansion.unfollowedRan) evaluation.run.unfollowedRanBefore()
        // Elixir's later calls get the modules the file defines, which the importing run doesn't take in. Past a raise
        // from the file, no later call runs: the raise ends the read, or declines where a `try` may catch it.
        if (expansion.top !is Expansion.Error && expansion.modules.isNotEmpty()) {
            decline("Config.__import__!/1 of %s, which defines a module", path)
        }

        when (val top = expansion.top) {
            is Expansion.Error -> {
                val exception = Expansion.Error.exception(top.kind, level)
                    ?: decline("Config.__import__!/1 of %s, whose raise isn't known", path)

                evaluation.mayRaise()
                evaluation.raiseFieldsUnknown(exception)
            }
            is Expansion.Expanded -> {}
            else -> {
                read.unitDeclined()
                evaluation.assumeNoRaise()
            }
        }

        return tuple(unknownOf(listOf(read.result)), ModuleUnknown)
    }

    /**
     * `Mix.Config.__import__!/2`: [path] expanded against [dir], and where it holds a wildcard, each file it matches,
     * each imported as `Config.__import__!/1` imports it.
     */
    private fun mixConfigImport(path: OtpErlangObject, dir: OtpErlangObject): OtpErlangObject {
        val read = evaluation.run.config ?: decline("Mix.Config.__import__!/2 outside a config read")
        val expanded = evaluation.remote(atom("Elixir.Path"), "expand", listOf(path, dir))
        val text = nameText("Mix.Config.__import__!/2", expanded)
        val paths =
            if (text.any { it in ConfigFiles.WILDCARD }) {
                properElements(seen(evaluation.remote(atom("Elixir.Path"), "wildcard", listOf(expanded))))
                    ?: decline("Mix.Config.__import__!/2 of the wildcard %s", expanded)
            } else {
                listOf(expanded)
            }

        paths.forEach { configImport(read, it) }

        return atom("ok")
    }

    /**
     * `Code.can_await_module_compilation?/0`, which is true only in a process Elixir's parallel compiler spawned: an `.ex`
     * file's, not a config read's. Where neither is known, as for an `.exs` file, `null` runs `Code`'s clause.
     */
    private fun canAwaitModuleCompilation(): OtpErlangObject? =
        when {
            evaluation.run.config != null -> bool(false)
            evaluation.run.file()?.endsWith(".ex") == true -> bool(true)
            else -> null
        }

    /**
     * `file:read_file/1` (`file.erl:1075-1076` at OTP-29.0.6): `{ok, Bytes}` of the file [name] names, read through the
     * run's files ([fileToRead]), else the error the OS gives. A name [fileName] can't take is `badarg`.
     */
    private fun readFile(name: OtpErlangObject): OtpErlangObject {
        val path = fileToRead(name, fileName(name) ?: return fileError("badarg")) { return fileError(it) }
        val bytes = evaluation.run.files.read(path) ?: decline("file:read_file of %s isn't known", name)

        return tuple(atom("ok"), OtpErlangBinary(bytes))
    }

    /**
     * Where the OS takes [name], whose file name is [text] ([locatedFile]), to a file `file:read_file/1` reads, else
     * [error] of the reason it gives none, by what is there ([entryAt]).
     */
    private inline fun fileToRead(name: OtpErlangObject, text: String, error: (String) -> Nothing): String {
        val path = locatedFile(name, text, error) { error(notFound(it)) }

        return when (entryAt(path)) {
            FileEntry.File -> path
            is FileEntry.Directory -> error("eisdir")
            FileEntry.Missing, FileEntry.Dangling -> error(notFound(path))
        }
    }

    /**
     * `file:read_file/2` (`file.erl:1105-1117` at OTP-29.0.6): the first `{error, Reason}` among [options], as
     * `check_args/1` gives it, else [readFile], whether they hold `raw` or not, as both read the same file. Options
     * that aren't a list have no clause, nor has `check_args/1` the tail of a list that isn't proper.
     */
    private fun readFile(name: OtpErlangObject, options: OtpErlangObject): OtpErlangObject {
        val (elements, tail) = cells(seen(options)) ?: functionClause("file", "read_file", listOf(name, options))

        for (element in elements) {
            val option = seen(element)
            if (isUnknown(option)) decline("file:read_file/2 of an option that isn't known")

            val tag = pairKey(option)?.let(::seen) ?: continue
            if (isUnknown(tag)) decline("file:read_file/2 of an option that isn't known")
            if (tag == atom("error")) return option
        }

        tail?.let { functionClause("file", "check_args", listOf(it)) }

        return readFile(name)
    }

    /**
     * The reason `File.read/1` gives no bytes for the binary [path], whose text is [text] ([nameText]), as `Config` and
     * `Code` read a file they load ([fileToRead]), `null` where it gives them.
     */
    fun readError(path: OtpErlangObject, text: String): OtpErlangObject? {
        fileToRead(path, text) { return atom(it) }

        return null
    }

    /** `{error, Reason}` of [reason]. */
    private fun fileError(reason: String): OtpErlangObject = tuple(atom("error"), atom(reason))

    /**
     * `file:file_name/1` (`file.erl:2975-2994` at OTP-29.0.6): a binary's bytes, else a list's characters, a nested
     * list's and an atom's in their place, as [flat] walks them, `null` (`badarg`) where a part is anything else. A
     * character past ASCII declines, as `file:native_name_encoding/0` decides what it names; so do bytes that aren't
     * UTF-8.
     */
    private fun fileName(term: OtpErlangObject): String? {
        if (isBinary(term)) return nameText("a file name", term)

        val flat = flat(term)
        val characters = flat.takeIf { it.stuck == null }?.let { characters(it.elements) } ?: return null
        if (!characters.all(::isAscii)) decline("a file name of %s, past ASCII", term)

        return String(characters, 0, characters.size)
    }

    /**
     * Where the OS takes the file name [path], which [name] gave: made absolute as Elixir makes it (`Run.absolute`),
     * then resolved through the run's files ([Filename.resolved]); [stuck] of the prefix past which a `.` or `..`
     * follows a name that isn't a directory. An empty name is [error] `enoent` and one holding a NUL `badarg`, as
     * `prim_file` answers them, but a binary on Windows, whose NULs its path check reads, which declines. A name ending
     * in a separator takes only a directory, as one ending in `/.` does, but on Windows, where `prim_file` takes the
     * separator off.
     */
    private inline fun locatedFile(name: OtpErlangObject, path: String, error: (String) -> Nothing, stuck: (String) -> Nothing): String {
        if (path.isEmpty()) error("enoent")
        if ('\u0000' in path) {
            if (binaryBytes(name) != null && fileNameOs() == OS.Windows) decline("the file name %s, which holds a NUL, on Windows", name)

            error("badarg")
        }

        val os = fileNameOs()
        val absolute =
            evaluation.run.absolute(path, os) ?: decline("the file name %s, whose absolute path isn't known", name)
        val directory = os != OS.Windows && Filename.isSeparator(absolute.last().code, os) && Filename.split(absolute, os).size > 1

        return when (val resolved = Filename.resolved(if (directory) "$absolute." else absolute, os) { entryAt(it).physical }) {
            is Filename.Resolved.At -> resolved.path
            is Filename.Resolved.NotADirectory -> stuck(resolved.prefix)
            Filename.Resolved.Unread -> decline("the file name %s, a part of which the OS may take as other than its name", name)
        }
    }

    /**
     * The reason the OS gives for no file at [path], which isn't a directory: `enoent` where the nearest of it and the
     * names above it that is anything is a directory, `enotdir` where it is a file. Windows, whose answer isn't read,
     * declines that.
     */
    private fun notFound(path: String): String {
        val os = fileNameOs()
        var at = path

        while (true) {
            ProgressManager.checkCanceled()

            when (entryAt(at)) {
                is FileEntry.Directory -> return "enoent"
                FileEntry.File -> {
                    if (os == OS.Windows) decline("a file name past the file $at on Windows")

                    return "enotdir"
                }
                FileEntry.Missing, FileEntry.Dangling -> {}
            }

            val parent = Filename.dirname(at, os)
            if (parent == at) return "enoent"
            at = parent
        }
    }

    /**
     * `filelib:wildcard/1,2` (`filelib.erl:159-172` at OTP-29.0.6), as [Filelib] ports it, listed through the run's
     * files as the module given lists: `file`, the default, every name, and `Path.Wildcard` those not starting with
     * `.`, and before [PATH_WILDCARD_READS_FILE_INFO] raising `undef` at a `..` after a wildcard. A relative pattern is
     * listed from the working directory given, or `.`, which the OS resolves from the run's
     * ([Run.absolute][org.elixir_lang.expander.Run.absolute]). A pattern that isn't a list, or a second argument that
     * is neither a list nor an atom, has no clause; `{badpattern, Reason}` is raised as `filelib` raises it. Another
     * module, an empty pattern, a pattern past ASCII, or a relative one's working directory past ASCII declines, as
     * does a listed name past ASCII where a part counts its characters or the name is given back ([nativeName]).
     */
    private fun wildcard(args: List<OtpErlangObject>): OtpErlangObject {
        val pattern = seen(args[0])
        val second = args.getOrNull(1)?.let(::seen)

        if (!isList(pattern) || second != null && !isList(second) && second !is OtpErlangAtom) {
            functionClause("filelib", "wildcard", args)
        }

        val pathWildcard = second == atom("Elixir.Path.Wildcard")
        if (second is OtpErlangAtom && second != atom("file") && !pathWildcard) {
            decline("filelib:wildcard/2 listing through %s", second)
        }
        val os = fileNameOs()
        val text = charlistText(pattern)?.takeIf(::isAscii)
            ?: decline("filelib:wildcard of %s, which isn't characters of ASCII", pattern)
        val cwd = {
            if (second == null || second is OtpErlangAtom) "." else {
                charlistText(second)?.takeIf(::isAscii) ?: decline("filelib:wildcard from %s, which isn't characters of ASCII", second)
            }
        }
        val filelib = Filelib(
            os,
            absolute = { evaluation.run.absolute(it, os) },
            list = { path -> listingAt(path)?.let { entries -> if (pathWildcard) entries.filter { !it.name.startsWith(".") } else entries } },
            entry = ::entryAt,
            readFileInfo = { path ->
                if (pathWildcard && !PATH_WILDCARD_READS_FILE_INFO.isSufficient(level)) {
                    evaluation.error(atom("undef"), frame("Elixir.Path.Wildcard", "read_file_info", list(listOf(nativeName(path)))))
                }
            },
        )
        val paths = try {
            filelib.wildcard(text, cwd)
        } catch (badPattern: Filelib.BadPattern) {
            evaluation.error(tuple(atom("badpattern"), atom(badPattern.reason)))
        } catch (unanswered: Filelib.Unanswered) {
            decline(unanswered.reason)
        }

        return list(paths.map(::nativeName))
    }

    /** [path], a name `filelib` gives back, as its charlist; declining past ASCII, as the native name encoding isn't read. */
    private fun nativeName(path: String): OtpErlangObject =
        if (isAscii(path)) charlist(path) else decline("filelib:wildcard giving %s, past ASCII", binary(path))

    /**
     * The names in the directory at [path] in the run's files, `null` where nothing or a file is there; declining where
     * that isn't known.
     */
    private fun listingAt(path: String): List<DirectoryListing.Entry>? =
        ((evaluation.run.files.listing(path) ?: decline("the listing of $path isn't known")) as? DirectoryListing.Names)?.entries

    /** The OS a file name of the run is taken by, declining where it isn't known. */
    private fun fileNameOs(): OS = evaluation.run.os ?: decline("a file name of a run whose OS isn't known")

    /** What is at [path] in the run's files, declining where that isn't known. */
    private fun entryAt(path: String): FileEntry = evaluation.run.files.entry(path) ?: decline("what is at $path isn't known")

    /**
     * `File.exists?/1,2`: whether a file or directory is where the OS takes [path] ([locatedFile]), read through the
     * run's files.
     */
    private fun fileExists(path: OtpErlangObject): OtpErlangObject {
        val located = locatedFile(path, nameText("File.exists?/1", path), { return bool(false) }) { return bool(false) }

        return bool(
            when (entryAt(located)) {
                FileEntry.Missing, FileEntry.Dangling -> false
                FileEntry.File, is FileEntry.Directory -> true
            },
        )
    }

    /** `Config.__merge__/2`: [config2]'s apps merged into [config1]'s, each app's keys merged by `deep_merge/3`. */
    private fun configMerge(config1: OtpErlangObject, config2: OtpErlangObject): OtpErlangObject {
        if (!isList(config1) || !isList(config2)) functionClause("Elixir.Config", "__merge__", listOf(config1, config2))

        val merged = Pairs<OtpErlangObject>()

        return keywordMerge(config1, config2) { app1, app2 ->
            keywordMerge(app1, app2) { value1, value2 -> deepMerge(value1, value2, merged) }
        }
    }

    /**
     * `Config.deep_merge/3`: two keyword lists merged, keys by key, else the later value, or a value not known built from
     * both where either isn't known and both may be keyword lists; each pair of parts merged once, in [merged].
     */
    private fun deepMerge(value1: OtpErlangObject, value2: OtpErlangObject, merged: Pairs<OtpErlangObject>): OtpErlangObject =
        merged[value1, value2] ?: when {
            !mayBeKeyword(value1) || !mayBeKeyword(value2) -> value2
            isUnknown(value1) || isUnknown(value2) -> unknownOf(listOf(value1, value2))
            isKeyword(value1) && isKeyword(value2) ->
                keywordMerge(value1, value2) { part1, part2 -> deepMerge(part1, part2, merged) }
                    .also { merged[value1, value2] = it }
            else -> value2
        }

    /**
     * Whether [term] is or may be a keyword list, without declining: an unknown other than a binary, or a proper list
     * whose elements each are or may be `{atom, _}` pairs.
     */
    private fun mayBeKeyword(term: OtpErlangObject): Boolean {
        return term is UnknownValue || properElements(term)?.all { element ->
            element is UnknownValue || pairKey(element).let { it is OtpErlangAtom || it is UnknownValue }
        } == true
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
        if (isUnknown(keywords1) || isUnknown(keywords2)) return unknownOf(listOf(keywords1, keywords2))
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
        val directory = evaluation.run.workingDirectory() ?: decline("file:get_cwd/0 where the compile directory isn't known")

        return tuple(atom("ok"), charlist(directory))
    }

    /**
     * `filename:dirname/1`, `basename/1,2`, `split/1`, `extension/1` and `rootname/1,2`, by the syntax of the OS the
     * run's SDK runs as. `extension/1` and `rootname/1,2` also walk a list's characters, as `flatten/1` gives them; the
     * others decline on a list or atom name.
     */
    private fun filename(key: String, args: List<OtpErlangObject>): OtpErlangObject? {
        fun name() = nameBytes("filename:$key", args[0])

        val native: (OS) -> OtpErlangObject = when (key) {
            "extension/1" -> { os -> extension(args[0], os) }
            "rootname/1" -> { os -> rootname(args[0], os) }
            "rootname/2" -> { os -> rootname(args[0], args[1], os) }
            "dirname/1" -> { os -> OtpErlangBinary(Filename.dirname(name(), os)) }
            "basename/1" -> { os -> OtpErlangBinary(Filename.basename(name(), os)) }
            "basename/2" -> { os -> OtpErlangBinary(Filename.basename(name(), extensionBytes(args[1]), os)) }
            "split/1" -> { os -> list(Filename.split(name(), os).map(::OtpErlangBinary)) }
            else -> return null
        }

        return native(evaluation.run.os ?: decline("filename:$key of a run whose OS isn't known"))
    }

    /** `filename:extension/1` of [name]: a binary's bytes, else the characters [flattened] gives. */
    private fun extension(name: OtpErlangObject, os: OS): OtpErlangObject {
        binaryBytes(name)?.let { bytes ->
            val start = Filename.extensionStart(unsigned(bytes), os)

            return OtpErlangBinary(if (start < 0) ByteArray(0) else bytes.copyOfRange(start, bytes.size))
        }

        val elements = flattened(name)
        val result = { start: Int -> if (start < 0) EMPTY else list(elements.subList(start, elements.size)) }
        val characters = characters(elements) ?: noClause(elements) { rest, before ->
            val walked = result(Filename.extensionStart(before, os))

            functionClause("filename", "extension", listOf(rest, walked, osFamily(os)))
        }

        return result(Filename.extensionStart(characters, os))
    }

    /**
     * `filename:rootname/1` of [name]: a binary's bytes, as `binary_to_list/1` gives them, else the characters
     * [flattened] gives.
     */
    private fun rootname(name: OtpErlangObject, os: OS): OtpErlangObject {
        binaryBytes(name)?.let { return OtpErlangBinary(bytes(Filename.rootname(unsigned(it), os).first)) }

        val elements = flattened(name)
        val characters = characters(elements) ?: noClause(elements) { rest, before ->
            val (root, extension) = Filename.rootname(before, os)
            val walked = listOf(charactersList(root.reversedArray()), charactersList(extension.reversedArray()))

            functionClause("filename", "rootname", listOf(rest) + walked + osFamily(os))
        }

        return charactersList(Filename.rootname(characters, os).first)
    }

    /**
     * `filename:rootname/2`: of binaries, by their bytes; where one is a binary, the other is one by
     * [filenameStringToBinary]; else of the characters [flattened] gives each. A rest of [name] holding something other
     * than a character is never the extension where it holds none, and declines where both do.
     */
    private fun rootname(name: OtpErlangObject, extension: OtpErlangObject, os: OS): OtpErlangObject {
        val nameBytes = binaryBytes(name)
        val extensionBytes = binaryBytes(extension)

        if (nameBytes != null || extensionBytes != null) {
            val root = Filename.rootname(
                unsigned(nameBytes ?: filenameStringToBinary(name)),
                unsigned(extensionBytes ?: filenameStringToBinary(extension)),
                os,
            )

            return OtpErlangBinary(bytes(root))
        }

        val elements = flattened(name)
        val extensionElements = flattened(extension)
        val extensionCharacters = characters(extensionElements)
        val characters = characters(elements) ?: noClause(elements) { rest, before ->
            if (extensionCharacters == null) decline("filename:rootname/2 of a name and an extension that hold other than characters")

            val walked = charactersList(before.reversedArray())

            functionClause("filename", "rootname2", listOf(rest, list(extensionElements), walked, osFamily(os)))
        }

        return charactersList(extensionCharacters?.let { Filename.rootname(characters, it, os) } ?: characters)
    }

    /** What [flat] walks: the [elements] it gives, and the part it [stuck] at, `null` where it walked the whole term. */
    private class Flat(val elements: List<OtpErlangObject>, val stuck: OtpErlangObject?)

    /**
     * `filename:flatten/1`'s walk, `do_flatten/2` (`filename.erl:1093-1106` at OTP-29.0.6), of a term that isn't a
     * binary: a list's elements in order, a nested list's and an atom's characters in their place, and an atom tail's
     * after them. It walks from the end, as `do_flatten/2` flattens a tail first, and stops at a part that is neither a
     * list nor an atom, with the elements after it. Past [COPY_NODES] elements visited it declines, as a nested list
     * may be shared many times over.
     */
    private fun flat(term: OtpErlangObject): Flat {
        val reversed = mutableListOf<OtpErlangObject>()
        var visited = 0

        fun visit() {
            if (++visited > COPY_NODES) decline("a file name of over $COPY_NODES elements")
        }

        fun walk(term: OtpErlangObject): OtpErlangObject? {
            ProgressManager.checkCanceled()

            val known = seen(term)

            if (known is OtpErlangAtom) {
                for (character in codePoints(known.atomValue()).asReversed()) {
                    visit()
                    reversed.add(character)
                }

                return null
            }

            val (elements, tail) = cells(known) ?: return term

            tail?.let(::walk)?.let { return it }

            for (element in elements.asReversed()) {
                visit()

                val head = seen(element)

                if (head is OtpErlangAtom || cells(head) != null) walk(head)?.let { return it } else reversed.add(head)
            }

            return null
        }

        val stuck = walk(term)

        return Flat(reversed.asReversed(), stuck)
    }

    /** `filename:flatten/1` of a term that isn't a binary ([flat]); a part that is neither a list nor an atom has no clause. */
    private fun flattened(term: OtpErlangObject): List<OtpErlangObject> {
        val flat = flat(term)

        flat.stuck?.let { functionClause("filename", "do_flatten", listOf(it, list(flat.elements))) }

        return flat.elements
    }

    /**
     * The characters [elements] are, where each is an integer, which `filename`'s walks compare with `/`, `\\` and `.`;
     * `null` where one isn't. An integer too large for an `Int` declines.
     */
    private fun characters(elements: List<OtpErlangObject>): IntArray? {
        val characters = IntArray(elements.size)

        for ((at, element) in elements.withIndex()) {
            if (element !is OtpErlangLong) return null

            characters[at] = smallInt(element) ?: decline("filename of a name holding %s, which is past an Int", element)
        }

        return characters
    }

    /** [raise] given the rest of [elements] from the first that isn't a character, and the characters before it. */
    private inline fun noClause(elements: List<OtpErlangObject>, raise: (OtpErlangList, IntArray) -> Nothing): Nothing {
        val at = elements.indexOfFirst { it !is OtpErlangLong }

        raise(list(elements.subList(at, elements.size)), checkNotNull(characters(elements.subList(0, at))))
    }

    /**
     * The extension `filename:basename/2` is given: a binary, or a list, which `filename_string_to_binary/1`
     * encodes. Anything else declines.
     */
    private fun extensionBytes(term: OtpErlangObject): ByteArray =
        if (isList(term)) filenameStringToBinary(term) else nameBytes("filename:basename/2", term)

    /**
     * `filename_string_to_binary/1` (`filename.erl:1109-1115` at OTP-29.0.6): [term] [flattened], then encoded by
     * `file:native_name_encoding/0`. A character or a byte past ASCII declines, as that encoding isn't read; an integer
     * that is no character is `badarg` in every encoding, as is any other term.
     */
    private fun filenameStringToBinary(term: OtpErlangObject): ByteArray {
        val elements = flattened(term)
        val bytes = Built()
        var pastAscii = false

        for (element in elements) {
            when (element) {
                is OtpErlangLong -> {
                    val character = codePoint(element) ?: badargOf("filename", "filename_string_to_binary", listOf(term))

                    if (isAscii(character)) bytes.write(character) else pastAscii = true
                }
                is OtpErlangBitstr if element.pad_bits() == 0 -> {
                    val binary = element.binaryValue()

                    if (binary.all { isAscii(it.toInt()) }) bytes.write(binary) else pastAscii = true
                }
                else -> badargOf("unicode", "characters_to_binary", listOf(list(elements), atom("unicode"), atom("utf8")))
            }
        }

        if (pastAscii) decline("filename_string_to_binary/1 of %s, past ASCII, by a native name encoding that isn't read", term)

        return bytes.toByteArray()
    }

    /** `os:type/0`'s family, as `filename`'s walks of characters are given it. */
    private fun osFamily(os: OS): OtpErlangAtom =
        atom(Filename.osType(os)?.first ?: decline("filename of an OS os:type/0 doesn't name"))

    private fun unsigned(bytes: ByteArray): IntArray = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

    private fun bytes(characters: IntArray): ByteArray = ByteArray(characters.size) { characters[it].toByte() }

    private fun charactersList(characters: IntArray): OtpErlangList = list(characters.map { integer(it.toLong()) })

    /** The bytes of the binary [term], declining where it is not one, as a list or atom name is not. */
    private fun nameBytes(function: String, term: OtpErlangObject): ByteArray =
        binaryBytes(term) ?: decline("$function of %s, which is no binary", term)

    /**
     * The text of the binary [term], declining where it is not one or isn't UTF-8, as a name's native encoding isn't
     * read.
     */
    fun nameText(function: String, term: OtpErlangObject): String =
        decodeUtf8(nameBytes(function, term)) ?: decline("$function of %s, which isn't UTF-8", term)

    /** `binary:at/2`: the byte of the binary [subject] at the zero-based [position]. */
    private fun byteAt(subject: OtpErlangObject, position: OtpErlangObject): OtpErlangObject {
        val badarg = { badargOf("binary", "at", listOf(subject, position)) }
        val bytes = binaryBytes(subject) ?: badarg()
        val index = smallInt(seen(position)) ?: badarg()

        return integer((bytes.getOrNull(index) ?: badarg()).toLong() and 0xFF)
    }

    /** `binary:last/1`: the last byte of the binary [subject], which mustn't be empty. */
    private fun lastByte(subject: OtpErlangObject): OtpErlangObject {
        val bytes = binaryBytes(subject)?.takeIf { it.isNotEmpty() } ?: badargOf("binary", "last", listOf(subject))

        return integer(bytes.last().toLong() and 0xFF)
    }

    /**
     * `binary:copy/2`: [args]' bitstring of whole bytes, its count times. A count of 0, or an empty bitstring, gives
     * `<<>>`, and before [BINARY_COPY_CHECKS_WHOLE_BYTES_FIRST] a count of 0 does so before the bitstring is checked. A
     * count is a non-negative integer of at most 64 bits.
     */
    private fun binaryCopy(args: List<OtpErlangObject>): OtpErlangObject {
        val badarg = { badargOf("binary", "copy", args) }
        val subject = seen(args[0]) as? OtpErlangBitstr ?: badarg()
        val count = (seen(args[1]) as? OtpErlangLong)?.takeIf { it.isULong }?.bigIntegerValue() ?: badarg()

        if (count.signum() == 0 && !BINARY_COPY_CHECKS_WHOLE_BYTES_FIRST.isSufficient(level)) return binary("")
        if (!isBinary(subject)) badarg()

        val bytes = subject.binaryValue()
        val bits = count.multiply(BigInteger.valueOf(bytes.size * 8L))

        reserveBits(0, if (bits.bitLength() < Long.SIZE_BITS) bits.toLong() else Long.MAX_VALUE)

        val copied = ByteArray(bits.toInt() / 8)

        if (copied.isEmpty()) return OtpErlangBinary(copied)

        // Doubles what is filled, so a count of millions takes a few dozen copies.
        bytes.copyInto(copied)
        var filled = bytes.size

        while (filled < copied.size) {
            val n = minOf(filled, copied.size - filled)
            copied.copyInto(copied, filled, 0, n)
            filled += n
        }

        return OtpErlangBinary(copied)
    }

    /** The bytes of the binary [term], `null` where it isn't one. */
    private fun binaryBytes(term: OtpErlangObject): ByteArray? = (seen(term) as? OtpErlangBitstr)?.takeIf(::isBinary)?.binaryValue()

    /** The base `integer_to_binary/2` and `binary_to_integer/2` are given, 10 where they aren't, `null` past 2..36. */
    private fun radix(args: List<OtpErlangObject>): Int? =
        if (args.size == 1) 10 else smallInt(seen(args[1]))?.takeIf { it in 2..36 }

    /**
     * `ordsets:is_element/2`, by its clauses: the first element of [set] not below [element] decides, and a tail that
     * isn't `[]` has no clause.
     */
    private fun isElement(element: OtpErlangObject, set: OtpErlangObject): OtpErlangObject {
        val noClause = { rest: OtpErlangObject -> functionClause("ordsets", "is_element", listOf(element, rest)) }
        val (elements, tail) = cells(set) ?: noClause(set)

        for (each in elements) {
            ProgressManager.checkCanceled()

            val order = compare(element, each, level)

            if (order < 0) return FALSE
            if (order == 0) return TRUE
        }

        return if (tail == null) FALSE else noClause(tail)
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

        val int = smallInt(seen(arity))

        return bool(int != null && arities.admits(int))
    }

    /** The text of the binary [term], `null` where it isn't a binary of UTF-8. */
    fun utf8(term: OtpErlangObject): String? = binaryBytes(term)?.let(::decodeUtf8)

    /**
     * `unicode:characters_to_binary/1` (`unicode.erl:305-311`) and `characters_to_list/1` (`:289-295`): chardata as a
     * UTF-8 binary or a list of code points. The list is a copy, so past [COPY_NODES] code points it declines.
     */
    private fun unicode(key: String, args: List<OtpErlangObject>): OtpErlangObject? =
        when (key) {
            "characters_to_binary/1" -> OtpErlangBinary(chardataBytes("characters_to_binary", args))
            "characters_to_list/1" -> {
                val text = String(chardataBytes("characters_to_list", args), Charsets.UTF_8)

                if (text.codePointCount(0, text.length) > COPY_NODES) {
                    decline("characters_to_list/1 of %s, past $COPY_NODES code points", args[0])
                }

                charlist(text)
            }
            else -> null
        }

    /**
     * The UTF-8 bytes of the chardata `unicode:`[function]`/1` is given in [args], [notChardata] (`badarg`) for a term
     * that isn't chardata, which OTP checks before it encodes any of the term, up to the first integer too large to be
     * small. Where a binary isn't UTF-8 or an integer isn't a code point, OTP gives `{error, ...}` or `{incomplete, ...}`,
     * which decline.
     */
    private fun chardataBytes(
        function: String,
        args: List<OtpErlangObject>,
        notChardata: () -> Nothing = { badargOf("unicode", function, args) },
    ): ByteArray {
        // One binary's bytes run on into the next's, so a character may be split between them.
        val bytes = Built()
        var codePoints = true
        val chardata = leaves(args[0], { bytes.write(it.binaryValue()) }) { integer ->
            if (small(integer) == null) decline("$function/1 of %s, which is no code point", integer)

            codePoint(integer)?.let { bytes.write(Character.toString(it).toByteArray()) } ?: run { codePoints = false }
        }

        if (!chardata) notChardata()
        if (!codePoints) decline("$function/1 of %s, which holds no code point", args[0])

        return bytes.toByteArray().takeIf { decodeUtf8(it) != null }
            ?: decline("$function/1 of %s, which isn't UTF-8", args[0])
    }

    /**
     * `logger:compare_levels/2` (`logger.erl:1297-1306`): `gt` where the first level is the more severe by [LOG_LEVELS],
     * and `badarg` where either term isn't a level, the same term twice included.
     */
    private fun compareLevels(args: List<OtpErlangObject>): OtpErlangObject {
        val (first, second) = args.map { LOG_LEVELS.indexOf((seen(it) as? OtpErlangAtom)?.atomValue()) }

        if (first < 0 || second < 0) badargOf("logger", "compare_levels", args)

        return atom(
            when {
                first == second -> "eq"
                first < second -> "gt"
                else -> "lt"
            },
        )
    }

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

        val bytes = binaryBytes(term)

        if (bytes != null) {
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
    private fun leaves(
        term: OtpErlangObject,
        binary: (OtpErlangBitstr) -> Unit,
        unknownBinary: (() -> Unit)? = null,
        integer: (OtpErlangLong) -> Unit,
    ): Boolean {
        var met = 0

        fun walk(term: OtpErlangObject): Boolean {
            ProgressManager.checkCanceled()

            if (term === UnknownBinary && unknownBinary != null) {
                unknownBinary()

                return true
            }

            val known = seen(term)

            if (known is OtpErlangBitstr) return (known.pad_bits() == 0).also { if (it) binary(known) }

            val (elements, tail) = cells(known) ?: return false

            for (element in elements) {
                if (++met > COPY_NODES) decline("iodata or chardata of over $COPY_NODES elements")

                if (element is OtpErlangLong) integer(element) else if (!walk(element)) return false
            }

            return tail == null || (tail === UnknownBinary || seen(tail) is OtpErlangBitstr) && walk(tail)
        }

        return walk(term)
    }

    /** `iolist_to_binary/1`: a binary whose bytes aren't known where a part of the iodata [term] is one. */
    private fun iolistToBinary(term: OtpErlangObject, badarg: () -> Nothing): OtpErlangObject {
        var unknown = false
        val bytes = iodataBytes(term, badarg) { unknown = true }

        return if (unknown) UnknownBinary else OtpErlangBinary(bytes)
    }

    /** The bytes of the iodata [term], as `iolist_to_binary/1` gives them; [badarg] where [term] isn't iodata. */
    private fun iodataBytes(term: OtpErlangObject, badarg: () -> Nothing, unknownBinary: (() -> Unit)? = null): ByteArray {
        val bytes = Built()
        val iodata = leaves(term, { bytes.write(it.binaryValue()) }, unknownBinary) { byte ->
            bytes.write(byte.bigIntegerValue().takeIf { it.signum() >= 0 && it.bitLength() <= 8 }?.toInt() ?: badarg())
        }

        if (!iodata) badarg()

        return bytes.toByteArray()
    }

    /**
     * `term_to_binary/1`: version 131, uncompressed. An atom whose characters are all Latin-1 is written in Latin-1
     * before [TERM_TO_BINARY_UTF8_ATOMS], and a map's pairs in the VM's order, declining where [inOrder] can't say it. A
     * fun, pid, port or reference, whose bytes name the node, declines, as does a bitstring not of whole bytes. Past
     * [COPY_NODES] terms it declines, as their parts may be shared many times over.
     */
    private fun termToBinary(term: OtpErlangObject): OtpErlangBinary {
        val bytes = Built()
        val latin1Atoms = !TERM_TO_BINARY_UTF8_ATOMS.isSufficient(level)
        var met = 0

        // [tag], then [n] in [width] bytes.
        fun tagged(tag: Int, n: Int, width: Int) {
            bytes.write(tag)
            for (shift in (width - 1) * 8 downTo 0 step 8) bytes.write(n ushr shift)
        }

        // [small]'s tag and a byte of [size] where it fits one, else [large]'s and [largeWidth] bytes.
        fun header(size: Int, small: Int, large: Int, largeWidth: Int) =
            if (size < 256) tagged(small, size, 1) else tagged(large, size, largeWidth)

        fun walk(term: OtpErlangObject) {
            ProgressManager.checkCanceled()

            if (++met > COPY_NODES) decline("term_to_binary/1 of over $COPY_NODES terms")

            when (val known = seen(term)) {
                is OtpErlangAtom -> {
                    val text = known.atomValue()
                    val latin1 = if (latin1Atoms) latin1(text) else null

                    if (latin1 != null) {
                        tagged(OtpExternal.atomTag, latin1.size, 2)
                        bytes.write(latin1)
                    } else {
                        val utf8 = text.toByteArray(Charsets.UTF_8)

                        header(utf8.size, OtpExternal.smallAtomUtf8Tag, OtpExternal.atomUtf8Tag, 2)
                        bytes.write(utf8)
                    }
                }
                is OtpErlangLong -> {
                    val n = known.bigIntegerValue()

                    when {
                        n.signum() >= 0 && n.bitLength() <= 8 -> tagged(OtpExternal.smallIntTag, n.toInt(), 1)
                        n.bitLength() <= 31 -> tagged(OtpExternal.intTag, n.toInt(), 4)
                        else -> {
                            // Little-endian, with no zero byte past the most significant.
                            val magnitude = n.abs().toByteArray().dropWhile { it == 0.toByte() }.reversed().toByteArray()

                            header(magnitude.size, OtpExternal.smallBigTag, OtpExternal.largeBigTag, 4)
                            bytes.write(if (n.signum() < 0) 1 else 0)
                            bytes.write(magnitude)
                        }
                    }
                }
                is OtpErlangDouble -> {
                    bytes.write(OtpExternal.newFloatTag)
                    bytes.write(ByteBuffer.allocate(8).putDouble(known.doubleValue()).array())
                }
                is OtpErlangBitstr -> {
                    if (known.pad_bits() != 0) decline("term_to_binary/1 of a bitstring not of whole bytes")

                    val binary = known.binaryValue()

                    tagged(OtpExternal.binTag, binary.size, 4)
                    bytes.write(binary)
                }
                is OtpErlangTuple -> {
                    header(known.arity(), OtpExternal.smallTupleTag, OtpExternal.largeTupleTag, 4)
                    known.elements().forEach(::walk)
                }
                is OtpErlangMap -> {
                    val pairs = inOrder(known, exact = true)

                    tagged(OtpExternal.mapTag, pairs.size, 4)
                    pairs.forEach { (key, value) ->
                        walk(key)
                        walk(value)
                    }
                }
                else -> {
                    val (elements, tail) = cells(known) ?: decline("term_to_binary/1 of %s, whose bytes name the node", known)
                    val string = elements.takeIf { tail == null && it.size <= MAX_STRING_EXT }?.let { list ->
                        ByteArray(list.size) { smallInt(seen(list[it]))?.takeIf { n -> n in 0..255 }?.toByte() ?: return@let null }
                    }

                    when {
                        elements.isEmpty() && tail == null -> bytes.write(OtpExternal.nilTag)
                        string != null -> {
                            tagged(OtpExternal.stringTag, string.size, 2)
                            bytes.write(string)
                        }
                        else -> {
                            tagged(OtpExternal.listTag, elements.size, 4)
                            elements.forEach(::walk)
                            if (tail == null) bytes.write(OtpExternal.nilTag) else walk(tail)
                        }
                    }
                }
            }
        }

        bytes.write(OtpExternal.versionTag)
        walk(term)

        return OtpErlangBinary(bytes.toByteArray())
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

/** `logger`'s levels, the more severe first, as `logger_config:level_to_int/1` orders them. */
private val LOG_LEVELS = listOf("none", "emergency", "alert", "critical", "error", "warning", "notice", "info", "debug", "all")

/** The longest list `term_to_binary/1` writes as a string, `STRING_EXT` (`external.c`'s `MAX_STRING_LEN`). */
private const val MAX_STRING_EXT = 0xFFFF

/** The node of a process or reference the evaluator makes. */
private const val LOCAL_NODE = "nonode@nohost"

/** The references made so far: Erlang's are unique on the node, so every run, a followed one included, counts on one. */
private val refsMade = AtomicLong()

/** The ids of a reference no evaluation has made before. */
private fun newRefIds(): IntArray = refsMade.incrementAndGet().let { intArrayOf(it.toInt(), (it ushr 32).toInt(), 0) }

/** A reference no evaluation has made before, as `make_ref/0` gives it. */
private fun newRef() = OtpErlangRef(LOCAL_NODE, newRefIds(), 0)

/**
 * An array `counters:new/2` made: its [kind], `atomics` or `write_concurrency`, and its [cells], each `null` where its
 * value isn't known.
 */
internal class CounterArray(val kind: String, val cells: Array<Long?>)

/** `self()`: the one process a macro body runs in. */
private val SELF = OtpErlangPid(LOCAL_NODE, 0, 0, 0)

/** The module of a `Range`. */
private val RANGE = atom("Elixir.Range")

/** A binary that stands for [UnknownBinary] in a type test. */
private val SOME_BINARY = OtpErlangBinary(ByteArray(0))

/** What a `receive ... after` does with its timeout once nothing in the mailbox matches ([receiveTimeout]). */
internal enum class ReceiveTimeout {
    /** `infinity`: it waits for a message. */
    NEVER,

    /** `0`: it runs out at once. */
    AT_ONCE,

    /** 1 to 4294967295 milliseconds: it runs out once they have passed. */
    LATER,

    /** Any other term: `receive` raises `timeout_value`. */
    BAD,
}

/** What a `receive ... after` does with [timeout] ([ReceiveTimeout]), or `null` where [timeout] isn't known. */
internal fun receiveTimeout(timeout: OtpErlangObject): ReceiveTimeout? =
    when {
        isUnknown(timeout) -> null
        timeout == INFINITY -> ReceiveTimeout.NEVER
        timeout !is OtpErlangLong -> ReceiveTimeout.BAD
        else -> timeout.bigIntegerValue().let {
            when {
                it.signum() < 0 || it > MAX_RECEIVE_TIMEOUT -> ReceiveTimeout.BAD
                it.signum() == 0 -> ReceiveTimeout.AT_ONCE
                else -> ReceiveTimeout.LATER
            }
        }
    }

/** The longest `receive ... after` timeout, in milliseconds. */
private val MAX_RECEIVE_TIMEOUT = BigInteger.valueOf(4294967295L)

private val INFINITY = atom("infinity")

private val GLOBAL = atom("global")
private val TRIM_ALL = atom("trim_all")

/**
 * The reference `binary:compile_pattern/1` gives the patterns of [bytes] as, under [tag]: only one it made is a compiled
 * pattern, as Erlang's is a magic reference that only it makes, so a tuple built by hand isn't.
 */
private class CompiledPattern(val tag: OtpErlangAtom, val bytes: List<ByteArray>, ids: IntArray) :
    OtpErlangRef(LOCAL_NODE, ids, 0) {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

/** Erlang's `MAX_ARG`. */
private const val MAX_ARITY = 255L

/** The one option of `sets:new/1` and `sets:from_list/2` that their first clause takes. */
private val VERSION_2 = tuple(atom("version"), integer(2))

/** The text `erlang:binary_to_integer/1,2` takes: a sign at most, then digits, which the base must hold. */
private val INTEGER_TEXT = Regex("[+-]?[0-9a-zA-Z]+")

/**
 * The text `erlang:binary_to_float/1` takes: digits on both sides of a point, then an exponent at most. The point is a
 * `.` or a `,`, on every OS (`sys_chars_to_double`).
 */
private val FLOAT_TEXT = Regex("[+-]?[0-9]+[.,][0-9]+([eE][+-]?[0-9]+)?")

/** The time units the VM's clocks name, the ones before OTP 19 included. */
private val TIME_UNITS = setOf(
    "native",
    "perf_counter",
    "second",
    "millisecond",
    "microsecond",
    "nanosecond",
    "seconds",
    "milli_seconds",
    "micro_seconds",
    "nano_seconds",
)

/** The modifiers `erlang:unique_integer/1` takes. */
private val UNIQUE_MODIFIERS = setOf("positive", "monotonic")

/** The node types `erlang:nodes/1` takes, alone or in a list. */
private val NODE_TYPES = setOf("visible", "hidden", "connected", "this", "known")

/** A unit the VM's clocks take: one of [TIME_UNITS], or a positive integer of parts per second. */
private fun isTimeUnit(term: OtpErlangObject): Boolean =
    isOneOf(term, TIME_UNITS) || (seen(term) as? OtpErlangLong)?.bigIntegerValue()?.signum() == 1

/** Whether [term] is an atom, one of [names]. */
private fun isOneOf(term: OtpErlangObject, names: Set<String>): Boolean = (seen(term) as? OtpErlangAtom)?.atomValue() in names

/** Whether [term] is a proper list of atoms each one of [names]. */
private fun isListOf(term: OtpErlangObject, names: Set<String>): Boolean = properElements(term)?.all { isOneOf(it, names) } == true

/** The small integers of a 64-bit VM. */
private val SMALL_INTEGERS = -(1L shl 59) until (1L shl 59)

/**
 * The arity `erlang:make_fun/3` makes a fun of from [arity] on [level]: a small integer, at most [MAX_ARITY] from
 * [MAKE_FUN_ARITY_CHECKED], or `null` where it raises `badarg`.
 */
internal fun makeFunArity(arity: BigInteger, level: ElixirLanguageLevel): Long? =
    arity.takeIf { it.bitLength() < Long.SIZE_BITS }?.toLong()
        ?.takeIf { it >= 0 && it in SMALL_INTEGERS && (it <= MAX_ARITY || !MAKE_FUN_ARITY_CHECKED.isSufficient(level)) }

/** The words in the VM's largest integer, `BIG_ARITY_MAX` on a 64-bit VM ([INTEGERS_UP_TO_4_MBIT]). */
private const val INTEGER_WORDS = (1 shl 16) - 1

/** Whether an integer of [bits] bits, its sign apart, is within the VM's largest on every OTP ([INTEGER_WORDS]). */
internal fun fitsEveryVmInteger(bits: Int) = bits <= INTEGER_WORDS * Long.SIZE_BITS

/** [INTEGER_WORDS] before [INTEGERS_UP_TO_4_MBIT]. */
private const val OLD_INTEGER_WORDS = (1 shl 19) - 1
