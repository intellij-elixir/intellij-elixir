package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_DELETE_DEFINITION
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * The remote functions that change the module whose body calls them, or another being compiled, keyed by receiver,
 * name and arity. The call's arguments are its expanded values, beside the nodes they were expanded from.
 */
internal object ModuleEffects {
    /** What a call does to the modules being compiled. */
    sealed interface Change {
        /** A change to the module's attributes. */
        class Attributes(val effect: Effect) : Change

        /**
         * A change to its definition table, which the walk applies at the call's place: [apply] is given the call, the
         * run, and whether the call is one Elixir surely runs.
         */
        class Definitions(val apply: (Compiling, ElixirAst, Run, Boolean) -> Expansion?) : Change
    }

    /**
     * A function that changes a module.
     *
     * @property unknownReceiver what a call of its name and arity does through a receiver that isn't known at expansion,
     *   which may be this function's
     * @property change the change to `module` of a call with `args`, expanded from `nodes`, once its receiver is known
     * @property target the index of the argument naming the module it changes
     */
    private class Row(
        val unknownReceiver: Change,
        val target: Int = 0,
        val change: (module: String, args: Arguments, nodes: List<ElixirAst>) -> Change?,
    )

    /** A call's arguments: [terms], and [values], what the evaluator gave for each where it did. */
    private class Arguments(val terms: List<Term>, val values: List<OtpErlangObject?>) {
        operator fun get(index: Int): Term = terms[index]
    }

    /**
     * What a call in [module]'s body of [receiver]'s [name] with [args], expanded from [nodes], does: `null` for
     * nothing, as for a call of a function that changes no module. [values] are what the evaluator gave for [args],
     * where it did. A receiver that isn't known at expansion may be any row's with the call's name and arity, so the
     * call does the most any of them does through one: a remover's stops.
     */
    fun of(
        receiver: Term,
        name: String,
        args: List<Term>,
        nodes: List<ElixirAst>,
        module: String,
        level: ElixirLanguageLevel,
        values: List<OtpErlangObject?> = emptyList(),
    ): Change? {
        val rows = rows(level)
        val key = (receiver as? Term.Atom)?.let { Triple(it.name, name, args.size) }
            ?: return rows.filterKeys { (_, rowName, arity) -> rowName == name && arity == args.size }.values
                .map(Row::unknownReceiver)
                .let { changes -> changes.firstOrNull { it is Change.Definitions } ?: changes.firstOrNull() }

        return rows[key]?.change(module, Arguments(args, values), nodes)
    }

    /**
     * The index of the argument naming the module [receiver]'s [name] of [arity] changes at [level], or `null` where it
     * has no row: where it is no function that changes a module.
     */
    fun target(receiver: String, name: String, arity: Int, level: ElixirLanguageLevel): Int? =
        rows(level)[Triple(receiver, name, arity)]?.target

    /** The module [target], a call's module argument, names: `null` where it isn't an atom ([Run.compilingNamed]). */
    private fun moduleNamed(target: Term?): String? = (target as? Term.Atom)?.name

    /**
     * [change] where [target], the module a call is given, is [module]; the change on another module where it is
     * another; [UNKNOWN_MODULE] where it isn't known.
     */
    private fun targeting(target: Term, module: String, change: () -> Change.Attributes): Change.Attributes =
        when (val name = moduleNamed(target)) {
            null -> UNKNOWN_MODULE
            module -> change()
            else -> Change.Attributes(Effect.OtherModule(name, change().effect))
        }

    /** `Module`'s attribute functions take the module first and the attribute second (`Mod:1595`, `:1729`, `:1782`). */
    private fun attributeCall(effect: (name: String, args: Arguments) -> Effect) =
        Row(UNKNOWN_MODULE) { module, args, _ ->
            targeting(args[0], module) { named(args[1]) { effect(it, args) } }
        }

    private fun named(key: Term, effect: (String) -> Effect): Change.Attributes =
        if (key is Term.Atom) Change.Attributes(effect(key.name)) else UNKNOWN_ATTRIBUTE

    private val UNKNOWN_ATTRIBUTE = Change.Attributes(Effect.UnknownEffect)

    private val UNKNOWN_MODULE = Change.Attributes(Effect.OtherModule(null, Effect.UnknownEffect))

    private val WRITE = attributeCall { name, args -> Effect.Write(name, args[2], args.values.getOrNull(2)) }

    private val REGISTER = attributeCall { name, args -> Effect.Register(name, accumulate(args[2])) }

