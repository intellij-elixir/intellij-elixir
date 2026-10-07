package org.elixir_lang.declaration

/**
 * How the plugin models a macro of `Kernel` or `Kernel.SpecialForms`, and what a `do` block given to it does to the
 * module around it.
 *
 * [block] is module accumulation only: whether a definition, attribute or `defstruct` written in the block belongs to
 * the enclosing module. It is not variable or directive scope: `if` is [Block.IN_PLACE], yet a variable bound or an
 * `alias` made in its block does not outlive the block.
 *
 * The table is keyed by name, and some names are a macro at one arity and a function at another (`get_in/1` and
 * `get_in/2`), so a role does not say that a call is a macro call.
 */
data class MacroRole(val modelling: Modelling, val block: Block) {
    enum class Modelling {
        /** A form of `Kernel.SpecialForms`, which the expander implements itself. */
        SPECIAL_FORM,

        /** A macro whose effect the plugin writes down instead of expanding it. */
        SUMMARY,

        /** A macro whose expansion the expander follows. */
        EXPANDED,

        /** A macro the plugin does not model. */
        OPAQUE
    }

    enum class Block {
        /** The block runs as part of the enclosing module's body. */
        IN_PLACE,

        /** Nothing written in the block belongs to the enclosing module: a new module, a function body, or data. */
        BOUNDARY,

        /** Only the macro's expansion can say; the role of a macro the table does not list that is given a block. */
        EXPANSION_DEPENDENT,

        /** The macro takes no `do` block. */
        NOT_A_BLOCK
    }

    companion object {
        private val BY_NAME: Map<String, MacroRole> =
            listOf(
                MacroRole(Modelling.SPECIAL_FORM, Block.IN_PLACE) to
                    listOf("case", "cond", "for", "receive", "try", "with"),
                MacroRole(Modelling.SPECIAL_FORM, Block.BOUNDARY) to listOf("quote"),
                MacroRole(Modelling.SPECIAL_FORM, Block.NOT_A_BLOCK) to
                    listOf(
                        "%", "%{}", "&", ".", "::", "<<>>", "=", "^", "{}", "__CALLER__", "__DIR__", "__ENV__",
                        "__MODULE__", "__STACKTRACE__", "__aliases__", "__block__", "__cursor__", "alias", "fn", "import",
                        "require", "super", "unquote", "unquote_splicing"
                    ),
                MacroRole(Modelling.EXPANDED, Block.NOT_A_BLOCK) to
                    listOf(
                        "get_and_update_in", "get_in", "is_exception", "is_nil", "is_non_struct_map", "is_struct",
                        "match?", "pop_in", "put_in", "reraise", "tap", "then", "to_char_list", "to_charlist", "update_in"
                    ),
                // Its expansion is whatever the `:dbg_callback` configured at compile time makes it.
                MacroRole(Modelling.OPAQUE, Block.NOT_A_BLOCK) to listOf("dbg")
            ).flatMap { (role, names) -> names.map { it to role } }.plus(summaries()).let { pairs ->
                pairs.toMap().also { check(it.size == pairs.size) { "a name is given two roles" } }
            }

        /** Each name [MacroKey] summarises in `Kernel`, once, with the block its keys share. */
        private fun summaries(): List<Pair<String, MacroRole>> =
            MacroKey.entries
                .filter { it.receiver == "Elixir.Kernel" }
                .groupBy { it.macroName }
                .map { (name, keys) ->
                    val block = keys.map { it.block }.distinct().single()

                    name to MacroRole(Modelling.SUMMARY, block)
                }

        /** The role of the `Kernel` or `Kernel.SpecialForms` macro [name], or `null` when the table does not list it. */
        fun listed(name: String): MacroRole? = BY_NAME[name]

        fun of(name: String, hasDoBlock: Boolean): MacroRole = listed(name) ?: unlisted(hasDoBlock)

        /** The role of a macro that is not one of `Kernel`'s or `Kernel.SpecialForms`'s. */
        fun unlisted(hasDoBlock: Boolean): MacroRole =
            MacroRole(Modelling.OPAQUE, if (hasDoBlock) Block.EXPANSION_DEPENDENT else Block.NOT_A_BLOCK)
    }
}
