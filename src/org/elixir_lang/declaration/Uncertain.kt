package org.elixir_lang.declaration

/** Why an answer may not be the one Elixir gives. */
enum class Uncertain {
    /** The use is past where the expansion stopped, so it reads the env as it stood at the stop. */
    STOP,

    /** The use is past a macro call the expander can't expand, so it reads an env that misses what the call adds. */
    OPAQUE,

    /** The use is in no node the expansion entered, so it reads the file's top env. */
    UNREACHED,
}
