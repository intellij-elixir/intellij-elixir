package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTE_REFUSED_IN_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_BODY_DOC_READ_BINDS_VALUE
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * `Kernel.@/1` (`K:3711–3870`): its checks in Elixir's order, then what the macro returns for the level, which takes
 * the module's next counter (`elixir_dispatch:expand_quoted/7`) and, when it is code, is linified with it and expanded
 * where the `@` is. In a function, a read returns the attribute's value as it stands.
 */
internal val ATTRIBUTE = Summary { _, node, state, env, run ->
    val call = (node.arguments!!.single() as? ElixirAst.Call)?.takeIf { it.callee is ElixirAst.Literal.Atom }
        ?: return@Summary Expansion.Unported(node)
    val name = (call.callee as ElixirAst.Literal.Atom).name
    val args = call.arguments
    val inFunction = env.function != null
    val refused = env.context == Env.Context.MATCH ||
        env.context == Env.Context.GUARD && ATTRIBUTE_REFUSED_IN_GUARD.isSufficient(run.level)
    val returned = when {
        env.module == null -> Returned.Ended(Expansion.Error("attribute_outside_module", node))
        !inFunction && refused -> Returned.Ended(Expansion.Error("attribute_in_match_or_guard", node))
        args != null && name in TYPESPECS -> typespec(name, args, node, env, run)
        args.isNullOrEmpty() -> read(name, node, env, run)
        args.size == 1 -> write(name, call, args.single(), node, env, run)
        else -> Returned.Ended(Expansion.Error("attribute_arity", node))
    }

    when (returned) {
        is Returned.Ended -> returned.expansion
        is Returned.Value -> {
            run.counters.next(env.module)

            Expansion.Expanded(state, env, returned.term)
        }
        is Returned.Code -> {
            val counter = run.counters.next(env.module)
            val linified = linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, returned.node)

            run.compiling[env.module!!]?.built(linified, node)

            Expander.expand(linified, state, env, run)
        }
    }
}

/** What `@` returns, if it does: a value, or the code Elixir expands where the `@` is. */
private sealed interface Returned {
    /** The macro raises, or the port stops here. */
    data class Ended(val expansion: Expansion) : Returned

    data class Value(val term: Term) : Returned

    data class Code(val node: ElixirAst) : Returned
}

/** `Kernel.Typespec.deftypespec/6` of the first argument escaped, which runs only its `unquote` fragments. */
private fun typespec(name: String, args: List<ElixirAst>, node: ElixirAst.Call, env: Env, run: Run): Returned {
    // `hd([])` raises.
    val spec = args.firstOrNull() ?: return Returned.Ended(Expansion.Unported(node))
    val escaped = when (val escape = Quote.escape(spec, node.meta, env, run)) {
        is Quote.Escaped.Built -> escape.node
        is Quote.Escaped.Stopped -> return Returned.Ended(escape.expansion)
    }
    val built = Built(node.meta)

    // The file is `Code.compile_string/2`'s, and the cached env's position isn't modelled.
    return Returned.Code(
        built.remoteCall(
            KERNEL_TYPESPEC,
            "deftypespec",
            built.atom(name),
            escaped,
            built.integer(line(node.meta)),
            ElixirAst.Literal.Binary(node.meta, "nofile".toByteArray()),
            built.atom(env.module!!),
            built.integer(0),
        ),
    )
}

