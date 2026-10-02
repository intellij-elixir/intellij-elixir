package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** [legStructs] read from the leg's beams, whose struct metadata takes the shape the leg's Elixir writes. */
class LegStructsTest {
    @Test
    fun `a struct with enforced keys`() =
        assertEquals(
            ModuleStruct.Present(setOf("major", "minor", "patch", "pre", "build"), enforced("major", "minor", "patch")),
            legStructs.of("Elixir.Version")
        )

    @Test
    fun `a struct without enforced keys`() {
        val uri = legStructs.of("Elixir.URI") as ModuleStruct.Present

        assertEquals(enforced(), uri.enforced)
        assertEquals(true, "host" in uri.fields)
        assertEquals(false, "__struct__" in uri.fields)
    }

    @Test
    fun `a module without a struct`() {
        assertEquals(ModuleStruct.Absent, legStructs.of("Elixir.Enum"))
        assertEquals(ModuleStruct.Absent, legStructs.of("lists"))
        assertEquals(ModuleStruct.Absent, legStructs.of("Elixir.NoSuchModule"))
    }

    /** [keys] as the leg's metadata records them: 1.18 and 1.19.0-rc.0 record none. */
    private fun enforced(vararg keys: String): Enforced {
        val elixir = legLevel().elixir

        return if (elixir >= ElixirLanguageLevel.of("1.18.0-rc.0").elixir &&
            elixir < ElixirLanguageLevel.of("1.19.0-rc.1").elixir
        ) {
            Enforced.Unknown
        } else {
            Enforced.Known(keys.toSet())
        }
    }
}
