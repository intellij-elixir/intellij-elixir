package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
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

    /** From 1.15 an unknown key is reported and expansion carries on; from 1.20.0-rc.2 the key is left to types. */
    fun testAnUnknownKeyIsLeftToTypes() {
        assertKeyErrors("u = 1\n%URI{nope: x} = u", "{u:0 x:1} next 2", "unknown_key_for_struct")
        assertKeyErrors("u = 1\n%URI{u | nope: 1}", "{u:0} next 1", "unknown_key_for_struct")
    }

    fun testEachUnknownKeyIsReported() =
        assertKeyErrors(
            "u = 1\n%URI{nope: x, nah: y} = u",
            "{u:0 x:1 y:2} next 3",
            "unknown_key_for_struct",
            "unknown_key_for_struct",
        )

    /** From 1.17 a key that isn't an atom is invalid, and before 1.20.0-rc.2 it is an unknown key as well. */
    fun testANonAtomKeyIsStillInvalid() {
        assertNonAtomKey("u = 1\n%URI{\"host\" => h} = u", "{h:1 u:0} next 2")
        assertNonAtomKey("u = 1\n%URI{u | \"host\" => 1}", "{u:0} next 1")
    }

    /** A build raises for a key the struct lacks, after reporting it invalid from 1.17. */
    fun testANonAtomKeyOfABuildRaises() =
        assertLevels("%URI{\"host\" => 1}", LEVELS) { version ->
            val error = "error struct_unknown_key `%URI{\"host\" => 1}`"

            if (isBefore(version, "1.17.0-rc.0")) error else "$error; reported invalid_key_for_struct `%URI{\"host\" => 1}`"
        }

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

    /**
     * [code] raises the first of [kinds] up to 1.14; from 1.15 it expands to `expanded [read]` and reports each of
     * [kinds], until 1.20.0-rc.2 leaves the keys to types.
     */
    private fun assertKeyErrors(code: String, read: String, vararg kinds: String) {
        val at = code.substringAfterLast("\n").substringBefore(" = ")

        assertLevels(code, LEVELS + "1.20.0-rc.2") { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> "error ${kinds.first()} `$at`"
                isBefore(version, "1.20.0-rc.2") -> "expanded $read" + kinds.joinToString("") { "; reported $it `$at`" }
                else -> "expanded $read"
            }
        }
    }

    /**
     * [code] raises `unknown_key_for_struct` up to 1.14. From 1.15 it expands to `expanded [read]` and reports the key
     * unknown; from 1.17 it reports it invalid first; and from 1.20.0-rc.2 only invalid.
     */
    private fun assertNonAtomKey(code: String, read: String) {
        val at = code.substringAfterLast("\n").substringBefore(" = ")

        assertLevels(code, LEVELS + "1.20.0-rc.2") { version ->
            val reported = listOfNotNull(
                "invalid_key_for_struct".takeUnless { isBefore(version, "1.17.0-rc.0") },
                "unknown_key_for_struct".takeIf { isBefore(version, "1.20.0-rc.2") },
            )

            if (isBefore(version, "1.15.0-rc.0")) {
                "error unknown_key_for_struct `$at`"
            } else {
                "expanded $read" + reported.joinToString("") { "; reported $it `$at`" }
            }
        }
    }

    /** The expansion, then each error Elixir reports and carries on after. */
    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val run = Run(level, ExpansionObserver.NONE, exports, structs)
        val env = Env.empty(level, kernel).copy(module = module, contextModules = contextModules, function = function)
        val expansion = Expander.expand(lower(code, level), ExState.empty(level), env, run)
        val reported = run.errors.map { "reported ${it.kind} `${it.at.meta.origin.substring(code)}`" }

        return (listOf(render(code, expansion)) + reported).joinToString("; ")
    }
}
