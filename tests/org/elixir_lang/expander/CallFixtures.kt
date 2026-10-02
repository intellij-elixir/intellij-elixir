package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * A few of `Kernel`'s imports and the exports of the modules the call tests name, as each module's `__info__` or
 * `module_info(exports)` gives them, with [DirectiveFixtures.EXPORTS]'s modules besides.
 */
internal object CallFixtures {
    val KERNEL = KernelImports(
        functions = nameArities(
            "+/1 +/2 ++/2 -/1 -/2 ==/2 >/2 abs/1 elem/2 inspect/1 inspect/2 is_integer/1 is_map_key/2 max/2 node/0 " +
                "put_elem/3 self/0"
        ),
        macros = nameArities("and/2 binding/0 if/2 to_string/1 |>/2"),
    )

    val EXPORTS = Exports { module ->
        when (module) {
            "Elixir.Kernel" -> ModuleExports.Present(KERNEL.functions, KERNEL.macros, hasInfo = true)
            "Elixir.Integer" ->
                ModuleExports.Present(nameArities("parse/1 to_string/1 to_string/2"), nameArities("is_even/1 is_odd/1"), true)
            "Elixir.Record" -> ModuleExports.Present(nameArities("extract/2"), nameArities("is_record/1 is_record/2"), true)
            "Elixir.String.Chars" -> ModuleExports.Present(nameArities("to_string/1"), emptyList(), hasInfo = true)
            "Elixir.String" -> ModuleExports.Present(nameArities("length/1"), emptyList(), hasInfo = true)
            "Elixir.Map" -> ModuleExports.Present(nameArities("get/2 get/3"), emptyList(), hasInfo = true)
            "Elixir.System" -> ModuleExports.Present(nameArities("stacktrace/0"), emptyList(), hasInfo = true)
            "lists" ->
                ModuleExports.Present(nameArities("module_info/0 module_info/1 reverse/1"), emptyList(), hasInfo = false)
            else -> DirectiveFixtures.EXPORTS.of(module)
        }
    }

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }
}
