package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.DEFDELEGATE_ALL_EACH
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFDELEGATE_HEAD_BUILT_BY_HAND
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * The output of `Kernel.defdelegate/2` for [funs], the heads escaped with their unquotes, and [opts], as the release has
 * it. From [DEFDELEGATE_ALL_EACH] `Kernel.Utils` checks the options and takes each head apart; before it the output holds
 * the checks and the warnings, and `Kernel.Utils.defdelegate/2` takes each head apart.
 */
internal class DelegateOutput(
    private val s: Synthetic,
    private val level: ElixirLanguageLevel,
    private val funs: ElixirAst,
    private val opts: ElixirAst,
) {
    private val k = Nodes(s, level, KERNEL, generated = false)
    private val utils = kernelAlias(s, "Kernel", "Utils")

    fun output(): ElixirAst =
        k.block(
            k.assign(k.v("funs"), funs),
            k.assign(k.v("opts"), opts),
            if (DEFDELEGATE_ALL_EACH.isSufficient(level)) {
                k.block(
                    k.assign(k.v("target"), k.remote(utils, "defdelegate_all", listOf(k.v("funs"), k.v("opts"), k.v("__ENV__")))),
                    each("defdelegate_each"),
                )
            } else {
                k.block(target(), warnOfList(), warnOfAppendFirst(), each("defdelegate"))
            },
        )

    /** `Keyword.get(opts, :to) || raise ArgumentError, "expected to: to be given as argument"`. */
    private fun target(): ElixirAst {
        val get = k.remote(kernelAlias(s, "Keyword"), "get", listOf(k.v("opts"), s.atom("to")))
        val raise = k.imported(
            "raise",
            listOf(kernelAlias(s, "ArgumentError"), k.text("expected to: to be given as argument")),
            listOf(1 to KERNEL, 2 to KERNEL),
        )

        return k.assign(k.v("target"), k.imported("||", listOf(get, raise), listOf(2 to KERNEL)))
    }

    private fun warnOfList(): ElixirAst =
        warnIf(
            k.imported("is_list", listOf(k.v("funs")), listOf(1 to KERNEL)),
            "passing a list to Kernel.defdelegate/2 is deprecated, please define each delegate separately",
        )

    private fun warnOfAppendFirst(): ElixirAst =
        warnIf(
            k.remote(kernelAlias(s, "Keyword"), "has_key?", listOf(k.v("opts"), s.atom("append_first"))),
            "Kernel.defdelegate/2 :append_first option is deprecated",
        )

    /** `if condition do IO.warn(message, Macro.Env.stacktrace(__ENV__)) end`. */
    private fun warnIf(condition: ElixirAst, message: String): ElixirAst {
        val stacktrace = k.remote(kernelAlias(s, "Macro", "Env"), "stacktrace", listOf(k.v("__ENV__")))
        val warn = k.remote(kernelAlias(s, "IO"), "warn", listOf(k.text(message), stacktrace))

        return k.imported("if", listOf(condition, s.keywords(listOf("do" to warn))), listOf(2 to KERNEL))
    }

    /** `for fun <- List.wrap(funs) do ... end`, which defines a delegate for each head. */
    private fun each(take: String): ElixirAst {
        val wrapped = k.remote(kernelAlias(s, "List"), "wrap", listOf(k.v("funs")))
        val parts = k.tuple(listOf(k.v("name"), k.v("args"), k.v("as"), k.v("as_args")))
        val taken = k.assign(parts, k.remote(utils, take, listOf(k.v("fun"), k.v("opts"))))
        val length = k.remote(s.atom("erlang"), "length", listOf(k.v("as_args")))
        val delegateTo = k.tuple(listOf(k.v("target"), k.v("as"), length))
        val doc = k.kernelAt(k.local("doc", listOf(s.keywords(listOf("delegate_to" to delegateTo)))))
        val loop = s.keywords(listOf("do" to k.block(taken, doc, definition())))

        return k.call("for", listOf(k.call("<-", listOf(k.v("fun"), wrapped)), loop))
    }

    /**
     * `def unquote({name, [line: __ENV__.line], args}), do: unquote(target).unquote(as)(unquote_splicing(as_args))`, and
     * before [DEFDELEGATE_HEAD_BUILT_BY_HAND] `def unquote(name)(unquote_splicing(args)), do: ...`.
     */
    private fun definition(): ElixirAst {
        val line = ElixirAst.Call(
            s.meta(k.base + entry("no_parens", "true")),
            ElixirAst.Call(s.meta(k.base), s.atom("."), listOf(k.v("__ENV__"), s.atom("line"))),
            emptyList(),
        )
        val head = if (DEFDELEGATE_HEAD_BUILT_BY_HAND.isSufficient(level)) {
            k.local("unquote", listOf(k.tuple(listOf(k.v("name"), s.keywords(listOf("line" to line)), k.v("args")))))
        } else {
            ElixirAst.Call(
                s.meta(k.base),
                k.local("unquote", listOf(k.v("name"))),
                listOf(k.local("unquote_splicing", listOf(k.v("args")))),
            )
        }
        val callee = k.remote(k.call("unquote", listOf(k.v("target"))), "unquote", listOf(k.v("as")))
        val body = ElixirAst.Call(s.meta(), callee, listOf(k.call("unquote_splicing", listOf(k.v("as_args")))))

        return k.imported("def", listOf(head, s.keywords(listOf("do" to body))), BOOT_DEF)
    }
}
