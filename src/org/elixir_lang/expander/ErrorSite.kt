package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * Each place Elixir reports an error through `elixir_errors:function_error/4`, which carries on inside a function of
 * a module being compiled, or `module_error/4`, which carries on inside a module, that the port has a counterpart
 * for. Sites that call `file_error/4` always raise, so they build their [Expansion.Error] directly.
 *
 * @property kind the error's reason as atom text
 * @property crash the exception Elixir's own code raises on what the helper returns, when it can't use it
 */
internal enum class ErrorSite(val kind: String, private val helper: Helper, private val crash: String? = null) {
    /** `elixir_expand`'s `__CALLER__` outside a macro's body. The node is kept. */
    CALLER_NOT_ALLOWED("caller_not_allowed", Helper.FUNCTION),

    /** `elixir_expand`'s `__STACKTRACE__` outside `rescue` or `catch`. The node is kept. */
    STACKTRACE_NOT_ALLOWED("stacktrace_not_allowed", Helper.FUNCTION),

    /** `elixir_expand`'s `^` whose argument isn't a variable. The `^` is kept, with the state from before it. */
    INVALID_ARG_FOR_PIN("invalid_arg_for_pin", Helper.FUNCTION),

    /** `elixir_expand`'s `^` outside a pattern. The `^` is kept. */
    PIN_OUTSIDE_OF_MATCH("pin_outside_of_match", Helper.FUNCTION),

    /** `elixir_expand`'s anonymous call of an atom. The call is kept, with its arguments expanded. */
    INVALID_FUNCTION_CALL("invalid_function_call", Helper.FUNCTION),

    /** `elixir_expand:expand_remote/8`'s call with parentheses on a map in a guard. The call is kept, without arguments. */
    PARENS_MAP_LOOKUP("parens_map_lookup", Helper.FUNCTION),

    /** `elixir_expand`'s `_` outside a pattern. The `_` is kept and takes a version as it would in a pattern. */
    UNBOUND_UNDERSCORE("unbound_underscore", Helper.FUNCTION),

    /** `elixir_expand`'s undefined variable outside a pattern, or in a size it can't read. The variable is kept. */
    UNDEFINED_VAR("undefined_var", Helper.FUNCTION),

    /** `elixir_expand`'s undefined variable under `^`. The variable is kept. */
    UNDEFINED_VAR_PIN("undefined_var_pin", Helper.FUNCTION),

    /** `elixir_bitstring:validate_expr/3`: a pattern segment that can't be matched. The segment is kept. */
    UNKNOWN_MATCH("unknown_match", Helper.FUNCTION),

    /** `elixir_bitstring:validate_spec_arg/6`: a unit that isn't an integer, which is kept. */
    BAD_UNIT_ARGUMENT("bad_unit_argument", Helper.FUNCTION),

    /** `elixir_bitstring:expand_each_spec/6`: a spec given twice with different values. The later one is kept. */
    BITTYPE_MISMATCH_SPEC("bittype_mismatch", Helper.FUNCTION),

    /** `elixir_bitstring:expand_each_spec/6`: a spec that is neither a name nor a call. It is dropped. */
    UNDEFINED_BITTYPE("undefined_bittype", Helper.FUNCTION),

    /** `elixir_bitstring:type/4`: a type spec the value's own type can't take. The spec's type is kept. */
    BITTYPE_MISMATCH_TYPE("bittype_mismatch", Helper.FUNCTION),

    /** `elixir_bitstring:validate_size_required/6`: an unsized binary before a pattern's last segment. */
    UNSIZED_BINARY_REQUIRED("unsized_binary", Helper.FUNCTION),

    /** `elixir_bitstring:size_and_unit/5`: a size or unit on a literal bitstring. They are dropped. */
    BITTYPE_LITERAL_BITSTRING("bittype_literal_bitstring", Helper.FUNCTION),

    /** `elixir_bitstring:size_and_unit/5`: a size or unit on a literal string. They are dropped. */
    BITTYPE_LITERAL_STRING("bittype_literal_string", Helper.FUNCTION),

    /** `elixir_bitstring:build_spec/8`: a size or unit on a `utf` type. */
    BITTYPE_UTF("bittype_utf", Helper.FUNCTION),

    /** `elixir_bitstring:build_spec/8`: `signed` or `unsigned` on a `utf`, binary or bitstring type. */
    BITTYPE_SIGNED("bittype_signed", Helper.FUNCTION),

    /** `elixir_bitstring:build_spec/8`: a unit other than 1 on a bitstring. */
    BITTYPE_MISMATCH_UNIT("bittype_mismatch", Helper.FUNCTION),

