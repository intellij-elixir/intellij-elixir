package org.elixir_lang.snapshot

import org.elixir_lang.declaration.Declared

/** The declaration's form, lowercased, or `compiled` for an export of a compiled file. */
fun Declared.label(): String =
    when (this) {
        is Declared.Source -> form.name.lowercase()
        is Declared.Compiled -> "compiled"
    }
