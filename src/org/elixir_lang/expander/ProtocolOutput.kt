package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_CHECKED_BY_IMPL_BANG
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_EXPANDS_PROTOCOL
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_IMPL4
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_MODULEDOC_FALSE
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_TARGET_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_ANY_CONCAT
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BEFORE_COMPILE
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BEHAVIOUR
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BLOCK_RESULT
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_CONCAT
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_DISPATCHES_BY_IMPL_TARGET
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_EXCLUDES_DEFDELEGATE
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_EXCLUDES_GUARDS
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_EXCLUDES_PRIVATE_AND_MACROS
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_FUNCTIONS_SPEC_COMPUTED
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_METADATA_ATTRIBUTES
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_TYPE_DOC
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_TYPE_UNLESS
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_UNDEFINED_IMPL_DESCRIPTION
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_UNDEFINED_IMPL_DESCRIPTION_DEFAULT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger

internal const val PROTOCOL = "Elixir.Protocol"

/** The types `defprotocol` gives an `impl_for/1` clause each, with the guard that tells them. */
internal val BUILT_IN = listOf(
    "Tuple" to "is_tuple",
    "Atom" to "is_atom",
    "List" to "is_list",
    "Map" to "is_map",
    "BitString" to "is_bitstring",
    "Integer" to "is_integer",
    "Float" to "is_float",
    "Function" to "is_function",
    "PID" to "is_pid",
    "Port" to "is_port",
    "Reference" to "is_reference",
)

/** The attribute `defprotocol` keeps the functions declared in. */
internal fun functionsAttribute(level: ElixirLanguageLevel): String =
    if (PROTOCOL_BEFORE_COMPILE.isSufficient(level)) "__functions__" else "functions"

/** `Kernel.@/1` of a call to the attribute [name], as a `quote` in `Protocol` writes it. */
private fun Nodes.at(name: String, vararg args: ElixirAst): ElixirAst =
    imported("@", listOf(local(name, args.toList())), listOf(1 to KERNEL))

/** `@name`. */
private fun Nodes.read(name: String): ElixirAst = imported("@", listOf(localVariable(name)), listOf(1 to KERNEL))

/** `Kernel.def` or `Kernel.defp` of [args]. */
private fun Nodes.kernelDef(kind: String, vararg args: ElixirAst): ElixirAst = remote(alias("Kernel"), kind, args.toList())

private fun Nodes.module(name: String) = alias(name)

/**
 * The output of `Protocol.__protocol__/2`, which `defprotocol` returns: the module with the attributes and imports that
 * make it a protocol, the [block] it was given, and what `after_defprotocol/0` defines after it (`P:801–826`,
 * `P:928–1041`).
 */