/** `do_at/5` with one argument: `Module.__put_attribute__/4,5`, or a raise. */
private fun write(
    name: String,
    call: ElixirAst.Call,
    arg: ElixirAst,
    node: ElixirAst.Call,
    env: Env,
    run: Run,
): Returned {
    val lazily = ATTRIBUTES_EXPANDED_LAZILY.isSufficient(run.level)

    return when {
        env.function != null -> Returned.Ended(Expansion.Error("attribute_set_in_function", node))
        name == "behavior" ->
            // Before it, `IO.warn/2`'s `:ok`.
            if (lazily) Returned.Ended(Expansion.Error("behavior_attribute", node)) else Returned.Value(OK)
        else -> {
            val built = Built(node.meta)
            val quoted = call.meta.keys.any { it is Meta.Key.Entry && it.name == "context" }
            val envLine = built.integer(line(node.meta))
            val line = if (quoted) built.atom("nil") else envLine
            val value = if (name in DOCS) ElixirAst.Tuple(node.meta, listOf(envLine, arg)) else arg
            // `collect_traces/3`'s traces change only `alias_reference` events, which aren't compared.
            val traces = listOfNotNull(ElixirAst.ListNode(node.meta, emptyList()).takeIf { lazily })

            Returned.Code(
                built.remoteCall(
                    ELIXIR_MODULE,
                    "__put_attribute__",
                    *(listOf(built.atom(env.module!!), built.atom(name), value, line) + traces).toTypedArray(),
                ),
            )
        }
    }
}

/**
 * `do_at/5` with no argument: in a function, the value as it stands; in the module body,
 * `Module.__get_attribute__/3,4`, whose value is known only at run time, with a doc's line dropped.
 */
private fun read(name: String, node: ElixirAst.Call, env: Env, run: Run): Returned {
    val module = env.module!!

    if (env.function != null) {
        val compiling = run.compiling[module] ?: return Returned.Ended(Expansion.Unported(node))
        val value = compiling.attributes.read(name)

        compiling.read(name, node, value)

        return Returned.Value(value)
    }

    val lazily = ATTRIBUTES_EXPANDED_LAZILY.isSufficient(run.level)
    val doc = name in DOCS
    val built = Built(node.meta)
    val trailing = listOfNotNull(built.atom((!doc).toString()).takeIf { lazily })
    val get = built.remoteCall(
        ELIXIR_MODULE,
        "__get_attribute__",
        *(listOf(built.atom(module), built.atom(name), built.integer(line(node.meta))) + trailing).toTypedArray(),
    )

    if (!doc) return Returned.Code(get)

    val fallback = if (MODULE_BODY_DOC_READ_BINDS_VALUE.isSufficient(run.level)) "value" else "other"

    return Returned.Code(built.docPart(get, fallback))
}

/** The nodes `@` builds, all at the `@`'s position, its variables in `Kernel`'s context. */
private class Built(private val meta: Meta) {
    fun atom(name: String) = ElixirAst.Literal.Atom(meta, name)

    fun integer(value: Int) = ElixirAst.Literal.Integer(meta, value.toBigInteger())

    fun variable(name: String) = ElixirAst.Call(meta, atom(name), null, ElixirAst.VariableContext.Atom(KERNEL))

    fun remoteCall(module: String, function: String, vararg args: ElixirAst) =
        ElixirAst.Call(meta, ElixirAst.Call(meta, atom("."), listOf(atom(module), atom(function))), args.toList())

    /** `case get do {_, doc} -> doc; fallback -> fallback end`. */
    fun docPart(get: ElixirAst, fallback: String): ElixirAst {
        val clause = { pattern: ElixirAst, body: ElixirAst ->
            ElixirAst.Call(meta, atom("->"), listOf(ElixirAst.ListNode(meta, listOf(pattern)), body))
        }
        val clauses = listOf(
            clause(ElixirAst.Tuple(meta, listOf(variable("_"), variable("doc"))), variable("doc")),
            clause(variable(fallback), variable(fallback)),
        )
        val doBlock = ElixirAst.Tuple(meta, listOf(atom("do"), ElixirAst.ListNode(meta, clauses)))

        return ElixirAst.Call(meta, atom("case"), listOf(get, ElixirAst.ListNode(meta, listOf(doBlock))))
    }
}

/**
 * `elixir_def:retrieve_location/2` (`Def:218–232`), which takes `@file` as [definition] starts, before its name is
 * checked and its arguments and body are expanded. A definition that isn't a statement of the module body may start at
 * any point, so every later read of `@file` is unknown.
 */
