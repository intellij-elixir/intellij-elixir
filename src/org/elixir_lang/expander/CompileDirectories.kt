package org.elixir_lang.expander

/** The directory Elixir compiled each file in, the working directory a kept `quote`'s file is taken relative to. */
fun interface CompileDirectories {
    /** The directory [file], a path, was compiled in, or `null` where it isn't known. */
    fun of(file: String): String?

    companion object {
        val UNKNOWN = CompileDirectories { null }
    }
}