internal class ProtocolOutput(
    private val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val name: ElixirAst,
    private val block: ElixirAst,
) {
    private val p = Nodes(s, level, PROTOCOL, generated = false)
    private val g = Nodes(s, level, PROTOCOL, generated = true, line = false)
    private val functions = functionsAttribute(level)
    private val one get() = s.integer(BigInteger.ONE)

    private fun has(feature: ElixirLanguageFeature) = feature.isSufficient(level)

    fun output(): ElixirAst {
        val result = has(PROTOCOL_BLOCK_RESULT)
        val body = p.block(
            listOfNotNull(
                p.at("behaviour", p.module("Protocol")).takeIf { has(PROTOCOL_BEHAVIOUR) },
                p.at("before_compile", p.module("Protocol")).takeIf { has(PROTOCOL_BEFORE_COMPILE) },
                p.local("import", listOf(p.module("Kernel"), s.keywords(listOf("except" to s.list(excluded()))))),
                p.local("import", listOf(p.module("Protocol"), s.keywords(listOf("only" to s.list(arity("def", 1)))))),
                p.at("compile", s.atom("debug_info")),
                p.at(functions, s.list()),
                p.at("fallback_to_any", s.atom("false")),
                p.at("undefined_impl_description", p.text("")).takeIf { has(PROTOCOL_UNDEFINED_IMPL_DESCRIPTION_DEFAULT) },
                p.assign(p.v(if (result) "res" else "_"), block),
                afterDefprotocol(),
                p.v("res").takeIf { result },
            ),
        )

        return p.imported("defmodule", listOf(name, s.keywords(listOf("do" to body))), listOf(2 to KERNEL))
    }

    /** The functions and macros of `Kernel` the protocol imports without: `def`, and those the entries name. */
    private fun excluded(): List<ElixirAst> {
        val privateAndMacros = has(PROTOCOL_EXCLUDES_PRIVATE_AND_MACROS)
        val delegate = has(PROTOCOL_EXCLUDES_DEFDELEGATE)
        val guards = has(PROTOCOL_EXCLUDES_GUARDS)

        return listOf(
            Triple("def", 1, true),
            Triple("def", 2, true),
            Triple("defp", 1, privateAndMacros),
            Triple("defp", 2, privateAndMacros),
            Triple("defdelegate", 2, delegate),
            Triple("defguard", 1, guards),
            Triple("defguardp", 1, guards),
            Triple("defmacro", 1, privateAndMacros),
            Triple("defmacro", 2, privateAndMacros),
            Triple("defmacrop", 1, privateAndMacros),
            Triple("defmacrop", 2, privateAndMacros),
        ).filter { it.third }.map { (function, count, _) -> arity(function, count) }
    }

    /** `{name, arity}`, as the keyword pair `name: arity` is. */
    private fun arity(name: String, count: Int): ElixirAst = s.tuple(s.atom(name), s.integer(BigInteger.valueOf(count.toLong())))

    /**
     * One `quote bind_quoted: [built_in: built_in()]` up to [PROTOCOL_UNDEFINED_IMPL_DESCRIPTION_DEFAULT], with
     * `impl_for!/1` after the clauses of the built-in types. From it that quote ends with `__protocol__/1`, and
     * `impl_for!/1` follows in a `quote generated: true` of its own.
     */
    private fun afterDefprotocol(): ElixirAst {
        val builtIn = s.list(BUILT_IN.map { (module, guard) -> s.tuple(s.atom("Elixir.$module"), s.atom(guard)) })

        fun bindQuoted(items: List<ElixirAst>) = p.block(p.assign(p.v("built_in"), builtIn), p.block(items))

        if (!has(PROTOCOL_UNDEFINED_IMPL_DESCRIPTION_DEFAULT)) {
            return bindQuoted(head() + fallback(p) + descriptionRead() + implForBang(p) + tail())
        }

        return g.block(listOf(bindQuoted(head() + tail())) + fallback(g) + implForBang(g))
    }

    /** `any_impl_for`, the attributes of the functions `impl_for/1` is made of, and its clauses up to the built-in types'. */
    private fun head(): List<ElixirAst> =
        listOf(
            p.assign(p.v("any_impl_for"), anyImplFor()),
            p.at(
                "dialyzer",
                s.tuple(s.atom("nowarn_function"), s.keywords(listOf("__protocol__" to one, "impl_for" to one, "impl_for!" to one))),
            ),
            p.at("doc", s.atom("false")),
            p.at("spec", p.call("::", listOf(p.call("impl_for", listOf(p.v("term"))), p.call("|", listOf(p.v("atom"), s.atom("nil")))))),
            p.kernelDef("def", p.local("impl_for", listOf(p.v("data")))),
            structClause(),
            builtIns(),
        )

    /** `struct_impl_for/1`, the default `t/0`, and what the protocol keeps of itself in `__protocol__/1`. */
    private fun tail(): List<ElixirAst> {
        val metadata = if (has(PROTOCOL_METADATA_ATTRIBUTES)) "__protocol__" else "protocol"

        return listOf(
            structImplFor(),
            p.at("compile", s.tuple(s.atom("inline"), s.keywords(listOf("struct_impl_for" to one)))),
            typeT(),
            p.remote(
                p.module("Module"),
                "register_attribute",
                listOf(p.v("__MODULE__"), s.atom(metadata), s.keywords(listOf("persist" to s.atom("true")))),
            ),
            p.at(
                metadata,
                s.keywords(
                    listOf(
                        "fallback_to_any" to p.imported(
                            "!",
                            listOf(p.imported("!", listOf(p.read("fallback_to_any")), listOf(1 to KERNEL))),
                            listOf(1 to KERNEL),
                        ),
                    ),
                ),
            ),
            p.at("doc", s.atom("false")),
            p.at("spec", p.call("::", listOf(p.call("__protocol__", listOf(s.atom("module"))), p.v("__MODULE__")))),
            p.at("spec", p.call("::", listOf(p.call("__protocol__", listOf(s.atom("functions"))), functionsSpec()))),
            p.at("spec", p.call("::", listOf(p.call("__protocol__", listOf(s.atom("consolidated?"))), typeOf("boolean")))),
            p.at(
                "spec",
                p.call(
                    "::",
                    listOf(
                        p.call("__protocol__", listOf(s.atom("impls"))),
                        p.call("|", listOf(s.atom("not_consolidated"), s.tuple(s.atom("consolidated"), s.list(typeOf("module"))))),
                    ),
                ),
            ),
            p.kernelDef("def", p.local("__protocol__", listOf(s.atom("module"))), s.keywords(listOf("do" to p.v("__MODULE__")))),
            p.kernelDef(
                "def",
                p.local("__protocol__", listOf(s.atom("functions"))),
                s.keywords(listOf("do" to p.call("unquote", listOf(p.remote(s.atom("lists"), "sort", listOf(p.read(functions))))))),
            ),
            p.kernelDef("def", p.local("__protocol__", listOf(s.atom("consolidated?"))), s.keywords(listOf("do" to s.atom("false")))),
            p.kernelDef("def", p.local("__protocol__", listOf(s.atom("impls"))), s.keywords(listOf("do" to s.atom("not_consolidated")))),
        )
    }

    /**
     * `if @fallback_to_any do <the Any implementation> else nil end`: `Protocol.__concat__(__MODULE__, "Any")`, the alias
     * `__MODULE__.Any` (which v1.16 and v1.17 wrap in `quote do: unquote(...)`, giving the same atom), or before
     * [PROTOCOL_DISPATCHES_BY_IMPL_TARGET] the quoted call of its `__impl__(:target)`.
     */
    private fun anyImplFor(): ElixirAst {
        val any = when {
            has(PROTOCOL_ANY_CONCAT) -> p.remote(p.module("Protocol"), "__concat__", listOf(p.v("__MODULE__"), p.text("Any")))
            has(PROTOCOL_DISPATCHES_BY_IMPL_TARGET) ->
                p.call("quote", listOf(s.keywords(listOf("do" to implTarget(p.call("unquote", listOf(p.nestedAlias("Any"))))))))
            else -> p.nestedAlias("Any")
        }

        return p.imported("if", listOf(p.read("fallback_to_any"), s.keywords(listOf("do" to any, "else" to s.atom("nil")))), listOf(2 to KERNEL))
    }

    /** `receiver.__impl__(:target)`. */
    private fun implTarget(receiver: ElixirAst): ElixirAst = p.remote(receiver, "__impl__", listOf(s.atom("target")))

    /** [typeName] as the specs of `__protocol__/1` spell it: bare before [PROTOCOL_FUNCTIONS_SPEC_COMPUTED] went, else with parentheses. */
    private fun typeOf(typeName: String): ElixirAst =
        if (has(PROTOCOL_FUNCTIONS_SPEC_COMPUTED)) p.v(typeName) else p.call(typeName, emptyList())

    /** The type of `__protocol__(:functions)`: computed from the functions declared before v1.18. */
    private fun functionsSpec(): ElixirAst =
        if (has(PROTOCOL_FUNCTIONS_SPEC_COMPUTED)) {
            p.call("unquote", listOf(p.remote(p.module("Protocol"), "__functions_spec__", listOf(p.read(functions)))))
        } else {
            s.list(s.tuple(p.call("atom", emptyList()), p.call("arity", emptyList())))
        }

    /** `def impl_for(%struct{})`, which delegates to `struct_impl_for/1`. */
    private fun structClause(): ElixirAst {
        val struct = p.structVariable()
        val head = p.local("impl_for", listOf(p.call("%", listOf(struct, p.call("%{}", emptyList())))))

        return p.kernelDef("def", head, s.keywords(listOf("do" to p.call("struct_impl_for", listOf(struct)))))
    }

    /** `:lists.foreach(fn {mod, guard} -> ... end, built_in)`, which defines the clause of each built-in type. */
    private fun builtIns(): ElixirAst {
        val guard = ElixirAst.Call(
            s.meta(p.base),
            p.remote(s.atom("erlang"), "unquote", listOf(p.v("guard"))),
            listOf(p.v("data")),
        )
        val head = p.call("when", listOf(p.local("impl_for", listOf(p.v("data"))), guard))
        val body = p.block(
            p.assign(p.v("target"), concat(p.v("mod"))),
            p.kernelDef("def", head, s.keywords(listOf("do" to lookup(p.call("unquote", listOf(p.v("target"))))))),
        )

        return p.remote(
            s.atom("lists"),
            "foreach",
            listOf(p.fn(listOf(p.tuple(listOf(p.v("mod"), p.v("guard")))), body), p.v("built_in")),
        )
    }

    /** `Protocol.__concat__/2`, or `Module.concat/2` before [PROTOCOL_CONCAT], of the protocol and [type]. */
    private fun concat(type: ElixirAst): ElixirAst =
        if (has(PROTOCOL_CONCAT)) {
            p.remote(p.module("Protocol"), "__concat__", listOf(p.v("__MODULE__"), type))
        } else {
            p.remote(p.module("Module"), "concat", listOf(p.v("__MODULE__"), type))
        }

    /**
     * The module [implementation] names if it is one, else the `Any` implementation: a `case` of
     * `Code.ensure_compiled/1`, or before [PROTOCOL_DISPATCHES_BY_IMPL_TARGET] a `try` of its `__impl__(:target)`.
     */
    private fun lookup(implementation: ElixirAst): ElixirAst {
        val any = p.call("unquote", listOf(p.v("any_impl_for")))

        return if (has(PROTOCOL_DISPATCHES_BY_IMPL_TARGET)) {
            p.call(
                "try",
                listOf(
                    s.keywords(
                        listOf(
                            "do" to implTarget(implementation),
                            "rescue" to s.list(p.arrow(listOf(p.alias("UndefinedFunctionError")), any)),
                        ),
                    ),
                ),
            )
        } else {
            p.case(
                p.remote(p.module("Code"), "ensure_compiled", listOf(implementation)),
                listOf(
                    p.arrow(listOf(p.tuple(listOf(s.atom("module"), p.v("module")))), p.v("module")),
                    p.arrow(listOf(p.tuple(listOf(s.atom("error"), p.v("_")))), any),
                ),
            )
        }
    }

    /** `defp struct_impl_for(struct)`, the implementation of a struct. */
    private fun structImplFor(): ElixirAst {
        val struct = p.structVariable()
        val body = if (has(PROTOCOL_DISPATCHES_BY_IMPL_TARGET)) {
            p.block(p.assign(p.v("target"), concat(struct)), lookup(p.v("target")))
        } else {
            lookup(concat(struct))
        }

        return p.kernelDef("defp", p.local("struct_impl_for", listOf(struct)), s.keywords(listOf("do" to body)))
    }

    /** `if not Module.defines_type?(__MODULE__, {:t, 0})` (`unless` before 1.18): the default `t/0`. */
    private fun typeT(): ElixirAst {
        val defined = p.remote(p.module("Module"), "defines_type?", listOf(p.v("__MODULE__"), s.tuple(s.atom("t"), s.integer(BigInteger.ZERO))))
        val type = p.at("type", p.call("::", listOf(p.v("t"), p.v("term"))))
        val declare = if (has(PROTOCOL_TYPE_DOC)) {
            p.block(p.at("typedoc", p.text("All the types that implement this protocol.\n")), type)
        } else {
            type
        }

        return if (has(PROTOCOL_TYPE_UNLESS)) {
            p.imported("unless", listOf(defined, s.keywords(listOf("do" to declare))), listOf(2 to KERNEL))
        } else {
            p.imported(
                "if",
                listOf(p.imported("not", listOf(defined), listOf(1 to KERNEL)), s.keywords(listOf("do" to declare))),
                listOf(2 to KERNEL),
            )
        }
    }

    /** `impl_for_fallback`: `def impl_for(_)`, which answers the `Any` implementation or `nil`. */
    private fun fallback(n: Nodes): List<ElixirAst> =
        listOf(n.kernelDef("def", n.local("impl_for", listOf(n.v("_"))), s.keywords(listOf("do" to n.call("unquote", listOf(n.v("any_impl_for")))))))

    /** `undefined_impl_description = Module.get_attribute(__MODULE__, :undefined_impl_description, "")`. */
    private fun descriptionRead(): List<ElixirAst> =
        if (has(PROTOCOL_UNDEFINED_IMPL_DESCRIPTION)) {
            listOf(
                p.assign(
                    p.v("undefined_impl_description"),
                    p.remote(
                        p.module("Module"),
                        "get_attribute",
                        listOf(p.v("__MODULE__"), s.atom("undefined_impl_description"), p.text("")),
                    ),
                ),
            )
        } else {
            emptyList()
        }

    /** `@doc false`, `@spec` and `if any_impl_for do def impl_for!(data) ... else ... end`, in the nodes of [n]. */
    private fun implForBang(n: Nodes): List<ElixirAst> {
        val data = n.v("data")
        val description = when {
            has(PROTOCOL_UNDEFINED_IMPL_DESCRIPTION_DEFAULT) -> listOf("description" to p.read("undefined_impl_description"))
            has(PROTOCOL_UNDEFINED_IMPL_DESCRIPTION) ->
                listOf("description" to p.call("unquote", listOf(p.v("undefined_impl_description"))))
            else -> emptyList()
        }
        val raise = p.imported(
            "raise",
            listOf(
                p.alias("Protocol", "UndefinedError"),
                s.keywords(listOf("protocol" to p.v("__MODULE__"), "value" to p.v("data")) + description),
            ),
            listOf(1 to KERNEL, 2 to KERNEL),
        )
        val delegating = n.kernelDef("def", n.local("impl_for!", listOf(data)), s.keywords(listOf("do" to n.call("impl_for", listOf(data)))))
        val raising = n.kernelDef(
            "def",
            n.local("impl_for!", listOf(data)),
            s.keywords(listOf("do" to n.imported("||", listOf(n.call("impl_for", listOf(data)), raise), listOf(2 to KERNEL)))),
        )

        return listOf(
            n.at("doc", s.atom("false")),
            n.at("spec", n.call("::", listOf(n.call("impl_for!", listOf(n.v("term"))), n.v("atom")))),
            n.imported("if", listOf(n.v("any_impl_for"), s.keywords(listOf("do" to delegating, "else" to raising))), listOf(2 to KERNEL)),
        )
    }
}

