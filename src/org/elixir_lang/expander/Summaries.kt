package org.elixir_lang.expander

import org.elixir_lang.declaration.MacroKey
import org.elixir_lang.lowering.ElixirAst

/** A macro whose expansion is modelled. */
internal fun interface Summary {
    /** What expanding [node], a call that dispatches as [dispatch], gives. */
    fun expand(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion

    /** A macro whose output is built, then expanded where the call was; `Macro.expand/2` takes the output itself. */
    abstract class Rewrite : Summary {
        /** The macro's output for [node], a call that dispatches as [dispatch]. */
        abstract fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Output

        final override fun expand(
            dispatch: Dispatch,
            node: ElixirAst.Call,
            state: ExState,
            env: Env,
            run: Run,
        ): Expansion =
            when (val output = output(dispatch, node, state, env, run)) {
                is Output.Built -> expandQuoted(node, dispatch.receiver, output.ast, state, env, run)
                is Output.Raised -> Expansion.Error(output.kind, node)
                is Output.Unported -> Expansion.Unported(output.at)
                is Output.Stopped -> output.expansion
            }
    }

    /** What a [Rewrite]'s macro gives. */
    sealed class Output {
        /** The macro's output, [ast]. */
        class Built(val ast: ElixirAst) : Output()

        /** The macro raised, an error of [kind]. */
        class Raised(val kind: String) : Output()

        /** An input the port can't follow, at [at]. */
        class Unported(val at: ElixirAst) : Output()

        /** The macro's own `Macro.expand/2` stopped where the port does, as [expansion]. */
        class Stopped(val expansion: Expansion) : Output()
    }
}

/** The summary registry: each modelled macro, by its [MacroKey]. */
internal object Summaries {
    fun of(dispatch: Dispatch): Summary? = MacroKey.of(dispatch.receiver, dispatch.name, dispatch.arity)?.let(::of)

    /** [key]'s summary, or `null` until its expansion is modelled. */
    fun of(key: MacroKey): Summary? =
        when (key) {
            MacroKey.VAR_BANG_1, MacroKey.VAR_BANG_2 -> VAR_BANG
            MacroKey.ALIAS_BANG -> ALIAS_BANG
            MacroKey.DEF_1, MacroKey.DEF_2, MacroKey.DEFP_1, MacroKey.DEFP_2, MacroKey.DEFMACRO_1, MacroKey.DEFMACRO_2,
            MacroKey.DEFMACROP_1, MacroKey.DEFMACROP_2 -> DEFINE
            MacroKey.DEFMODULE -> DEFMODULE
            MacroKey.AT -> ATTRIBUTE
            MacroKey.IF, MacroKey.UNLESS, MacroKey.AND_AND, MacroKey.OR_OR, MacroKey.NOT, MacroKey.AND, MacroKey.OR,
            MacroKey.PIPE, MacroKey.IN, MacroKey.CONCAT, MacroKey.TO_STRING, MacroKey.RAISE_1, MacroKey.RAISE_2,
            MacroKey.BINDING_0, MacroKey.BINDING_1, MacroKey.DESTRUCTURE, MacroKey.RANGE, MacroKey.STEP_RANGE,
            MacroKey.FULL_RANGE, MacroKey.DEFSTRUCT, MacroKey.DEFEXCEPTION, MacroKey.DEFGUARD,
            MacroKey.DEFGUARDP, MacroKey.DEFOVERRIDABLE, MacroKey.DEFDELEGATE, MacroKey.DEFPROTOCOL, MacroKey.DEFIMPL_2,
            MacroKey.DEFIMPL_3, MacroKey.USE_1, MacroKey.USE_2, MacroKey.SIGIL_C_UPPER, MacroKey.SIGIL_D,
            MacroKey.SIGIL_N, MacroKey.SIGIL_R_UPPER, MacroKey.SIGIL_S_UPPER, MacroKey.SIGIL_T, MacroKey.SIGIL_U,
            MacroKey.SIGIL_W_UPPER, MacroKey.SIGIL_C, MacroKey.SIGIL_R, MacroKey.SIGIL_S, MacroKey.SIGIL_W,
            MacroKey.PROTOCOL_DEF, MacroKey.UTILS_DEFGUARD -> null
        }
}

/**
 * A call of a macro, which [node] dispatches as [dispatch]: its summary's expansion, or [Expansion.Opaque] where the
 * macro has none. The observer is told of the dispatch only when it is modelled.
 */
internal fun macro(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
    summarised(dispatch, node, opaque = { it }) { summary ->
        run.observer.dispatched(node, dispatch)

        summary.expand(dispatch, node, state, env, run)
    }

/** [summarised] of [dispatch]'s summary, or [opaque] of the [Expansion.Opaque] a call [node] gives where it has none. */
internal inline fun <T> summarised(
    dispatch: Dispatch,
    node: ElixirAst.Call,
    opaque: (Expansion.Opaque) -> T,
    summarised: (Summary) -> T,
): T =
    when (val summary = Summaries.of(dispatch)) {
        null -> opaque(Expansion.Opaque(node, dispatch))
        else -> summarised(summary)
    }
