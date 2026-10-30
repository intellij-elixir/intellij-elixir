package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_BIND_QUOTED
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_ESCAPES_STRUCT
import org.elixir_lang.language_level.ElixirLanguageFeature.ENFORCE_KEYS_MUST_BE_FIELDS
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_ATTRIBUTE_RENAMED
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.Evaluated
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * `Kernel.defstruct/1` (`K:5622–5638`, `U:107–233`): its output is expanded where the call is. What
 * `Kernel.Utils.defstruct` computes from the fields and `@enforce_keys` is computed when the module body runs to the
 * call, by the effect that takes the place of the output's statements. The output's definitions, and up to 1.13 the
 * `case` on `@enforce_keys` that holds `__struct__/1`'s clauses, run after it as the body runs them.
 */
internal val DEFSTRUCT = Summary { _, node, state, env, run ->
    val compiling = moduleBody(env, run)

    if (compiling == null) {
        Expansion.Unported(node)
    } else {
        defstruct(node, node.arguments!!.single(), env.module!!, compiling, state, env, run)
    }
}

/**
 * The compilation of the module [env] is at the top level of the body of: the one place `defstruct` and `defexception`
 * are followed. In a function, a guard or a match, they define nothing the expander follows.
 */
internal fun moduleBody(env: Env, run: Run): Compiling? =
    env.module?.let { run.compiling[it] }?.takeIf { env.function == null && env.context == Env.Context.NONE }

/** [node], a `defstruct` of [fields] in the body of [module] that [compiling] holds, expanded. */
private fun defstruct(
    node: ElixirAst.Call,
    fields: ElixirAst,
    module: String,
    compiling: Compiling,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val output = StructOutput(Synthetic(node.meta), run.level, module, fields)
    val watched = Watched(fields)
    val from = run.pending.size
    val expansion = expandQuoted(node, KERNEL, output.output(), state, env, run, watched, ports = true)
    val queued = run.pending.subList(from, run.pending.size).toList()
    // The definitions, and the statement whose branches hold definitions: what runs after the effect.
    val kept = queued.filter { it is Pending.Definition || it.inner.any { unit -> unit is Pending.Definition } }
    val definitions = queued.unnested().filterIsInstance<Pending.Definition>()
    val results = queued.firstNotNullOfOrNull { (it as? Pending.Match)?.node?.let(output::results) }
    val effect = DefstructEffect(
        node,
        module,
        output,
        { compiling, bindings -> fieldsAt(watched, node, compiling, state, env, run, bindings) },
        { default, compiling, bindings -> compiling.values.evaluate(default, state, env, run, bindings) },
        kept,
        results.orEmpty(),
        compiling.statements,
        run,
    ).takeIf { expansion is Expansion.Expanded && definitions.size == output.definitionCount }

    // A struct the output stops short of is unreadable, as one the effect can't follow is.
    run.queueOutput(
        from,
        node,
        compiling.isStatement(node),
        queued = { effect?.queued() ?: kept },
        apply = { compiling, statement, bindings ->
            if (effect == null) unreadable(compiling) else effect.apply(compiling, statement, bindings)
        },
    )

    return expansion
}

private fun unreadable(compiling: Compiling): Expansion? {
    compiling.struct = ModuleStruct.Unreadable

    return null
}

/**
 * The fields [fields] watched, as the module body has run to the call [at]: the term the expansion gave, or where that
 * isn't known, the node expanded evaluated from [bindings], the variables the walk has at the call.
 */
private fun fieldsAt(
    fields: Watched,
    at: ElixirAst,
    compiling: Compiling,
    state: ExState,
    env: Env,
    run: Run,
    bindings: HashMap<Binding, OtpErlangObject>,
): Fields {
    val term = fields.value ?: return Fields.Dynamic
    val expanded = fields.expanded ?: return Fields.Dynamic

    return when (val values = evaluatedArguments(listOf(term), listOf(expanded), compiling, state, env, run, bindings)) {
        is ArgumentValues.Evaluated -> fieldsOf(fields.node, values.terms.single(), at, run.level, run.exports)
        is ArgumentValues.Raised -> Fields.Raised(values.expansion)
    }
}

