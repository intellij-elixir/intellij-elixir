package org.elixir_lang.expander

/** The structs the struct tests name, in a module body of `Case` defined after `Sibling` and `Defined`. */
internal object StructFixtures {
    const val MODULE = "Elixir.Case"

    /** `Sibling` defines no struct; `Defined` defines one with no fields. */
    val CONTEXT_MODULES = listOf("Elixir.Sibling", "Elixir.Defined")

    private val URI_FIELDS = setOf("scheme", "path", "query", "fragment", "port", "host", "userinfo", "authority")
    private val VERSION_FIELDS = setOf("major", "minor", "patch", "pre", "build")

    /**
     * `URI` and `Version` as their beams record them; `Legacy` is `Version` as 1.18 records it, without the enforced
     * keys; `Handwritten` defines `__struct__/1` by hand, so it has no struct metadata.
     */
    val STRUCTS = Structs { module ->
        when (module) {
            "Elixir.URI" -> ModuleStruct.Present(URI_FIELDS, Enforced.Known(emptySet()))
            "Elixir.Version" -> ModuleStruct.Present(VERSION_FIELDS, Enforced.Known(setOf("major", "minor", "patch")))
            "Elixir.Legacy" -> ModuleStruct.Present(VERSION_FIELDS, Enforced.Unknown)
            "Elixir.Handwritten" -> ModuleStruct.Unreadable
            "Elixir.Defined" -> ModuleStruct.Present(emptySet(), Enforced.Known(emptySet()))
            else -> ModuleStruct.Absent
        }
    }
}
