package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * In its own body, outside a function, the module being defined has no struct yet, whatever [Structs] gives for it:
 * Elixir expands the body before it evaluates any `defstruct` in it, and from 1.14.1 never reads the module's compiled
 * code. Before 1.14.1 a loaded module of the same name answers instead, which the expander can't know, so it is
 * unported there.
 */
class ModuleBeingDefinedStructTest : ExpanderTestCase() {
    override val module: String = StructFixtures.MODULE
    override val structs: Structs = STRUCTS

    fun testABuild() = assertInaccessibleFrom1_14_1("%__MODULE__{a: 1}", "%__MODULE__{a: 1}")

    fun testAMatch() = assertInaccessibleFrom1_14_1("u = 1\n%__MODULE__{a: a} = u", "%__MODULE__{a: a}")

    fun testAnUpdate() = assertInaccessibleFrom1_14_1("u = 1\n%__MODULE__{u | a: 2}", "%__MODULE__{u | a: 2}")

    /** [code] is unported at [at] up to 1.14.0, and `inaccessible_struct` there from 1.14.1. */
    private fun assertInaccessibleFrom1_14_1(code: String, at: String) =
        assertLevels(code, (LEVELS + listOf("1.14.0", "1.14.1")).sortedBy { ElixirLanguageLevel.of(it).elixir }) {
            if (isBefore(it, "1.14.1")) "unported `$at`" else "error inaccessible_struct `$at`"
        }

    companion object {
        /** The module being defined has a struct of one field, `a`, as its compiled metadata would give it. */
        val STRUCTS = Structs { module ->
            if (module == StructFixtures.MODULE) {
                ModuleStruct.Present(setOf("a"), Enforced.Known(emptySet()))
            } else {
                ModuleStruct.Absent
            }
        }
    }
}
