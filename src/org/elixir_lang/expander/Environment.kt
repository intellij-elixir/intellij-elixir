package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_VARIABLES_REFUSED_IN_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ENV_VERSIONED_VARS
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/** `__CALLER__`, which only a macro's body can read. */
internal fun expandCaller(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    (if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
        ?: if (state.caller) {
            Expansion.Expanded(state, env, NODE)
        } else {
            report(ErrorSite.CALLER_NOT_ALLOWED, node, env, run) { Expansion.Expanded(state, env, NODE) }
        }

/** `__ENV__`, the escaped env, which is a map's AST. */
internal fun expandEnv(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    (if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
        ?: if (env.context == Env.Context.MATCH) {
            Expansion.Error("env_not_allowed", node)
        } else {
            Expansion.Expanded(state, env, NODE)
        }

/**
 * `__ENV__.field`, with no arguments: the field of the escaped env, or, for a key it lacks, the call on the map, which
 * raises at run time.
 */
internal fun expandEnvField(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (environment, field) = (node.callee as ElixirAst.Call).arguments!!

    if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(environment, env)?.let { return it }

    val value = when (val name = (field as ElixirAst.Literal.Atom).name) {
        "__struct__" -> Term.Atom("Elixir.Macro.Env")
        "module" -> atom(env.module)
        // `Env` holds no file.
        "file" -> Term.Binary(null)
        "line" -> Term.Integer(BigInteger.valueOf(lineOf(environment.meta).toLong()))
        "function" -> env.function?.let { Term.Pair(Term.Atom(it.name), integer(it.arity)) } ?: NIL
        "context" -> Term.Atom(env.context.name.lowercase().takeUnless { it == "none" } ?: "nil")
        "aliases" -> Term.List(env.aliases.map { Term.Pair(Term.Atom(it.alias), Term.Atom(it.module)) })
        "requires" -> Term.List(env.requires.map(Term::Atom))
        "functions" -> imports(env.functions)
        "macros" -> imports(env.macros)
        "macro_aliases" -> Term.List(env.macroAliases.map { Term.Pair(Term.Atom(it.alias), macroAlias(it)) })
        "context_modules" -> Term.List(env.contextModules.map(Term::Atom))
        // A module body's are node state; a function's are `nil` and `[]`.
        "lexical_tracker" -> if (env.function == null) return Expansion.Unported(node) else NIL
        "tracers" -> if (env.function == null) return Expansion.Unported(node) else Term.List(emptyList())
        in BEFORE_VERSIONED_VARS ->
            if (MACRO_ENV_VERSIONED_VARS.isSufficient(run.level)) NODE else return Expansion.Unported(node)
        // `versioned_vars` is a map's AST, and any other name is the call on the map.
        else -> NODE
    }

    return Expansion.Expanded(state, env, value)
}

private fun atom(name: String?): Term = name?.let(Term::Atom) ?: NIL

private fun integer(value: Int): Term = Term.Integer(BigInteger.valueOf(value.toLong()))

private fun imports(imports: List<Env.Imports>): Term =
    Term.List(
        imports.map { (module, nameArities) ->
            val pairs = nameArities.map { Term.Pair(Term.Atom(it.name), integer(it.arity)) }

            Term.Pair(Term.Atom(module), Term.List(pairs))
        }
    )

private fun macroAlias(macroAlias: Env.MacroAlias): Term {
    val counter = when (val counter = macroAlias.counter) {
        is Env.Counter.InModule -> Term.Pair(Term.Atom(counter.module), Term.Integer(BigInteger.valueOf(counter.n)))
        is Env.Counter.Unique -> Term.Integer(BigInteger.valueOf(counter.n))
    }

    return Term.Pair(counter, Term.Atom(macroAlias.module))
}

/** The variables' fields of `__ENV__` before [MACRO_ENV_VERSIONED_VARS], which no `Env` holds. */
private val BEFORE_VERSIONED_VARS = setOf("vars", "current_vars", "unused_vars", "prematch_vars", "contextual_vars")