/**
 * The output of `Protocol.def/1` for the function [name] with the written [arguments] (`P:279–315`): the head, kept for
 * the docs, the definition that dispatches on the first argument, and the callback.
 */
internal class ProtocolDefOutput(
    private val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val name: String,
    private val arguments: List<ElixirAst>,
) {
    private val p = Nodes(s, level, PROTOCOL, generated = false)
    private val g = Nodes(s, level, PROTOCOL, generated = true, line = false)

    fun output(): ElixirAst {
        val arity = arguments.size
        val callArguments = listOf(p.v("term")) + (2..arity).map { p.v("arg$it") }
        val typeArguments = listOf(p.v("t")) + (2..arity).map { p.v("term") }
        val functions = functionsAttribute(level)

        val callback = g.imported(
            "||",
            listOf(
                g.remote(g.module("Module"), "spec_to_callback", listOf(g.v("__MODULE__"), g.tuple(listOf(g.v("name"), g.v("arity"))))),
                g.at("callback", g.call("::", listOf(g.call(name, typeArguments), g.v("term")))),
            ),
            listOf(2 to KERNEL),
        )

        return g.block(
            g.assign(g.v("name"), s.atom(name)),
            g.assign(g.v("arity"), s.integer(BigInteger.valueOf(arity.toLong()))),
            g.at(functions, s.list(g.call("|", listOf(g.tuple(listOf(g.v("name"), g.v("arity"))), g.read(functions))))),
            g.kernelDef("def", g.local(name, arguments)),
            g.kernelDef(
                "def",
                g.local(name, callArguments),
                s.keywords(listOf("do" to g.remote(g.call("impl_for!", listOf(g.v("term"))), name, callArguments))),
            ),
            callback,
        )
    }
}