    /** `elixir_bitstring:build_spec/8`: a float of a size other than 16, 32 or 64, whose `[]` `expand_specs` can't take. */
    BITTYPE_FLOAT_SIZE("bittype_float_size", Helper.FUNCTION, MATCH_ERROR),

    /** `elixir_bitstring:build_spec/8`: an integer or float with a unit and no size, whose `[]` `expand_specs` can't take. */
    BITTYPE_UNIT("bittype_unit", Helper.FUNCTION, MATCH_ERROR),

    /**
     * `elixir_bitstring:concat_or_prepend_bitstring/6`: a nested bitstring, before a pattern's last segment, whose last
     * part is an unsized binary.
     */
    UNSIZED_BINARY_NESTED("unsized_binary", Helper.FUNCTION),

    /**
     * `elixir_bitstring:concat_or_prepend_bitstring/6`: a nested bitstring typed `binary` whose bits aren't a multiple
     * of 8. Its parts are spliced in.
     */
    UNALIGNED_BINARY("unaligned_binary", Helper.FUNCTION),

    /**
     * `elixir_expand`'s local call in a pattern, a guard or a bitstring size. The arguments are expanded, and the call
     * isn't traced.
     */
    INVALID_LOCAL_INVOCATION("invalid_local_invocation", Helper.MODULE),

    /**
     * `elixir_def`'s bodiless head with an argument that isn't a variable, or from 1.20.0-rc.2 with a guard. Its
     * clauses aren't stored, and the head is.
     */
    INVALID_FUNCTION_HEAD("invalid_function_head", Helper.MODULE),

    /** A local call, once the module's body has run, to a function the module doesn't define. */
    UNDEFINED_FUNCTION("undefined_function", Helper.MODULE),

    /** A local call, once the module's body has run, to a macro the module defines after it. */
    INCORRECT_DISPATCH("incorrect_dispatch", Helper.MODULE),

    /** `elixir_map`'s struct pattern or update with a key the struct lacks, once per key. The struct is expanded. */
    UNKNOWN_KEY_FOR_STRUCT("unknown_key_for_struct", Helper.FUNCTION),

    /** `elixir_map`'s struct with a key that isn't an atom, once per key, before the struct is read. */
    INVALID_KEY_FOR_STRUCT("invalid_key_for_struct", Helper.FUNCTION);

    /** Whether Elixir at [level] carries on after this site's error in [env]. */
    fun outcome(level: ElixirLanguageLevel, env: Env): Outcome =
        when {
            !FUNCTION_ERRORS_CONTINUE.isSufficient(level) -> Outcome.Raises
            helper == Helper.FUNCTION && env.function == null -> Outcome.Raises
            // The module being expanded is always open, so `elixir_module:taint/1` succeeds.
            env.module == null -> Outcome.Raises
            crash != null -> Outcome.Crashes(crash)
            else -> Outcome.Continues
        }

    /**
     * `function_error/4` behaves as `module_error/4` inside a function, and `module_error/4` as `file_error/4` outside
     * a module.
     */
    private enum class Helper { FUNCTION, MODULE }
}

/** What follows an [ErrorSite]'s error. */
internal sealed interface Outcome {
    /** The error is reported and expansion carries on. */
    data object Continues : Outcome

    /** The error stops expansion. */
    data object Raises : Outcome

    /** The error is reported, and then Elixir's own code raises [exception] on what the helper returned. */
    data class Crashes(val exception: String) : Outcome
}

/** An error Elixir reported and carried on after. */
internal data class Reported(val kind: String, val at: ElixirAst)

/** Elixir's own code raising [exception] after [error] was reported, which ends the compile. */
internal data class Crash(val error: Expansion.Error, val exception: String)

/**
 * Reports [site]'s error at [at] as Elixir does in [env]: if it carries on, the error is logged in [run] and
 * [continuation] gives the expansion; otherwise the error ends it.
 */
internal inline fun report(site: ErrorSite, at: ElixirAst, env: Env, run: Run, continuation: () -> Expansion): Expansion =
    when (val outcome = site.outcome(run.level, env)) {
        Outcome.Continues -> {
            run.errors += Reported(site.kind, at)
            continuation()
        }
        Outcome.Raises -> Expansion.Error(site.kind, at)
        is Outcome.Crashes -> {
            run.errors += Reported(site.kind, at)
            Expansion.Error(site.kind, at).also { run.crash = Crash(it, outcome.exception) }
        }
    }

/** [report] for a site that carries on with what it had: `null` where Elixir carries on, else the expansion it ends with. */
internal fun reportOrEnd(site: ErrorSite, at: ElixirAst, env: Env, run: Run): Expansion? =
    report(site, at, env, run) { return null }

internal const val MATCH_ERROR = "Elixir.MatchError"
internal const val ARITHMETIC_ERROR = "Elixir.ArithmeticError"
