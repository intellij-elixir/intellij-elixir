package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.APPLICATION_ENV_IN_BODY
import org.elixir_lang.language_level.ElixirLanguageFeature.LOCAL_MACRO_CHECKED_FOR_DEPRECATION
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED
import org.elixir_lang.lowering.ElixirAst

/** What a call that `check_deprecated/6` checks calls. */
internal enum class CalledKind { FUNCTION, MACRO }

/**
 * `elixir_dispatch:check_deprecated/6` of [call], which dispatches as the macro [dispatch]. Until
 * [LOCAL_MACRO_CHECKED_FOR_DEPRECATION] a local macro is expanded without the check.
 */
internal fun checkDeprecated(dispatch: Dispatch, call: ElixirAst, env: Env, run: Run) {
    if (dispatch.kind == Dispatch.Kind.LOCAL_MACRO && !LOCAL_MACRO_CHECKED_FOR_DEPRECATION.isSufficient(run.level)) return

    checkDeprecated(CalledKind.MACRO, call, dispatch.receiver, dispatch.name, dispatch.arity, env, run)
}

/**
 * `elixir_dispatch:check_deprecated/6` of [call], which calls [name]/[arity] of [receiver] as a [kind]: the warning it
 * reports, if any. Only a module the run compiled is loaded, so it is the only receiver whose `@deprecated` definitions
 * are read.
 */
internal fun checkDeprecated(
    kind: CalledKind,
    call: ElixirAst,
    receiver: String,
    name: String,
    arity: Int,
    env: Env,
    run: Run,
) {
    val level = run.level
    // A macro is checked wherever it is called from 1.12.2; a function only in a module body, where the runtime
    // pass can't see it.
    val checked = env.function == null || kind == CalledKind.MACRO && UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED.isSufficient(level)

    when {
        receiver == ERLANG || receiver == KERNEL -> Unit
        UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED.isSufficient(level) && receiver in ELIXIR_DEFINERS -> Unit
        APPLICATION_ENV_IN_BODY.isSufficient(level) && receiver == APPLICATION -> {
            val inBody = env.function == null && (env.module != null || kind == CalledKind.MACRO)

            if (inBody && name in APPLICATION_ENV) run.warnings += Warning.CompileEnv(call, name, arity)
        }
        checked ->
            run.deprecation(receiver, NameArity(name, arity))?.let {
                run.warnings += Warning.Deprecated(call, receiver, name, arity, it)
            }
    }
}

private const val APPLICATION = "Elixir.Application"

/** The modules that define `def` and `defmodule`, which have no deprecations to read. */
private val ELIXIR_DEFINERS = setOf("elixir_def", "elixir_module")

private val APPLICATION_ENV = setOf("get_env", "fetch_env", "fetch_env!")
