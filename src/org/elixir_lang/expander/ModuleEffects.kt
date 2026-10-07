package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term

/**
 * The remote functions that change the attributes of the module whose body calls them, keyed by their dispatch. The
 * call's arguments are its expanded values.
 */
internal object ModuleEffects {
    private fun interface Row {
        /** The effect on [module] of a call with [args], once its module and attribute are known. */
        fun effect(module: String, args: List<Term>): Effect?
    }

    /**
     * What a call in [module]'s body that dispatches as [dispatch] does to [module]'s attributes: `null` for nothing,
     * as for a call of another module.
     */
    fun of(dispatch: Dispatch, args: List<Term>, module: String, level: ElixirLanguageLevel): Effect? {
        val putArity = if (ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level)) 5 else 4
        val row = when (Triple(dispatch.receiver, dispatch.name, dispatch.arity)) {
            Triple(ELIXIR_MODULE, "put_attribute", 3), Triple(ELIXIR_MODULE, "__put_attribute__", putArity) -> WRITE
            Triple(ELIXIR_MODULE, "register_attribute", 3) -> REGISTER
            Triple(ELIXIR_MODULE, "delete_attribute", 2) -> DELETE
            Triple(KERNEL_TYPESPEC, "deftypespec", 6) -> TYPESPEC_WRITE
            else -> return null
        }

        return row.effect(module, args)
    }

    /** `Module`'s attribute functions take the module first and the attribute second (`Mod:1595`, `:1729`, `:1782`). */
    private fun attributeCall(effect: (name: String, args: List<Term>) -> Effect) =
        Row { module, args ->
            when (val target = args[0]) {
                is Term.Atom -> if (target.name == module) named(args[1]) { effect(it, args) } else null
                else -> Effect.UnknownEffect
            }
        }

    private fun named(key: Term, effect: (String) -> Effect): Effect =
        if (key is Term.Atom) effect(key.name) else Effect.UnknownEffect

    private val WRITE = attributeCall { name, args -> Effect.Write(name, args[2]) }

    private val REGISTER = attributeCall { name, args -> Effect.Register(name, accumulate(args[2])) }

    private val DELETE = attributeCall { name, _ -> Effect.Delete(name) }

    /** `deftypespec(kind, expr, line, file, module, pos)`, where `kind` is the attribute. */
    private val TYPESPEC_WRITE = Row { module, args ->
        when (val target = args[4]) {
            is Term.Atom -> if (target.name == module) named(args[0]) { Effect.TypespecWrite(it) } else null
            else -> Effect.UnknownEffect
        }
    }

    /**
     * `Keyword.get(options, :accumulate)` taken as a boolean (`Mod:1790`): its first `accumulate` pair, or `null` where
     * an element before it, or the list itself, isn't known.
     */
    private fun accumulate(options: Term): Boolean? {
        if (options !is Term.List) return null

        for (element in options.elements) {
            when (element) {
                is Term.Pair -> {
                    when (val key = element.first) {
                        is Term.Atom -> if (key.name != "accumulate") continue
                        is Term.Node, Term.Unexpanded -> return null
                        else -> continue
                    }

                    return when (val value = element.second) {
                        is Term.Atom -> value.name != "nil" && value.name != "false"
                        is Term.Integer, is Term.Binary, is Term.List, is Term.Pair, Term.NonTuple -> true
                        is Term.Node, Term.Unexpanded -> null
                    }
                }
                is Term.Atom, is Term.Integer, is Term.Binary, is Term.List, Term.NonTuple -> continue
                is Term.Node, Term.Unexpanded -> return null
            }
        }

        return if (options.tail == null) false else null
    }
}

internal const val ELIXIR_MODULE = "Elixir.Module"

internal const val KERNEL_TYPESPEC = "Elixir.Kernel.Typespec"
