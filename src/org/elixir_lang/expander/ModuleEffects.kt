package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * The remote functions that change the module whose body calls them, keyed by their dispatch. The call's arguments are
 * its expanded values, beside the nodes they were expanded from.
 */
internal object ModuleEffects {
    /** What a call does to the module being compiled. */
    sealed interface Change {
        /** A change to the module's attributes. */
        class Attributes(val effect: Effect) : Change

        /** A change to its definition table, which the walk applies at the call's place: [apply] is given the call and the exports. */
        class Definitions(val apply: (Compiling, ElixirAst, Exports) -> Expansion?) : Change
    }

    private fun interface Row {
        /**
         * The change to [module] of a call with [args], expanded from [nodes], once its module and attribute are
         * known.
         */
        fun change(module: String, args: List<Term>, nodes: List<ElixirAst>): Change?
    }

    /**
     * What a call in [module]'s body that dispatches as [dispatch], with [args] expanded from [nodes], does to
     * [module]: `null` for nothing, as for a call of another module.
     */
    fun of(
        dispatch: Dispatch,
        args: List<Term>,
        nodes: List<ElixirAst>,
        module: String,
        level: ElixirLanguageLevel,
    ): Change? {
        val putArity = if (ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level)) 5 else 4
        val row = when (Triple(dispatch.receiver, dispatch.name, dispatch.arity)) {
            Triple(ELIXIR_MODULE, "put_attribute", 3), Triple(ELIXIR_MODULE, "__put_attribute__", putArity) -> WRITE
            Triple(ELIXIR_MODULE, "register_attribute", 3) -> REGISTER
            Triple(ELIXIR_MODULE, "delete_attribute", 2) -> DELETE
            Triple(KERNEL_TYPESPEC, "deftypespec", 6) -> TYPESPEC_WRITE
            Triple(ELIXIR_MODULE, "make_overridable", 2) -> MAKE_OVERRIDABLE
            else -> return null
        }

        return row.change(module, args, nodes)
    }

    /**
     * [change] where [target], the module a call is given, is [module]; `null` where it is another; [unknown] where it
     * isn't known.
     */
    private fun targeting(target: Term, module: String, unknown: Change, change: () -> Change?): Change? =
        when (target) {
            is Term.Atom -> if (target.name == module) change() else null
            else -> unknown
        }

    /** `Module`'s attribute functions take the module first and the attribute second (`Mod:1595`, `:1729`, `:1782`). */
    private fun attributeCall(effect: (name: String, args: List<Term>) -> Effect) =
        Row { module, args, _ ->
            targeting(args[0], module, UNKNOWN_ATTRIBUTE) { named(args[1]) { effect(it, args) } }
        }

    private fun named(key: Term, effect: (String) -> Effect): Change =
        if (key is Term.Atom) Change.Attributes(effect(key.name)) else UNKNOWN_ATTRIBUTE

    private val UNKNOWN_ATTRIBUTE = Change.Attributes(Effect.UnknownEffect)

    private val WRITE = attributeCall { name, args -> Effect.Write(name, args[2]) }

    private val REGISTER = attributeCall { name, args -> Effect.Register(name, accumulate(args[2])) }

    private val DELETE = attributeCall { name, _ -> Effect.Delete(name) }

    /** `deftypespec(kind, expr, line, file, module, pos)`, where `kind` is the attribute and `expr` the spec, escaped. */
    private val TYPESPEC_WRITE = Row { module, args, nodes ->
        targeting(args[4], module, UNKNOWN_ATTRIBUTE) {
            named(args[0]) { Effect.TypespecWrite(it, specHead(nodes[1])) }
        }
    }

    /**
     * The name and arity `Kernel.Typespec`'s `translate_spec` reads from [spec], the node building the escaped spec:
     * `name(args) :: return`, alone or before `when`, with an atom name other than `::` and a list of arguments or
     * none. `null` where Elixir raises for it, or it isn't known, as where an `unquote` builds the name or the
     * arguments.
     */
    private fun specHead(spec: ElixirAst): NameArity? {
        val typed = escapedCall(spec, "when")?.first() ?: spec
        val head = escapedCall(typed, "::")?.first() as? ElixirAst.Tuple ?: return null
        val (name, meta, arguments) = head.elements.takeIf { it.size == 3 } ?: return null

        if (name !is ElixirAst.Literal.Atom || name.name == "::" || meta !is ElixirAst.ListNode) return null

        return when (arguments) {
            is ElixirAst.ListNode -> NameArity(name.name, arguments.elements.size)
            is ElixirAst.Literal.Atom -> NameArity(name.name, 0)
            else -> null
        }
    }

    /** The two arguments of the call of [name] that [escaped] builds, or `null` where it builds anything else. */
    private fun escapedCall(escaped: ElixirAst, name: String): List<ElixirAst>? {
        val (callee, _, arguments) = (escaped as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 3 } ?: return null

        return (arguments as? ElixirAst.ListNode)?.elements
            ?.takeIf { (callee as? ElixirAst.Literal.Atom)?.name == name && it.size == 2 }
    }

    /** `Module.make_overridable(module, definitions)`: a call of another module is not followed. */
    private val MAKE_OVERRIDABLE = Row { module, args, _ ->
        val unknown = Change.Definitions { _, at, _ -> Expansion.Unported(at) }

        targeting(args[0], module, unknown) {
            Change.Definitions { compiling, at, exports -> makeAllOverridable(compiling, args[1], at, exports) }
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

                    return element.second.isTruthy()
                }
                is Term.Atom, is Term.Integer, is Term.Binary, is Term.List, is Term.Tuple, Term.NonTuple -> continue
                is Term.Node, Term.Unexpanded -> return null
            }
        }

        return if (options.tail == null) false else null
    }
}

internal const val ELIXIR_MODULE = "Elixir.Module"

internal const val KERNEL_TYPESPEC = "Elixir.Kernel.Typespec"
