package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.ATTRIBUTES_EXPANDED_LAZILY
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_BIND_QUOTED
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_ESCAPES_STRUCT
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_PASSES_ENV
import org.elixir_lang.language_level.ElixirLanguageFeature.FROM_INTERPOLATION
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_ATTRIBUTE_RENAMED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import java.util.IdentityHashMap

private const val KERNEL_UTILS = "Elixir.Kernel.Utils"
private const val BOOTSTRAP = "elixir_bootstrap"

/**
 * The output of `Kernel.defstruct/1` for [fields], the AST `defstruct` is called with, in `K:5622–5638` as the release
 * has it, and the bodies `Kernel.Utils.defstruct` builds for `__struct__/1`. Three shapes:
 * - up to 1.13, the output checks for a second call, writes the attributes and chooses the clause with a `case` on
 *   `@enforce_keys` ([DEFSTRUCT_BIND_QUOTED]);
 * - from 1.14, `Kernel.Utils.defstruct` does that, and gives the clause and its body;
 * - from 1.18, it also gives the struct escaped, and `__struct__/0` returns that ([DEFSTRUCT_ESCAPES_STRUCT]).
 */
internal class StructOutput(
    private val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val module: String,
    private val fields: ElixirAst,
) {
    private val bound = DEFSTRUCT_BIND_QUOTED.isSufficient(level)
    private val escaped = DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level)
    private val kernel = Nodes(s, level, KERNEL, generated = false)
    private val utils = Nodes(s, level, KERNEL_UTILS, generated = escaped)
    private val plainUtils = Nodes(s, level, KERNEL_UTILS, generated = false)

    /** How many definitions the output queues: `__struct__/0`, and the clause or clauses of `__struct__/1`. */
    val definitionCount = if (bound) 2 else 3

    fun output(): ElixirAst = if (bound) bindQuoted() else legacy()

    private fun bindQuoted(): ElixirAst {
        val k = kernel
        val struct = structVariable()
        val results = listOfNotNull(struct, k.v("derive"), k.v("escaped_struct").takeIf { escaped }, k.v("kv"), k.v("body"))
        val utilsArgs = listOfNotNull(k.v("__MODULE__"), k.v("fields"), k.v("bootstrapped?"), k.v("__ENV__").takeIf { passesEnv() })

        return k.block(
            k.assign(k.v("fields"), fields),
            k.assign(k.v("bootstrapped?"), s.atom("true")),
            k.block(
                k.assign(k.tuple(results), k.remote(kernelAlias(s, "Kernel", "Utils"), "defstruct", utilsArgs)),
                derive(),
                k.imported(
                    "def",
                    listOf(
                        k.local("__struct__", emptyList()),
                        s.keywords(listOf("do" to if (escaped) unquote("escaped_struct") else structRead(k))),
                    ),
                    BOOT_DEF,
                ),
                k.imported(
                    "def",
                    listOf(k.local("__struct__", listOf(unquote("kv"))), s.keywords(listOf("do" to unquote("body")))),
                    BOOT_DEF,
                ),
                announce(),
                structVariable(),
            ),
        )
    }

    private fun passesEnv() = DEFSTRUCT_PASSES_ENV.isSufficient(level)

    private fun structVariable(): ElixirAst = kernel.structVariable()

    private fun unquote(name: String): ElixirAst = s.call("unquote", listOf(kernel.v(name)))

    private fun derive(): ElixirAst {
        val k = kernel
        val derive = k.v("derive")
        val call = k.remote(kernelAlias(s, "Protocol"), "__derive__", listOf(derive, k.v("__MODULE__"), k.v("__ENV__")))

        return s.case(derive, listOf(s.arrow(listOf(s.list()), s.atom("ok")), s.arrow(listOf(k.v("_")), call)))
    }

    private fun announce(): ElixirAst =
        kernel.remote(kernelAlias(s, "Kernel", "Utils"), "announce_struct", listOf(kernel.v("__MODULE__")))

    /** `@__struct__` (`@struct` up to [STRUCT_ATTRIBUTE_RENAMED]), as `elixir_bootstrap` builds the read. */
    private fun structRead(k: Nodes): ElixirAst = k.imported("@", listOf(k.localVariable(structAttribute())), BOOT_AT)

    private fun structAttribute() = if (STRUCT_ATTRIBUTE_RENAMED.isSufficient(level)) "__struct__" else "struct"

    /** Up to 1.13: the output holds the checks, the attribute writes and the `case`. */
    private fun legacy(): ElixirAst {
        val k = kernel
        val attribute = structAttribute()
        val named = if (STRUCT_ATTRIBUTE_RENAMED.isSufficient(level)) {
            k.call(attribute, listOf(k.v("struct")))
        } else {
            k.imported(attribute, listOf(k.v("struct")), listOf(1 to KERNEL))
        }

        val twice = k.imported(
            "if",
            listOf(
                k.remote(kernelAlias(s, "Module"), "has_attribute?", listOf(k.v("__MODULE__"), s.atom(attribute))),
                s.keywords(listOf("do" to raise(k, "defstruct has already been called for ", k.inspect(remote = true), ", defstruct can only be called once per module"))),
            ),
            listOf(2 to KERNEL),
        )

        return k.block(
            twice,
            k.assign(
                k.tuple(listOf(k.v("struct"), k.v("keys"), k.v("derive"))),
                k.remote(kernelAlias(s, "Kernel", "Utils"), "defstruct", listOf(k.v("__MODULE__"), fields)),
            ),
            k.imported("@", listOf(named), BOOT_AT),
            k.imported("@", listOf(k.call("enforce_keys", listOf(k.v("keys")))), BOOT_AT),
            derive(),
            k.imported(
                "def",
                listOf(k.local("__struct__", emptyList()), s.keywords(listOf("do" to structRead(k)))),
                BOOT_DEF,
            ),
            s.case(
                k.imported("@", listOf(k.localVariable("enforce_keys")), BOOT_AT),
                listOf(s.arrow(listOf(s.list()), plainClause(k)), s.arrow(listOf(k.v("_")), enforcedClause(k))),
            ),
            announce(),
            k.v("struct"),
        )
    }

    /** `def __struct__(kv)` of the first arm: no enforced keys. */
    private fun plainClause(k: Nodes): ElixirAst {
        val reduce = k.remote(
            kernelAlias(s, "Enum"),
            "reduce",
            listOf(
                k.v("kv"),
                structRead(k),
                k.fn(
                    listOf(k.tuple(listOf(k.v("key"), k.v("val"))), k.v("map")),
                    k.remote(kernelAlias(s, "Map"), "replace!", listOf(k.v("map"), k.v("key"), k.v("val"))),
                ),
            ),
        )

        return k.imported("def", listOf(k.local("__struct__", listOf(k.v("kv"))), s.keywords(listOf("do" to reduce))), BOOT_DEF)
    }

    /** `def __struct__(kv)` of the second arm: keys the call must give. */
    private fun enforcedClause(k: Nodes): ElixirAst {
        val keys = k.imported("@", listOf(k.localVariable("enforce_keys")), BOOT_AT)
        val replaced = k.remote(kernelAlias(s, "Map"), "replace!", listOf(k.v("map"), k.v("key"), k.v("val")))
        val deleted = k.remote(kernelAlias(s, "List"), "delete", listOf(k.v("keys"), k.v("key")))
        val reduce = k.remote(
            kernelAlias(s, "Enum"),
            "reduce",
            listOf(
                k.v("kv"),
                k.tuple(listOf(structRead(k), keys)),
                k.fn(
                    listOf(k.tuple(listOf(k.v("key"), k.v("val"))), k.tuple(listOf(k.v("map"), k.v("keys")))),
                    k.tuple(listOf(replaced, deleted)),
                ),
            ),
        )
        val body = k.block(
            k.assign(k.tuple(listOf(k.v("map"), k.v("keys"))), reduce),
            s.case(
                k.v("keys"),
                listOf(
                    s.arrow(listOf(s.list()), k.v("map")),
                    s.arrow(listOf(k.v("_")), missingKeys(k)),
                ),
            ),
        )

        return k.imported("def", listOf(k.local("__struct__", listOf(k.v("kv"))), s.keywords(listOf("do" to body))), BOOT_DEF)
    }

    /** `raise ArgumentError, "the following keys must also be given when building struct #{inspect(__MODULE__)}: ..."`. */
    private fun missingKeys(q: Nodes): ElixirAst =
        raise(
            q,
            "the following keys must also be given when building ",
            "struct ",
            q.inspect(remote = false),
            ": ",
            q.inspect(remote = false, expression = q.v("keys")),
        )

    /** `raise ArgumentError, head <> "#{...}..."`: the parts after [head] are text and interpolated expressions, in turn. */
    private fun raise(q: Nodes, head: String, vararg parts: Any): ElixirAst =
        q.imported(
            "raise",
            listOf(kernelAlias(s, "ArgumentError"), q.concat(q.text(head), q.binary(*parts))),
            listOf(1 to KERNEL, 2 to KERNEL),
        )

    /**
     * The definitions the output queued, [queued], with the placeholders of `__struct__/0` and `__struct__/1` filled
     * from what `Kernel.Utils.defstruct` gave: [entries], the fields in order, and the [enforced] keys.
     */
    fun definitions(
        queued: List<Pending.Definition>,
        enforced: List<String>?,
        entries: List<Pair<String, ElixirAst>>?,
    ): List<Pending> {
        if (!bound) return listOf(queued[0], queued[if (enforced.isNullOrEmpty()) 1 else 2])

        if (enforced == null || entries == null) return queued

        val clause = bodyOf(enforced, entries)
        val fragments = mapOf("kv" to plainUtils.v("kv"), "body" to clause, "escaped_struct" to struct(entries))

        return queued.map { filled(it, fragments) }
    }

    /** `Kernel.Utils.defstruct`'s `body`: what `__struct__/1` does with its keyword list. */
    private fun bodyOf(enforced: List<String>, entries: List<Pair<String, ElixirAst>>): ElixirAst {
        val u = utils
        val initial = if (escaped) struct(entries) else structReadUtils()
        val update = u.call("%{}", listOf(u.call("|", listOf(u.v("map"), s.list(u.tuple(listOf(u.v("key"), u.v("val"))))))))

        if (enforced.isEmpty()) {
            return u.remote(
                kernelAlias(s, "Enum"),
                "reduce",
                listOf(u.v("kv"), initial, u.fn(listOf(u.tuple(listOf(u.v("key"), u.v("val"))), u.v("map")), update)),
            )
        }

        val keys = s.list(enforced.map(s::atom))
        val deleted = u.remote(kernelAlias(s, "List"), "delete", listOf(u.v("keys"), u.v("key")))
        val reduce = u.remote(
            kernelAlias(s, "Enum"),
            "reduce",
            listOf(
                u.v("kv"),
                u.tuple(listOf(initial, keys)),
                u.fn(
                    listOf(u.tuple(listOf(u.v("key"), u.v("val"))), u.tuple(listOf(u.v("map"), u.v("keys")))),
                    u.tuple(listOf(update, deleted)),
                ),
            ),
        )

        return u.block(
            u.assign(u.tuple(listOf(u.v("map"), u.v("keys"))), reduce),
            u.case(u.v("keys"), listOf(u.arrow(listOf(s.list()), u.v("map")), u.arrow(listOf(u.v("_")), missingKeys(u)))),
        )
    }

    private fun structReadUtils(): ElixirAst = utils.imported("@", listOf(utils.localVariable("__struct__")), listOf(1 to KERNEL))

    private fun struct(entries: List<Pair<String, ElixirAst>>): ElixirAst {
        val defaults = LinkedHashMap<String, ElixirAst>()

        defaults["__struct__"] = s.atom(module)
        for ((key, value) in entries) defaults[key] = value

        return s.escapedMap(defaults.entries.map { it.key to it.value })
    }

    /** [definition] with each `unquote` of a name in [fragments] replaced by that name's AST. */
    private fun filled(definition: Pending.Definition, fragments: Map<String, ElixirAst>): Pending.Definition {
        val replacements = IdentityHashMap<ElixirAst, ElixirAst>()

        fun collect(node: ElixirAst) {
            val name = (node as? ElixirAst.Call)?.takeIf { isCall(it, "unquote", 1) }
                ?.let { ((it.arguments!!.single() as? ElixirAst.Call)?.callee as? ElixirAst.Literal.Atom)?.name }

            if (name != null && name in fragments) replacements[node] = fragments.getValue(name) else children(node).forEach(::collect)
        }

        collect(definition.head)
        definition.body?.let(::collect)

        return Pending.Definition(
            definition.kind,
            definition.node,
            substitute(definition.head, replacements),
            definition.body?.let { substitute(it, replacements) },
            definition.unnamedAt,
            stop = null,
            definition.env,
            definition.ordered,
            definition.statement,
            definition.checksClauses,
        )
    }

}

