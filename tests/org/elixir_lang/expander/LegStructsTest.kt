package org.elixir_lang.expander

import org.junit.Assert.assertEquals
import org.junit.Test

/** [legStructs] read from the leg's beams, whose struct metadata takes the shape the leg's Elixir writes. */
class LegStructsTest {
    @Test
    fun `a struct with enforced keys`() =
        assertEquals(
            ModuleStruct.Present(
                setOf("major", "minor", "patch", "pre", "build"),
                Enforced.Known(setOf("major", "minor", "patch")),
            ),
            legStructs.of("Elixir.Version")
        )

    @Test
    fun `a struct without enforced keys`() {
        val uri = legStructs.of("Elixir.URI") as ModuleStruct.Present

        assertEquals(Enforced.Known(emptySet()), uri.enforced)
        assertEquals(true, "host" in uri.fields)
        assertEquals(false, "__struct__" in uri.fields)
    }

    @Test
    fun `a module without a struct`() {
        assertEquals(ModuleStruct.Absent, legStructs.of("Elixir.Enum"))
        assertEquals(ModuleStruct.Absent, legStructs.of("lists"))
        assertEquals(ModuleStruct.Absent, legStructs.of("Elixir.NoSuchModule"))
    }
}