/** What the fields given to `defstruct` are, as far as their evaluation is followed. */
private sealed interface Fields {
    /** Evaluated: [entries] are the fields up to the first that [error] names, if one raises. */
    class Known(val entries: List<Pair<String, ElixirAst>>, val error: String?) : Fields

    /** A value the expander doesn't evaluate. */
    data object Dynamic : Fields

    /** Evaluated, and not a list. */
    data object NotAList : Fields

    /** Evaluating them raises, ending the module body with [expansion]. */
    class Raised(val expansion: Expansion) : Fields
}

/**
 * A value of the fields: the [term] its expansion gave, or `null` where it gave none, and the [node] it was written as,
 * or `null` where that isn't known to line up with the term.
 */
private class FieldPart(val term: Term?, val node: ElixirAst?)

/**
 * `Kernel.Utils.defstruct`'s check of `fields` and its `mapper`, for the [node] `defstruct` is given. The [term] it
 * expanded to decides each key and default it has a literal value for; the node's shape decides only where the
 * expansion computed none: a map, a number, a function.
 *
 * @param at the call, whose location the values built from terms take
 */
private fun fieldsOf(node: ElixirAst, term: Term?, at: ElixirAst, level: ElixirLanguageLevel, exports: Exports): Fields {
    if (term == null) return Fields.Dynamic

    val s = Synthetic(at.meta)
    val fragment = s.atom("nil")
    val escape = QuoteEscape(ImportTermEscapeView, AstEscapeBuilder(s), { literalOf(fragment, it) }, level, exports)
    val escaped = { part: Term -> (escape.escape(part) as? EscapeResult.Built)?.value }

    val parts = when {
        term is Term.List -> {
            if (term.tail != null) return Fields.Dynamic

            val nodes = (node as? ElixirAst.ListNode)?.elements?.takeIf { it.size == term.elements.size }

            term.elements.mapIndexed { index, element -> FieldPart(element, nodes?.get(index)) }
        }
        node is ElixirAst.ListNode -> node.elements.map { FieldPart(null, it) }
        else -> return if (escaped(term) != null || evaluated(node, level) != Value.DYNAMIC) Fields.NotAList else Fields.Dynamic
    }

    val fields = parts.map { fieldOf(it, fragment, level, escaped) }

    if (fields.any { it.key is FieldKey.Unknown || it.value == Value.DYNAMIC }) return Fields.Dynamic

    val entries = mutableListOf<Pair<String, ElixirAst>>()

    for (field in fields) {
        val key = (field.key as? FieldKey.Name)?.name ?: return Fields.Known(entries, "struct_field_not_atom")

        if (key == "__struct__" && DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level)) {
            return Fields.Known(entries, "struct_reserved_key")
        }

        if (field.value == Value.INVALID) return Fields.Known(entries, "struct_invalid_default")

        entries += key to field.default
    }

    return Fields.Known(entries, null)
}

private sealed interface FieldKey {
    data class Name(val name: String) : FieldKey

    data object NotAtom : FieldKey

    data object Unknown : FieldKey
}

private class FieldKeyed(val key: FieldKey, val value: Value, val default: ElixirAst)

/** The key and default [part] gives, [escaped] building each term it has: a bare key is `nil`'s. */
private fun fieldOf(part: FieldPart, nil: ElixirAst, level: ElixirLanguageLevel, escaped: (Term) -> ElixirAst?): FieldKeyed {
    val term = part.term
    val node = part.node
    val written = (node as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 }
    val pair = when {
        term is Term.Pair -> FieldPart(term.first, written?.get(0)) to FieldPart(term.second, written?.get(1))
        (term == null || term.isUnexpanded()) && written != null -> FieldPart(null, written[0]) to FieldPart(null, written[1])
        else -> null
    }

    if (pair == null) return FieldKeyed(keyOf(part, escaped), Value.LITERAL, nil)

    return FieldKeyed(keyOf(pair.first, escaped), valueOf(pair.second, level, escaped), defaultOf(pair.second, nil, escaped))
}

