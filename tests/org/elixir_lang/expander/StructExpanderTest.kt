package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** `%` building, updating and matching structs in a module body, against [StructFixtures.STRUCTS]. */
class StructExpanderTest : ExpanderTestCase() {
    override val exports: Exports = CallFixtures.EXPORTS
    override val kernel: KernelImports = CallFixtures.KERNEL
    override val structs: Structs = StructFixtures.STRUCTS
    override val module: String = StructFixtures.MODULE
    override val contextModules: List<String> = StructFixtures.CONTEXT_MODULES

    // Building

    fun testABuild() {
        assertEvery("%URI{}", "expanded {} next 0")
        assertEvery("%URI{host: \"a\", port: 1}", "expanded {} next 0")
        assertEvery("alias URI, as: U\n%U{}", "expanded {} next 0")
        assertEvery("%Version{major: 1, minor: 0, patch: 0}", "expanded {} next 0")
    }

    fun testABuildReadsWhatTheValuesRead() = assertEvery("x = 1\n%URI{port: x}", "expanded {x:0} next 1")

    fun testTheValuesAreExpandedBeforeTheStructIsRead() =
        assertSplit(
            "%NoSuchStruct{a: y}",
            "1.15.0-rc.0",
            "error undefined_function `y`",
            "error undefined_var `y`"
        )

    fun testAStructKeyIsDropped() = assertEvery("%URI{__struct__: Foo}", "expanded {} next 0")

    /** `lists:keytake/3` takes any tuple whose first element is `__struct__`, so the AST of a variable or call too. */
    fun testAStructVariableOrCallIsDroppedAsAKey() {
        assertEvery("%URI{__struct__}", "expanded {} next 0")
        assertEvery("%URI{__struct__(1)}", "expanded {} next 0")
        assertEvery("u = 1\n%URI{u | __struct__}", "expanded {u:0} next 1")
    }

    fun testASecondStructKeyIsUnported() =
        assertEvery("%URI{__struct__: Foo, __struct__: Bar}", "unported `%URI{__struct__: Foo, __struct__: Bar}`")

    fun testAnUnknownKeyRaisesFromTheStructFunction() =
        assertEvery("%URI{nope: 1}", "error struct_unknown_key `%URI{nope: 1}`")

    fun testAMissingEnforcedKeyRaisesFromTheStructFunction() =
        assertEvery("%Version{major: 1}", "error struct_missing_enforced_keys `%Version{major: 1}`")

    fun testAnUnknownKeyWinsOverAMissingEnforcedOne() =
        assertEvery("%Version{nope: 1}", "error struct_unknown_key `%Version{nope: 1}`")

    fun testANonAtomKeyIsInvalidFrom1_17() =
        assertSplit(
            "%URI{\"host\" => 1}",
            "1.17.0-rc.0",
            "error struct_unknown_key `%URI{\"host\" => 1}`",
            "error invalid_key_for_struct `%URI{\"host\" => 1}`"
        )

    fun testAKeyIsReadAsItsExpansion() =
        assertEvery("%URI{__MODULE__ => 1}", "error struct_unknown_key `%URI{__MODULE__ => 1}`")

    fun testAnUndefinedStruct() {
        assertEvery("%NoSuchStruct{}", "error undefined_struct `%NoSuchStruct{}`")
        assertEvery("%:lists{}", "error undefined_struct `%:lists{}`")
    }

    /** Before 1.14.1 a loaded module of the same name answers, which the expander can't know. */
    fun testTheModuleBeingDefinedHasNoStructYet() =
        assertSplit("%__MODULE__{}", "1.14.1", "unported `%__MODULE__{}`", "error inaccessible_struct `%__MODULE__{}`")

    fun testAContextModuleWithoutAStructIsInaccessible() =
        assertEvery("%Sibling{}", "error inaccessible_struct `%Sibling{}`")

    fun testAContextModuleWithAStruct() = assertEvery("%Defined{}", "expanded {} next 0")

    fun testAStructWithoutMetadataIsUnported() = assertEvery("%Handwritten{}", "unported `%Handwritten{}`")

    /** 1.18 records no enforced keys, so a build that gives every field is the only one whose answer is known. */
    fun testABuildOfAStructWithUnknownEnforcedKeys() {
        assertEvery("%Legacy{}", "unported `%Legacy{}`")
        assertEvery("%Legacy{major: 1, minor: 0, patch: 0}", "unported `%Legacy{major: 1, minor: 0, patch: 0}`")
        assertEvery("%Legacy{major: 1, minor: 0, patch: 0, pre: [], build: nil}", "expanded {} next 0")
        assertEvery("%Legacy{nope: 1}", "error struct_unknown_key `%Legacy{nope: 1}`")
    }

    fun testAVariableIsNotAStructName() = assertEvery("x = URI\n%x{}", "error invalid_struct_name `%x{}`")

