package org.elixir_lang.beam.decompiler

import org.elixir_lang.code.RESERVED_WORDS

/**
 * Elixir reserved words and literals that cannot name an `@type`/`@opaque`.
 *
 * Unlike `def`, a typespec attribute accepts no `unquote(:"...")` name fragment, and literals such as
 * `nil`/`true`/`false` are not identifiers at all, so an Erlang type whose name is one of these has no
 * valid Elixir declaration.  Rather than emit invalid source or silently drop it, the decompiler
 * preserves such a type as a comment (see [commentOut]) - keeping the output parseable while still
 * recording what the module declared and why it can't be expressed.
 */
object ReservedTypeName {
    fun isReserved(name: String): Boolean = name in RESERVED_WORDS

    /**
     * Renders [renderedDeclaration] (a full `@type ...`/`@opaque ...` string, possibly multi-line) as a
     * comment, prefixed by a one-line explanation.  Every line is commented so a multi-line body stays
     * within the comment and the surrounding source keeps parsing.
     *
     * The caller indents the first line (the header); the declaration lines carry their own two-space
     * indent so they align under the header regardless of how many lines the body spans.
     */
    fun commentOut(name: String, renderedDeclaration: String): String {
        val header = "# `$name` is a reserved Elixir word and cannot name an Elixir type " +
                "(`@type` takes no `unquote` fragment); the Erlang type is preserved as a comment:"
        val commentedDeclaration = renderedDeclaration
            .lineSequence()
            .joinToString("\n") { "  #   $it" }

        return "$header\n$commentedDeclaration"
    }
}
