package org.elixir_lang.utils

import org.elixir_lang.code.Identifier

object ElixirModulesUtil {
    // Matches a valid Elixir alias after stripping the "Elixir." prefix,
    // e.g. "Foo", "Foo.Bar", "Foo.Bar.Baz" - each segment starts with [A-Z]
    // and contains only [a-zA-Z0-9_].
    internal val elixirAliasSegmentsRegex = Regex("([A-Z][a-zA-Z0-9_]*)(\\.[A-Z][a-zA-Z0-9_]*)*")

    fun erlangModuleNameToElixir(moduleName: String): String =
            when {
                moduleName == "true" || moduleName == "false" || moduleName == "nil" -> moduleName
                moduleName.startsWith("Elixir.") -> {
                    val stripped = moduleName.removePrefix("Elixir.")
                    if (elixirAliasSegmentsRegex.matches(stripped)) {
                        stripped
                    } else {
                        // Not a valid alias (e.g. "Benchfella:tests") - render as a quoted atom
                        ":\"${stripped.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    }
                }
                else -> ":" + Identifier.inspectAsFunction(moduleName)
            }
}