    fun testANonMapAfterTheStructName() {
        val code = "%URI{}"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: error non_map_after_struct `%URI{}`" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val struct = lower(code, level) as ElixirAst.Call
                val (name, map) = struct.arguments!!
                val ast = ElixirAst.Call(struct.meta, struct.callee, listOf(name, ElixirAst.Literal.Atom(map.meta, "a")))

                "$version: " + render(code, expandAst(ast, level))
            }
        )
    }

    // Updating

    fun testAnUpdate() = assertEvery("u = 1\n%URI{u | host: \"b\"}", "expanded {u:0} next 1")

    fun testAnUpdateOfAnUnknownKey() =
        assertEvery("u = 1\n%URI{u | nope: 1}", "error unknown_key_for_struct `%URI{u | nope: 1}`")

    fun testAnUpdateOfANonAtomKey() =
        assertSplit(
            "u = 1\n%URI{u | \"host\" => 1}",
            "1.17.0-rc.0",
            "error unknown_key_for_struct `%URI{u | \"host\" => 1}`",
            "error invalid_key_for_struct `%URI{u | \"host\" => 1}`"
        )

    fun testAnUpdateDoesNotCheckEnforcedKeys() {
        assertEvery("u = 1\n%Version{u | major: 2}", "expanded {u:0} next 1")
        assertEvery("u = 1\n%Legacy{u | major: 2}", "expanded {u:0} next 1")
    }

    fun testAnUpdateOfAnUndefinedStruct() {
        assertEvery("u = 1\n%NoSuchStruct{u | a: 1}", "error undefined_struct `%NoSuchStruct{u | a: 1}`")
        assertSplit(
            "u = 1\n%__MODULE__{u | a: 1}",
            "1.14.1",
            "unported `%__MODULE__{u | a: 1}`",
            "error inaccessible_struct `%__MODULE__{u | a: 1}`"
        )
        assertEvery("u = 1\n%Handwritten{u | a: 1}", "unported `%Handwritten{u | a: 1}`")
    }

    // Matching

    fun testAMatch() = assertEvery("u = 1\n%URI{host: h} = u", "expanded {h:1 u:0} next 2")

    fun testAMatchOfAnUnknownKey() =
        assertEvery("u = 1\n%URI{nope: x} = u", "error unknown_key_for_struct `%URI{nope: x}`")

    fun testAMatchOfANonAtomKey() =
        assertSplit(
            "u = 1\n%URI{\"host\" => h} = u",
            "1.17.0-rc.0",
            "error unknown_key_for_struct `%URI{\"host\" => h}`",
            "error invalid_key_for_struct `%URI{\"host\" => h}`"
        )

    fun testAMatchDoesNotCheckEnforcedKeys() {
        assertEvery("u = 1\n%Version{major: m} = u", "expanded {m:1 u:0} next 2")
        assertEvery("u = 1\n%Legacy{major: m} = u", "expanded {m:1 u:0} next 2")
    }

    fun testAMatchOfAnUndefinedStruct() {
        assertEvery("u = 1\n%NoSuchStruct{} = u", "error undefined_struct `%NoSuchStruct{}`")
        assertSplit(
            "u = 1\n%__MODULE__{} = u",
            "1.14.1",
            "unported `%__MODULE__{}`",
            "error inaccessible_struct `%__MODULE__{}`"
        )
        assertEvery("u = 1\n%Sibling{} = u", "error inaccessible_struct `%Sibling{}`")
        assertEvery("u = 1\n%Handwritten{} = u", "unported `%Handwritten{}`")
    }

    fun testAVariableStructNameInAMatch() {
        assertEvery("%x{} = %URI{}", "expanded {x:0} next 1")
        assertEvery("x = URI\n%^x{} = %URI{}", "expanded {x:0} next 1")
        assertSplit("%_{} = %URI{}", "1.20.0-rc.5", "expanded {} next 0", "expanded {} next 1")
    }

    fun testANonAtomStructNameInAMatch() {
        val code = "%URI{} = 1"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: error invalid_struct_name_in_match `%URI{}`" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val match = lower(code, level) as ElixirAst.Call
                val (struct, right) = match.arguments!!
                val (name, map) = (struct as ElixirAst.Call).arguments!!
                val integer = ElixirAst.Literal.Integer(name.meta, 1.toBigInteger())
                val ast = ElixirAst.Call(
                    match.meta,
                    match.callee,
                    listOf(ElixirAst.Call(struct.meta, struct.callee, listOf(integer, map)), right),
                )

                "$version: " + render(code, expandAst(ast, level))
            }
        )
    }

    // What the observer is told

    fun testTheStructIsReportedAfterTheDispatchesInItsValues() =
        assertReported("%URI{host: abs(1), port: 2}", "dispatched | struct Elixir.URI host port")

    fun testTheDroppedStructKeyIsNotReported() =
        assertReported("%URI{__struct__: Foo, host: \"a\"}", "struct Elixir.URI host")

    fun testAnUpdateIsReported() = assertReported("u = 1\n%URI{u | host: \"b\"}", "struct Elixir.URI host")

    fun testAMatchIsReported() = assertReported("u = 1\n%URI{host: h} = u", "struct Elixir.URI host")

    fun testAVariableStructNameIsNotReported() = assertReported("%x{} = %URI{}", "struct Elixir.URI")

    /** From 1.20.0-rc.2 Elixir traces a struct before it loads it, so an error there follows the trace. */
    fun testAStructThatDoesNotExpandIsNotReportedBefore1_20_0_rc_2() {
        val before = LEVELS.filter { isBefore(it, "1.20.0-rc.2") }

        assertReported("%URI{nope: 1}", "", before)
        assertReported("%NoSuchStruct{}", "", before)
        assertReported("%Handwritten{}", "")
    }

    private fun assertReported(code: String, expected: String, versions: List<String> = LEVELS) =
        assertEquals(
            versions.joinToString("\n") { "$it: $expected" },
            versions.joinToString("\n") { version ->
                val events = mutableListOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                        events.add("dispatched")
                    }

                    override fun structExpanded(node: ElixirAst, module: String, keys: List<String>) {
                        events.add((listOf("struct", module) + keys).joinToString(" "))
                    }
                }

                expand(code, version, observer)

                "$version: " + events.joinToString(" | ")
            }
        )

    private fun expandAst(ast: ElixirAst, level: ElixirLanguageLevel) =
        Expander.expand(
            ast,
            ExState.empty(level),
            Env.empty(level, kernel).copy(module = module, contextModules = contextModules),
            level,
            exports,
            structs,
        )
}
