package org.elixir_lang.declaration

/** Why an answer may not be the one Elixir gives. */
enum class Uncertain {
    /** The use is past where the expansion stopped, so it reads the env as it stood at the stop. */
    STOP,

    /** The use is past a macro call the expander can't expand, so it reads an env that misses what the call adds. */
    OPAQUE,

    /** The use is in no node the expansion entered, so it reads the file's top env. */
    UNREACHED,

    /** A module the lookup read isn't known to its end: not compiled, not expanded to its end, or unreadable, or none was named. */
    MODULE,

    /** The module has more than one copy, and which one Elixir loads is unknown: the definition is in one of them. */
    SEVERAL,

    /** There was no expansion to read: the file has no SDK inputs, the IDE is indexing, or the expander doesn't answer the use. */
    NO_EXPANSION,
}