    private val DELETE = attributeCall { name, _ -> Effect.Delete(name) }

    /** `deftypespec(kind, expr, line, file, module, pos)`, where `kind` is the attribute and `expr` the spec, escaped. */
    private val TYPESPEC_WRITE = Row(UNKNOWN_MODULE, TYPESPEC_MODULE) { module, args, nodes ->
        targeting(args[TYPESPEC_MODULE], module) {
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

    /**
     * The call stops the module there: what it takes out of the table isn't followed, so it may have written [target],
     * the module it is given, or any being compiled where that isn't known.
     */
    private fun stop(target: Term?) = Change.Definitions { _, at, run, _ ->
        mayHaveWritten(run.compilingNamed(moduleNamed(target)).filterNotNull())
        Expansion.Unported(at)
    }

    private val STOP = stop(null)

    /**
     * `Module.make_overridable(module, definitions)`. A call of another module stops, as that may be one being compiled,
     * which a module nested in it can name, and for one that isn't, Elixir raises (`Mod:1499`, `:1525`).
     */
    private val MAKE_OVERRIDABLE = Row(STOP) { module, args, _ ->
        if (moduleNamed(args[0]) == module) {
            Change.Definitions { compiling, at, run, ordered ->
                makeAllOverridable(compiling, args[1], at, run.exports, ordered)
            }
        } else {
            stop(args[0])
        }
    }

    /**
     * `Module.delete_definition(module, {name, arity})` (`Mod:1478–1483` at v1.20.4): the definition taken out of the
     * module's table where the call is the module's own, Elixir surely runs it there, and whether the table holds the
     * definition is known ([Compiling.defines]); otherwise it stops.
     */
    private val DELETE_DEFINITION = Row(STOP) { module, args, _ ->
        val definition = (args[1] as? Term.Pair)?.let(::definitionNamed)

        if (moduleNamed(args[0]) == module && definition != null) {
            Change.Definitions { compiling, at, run, ordered ->
                val defined = compiling.defines(definition, null)

                if (ordered && compiling.values.certainHere() && defined != null) {
                    if (defined) deleteDefinition(compiling, definition)
                    null
                } else {
                    stop(args[0]).apply(compiling, at, run, ordered)
                }
            }
        } else {
            stop(args[0])
        }
    }

    /** A remover this doesn't follow, which stops the module whatever its target. */
    private val STOPS = Row(STOP) { _, args, _ -> stop(args[0]) }

    /**
     * Every function that takes a definition out of the module's table: `elixir_def:take_definition/2` and its callers
     * in `Module` (`Mod:1482`, `:1504`). Elixir removes from its table through no other.
     */
    private val REMOVERS = mapOf(
        Triple(ELIXIR_MODULE, "make_overridable", 2) to MAKE_OVERRIDABLE,
        Triple(ELIXIR_MODULE, "delete_definition", 2) to DELETE_DEFINITION,
        Triple(ELIXIR_DEF, "take_definition", 2) to STOPS,
    )

    /**
     * Every row, keyed by receiver, name and arity, for each of whether attributes expand lazily, which gives
     * `Module.__put_attribute__/5` in place of `/4`, and whether `Module.delete_definition/2` exists.
     */
    private val ROWS = listOf(false, true).flatMap { lazily -> listOf(false, true).map { lazily to it } }
        .associateWith { (lazily, deletes) ->
            mapOf(
                Triple(ELIXIR_MODULE, "put_attribute", 3) to WRITE,
                Triple(ELIXIR_MODULE, "__put_attribute__", if (lazily) 5 else 4) to WRITE,
                Triple(ELIXIR_MODULE, "register_attribute", 3) to REGISTER,
                Triple(ELIXIR_MODULE, "delete_attribute", 2) to DELETE,
                Triple(KERNEL_TYPESPEC, "deftypespec", 6) to TYPESPEC_WRITE,
            ) + if (deletes) REMOVERS else REMOVERS - Triple(ELIXIR_MODULE, "delete_definition", 2)
        }

    private fun rows(level: ElixirLanguageLevel) =
        ROWS.getValue(ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level) to MODULE_DELETE_DEFINITION.isSufficient(level))

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

private const val ELIXIR_DEF = "elixir_def"

internal const val KERNEL_TYPESPEC = "Elixir.Kernel.Typespec"

/** The argument of `Kernel.Typespec.deftypespec/6` naming the module it writes. */
private const val TYPESPEC_MODULE = 4