/**
 * The output of `Protocol.__impl__/3` for one `for` (`P:1068–1113`): the module [type] names, a `defmodule` that keeps
 * the [protocol] and the type in attributes, runs the [block] and defines `__impl__/1`.
 */
internal class ImplOutput(
    private val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val protocol: ElixirAst,
    val type: ElixirAst,
    private val block: ElixirAst,
) {
    private val p = Nodes(s, level, PROTOCOL, generated = false)

    /** The variable `defmodule` is given, whose value the macro computes when the body runs. */
    val name: ElixirAst = p.v("name")

    private fun has(feature: ElixirLanguageFeature) = feature.isSufficient(level)

    /** The `protocol` that `@behaviour` is given before [DEFIMPL_EXPANDS_PROTOCOL], whose value the macro knows. */
    val behaviourVariable: ElixirAst? =
        p.v("protocol").takeUnless { has(DEFIMPL_EXPANDS_PROTOCOL) && protocol is ElixirAst.Literal.Atom }

    fun output(): ElixirAst {
        val protocolModule = p.module("Protocol")
        val check = if (has(DEFIMPL_CHECKED_BY_IMPL_BANG)) "__impl__!" else "__ensure_defimpl__"
        val concat = if (has(PROTOCOL_CONCAT)) {
            p.remote(protocolModule, "__concat__", listOf(p.v("protocol"), p.v("for")))
        } else {
            p.remote(p.module("Module"), "concat", listOf(p.v("protocol"), p.v("for")))
        }

        return p.block(
            p.assign(p.v("protocol"), protocol),
            p.assign(p.v("for"), type),
            p.assign(p.v("name"), concat),
            p.remote(protocolModule, "assert_protocol!", listOf(p.v("protocol"))),
            p.remote(protocolModule, check, listOf(p.v("protocol"), p.v("for"), p.v("__ENV__"))),
            p.imported("defmodule", listOf(name, s.keywords(listOf("do" to body()))), listOf(2 to KERNEL)),
        )
    }

    private fun body(): ElixirAst {
        val result = has(DEFIMPL_IMPL4)
        val attribute = if (has(PROTOCOL_METADATA_ATTRIBUTES)) "__impl__" else "protocol_impl"

        return p.block(
            listOfNotNull(
                p.at("moduledoc", s.atom("false")).takeIf { has(DEFIMPL_MODULEDOC_FALSE) },
                p.at("behaviour", behaviourVariable ?: p.local("require", listOf(protocol))),
                p.at("protocol", p.v("protocol")),
                p.at("for", p.v("for")),
                if (result) p.assign(p.v("res"), block) else block,
                p.remote(
                    p.module("Module"),
                    "register_attribute",
                    listOf(p.v("__MODULE__"), s.atom(attribute), s.keywords(listOf("persist" to s.atom("true")))),
                ),
                p.at(attribute, s.keywords(listOf("protocol" to p.read("protocol"), "for" to p.read("for")))),
                implementation(),
                p.v("res").takeIf { result },
            ),
        )
    }

    /** `quote unquote: false`'s `__impl__/1`, whose unquotes run in the module body, where the variables are. */
    private fun implementation(): ElixirAst {
        val keys = when {
            !has(PROTOCOL_DISPATCHES_BY_IMPL_TARGET) -> listOf("for", "protocol")
            has(DEFIMPL_TARGET_FIRST) -> listOf("target", "for", "protocol")
            else -> listOf("for", "target", "protocol")
        }

        return p.block(listOf(p.at("doc", s.atom("false"))) + keys.map(::spec) + keys.map(::function))
    }

    /** `@spec __impl__(:key) :: unquote(key)`, `__MODULE__` for the `:target` the implementation is the target of. */
    private fun spec(key: String): ElixirAst =
        p.at("spec", p.call("::", listOf(p.call("__impl__", listOf(s.atom(key))), value(key))))

    private fun function(key: String): ElixirAst =
        p.imported(
            "def",
            listOf(p.local("__impl__", listOf(s.atom(key))), s.keywords(listOf("do" to value(key)))),
            listOf(1 to KERNEL, 2 to KERNEL),
        )

    private fun value(key: String): ElixirAst = if (key == "target") p.v("__MODULE__") else p.call("unquote", listOf(p.v(key)))
}