/** Whether the expansion computed no value for the term: the node it was written as says what it is. */
private fun Term.isUnexpanded(): Boolean = this is Term.Node || this is Term.NonTuple || this == Term.Unexpanded

private fun keyOf(part: FieldPart, escaped: (Term) -> ElixirAst?): FieldKey {
    val literal = part.term?.let(escaped)
    val node = part.node

    return when {
        literal is ElixirAst.Literal.Atom -> FieldKey.Name(literal.name)
        literal != null -> FieldKey.NotAtom
        node is ElixirAst.Literal.Atom -> FieldKey.Name(node.name)
        node is ElixirAst.Literal || node is ElixirAst.ListNode || node is ElixirAst.Tuple -> FieldKey.NotAtom
        else -> FieldKey.Unknown
    }
}

private fun valueOf(part: FieldPart, level: ElixirLanguageLevel, escaped: (Term) -> ElixirAst?): Value =
    if (part.term?.let(escaped) != null) {
        Value.LITERAL
    } else {
        part.node?.let { evaluated(it, level) } ?: Value.DYNAMIC
    }

private fun defaultOf(part: FieldPart, nil: ElixirAst, escaped: (Term) -> ElixirAst?): ElixirAst =
    part.term?.let(escaped) ?: part.node?.let(::unlocated) ?: nil

/** [node] with the locations of its metadata taken out: `elixir_quote:escape/3` gives a default none. */
private fun unlocated(node: ElixirAst): ElixirAst {
    ProgressManager.checkCanceled()

    fun meta(meta: Meta) = Meta(meta.origin, meta.start, meta.end, meta.keys.filterNot { it is Meta.Key.Location }, meta.built)

    return when (node) {
        is ElixirAst.Call ->
            ElixirAst.Call(meta(node.meta), unlocated(node.callee), node.arguments?.map(::unlocated), node.context)
        is ElixirAst.Alias -> ElixirAst.Alias(meta(node.meta), node.segments.map(::unlocated))
        is ElixirAst.Block -> ElixirAst.Block(meta(node.meta), node.expressions.map(::unlocated))
        is ElixirAst.Tuple -> ElixirAst.Tuple(meta(node.meta), node.elements.map(::unlocated))
        is ElixirAst.ListNode -> ElixirAst.ListNode(meta(node.meta), node.elements.map(::unlocated))
        else -> node
    }
}

private enum class Value { LITERAL, INVALID, DYNAMIC }

/**
 * What evaluating [node] and escaping the value gives, for a node whose value the expansion didn't compute: a literal,
 * a function, which `elixir_quote:escape` refuses, or a value the expander doesn't evaluate. A node with a part of the
 * last kind is of it. A capture of a remote function escapes as its external fun; any other capture may be of an
 * imported function, which does, or of a local one, which doesn't, so it isn't followed. Before the struct is escaped
 * in the output ([DEFSTRUCT_ESCAPES_STRUCT]) the compiler expands that fun where `__struct__/0` and `/1` read it, which
 * the expander has no value to do, so it isn't followed either.
 */
private fun evaluated(node: ElixirAst, level: ElixirLanguageLevel): Value {
    ProgressManager.checkCanceled()

    return when {
        node is ElixirAst.Literal -> Value.LITERAL
        node is ElixirAst.ListNode -> combined(node.elements, level)
        node is ElixirAst.Tuple -> combined(node.elements, level)
        isNamedCall(node, "fn") -> Value.INVALID
        isCall(node, "&", 1) ->
            if (DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level) && isRemoteCapture((node as ElixirAst.Call).arguments!!.single())) {
                Value.LITERAL
            } else {
                Value.DYNAMIC
            }
        isCall(node, "-", 1) || isCall(node, "+", 1) -> {
            val operand = (node as ElixirAst.Call).arguments!!.single()

            if (operand is ElixirAst.Literal.Integer || operand is ElixirAst.Literal.Float) Value.LITERAL else Value.DYNAMIC
        }
        isMap(node) -> {
            val pairs = (node as ElixirAst.Call).arguments!!

            if (pairs.all { it is ElixirAst.Tuple && it.elements.size == 2 }) {
                combined(pairs.flatMap { (it as ElixirAst.Tuple).elements }, level)
            } else {
                Value.DYNAMIC
            }
        }
        else -> Value.DYNAMIC
    }
}

