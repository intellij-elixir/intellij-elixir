package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst

/**
 * `%` inside a function of the module being defined, against [StructFixtures.STRUCTS]: from 1.20.0-rc.2 an update or a
 * match reads no struct, leaving its fields to the type checker.
 */
class StructInFunctionExpanderTest : ExpanderTestCase() {
    override val structs: Structs = StructFixtures.STRUCTS
    override val module: String = StructFixtures.MODULE
    override val contextModules: List<String> = StructFixtures.CONTEXT_MODULES
    override val function: NameArity = NameArity("f", 1)

    fun testAMatchOfAnUndefinedStructIsLeftToTypes() {
        assertLeftToTypes("u = 1\n%NoSuchStruct{} = u", "error undefined_struct `%NoSuchStruct{}`", "{u:0} next 1")
        assertLeftToTypes("u = 1\n%Sibling{} = u", "error undefined_struct `%Sibling{}`", "{u:0} next 1")
        assertLeftToTypes("u = 1\n%Handwritten{} = u", "unported `%Handwritten{}`", "{u:0} next 1")
        assertLeftToTypes("u = 1\n%__MODULE__{} = u", "unported `%__MODULE__{}`", "{u:0} next 1")
    }

    fun testAnUpdateOfAnUndefinedStructIsLeftToTypes() {
        assertLeftToTypes(
            "u = 1\n%NoSuchStruct{u | a: 1}",
            "error undefined_struct `%NoSuchStruct{u | a: 1}`",
            "{u:0} next 1"
        )
        assertLeftToTypes("u = 1\n%Handwritten{u | a: 1}", "unported `%Handwritten{u | a: 1}`", "{u:0} next 1")
        assertLeftToTypes("u = 1\n%__MODULE__{u | a: 1}", "unported `%__MODULE__{u | a: 1}`", "{u:0} next 1")
    }

    fun testAnUnknownKeyIsLeftToTypes() {
        assertLeftToTypes("u = 1\n%URI{nope: x} = u", "error unknown_key_for_struct `%URI{nope: x}`", "{u:0 x:1} next 2")
        assertLeftToTypes(
            "u = 1\n%URI{u | nope: 1}",
            "error unknown_key_for_struct `%URI{u | nope: 1}`",
            "{u:0} next 1"
        )
    }

    fun testANonAtomKeyIsStillInvalid() =
        assertSplit(
            "u = 1\n%URI{\"host\" => h} = u",
            "1.17.0-rc.0",
            "error unknown_key_for_struct `%URI{\"host\" => h}`",
            "error invalid_key_for_struct `%URI{\"host\" => h}`"
        )

    fun testABuildStillReadsTheStruct() {
        assertEvery("%NoSuchStruct{}", "error undefined_struct `%NoSuchStruct{}`")
        assertEvery("%Sibling{}", "error undefined_struct `%Sibling{}`")
        assertEvery("%__MODULE__{}", "unported `%__MODULE__{}`")
        assertEvery("%URI{nope: 1}", "error struct_unknown_key `%URI{nope: 1}`")
    }

    fun testAStructLeftToTypesIsReported() =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: " + if (isBefore(it, "1.20.0-rc.2")) "" else "Elixir.NoSuchStruct" },
            LEVELS.joinToString("\n") { version ->
                val reported = mutableListOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun structExpanded(node: ElixirAst, module: String, keys: List<String>) {
                        reported.add(module)
                    }
                }

                expand("u = 1\n%NoSuchStruct{} = u", version, observer)

                "$version: " + reported.joinToString()
            }
        )

    /** [code] gives [before] before 1.20.0-rc.2, and expands to `expanded [from]` from it. */
    private fun assertLeftToTypes(code: String, before: String, from: String) =
        assertSplit(code, "1.20.0-rc.2", before, "expanded $from")
}
