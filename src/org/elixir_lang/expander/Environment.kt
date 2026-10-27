package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.expander.interpreter.EMPTY
import org.elixir_lang.expander.interpreter.FALSE
import org.elixir_lang.expander.interpreter.ModuleUnknown
import org.elixir_lang.expander.interpreter.NIL
import org.elixir_lang.expander.interpreter.UnknownBinary
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.binary
import org.elixir_lang.expander.interpreter.integer
import org.elixir_lang.expander.interpreter.list
import org.elixir_lang.expander.interpreter.mapOf
import org.elixir_lang.expander.interpreter.termOf
import org.elixir_lang.expander.interpreter.tuple
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_VARIABLES_REFUSED_IN_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ENV_VERSIONED_VARS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** `__CALLER__`, which only a macro's body can read. */
internal fun expandCaller(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    (if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
        ?: if (state.caller) {
            Expansion.Expanded(state, env, NODE, run.ast { node })
        } else {
            report(ErrorSite.CALLER_NOT_ALLOWED, node, env, run) { Expansion.Expanded(state, env, NODE, run.ast { node }) }
        }

/** `__ENV__`, the escaped env, which is a map's AST. */
internal fun expandEnv(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    (if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
        ?: if (env.context == Env.Context.MATCH) {
            Expansion.Error("env_not_allowed", node)
        } else {
            // Can't build: Elixir gives the escaped env, a map's AST of fields `Env` doesn't hold.
            Expansion.Expanded(state, env, NODE, run.ast { node })
        }

/**
 * `__ENV__.field`, with no arguments: the field of the escaped env ([envField]), recorded as its value; or, for a key it
 * lacks, the call on the map, which raises `KeyError` at run time, so has no value to record.
 */
internal fun expandEnvField(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (environment, field) = (node.callee as ElixirAst.Call).arguments!!

    if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(environment, env)?.let { return it }

    val term = envField((field as ElixirAst.Literal.Atom).name, env, state, lineOf(environment.meta), true, run)
        ?: return Expansion.Expanded(state, env, NODE, run.ast { node })
    val value = termOf(term)

    if (holds(value)) {
        return recordingValue(node, env, run, Expansion.Expanded(state, env, value, run.ast { escaped(value, node, run) ?: node }))
    }

    recordDispatch(node, ModuleClauses.Dispatched.Macro(ElixirAst.Value(node.meta, term)), env, run)

    return Expansion.Expanded(state, env, NODE, run.ast { escaped(TermEscapeView, term, node, run) { null } ?: node })
}

/** Whether [term] holds the whole value it was copied from: no part of it is [Term.Unexpanded] or any other node. */
private fun holds(term: Term): Boolean =
    when (term) {
        is Term.Atom, is Term.Integer, is Term.Binary -> true
        is Term.List -> term.elements.all(::holds) && term.tail?.let(::holds) != false
        is Term.Pair -> holds(term.first) && holds(term.second)
        is Term.Tuple -> term.elements.all(::holds)
        else -> false
    }

/** The fields of the leg's `Macro.Env`, `__struct__` apart. */
internal fun envFields(level: ElixirLanguageLevel): List<String> =
    COMMON_FIELDS + if (MACRO_ENV_VERSIONED_VARS.isSufficient(level)) listOf("versioned_vars") else BEFORE_VERSIONED_VARS

/**
 * The [field] of the env at [line] where [env] and [state] hold, on [run]'s leg; `null` where its `Macro.Env` has no
 * such field. [escaped] is `__ENV__`, which `elixir_expand:escape_env_entries` builds; otherwise it is `__CALLER__`, the
 * env itself before [MACRO_ENV_VERSIONED_VARS] and `elixir_env:to_caller/1`'s from it. What the plugin doesn't hold is
 * [ModuleUnknown]: the compiler's process state, the lexical tracker and tracers but `__ENV__`'s in a function, and
 * before [MACRO_ENV_VERSIONED_VARS] `vars` and the unused variables, which `ExState` doesn't track.
 */
internal fun envField(field: String, env: Env, state: ExState, line: Int, escaped: Boolean, run: Run): OtpErlangObject? {
    if (field != "__struct__" && field !in envFields(run.level)) return null

    return when (field) {
        "__struct__" -> atom("Elixir.Macro.Env")
        "module" -> atom(env.module ?: "nil")
        "file" -> run.file?.let(::binary) ?: UnknownBinary
        "line" -> integer(line.toLong())
        "function" -> env.function?.let { tuple(atom(it.name), integer(it.arity.toLong())) } ?: NIL
        "context" -> when (env.context) {
            Env.Context.NONE -> NIL
            Env.Context.MATCH -> atom("match")
            Env.Context.GUARD -> atom("guard")
        }
        "aliases" -> list(env.aliases.map { tuple(atom(it.alias), atom(it.module)) })
        "requires" -> list(env.requires.map(::atom))
        "functions" -> imports(env.functions)
        "macros" -> imports(env.macros)
        "macro_aliases" -> list(env.macroAliases.map { tuple(atom(it.alias), tuple(macroAlias(it.counter), atom(it.module))) })
        "context_modules" -> list(env.contextModules.map(::atom))
        // `__ENV__` in a function holds none; elsewhere they are the compiler's.
        "lexical_tracker" -> if (escaped && env.function != null) NIL else ModuleUnknown
        "tracers" -> if (escaped && env.function != null) EMPTY else ModuleUnknown
        "versioned_vars" -> variables(state.read, run)
        "current_vars" -> tuple(
            variables(state.read, run),
            when (val write = state.write) {
                ExState.Write.NotWriting -> FALSE
                is ExState.Write.Writing -> variables(write.vars, run)
            },
        )
        "unused_vars" -> tuple(ModuleUnknown, integer(state.version.toLong()))
        "prematch_vars" -> when (val prematch = state.prematch) {
            is ExState.Prematch.OutsideMatch -> atom(prematch.mode.name.lowercase())
            is ExState.Prematch.InMatch -> tuple(variables(prematch.read, run), integer(prematch.version.toLong()))
            is ExState.Prematch.Bitsize ->
                tuple(variables(prematch.match.read, run), integer(prematch.match.version.toLong()))
        }
        // `elixir_def:env_for_expansion/3` and `elixir_clauses`' `catch` and `rescue` set them, each `catch` or `rescue`
        // adding its own.
        "contextual_vars" ->
            list(List(state.stacktraces) { atom("__STACKTRACE__") } + listOfNotNull(atom("__CALLER__").takeIf { state.caller }))
        "vars" -> ModuleUnknown
        else -> null
    }
}

/** [read] as Elixir holds it: each `{name, context}` to its version. */
private fun variables(read: Map<Variable, Int>, run: Run): OtpErlangObject =
    mapOf(
        read.map { (variable, version) ->
            val context = when (val context = variable.context) {
                is Variable.Context.Atom -> atom(context.text)
                is Variable.Context.Counter -> macroAlias(context.counter)
            }

            tuple(atom(variable.name), context) to integer(version.toLong())
        },
        run.level,
    )

private fun imports(imports: List<Env.Imports>): OtpErlangObject =
    list(
        imports.map { (module, nameArities) ->
            tuple(atom(module), list(nameArities.map { tuple(atom(it.name), integer(it.arity.toLong())) }))
        }
    )

/** A counter as Elixir holds it: `{module, n}`, or the unique integer. */
private fun macroAlias(counter: Env.Counter): OtpErlangObject =
    when (counter) {
        is Env.Counter.InModule -> tuple(atom(counter.module), integer(counter.n))
        is Env.Counter.Unique -> integer(counter.n)
    }

/** The fields of `Macro.Env` on every leg, `__struct__` apart. */
private val COMMON_FIELDS = listOf(
    "aliases", "context", "context_modules", "file", "function", "functions", "lexical_tracker", "line",
    "macro_aliases", "macros", "module", "requires", "tracers",
)

/** The variables' fields of `Macro.Env` before [MACRO_ENV_VERSIONED_VARS]. */
private val BEFORE_VERSIONED_VARS = listOf("contextual_vars", "current_vars", "prematch_vars", "unused_vars", "vars")