private fun combined(nodes: List<ElixirAst>, level: ElixirLanguageLevel): Value {
    val values = nodes.map { evaluated(it, level) }

    return when {
        Value.DYNAMIC in values -> Value.DYNAMIC
        Value.INVALID in values -> Value.INVALID
        else -> Value.LITERAL
    }
}

/**
 * The effect of a `defstruct` at [node] on [module] once the body has run to it: `Kernel.Utils.defstruct`'s checks
 * and the record, its result bound to the output's [results], the variables the definitions of `__struct__/0` and
 * `__struct__/1` unquote, and the struct it gives as the output's value, each default [evaluate]d where the body is.
 * [queued] is what the output queued that runs after it: its definitions, and up to 1.13 the `case` on
 * `@enforce_keys` whose arms hold `__struct__/1`'s clauses.
 */
private class DefstructEffect(
    private val node: ElixirAst.Call,
    private val module: String,
    private val output: StructOutput,
    private val fields: (Compiling, HashMap<Binding, OtpErlangObject>) -> Fields,
    private val evaluate: (ElixirAst, Compiling, HashMap<Binding, OtpErlangObject>) -> Evaluated,
    private val queued: List<Pending>,
    private val results: Map<String, ElixirAst>,
    private val statements: Statements,
    private val run: Run,
) {
    private val level = run.level

    /** Whether the walk ran the `defstruct` as a statement, which its [queued] definitions read. */
    private var statement = false

    /**
     * The effect applied where the walk ran the `defstruct`, as a [statement] or not, binding the output's results into
     * [bindings], the variables the walk has there.
     */
    fun apply(compiling: Compiling, statement: Boolean, bindings: HashMap<Binding, OtpErlangObject>): Expansion? {
        this.statement = statement
        // A call that may not run is not followed.
        if (!statement) return stopped(compiling)

        val twice = compiling.struct != null

        // Up to 1.13 the output checks for a second call before it evaluates the fields.
        if (twice && !DEFSTRUCT_BIND_QUOTED.isSufficient(level)) return Expansion.Error("struct_twice", node)

        val result = fields(compiling, bindings)

        if (result is Fields.Raised) return result.expansion
        if (result == Fields.Dynamic) return stopped(compiling)
        if (twice) return Expansion.Error("struct_twice", node)

        val known = when (result) {
            Fields.NotAList -> return Expansion.Error("struct_fields_not_list", node)
            is Fields.Known -> result
            Fields.Dynamic -> return stopped(compiling)
            is Fields.Raised -> return result.expansion
        }

        known.error?.let { return Expansion.Error(it, node) }

        val keys = enforceKeysOf(compiling, level) ?: return stopped(compiling)

        // From 1.14 the row it read is marked used; 1.14.0 took it out instead, which isn't followed.
        if (DEFSTRUCT_BIND_QUOTED.isSufficient(level)) compiling.attributes.used("enforce_keys")
        val names = keys.map { (it as? Term.Atom)?.name }

        if (names.any { it == null }) return Expansion.Error("enforce_key_not_atom", node)

        val enforcedKeys = names.filterNotNull()
        val fieldKeys = known.entries.map { it.first }.toSet()

        if (ENFORCE_KEYS_MUST_BE_FIELDS.isSufficient(level) && notFields(enforcedKeys, fieldKeys + "__struct__").isNotEmpty()) {
            return Expansion.Error("enforce_keys_not_defined", node)
        }

        bind(bindings, enforcedKeys, known.entries)
        compiling.values.gave(node, struct(compiling, bindings, known.entries))
        compiling.struct = ModuleStruct.Present(fieldKeys - "__struct__", Enforced.Known(enforcedKeys.toSet()))

        if (!DEFSTRUCT_BIND_QUOTED.isSufficient(level)) {
            // `@enforce_keys keys`, which the output writes.
            compiling.attributes.apply(Effect.Write("enforce_keys", Term.List(enforcedKeys.map(Term::Atom))))
        }

        if (!DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level)) {
            // `@__struct__ struct`, written with the struct, which isn't a term the expander holds.
            compiling.attributes.markUnknown(if (STRUCT_ATTRIBUTE_RENAMED.isSufficient(level)) "__struct__" else "struct")
        }

        val derived = compiling.attributes.values("derive")

        // From 1.14 `Kernel.Utils.defstruct` takes `@derive` out of the table.
        if (DEFSTRUCT_BIND_QUOTED.isSufficient(level)) compiling.attributes.apply(Effect.Delete("derive"))

        return derive(node, derived, run)
    }

    /** `Kernel.Utils.defstruct`'s result, bound into [bindings] where the output's `=` binds it; unbound where it can't be escaped. */
    private fun bind(bindings: HashMap<Binding, OtpErlangObject>, enforced: List<String>, entries: List<Pair<String, ElixirAst>>) {
        val values = output.values(enforced, entries) ?: return

        for ((name, variable) in results) {
            val version = run.bodyDispatches.versionOf(variable) ?: continue

            values[name]?.let { bindings[Binding(version)] = it.toOtp() }
        }
    }

    /** The struct `Kernel.Utils.defstruct` gives for [entries] ([StructOutput.fields]): a default the evaluator can't give is unknown. */
    private fun struct(compiling: Compiling, bindings: HashMap<Binding, OtpErlangObject>, entries: List<Pair<String, ElixirAst>>): OtpErlangObject {
        val fields = output.fields(entries)
        val values = fields.map { (_, default) -> (evaluate(default, compiling, bindings) as? Evaluated.Value)?.value ?: Unknown }

        return OtpErlangMap(fields.map { (key, _) -> atom(key) }.toTypedArray(), values.toTypedArray())
    }

    /** A struct the expander can't follow: the module stops at the `defstruct`. */
    private fun stopped(compiling: Compiling): Expansion {
        compiling.struct = ModuleStruct.Unreadable

        return Expansion.Unported(node)
    }

    /** What the output queued after the effect, as statements where the `defstruct` is one. */
    fun queued(): List<Pending> = queued.map { if (statement && !it.isStatement(statements)) it.asStatement() else it }
}

