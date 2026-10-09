package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import java.math.BigInteger

/** The nodes Elixir builds, all at [at]'s position: a `quote`'s, or a macro call's for the macro's output. */
internal class Synthetic(private val at: Meta) {
    fun meta(keys: List<Meta.Key> = emptyList()) = Meta(at.origin, at.start, at.end, keys, built = true)

    fun atom(name: String) = ElixirAst.Literal.Atom(meta(), name)

    fun integer(value: BigInteger) = ElixirAst.Literal.Integer(meta(), value)

    fun list(vararg elements: ElixirAst) = list(elements.toList())

    fun list(elements: List<ElixirAst>) = ElixirAst.ListNode(meta(), elements)

    fun tuple(vararg elements: ElixirAst) = ElixirAst.Tuple(meta(), elements.toList())

    /** A tuple of [elements] with [keys]; only a tuple of other than two elements carries them, as `{:{}, Keys, _}`. */
    fun tuple(elements: List<ElixirAst>, keys: List<Meta.Key>) = ElixirAst.Tuple(meta(keys), elements)

    fun keywords(pairs: List<Pair<String, ElixirAst>>) = list(pairs.map { (key, value) -> tuple(atom(key), value) })

    /** A map as `elixir_quote:escape/3` gives it: its keys in term order, whatever order [entries] has. */
    fun escapedMap(entries: Iterable<Pair<String, ElixirAst>>) =
        call("%{}", entries.sortedWith { a, b -> compareCodePoints(a.first, b.first) }.map { (key, value) -> tuple(atom(key), value) })

    /** `{Key, Meta, elixir_quote}`, the variable the prelude binds a run-time option to, with the `quote`'s keys. */
    fun variable(key: String) =
        ElixirAst.Call(meta(at.keys), atom(key), null, ElixirAst.VariableContext.Atom("elixir_quote"))

    /** `{Name, Keys, Context}`. */
    fun variable(name: String, context: String, keys: List<Meta.Key> = emptyList()) =
        ElixirAst.Call(
            meta(keys),
            atom(name),
            null,
            if (context == "nil") ElixirAst.VariableContext.Nil else ElixirAst.VariableContext.Atom(context),
        )

    /** `{'=', Meta, [Left, Right]}`, with the `quote`'s keys. */
    fun match(left: ElixirAst, right: ElixirAst) = ElixirAst.Call(meta(at.keys), atom("="), listOf(left, right))

    /** `{Name, Keys, Args}`. */
    fun call(name: String, args: List<ElixirAst>, keys: List<Meta.Key> = emptyList()) =
        ElixirAst.Call(meta(keys), atom(name), args)

    /** `{{'.', Meta, [Module, Function]}, Meta, Args}`, with [source]'s keys. */
    fun remoteCall(source: Meta, module: String, function: String, args: List<ElixirAst>) =
        remoteCall(module, function, args, source.keys)

    /** `{{'.', Keys, [Module, Function]}, Keys, Args}`. */
    fun remoteCall(module: String, function: String, args: List<ElixirAst>, keys: List<Meta.Key> = emptyList()) =
        remoteCall(atom(module), function, args, keys)

    /** `{{'.', Keys, [Receiver, Function]}, Keys, Args}`. */
    fun remoteCall(receiver: ElixirAst, function: String, args: List<ElixirAst>, keys: List<Meta.Key> = emptyList()) =
        ElixirAst.Call(meta(keys), ElixirAst.Call(meta(keys), atom("."), listOf(receiver, atom(function))), args)

    /** `{'->', Keys, [Patterns, Body]}`. */
    fun arrow(patterns: List<ElixirAst>, body: ElixirAst, keys: List<Meta.Key> = emptyList()) =
        call("->", listOf(list(patterns), body), keys)

    /** `{'case', Keys, [Subject, [{do, Clauses}]]}`. */
    fun case(subject: ElixirAst, clauses: List<ElixirAst>, keys: List<Meta.Key> = emptyList()) =
        call("case", listOf(subject, keywords(listOf("do" to list(clauses)))), keys)

    /** [node], a literal, as a node of its own. */
    fun literal(node: ElixirAst.Literal): ElixirAst =
        when (node) {
            is ElixirAst.Literal.Atom -> atom(node.name)
            is ElixirAst.Literal.Integer -> integer(node.value)
            is ElixirAst.Literal.Float -> ElixirAst.Literal.Float(meta(), node.value)
            is ElixirAst.Literal.Binary -> ElixirAst.Literal.Binary(meta(), node.bytes)
        }
}

/** A metadata entry whose value is the atom [value]. */
internal fun entry(name: String, value: String) = Meta.Key.Entry(name, Meta.Value.Atom(value))

/**
 * The metadata `Kernel`'s `quote` gives a local call to a `Kernel` import of [arities]: `import: Kernel`, or from
 * [QUOTE_IMPORTS_EVERY_ARITY] `imports:` with each arity, after `context: Kernel`.
 */
internal fun kernelImportKeys(level: ElixirLanguageLevel, vararg arities: Int): List<Meta.Key> =
    listOf(entry("context", KERNEL), importKey(level, arities.map { it to KERNEL }))

/**
 * `elixir_quote:import_meta/5`'s import key for [imports], each `{arity, module}`: `import:` the first's module, or
 * from [QUOTE_IMPORTS_EVERY_ARITY] `imports:` with each.
 */
internal fun importKey(level: ElixirLanguageLevel, imports: List<Pair<Int, String>>): Meta.Key.Entry =
    if (QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level)) {
        val tuples = imports.map { (arity, module) ->
            Meta.Value.Tuple(listOf(Meta.Value.Integer(arity.toLong()), Meta.Value.Atom(module)))
        }

        Meta.Key.Entry("imports", Meta.Value.List(tuples))
    } else {
        entry("import", imports.first().second)
    }

/** `generated: true`. */
internal val GENERATED: List<Meta.Key> = listOf(entry("generated", "true"))
