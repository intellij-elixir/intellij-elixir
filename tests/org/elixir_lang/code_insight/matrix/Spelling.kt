package org.elixir_lang.code_insight.matrix

/**
 * Where in Elixir source a function's name is written, which decides how its atom must be spelled there to name it.
 */
enum class Position {
    /** After `Module.`, in a call or a capture. */
    REMOTE_CALL,

    /** A bare call, which only an identifier can be. */
    LOCAL_CALL,

    /** After the `:` of an atom literal, as `apply`'s second argument or an MFA tuple's middle element. */
    ATOM,

    /** Between the quotes of `:"..."`. */
    QUOTED_ATOM_BODY,
}

/**
 * How Elixir spells an atom at a [Position], by the rules `Macro.inspect_atom/2` follows on Elixir 1.20.4: written
 * from those rules, not shared with the plugin, so the matrix cannot agree with the code it checks by construction.
 *
 * Elixir reads a bare identifier as its NFC form, so a name that is not NFC can only be written quoted.
 */
object Spelling {
    private val IDENTIFIER = Regex("""[\p{Ll}\p{Lm}\p{Lo}\p{Nl}_][\p{L}\p{Mn}\p{Mc}\p{Nd}\p{Nl}\p{Pc}]*[?!]?""")

    /** An atom literal also takes an uppercase start and `@`, as `:Foo` and `:foo@bar`. */
    private val UNQUOTED_ATOM = Regex("""[\p{L}\p{Nl}_][\p{L}\p{Mn}\p{Mc}\p{Nd}\p{Nl}\p{Pc}@]*[?!]?""")

    /** [name] as written at [position], or null where no spelling there names it. */
    fun of(name: String, position: Position): String? {
        val nfc = name == nfc(name)

        return when (position) {
            Position.REMOTE_CALL -> if (nfc && IDENTIFIER.matches(name)) name else quoted(name)
            Position.LOCAL_CALL -> name.takeIf { nfc && IDENTIFIER.matches(name) }
            Position.ATOM -> if (nfc && UNQUOTED_ATOM.matches(name)) name else quoted(name)
            Position.QUOTED_ATOM_BODY -> escaped(name)
        }
    }

    private fun quoted(name: String): String = "\"${escaped(name)}\""

    private fun escaped(name: String): String =
        name.replace("\\", "\\\\").replace("\"", "\\\"").replace("#{", "\\#{")
}