/**
 * `@enforce_keys` as `Kernel.Utils.defstruct` reads it, or `null` where its value isn't known. From 1.14 that is its row
 * in the set table: none where there is no row, the list, or the single key. Up to 1.13 it is `List.wrap/1` of
 * `Module.get_attribute/2`, which makes a `nil` none too.
 */
private fun enforceKeysOf(compiling: Compiling, level: ElixirLanguageLevel): List<Term>? {
    val bound = DEFSTRUCT_BIND_QUOTED.isSufficient(level)
    val term = if (bound) {
        when (val row = compiling.attributes.row("enforce_keys")) {
            null -> return emptyList()
            is AttributeValue.Held, AttributeValue.Unknown -> return null
            is AttributeValue.Known -> row.term
        }
    } else {
        (compiling.attributes.read("enforce_keys") as? AttributeValue.Known)?.term ?: return null
    }

    return when (term) {
        is Term.List -> if (term.tail == null) term.elements else null
        NIL -> if (bound) listOf(term) else emptyList()
        is Term.Node, Term.Unexpanded -> null
        else -> listOf(term)
    }
}

/** `enforce_keys -- keys`: the enforced keys left once one is taken out for each of [fields]. */
private fun notFields(enforced: List<String>, fields: Set<String>): List<String> {
    val removed = HashSet<String>()

    // `--` takes away the first of each.
    return enforced.filter {
        ProgressManager.checkCanceled()

        !(it in fields && removed.add(it))
    }
}
