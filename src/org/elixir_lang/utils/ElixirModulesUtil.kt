package org.elixir_lang.utils

import org.elixir_lang.code.InspectAtom

object ElixirModulesUtil {
    // Matches a valid Elixir alias after stripping the "Elixir." prefix,
    // e.g. "Foo", "Foo.Bar", "Foo.Bar.Baz" - each segment starts with [A-Z]
    // and contains only [a-zA-Z0-9_].
    internal val elixirAliasSegmentsRegex = Regex("([A-Z][a-zA-Z0-9_]*)(\\.[A-Z][a-zA-Z0-9_]*)*")

    /** The module atom [moduleName] as Elixir's `inspect/1` writes it. */
    fun erlangModuleNameToElixir(moduleName: String): String = InspectAtom.literal(moduleName)
}
