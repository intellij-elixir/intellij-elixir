package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.DEFEXCEPTION_STRUCT_BANG
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger

/**
 * The output of `Kernel.defexception/1` for [fields], as the release has it: the struct, `message/1` and a binary
 * `exception/1` where the struct has a `message` field, and `exception/1` for a list, which up to
 * [DEFEXCEPTION_STRUCT_BANG] checks the keys against the struct itself.
 */
internal class ExceptionOutput(private val s: Synthetic, private val level: ElixirLanguageLevel, private val fields: ElixirAst) {
    private val k = Nodes(s, level, KERNEL, generated = false)

    fun output(): ElixirAst =
        k.block(
            k.assign(k.v("fields"), fields),
            k.block(
                k.kernelAt(k.call("behaviour", listOf(kernelAlias(s, "Exception")))),
                k.assign(k.structVariable(), defstruct()),
                withMessage(),
                k.kernelAt(impl()),
                definition(guarded("args", "is_list"), listException()),
                overridable("exception"),
            ),
        )

    /** `defstruct([__exception__: true] ++ fields)`. */
    private fun defstruct(): ElixirAst {
        val exception = s.keywords(listOf("__exception__" to s.atom("true")))

        return k.imported("defstruct", listOf(k.imported("++", listOf(exception, k.v("fields")), listOf(2 to KERNEL))), listOf(1 to KERNEL))
    }

    /** `if Map.has_key?(struct, :message) do ... end`. */
    private fun withMessage(): ElixirAst {
        val has = k.remote(kernelAlias(s, "Map"), "has_key?", listOf(k.structVariable(), s.atom("message")))
        val body = k.block(
            k.kernelAt(impl()),
            definition(k.local("message", listOf(k.v("exception"))), exceptionMessage()),
            overridable("message"),
            k.kernelAt(impl()),
            definition(guarded("msg", "is_binary"), k.call("exception", listOf(s.keywords(listOf("message" to k.v("msg")))))),
        )

        return k.imported("if", listOf(has, s.keywords(listOf("do" to body))), listOf(2 to KERNEL))
    }

    /** `exception.message`, which Elixir marks `no_parens`. */
    private fun exceptionMessage(): ElixirAst {
        val access = ElixirAst.Call(s.meta(k.base), s.atom("."), listOf(k.v("exception"), s.atom("message")))

        return ElixirAst.Call(s.meta(k.base + entry("no_parens", "true")), access, emptyList())
    }

    private fun impl() = k.call("impl", listOf(s.atom("true")))

    private fun definition(head: ElixirAst, body: ElixirAst) =
        k.imported("def", listOf(head, s.keywords(listOf("do" to body))), BOOT_DEF)

    private fun overridable(name: String): ElixirAst =
        k.imported("defoverridable", listOf(s.keywords(listOf(name to s.integer(BigInteger.ONE)))), listOf(1 to KERNEL))

    /** `exception(argument) when Kernel.guard(argument)`, annotated as `quote` does the head of a `def`. */
    private fun guarded(argument: String, guard: String): ElixirAst {
        val check = k.remote(kernelAlias(s, "Kernel"), guard, listOf(k.v(argument)))

        return k.annotatedHead(k.call("when", listOf(k.call("exception", listOf(k.v(argument))), check)))
    }

    private fun listException(): ElixirAst =
        if (DEFEXCEPTION_STRUCT_BANG.isSufficient(level)) {
            k.imported("struct!", listOf(k.v("__MODULE__"), k.v("args")), listOf(1 to KERNEL, 2 to KERNEL))
        } else {
            checked()
        }

    /** The body up to [DEFEXCEPTION_STRUCT_BANG]: the keys that are not fields are warned of and left out. */
    private fun checked(): ElixirAst {
        val member = k.remote(kernelAlias(s, "Map"), "has_key?", listOf(k.structVariable(), k.v("k")))
        val split = k.remote(
            kernelAlias(s, "Enum"),
            "split_with",
            listOf(k.v("args"), k.fn(listOf(k.tuple(listOf(k.v("k"), k.v("_")))), member)),
        )
        val warn = k.remote(kernelAlias(s, "IO"), "warn", listOf(unknownFields()))

        return k.block(
            k.assign(k.structVariable(), k.call("__struct__", emptyList())),
            k.assign(k.tuple(listOf(k.v("valid"), k.v("invalid"))), split),
            k.case(k.v("invalid"), listOf(k.arrow(listOf(s.list()), s.atom("ok")), k.arrow(listOf(k.v("_")), warn))),
            k.remote(kernelAlias(s, "Kernel"), "struct!", listOf(k.structVariable(), k.v("valid"))),
        )
    }

    /** The warning's text: six literals joined with `<>`, two of them with interpolations. */
    private fun unknownFields(): ElixirAst {
        val invalid = k.inspect(remote = true, expression = k.v("invalid"))
        val redefine = k.concat(
            k.binary("or redefine ", k.inspect(remote = true), ".exception/1 to "),
            k.concat(
                k.text("discard unknown fields. Future Elixir versions will raise on "),
                k.text("unknown fields given to raise/2"),
            ),
        )

        return k.concat(
            k.text("the following fields are unknown when raising "),
            k.concat(
                k.binary(k.inspect(remote = true), ": ", invalid, ". "),
                k.concat(k.text("Please make sure to only give known fields when raising "), redefine),
            ),
        )
    }
}
