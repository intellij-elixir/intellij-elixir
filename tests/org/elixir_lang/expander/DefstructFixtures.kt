package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** [AttributeFixtures]' modules with the ones `defstruct`'s output and the bodies it defines call. */
internal object DefstructFixtures {
    val KERNEL = KernelImports(
        functions = AttributeFixtures.KERNEL.functions + nameArities("is_binary/1 is_list/1 struct!/2"),
        macros = AttributeFixtures.KERNEL.macros + nameArities("<>/2 in/2 raise/2"),
    )

    val EXPORTS = Exports { module ->
        when (module) {
            "Elixir.Kernel" -> ModuleExports.Present(KERNEL.functions, KERNEL.macros, hasInfo = true)
            "elixir_bootstrap" ->
                ModuleExports.Present(
                    emptyList(),
                    nameArities("@/1 def/1 def/2 defp/1 defp/2 defmacro/1 defmacro/2 defmacrop/1 defmacrop/2"),
                    hasInfo = true,
                )
            "Elixir.Kernel.Utils" ->
                ModuleExports.Present(
                    nameArities("announce_struct/1 defstruct/2 defstruct/3 defstruct/4"),
                    nameArities("defguard/2"),
                    hasInfo = true,
                )
            "Elixir.Protocol" -> ModuleExports.Present(nameArities("__derive__/3"), emptyList(), hasInfo = true)
            "Elixir.Enum" -> ModuleExports.Present(nameArities("reduce/3 split_with/2"), emptyList(), hasInfo = true)
            "Elixir.IO" -> ModuleExports.Present(nameArities("warn/1"), emptyList(), hasInfo = true)
            "Elixir.Map" ->
                ModuleExports.Present(nameArities("get/2 get/3 has_key?/2 replace!/3"), emptyList(), hasInfo = true)
            "Elixir.List" -> ModuleExports.Present(nameArities("delete/2 first/1 last/1"), emptyList(), hasInfo = true)
            "Elixir.ArgumentError" ->
                ModuleExports.Present(nameArities("exception/1 message/1"), emptyList(), hasInfo = true)
            else -> AttributeFixtures.EXPORTS.of(module)
        }
    }

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }
}
