package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** [ModuleFixtures]' `Kernel` with `@/1` and `alias!/1`, and the functions `@` builds calls of. */
internal object AttributeFixtures {
    val KERNEL = KernelImports(
        functions = ModuleFixtures.KERNEL.functions,
        macros = ModuleFixtures.KERNEL.macros + nameArities("@/1 alias!/1"),
    )

    val EXPORTS = Exports { module ->
        when (module) {
            "Elixir.Kernel" -> ModuleExports.Present(KERNEL.functions, KERNEL.macros, hasInfo = true)
            "Elixir.Module" ->
                ModuleExports.Present(
                    nameArities(
                        "__get_attribute__/3 __get_attribute__/4 __put_attribute__/4 __put_attribute__/5 " +
                            "delete_attribute/2 has_attribute?/2 make_overridable/2 put_attribute/3 register_attribute/3"
                    ),
                    emptyList(),
                    hasInfo = true,
                )
            "elixir_bootstrap" -> ModuleExports.Present(emptyList(), nameArities("@/1"), hasInfo = true)
            "Elixir.Kernel.Typespec" ->
                ModuleExports.Present(nameArities("deftypespec/6"), emptyList(), hasInfo = true)
            else -> ModuleFixtures.EXPORTS.of(module)
        }
    }

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }
}