internal fun takeFile(definition: Pending.Definition, compiling: Compiling) {
    compiling.attributes.take("file")

    if (!compiling.isStatement(definition.node)) compiling.attributes.markUnknown("file")
}

/**
 * `Module.compile_definition_attributes/6` (`Mod:1852–1977`), which Elixir runs once [definition] is stored: its
 * `@impl`, `@deprecated` and `@doc` taken and recorded against it. A definition that isn't a statement of the module
 * body may be stored at any point, so every later read of what it takes is unknown.
 */
internal fun takeDefinitionAttributes(definition: Pending.Definition, owner: Owner.Definition, compiling: Compiling) {
    val attributes = compiling.attributes
    val kind = owner.kind
    val impl = attributes.take("impl")
    val deprecated = attributes.take("deprecated")
        ?.takeIf { it == AttributeValue.Unknown || (it as AttributeValue.Known).term is Term.Binary }
    val doc = docPart(attributes.take("doc"))

    if (!compiling.isStatement(definition.node)) attributes.markUnknown("impl", "deprecated", "doc")

    val impls = listOfNotNull(impl?.let { AttributeLog.Impl(kind, line(definition.node.meta), it) })
    val truthy = when (impl) {
        null -> false
        AttributeValue.Unknown -> null
        is AttributeValue.Known -> impl.term != NIL && impl.term != FALSE
    }
    val name = owner.name

    if (name == null) {
        if (kind.public || impls.isNotEmpty() || deprecated != null) {
            compiling.unnamed += AttributeLog.DefinitionAttributes(
                impls,
                if (kind.public) withoutImpl(doc, truthy) else null,
                deprecated,
                listOfNotNull(deprecated),
            )
        }

        return
    }

    val arity = owner.arity
    val key = NameArity(name, arity)
    val current = compiling.definitions[key]

    if (kind.public || impls.isNotEmpty() || deprecated != null) {
        val merged = when {
            !kind.public -> current?.doc
            doc == AttributeValue.Known(NIL) && current?.doc != null -> withoutImpl(current.doc, truthy)
            else -> withoutImpl(doc, truthy)
        }

        compiling.definitions[key] = AttributeLog.DefinitionAttributes(
            current?.impls.orEmpty() + impls,
            merged,
            deprecated ?: current?.deprecated,
            current?.reasons.orEmpty() + listOfNotNull(deprecated),
        )
    }

    if (deprecated != null) {
        val call = extractGuards(definition.head).first
        val defaults = (call as? ElixirAst.Call)?.arguments.orEmpty().count { isCall(it, "\\\\", 2) }

        for (defaultArity in arity - 1 downTo arity - defaults) {
            val defaultKey = NameArity(name, defaultArity)
            val existing = compiling.definitions[defaultKey]

            compiling.definitions[defaultKey] = existing?.copy(deprecated = deprecated, reasons = existing.reasons + deprecated)
                ?: AttributeLog.DefinitionAttributes(emptyList(), null, deprecated, listOf(deprecated))
        }
    }
}

/** `get_doc_info/2`: the doc of `{line, doc}`, or `nil` where none is set. */
private fun docPart(doc: AttributeValue?): AttributeValue =
    when (doc) {
        null -> AttributeValue.Known(NIL)
        AttributeValue.Unknown -> doc
        is AttributeValue.Known -> AttributeValue.Known((doc.term as? Term.Pair)?.second ?: doc.term)
    }

/** `if is_nil(doc) && impl, do: false, else: doc`, where [truthy] is `null` when `@impl`'s value isn't known. */
private fun withoutImpl(doc: AttributeValue, truthy: Boolean?): AttributeValue =
    when {
        doc != AttributeValue.Known(NIL) -> doc
        truthy == null -> AttributeValue.Unknown
        truthy -> AttributeValue.Known(FALSE)
        else -> doc
    }

private val OK = Term.Atom("ok")
