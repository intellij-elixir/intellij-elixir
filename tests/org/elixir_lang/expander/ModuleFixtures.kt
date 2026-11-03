package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * [CallFixtures]' `Kernel` with the definers, `List`'s `first/1` and `last/1` for an import that conflicts, and the
 * modules whose calls a test leaves unknown, which Elixir always has, so a name read after such a call isn't one it
 * created.
 */
internal object ModuleFixtures {
    val KERNEL = KernelImports(
        functions = CallFixtures.KERNEL.functions,
        macros = CallFixtures.KERNEL.macros +
            nameArities(
                "def/1 def/2 defexception/1 defguard/1 defguardp/1 defmacro/1 defmacro/2 defmacrop/1 defmacrop/2 " +
                    "defmodule/2 defoverridable/1 defp/1 defp/2 defstruct/1"
            ),
    )

    val EXPORTS = Exports { module ->
        when (module) {
            "Elixir.Kernel" -> ModuleExports.Present(KERNEL.functions, KERNEL.macros, hasInfo = true)
            "Elixir.Module" -> ModuleExports.Present(nameArities("make_overridable/2"), emptyList(), hasInfo = true)
            "Elixir.Kernel.Utils" -> ModuleExports.Present(emptyList(), nameArities("defguard/2"), hasInfo = true)
            "Elixir.List" -> ModuleExports.Present(nameArities("first/1 last/1"), emptyList(), hasInfo = true)
            "Elixir.Application" -> ModuleExports.Present(nameArities("get_env/3"), emptyList(), hasInfo = true)
            "elixir_def" -> ModuleExports.Present(nameArities("take_definition/2"), emptyList(), hasInfo = false)
            else -> CallFixtures.EXPORTS.of(module)
        }
    }

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }
}
