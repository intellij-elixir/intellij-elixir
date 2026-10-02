package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * The modules the directive tests load, as `__info__` or `module_info(exports)` gives them for the fixtures the
 * plain-Elixir oracle compiled: `M`, `M.A`, `M.B`, `S`, whose macros clash with special forms, and the Erlang module
 * `:x3e`. `Sig` and `SigMac` are written here: each exports a sigil name `is_sigil/1` has no clause for from 1.17 to
 * 1.20.0-rc.4, as a function and as a macro. So are the empty Erlang modules named U+F900 and U+20000, which Erlang
 * orders by code point and UTF-16 the other way round.
 */
internal object DirectiveFixtures {
    const val BMP = "\uF900"
    val SUPPLEMENTARY = String(Character.toChars(0x20000))

    val EXPORTS = Exports { module ->
        when (module) {
            "Elixir.M" -> ModuleExports.Present(
                nameArities("_hidden/1 f/1 f/2 g/1 sigil_x/2 uses/1"),
                nameArities("_hidden_mac/1 mac/1 sigil_Y/2"),
                hasInfo = true,
            )
            "Elixir.M.A" -> ModuleExports.Present(nameArities("a/1"), emptyList(), hasInfo = true)
            "Elixir.M.B" -> ModuleExports.Present(nameArities("b/1"), emptyList(), hasInfo = true)
            "Elixir.S" -> ModuleExports.Present(emptyList(), nameArities("alias/2 quote/1 s/1"), hasInfo = true)
            "Elixir.Sig" -> ModuleExports.Present(nameArities("sigil_ab/2 sigil_x/2"), emptyList(), hasInfo = true)
            "Elixir.SigMac" -> ModuleExports.Present(emptyList(), nameArities("sigil_cd/2 sigil_Z/2"), hasInfo = true)
            "x3e" -> ModuleExports.Present(
                nameArities("behaviour_info/1 f/1 g/2 module_info/0 module_info/1 sigil_x/2"),
                emptyList(),
                hasInfo = false,
            )
            BMP, SUPPLEMENTARY -> ModuleExports.Present(emptyList(), emptyList(), hasInfo = false)
            "Elixir.U" -> ModuleExports.Unreadable
            else -> ModuleExports.Absent
        }
    }

    /**
     * [env]'s aliases, requires, functions and macros as the oracle's probe prints `__CALLER__`'s with `inspect`:
     * less the default requires and `Kernel`'s imports.
     */
    fun inspect(env: Env): String =
        "[aliases: " + inspectList(env.aliases.map { TupleValue(listOf(atom(it.alias), atom(it.module))) }) +
            ", requires: " + inspectList(env.requires.filterNot { it in DEFAULT_REQUIRES }.map(::atom)) +
            ", functions: " + inspectList(imports(env.functions)) +
            ", macros: " + inspectList(imports(env.macros)) + "]"

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }

    private fun imports(imports: List<Env.Imports>): List<Value> =
        imports.filterNot { it.module == "Elixir.Kernel" }.map { (module, nameArities) ->
            TupleValue(listOf(atom(module), ListValue(nameArities.map { TupleValue(listOf(atom(it.name), IntegerValue(it.arity))) })))
        }

    private sealed interface Value

    private class AtomValue(val name: String) : Value

    private class IntegerValue(val value: Int) : Value

    private class TupleValue(val elements: List<Value>) : Value

    private class ListValue(val elements: List<Value>) : Value

    private fun atom(name: String) = AtomValue(name)

    private fun inspect(value: Value): String =
        when (value) {
            is AtomValue -> inspectAtom(value.name)
            is IntegerValue -> value.value.toString()
            is TupleValue -> value.elements.joinToString(", ", "{", "}", transform = ::inspect)
            is ListValue -> inspectList(value.elements)
        }

    private fun inspectList(elements: List<Value>): String =
        if (elements.isNotEmpty() && elements.all(::isKeywordPair)) {
            elements.joinToString(", ", "[", "]") { pair ->
                val (key, value) = (pair as TupleValue).elements

                "${(key as AtomValue).name}: ${inspect(value)}"
            }
        } else {
            elements.joinToString(", ", "[", "]", transform = ::inspect)
        }

    private fun isKeywordPair(value: Value) =
        value is TupleValue && value.elements.size == 2 && (value.elements[0] as? AtomValue)?.name?.matches(IDENTIFIER) == true

    private fun inspectAtom(name: String): String =
        when {
            name in setOf("nil", "true", "false", "Elixir") -> name
            // Stripping the prefix would print `:Elixir` as the alias.
            name == "Elixir.Elixir" || name.startsWith("Elixir.Elixir.") -> name
            name.startsWith("Elixir.") &&name.removePrefix("Elixir.").split('.').all { it.matches(ALIAS_SEGMENT) } ->
                name.removePrefix("Elixir.")
            name.matches(IDENTIFIER) -> ":$name"
            else -> ":\"$name\""
        }

    private val DEFAULT_REQUIRES = setOf("Elixir.Application", "Elixir.Kernel", "Elixir.Kernel.Typespec")
    private val IDENTIFIER = Regex("[a-z_][a-zA-Z0-9_]*[?!]?")
    private val ALIAS_SEGMENT = Regex("[A-Z][a-zA-Z0-9_]*")
}
