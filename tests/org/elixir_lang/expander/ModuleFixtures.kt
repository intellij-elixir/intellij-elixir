package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** [CallFixtures]' `Kernel` with the definers, and `List`'s `first/1` and `last/1` for an import that conflicts. */
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
            else -> CallFixtures.EXPORTS.of(module)
        }
    }

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }
}