/** The `elixir_bootstrap` imports a quoted `def` and `@` are marked with. */
internal val BOOT_DEF = listOf(1 to BOOTSTRAP, 2 to BOOTSTRAP)
internal val BOOT_AT = listOf(1 to BOOTSTRAP)

/** A `quote`d expression's nodes in [context], with the keys of a `quote` that is [generated] where it is. */
internal class Nodes(
    val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val context: String,
    generated: Boolean,
) {
    val base: List<Meta.Key> =
        if (generated) GENERATED + Meta.Key.Entry("line", Meta.Value.Integer(0)) else emptyList()

    fun keys(more: List<Meta.Key>) = base + more

    /** `{Name, Keys, Context}`. */
    fun v(name: String) = s.variable(name, context, base)

    /** `struct`, which `quote` marks with the imports of `Kernel`'s `struct/1,2` from [QUOTE_IMPORTS_EVERY_ARITY]. */
    fun structVariable(): ElixirAst =
        s.variable("struct", context, if (QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level)) kernelImportKeys(level, 1, 2) else base)

    /** `{Name, [context: Context], Context}`: the name of an attribute read, or a call, with no arguments. */
    fun localVariable(name: String) = s.variable(name, context, base + entry("context", context))

    /** `{Name, [context: Context], Args}`. */
    fun local(name: String, args: List<ElixirAst>) = s.call(name, args, base + entry("context", context))

    /** `{Name, [context: Context, import: Module], Args}`, `imports:` with each arity from [QUOTE_IMPORTS_EVERY_ARITY]. */
    fun imported(name: String, args: List<ElixirAst>, imports: List<Pair<Int, String>>) =
        s.call(name, args, base + listOf(entry("context", context), importKey(level, imports)))

    /** `Elixir.Kernel.@(argument)`, or before [ATTRIBUTES_EXPANDED_LAZILY] the `@` that `elixir_bootstrap` gives `Kernel` while it compiles. */
    fun kernelAt(argument: ElixirAst): ElixirAst =
        if (ATTRIBUTES_EXPANDED_LAZILY.isSufficient(level)) {
            remote(ElixirAst.Alias(s.meta(), listOf(s.atom("Elixir"), s.atom("Kernel"))), "@", listOf(argument))
        } else {
            imported("@", listOf(argument), BOOT_AT)
        }

    fun call(name: String, args: List<ElixirAst>) = s.call(name, args, base)

    /** The head of a `def` as `quote` annotates it: `context:` on the node [Quote.annotatedDefinitionHead] names. */
    fun annotatedHead(head: ElixirAst.Call): ElixirAst = withContext(head, Quote.annotatedDefinitionHead(head, level))

    private fun withContext(node: ElixirAst, target: ElixirAst?): ElixirAst {
        val call = node as? ElixirAst.Call ?: return node
        val arguments = call.arguments!!

        return when {
            call === target -> ElixirAst.Call(s.meta(call.meta.keys + entry("context", context)), call.callee, arguments)
            target != null -> ElixirAst.Call(call.meta, call.callee, listOf(withContext(arguments[0], target)) + arguments.drop(1))
            else -> node
        }
    }

    fun remote(receiver: ElixirAst, function: String, args: List<ElixirAst>) = s.remoteCall(receiver, function, args, base)

    fun assign(left: ElixirAst, right: ElixirAst) = s.call("=", listOf(left, right), base)

    fun block(vararg expressions: ElixirAst) = ElixirAst.Block(s.meta(base), expressions.toList())

    fun tuple(elements: List<ElixirAst>): ElixirAst = if (elements.size == 2) s.tuple(elements[0], elements[1]) else s.tuple(elements, base)

    fun fn(patterns: List<ElixirAst>, body: ElixirAst) = s.call("fn", listOf(s.arrow(patterns, body, base)), base)

    fun case(subject: ElixirAst, clauses: List<ElixirAst>) = s.case(subject, clauses, base)

    fun arrow(patterns: List<ElixirAst>, body: ElixirAst) = s.arrow(patterns, body, base)

    fun text(value: String) = ElixirAst.Literal.Binary(s.meta(), value.toByteArray())

    /** `<<...>>` of [parts], each text or an expression to interpolate. */
    fun binary(vararg parts: Any): ElixirAst =
        call("<<>>", parts.map { if (it is String) text(it) else interpolated(it as ElixirAst) })

    /** `left <> right`. */
    fun concat(left: ElixirAst, right: ElixirAst): ElixirAst = imported("<>", listOf(left, right), listOf(2 to KERNEL))

    /** `inspect(__MODULE__)`, or [expression]: as `Kernel.inspect/1` the macro calls, or the import the function does. */
    fun inspect(remote: Boolean, expression: ElixirAst = v("__MODULE__")): ElixirAst =
        if (remote) {
            remote(kernelAlias(s, "Kernel"), "inspect", listOf(expression))
        } else {
            imported("inspect", listOf(expression), listOf(1 to KERNEL, 2 to KERNEL))
        }

    /** `#{expression}`. */
    fun interpolated(expression: ElixirAst): ElixirAst {
        val interpolation = if (FROM_INTERPOLATION.isSufficient(level)) listOf(entry("from_interpolation", "true")) else emptyList()

        return call(
            "::",
            listOf(s.remoteCall(s.atom(KERNEL), "to_string", listOf(expression), base + interpolation), v("binary")),
        )
    }
}
