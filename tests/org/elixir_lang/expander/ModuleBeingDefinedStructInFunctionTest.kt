package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * Inside a function of its own body, the module being defined isn't read from [Structs] either: Elixir reads the
 * module's own definitions there, which aren't modelled, so `%` of it doesn't expand until 1.20.0-rc.2 leaves an update
 * or a match to the type checker.
 */
class ModuleBeingDefinedStructInFunctionTest : ExpanderTestCase() {
    override val module: String = StructFixtures.MODULE
    override val structs: Structs = ModuleBeingDefinedStructTest.STRUCTS
    override val function: NameArity = NameArity("f", 1)

    fun testABuild() = assertEvery("%__MODULE__{a: 1}", "unported `%__MODULE__{a: 1}`")

    fun testAMatch() =
        assertSplit(
            "u = 1\n%__MODULE__{a: a} = u",
            "1.20.0-rc.2",
            "unported `%__MODULE__{a: a}`",
            "expanded {a:1 u:0} next 2",
        )

    fun testAnUpdate() =
        assertSplit(
            "u = 1\n%__MODULE__{u | a: 2}",
            "1.20.0-rc.2",
            "unported `%__MODULE__{u | a: 2}`",
            "expanded {u:0} next 1",
        )
}
