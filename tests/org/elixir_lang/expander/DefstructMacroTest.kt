package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defstruct`: the two functions it defines, the events of the output Elixir expands it to on each release, and each
 * way it raises. The output is `Kernel.defstruct/1`'s and `Kernel.Utils.defstruct`'s, whose results the expander
 * computes from literal fields.
 */
class DefstructMacroTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    fun testAStructDefinesItsTwoFunctions() =
        assertEvery("defmodule A do\n  defstruct [:a]\nend", "module Elixir.A compiled$TABLE")

    fun testDefaultsOfEveryLiteralKind() =
        assertEvery(
            "defmodule A do\n  defstruct a: 1, b: [1, 2], c: %{x: :y}, d: {1, :z}, e: \"s\", f: 1.5, g: nil\nend",
            "module Elixir.A compiled$TABLE",
        )

    fun testEnforcedKeysAsAListAreAccepted() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a, :b]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testAnEnforcedKeyOnItsOwnIsAccepted() =
        assertEvery(
            "defmodule A do\n  @enforce_keys :a\n  defstruct [:a]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testNoEnforcedKeysAreAcceptedAsAnEmptyList() =
        assertEvery(
            "defmodule A do\n  @enforce_keys []\n  defstruct [:a]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testFieldsThatAreNotLiteralStopTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct Foo.fields()\nend",
            "module Elixir.A stopped `defstruct Foo.fields()`$TABLE",
        )

    fun testADefaultThatIsNotLiteralStopsTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct a: Foo.default()\nend",
            "module Elixir.A stopped `defstruct a: Foo.default()`$TABLE",
        )

    fun testEnforcedKeysThatAreNotKnownStopTheModule() =
        assertEvery(
            "defmodule A do\n  @enforce_keys Foo.keys()\n  defstruct [:a]\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testADefstructInAFunctionStopsTheModule() =
        assertEvery(
            "defmodule A do\n  def f, do: defstruct([:a])\nend",
            "module Elixir.A stopped `defstruct([:a])` | def f/0 line 2 clauses 1",
        )

    fun testADefstructInABranchStopsTheModule() =
        assertEvery(
            "defmodule A do\n  if Foo.ok() do\n    defstruct [:a]\n  else\n    defstruct [:b]\n  end\nend",
            "module Elixir.A stopped `defstruct [:b]` | def __struct__/0 line 5 clauses 2 | def __struct__/1 line 5 clauses 2",
        )

    fun testADefstructThatMayNotRunStopsTheModule() =
        assertEvery(
            "defmodule A do\n  if Foo.ok() do\n    defstruct [:a]\n  end\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testDefaultsThatNameModulesAreAtoms() =
        assertEvery(
            "defmodule A do\n  alias Foo.Bar\n  defstruct a: Bar, b: [Bar, __MODULE__], c: {Bar, 1}, d: %{}\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    /** Before the struct is escaped in the output, the compiler expands the fun in the definitions that read it. */
    fun testAnExternalCaptureIsAValidDefault() =
        assertSplit(
            "defmodule A do\n  defstruct f: &String.upcase/1\nend",
            "1.18.0-rc.0",
            "module Elixir.A stopped `defstruct f: &String.upcase/1`$TABLE",
            "module Elixir.A compiled$TABLE",
        )

    /** A capture that isn't `&Mod.f/n` may be of an imported function, which escapes, or a local one, which doesn't. */
    fun testAnyOtherCaptureStopsTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct f: &String.upcase(&1)\nend",
            "module Elixir.A stopped `defstruct f: &String.upcase(&1)`$TABLE",
        )

    fun testASecondDefstructRaises() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  defstruct [:b]\nend",
            "module Elixir.A raised struct_twice `defstruct [:b]`$TABLE",
        )

    fun testFieldsThatAreNotAListRaise() =
        assertEvery(
            "defmodule A do\n  defstruct :a\nend",
            "module Elixir.A raised struct_fields_not_list `defstruct :a`",
        )

    fun testAFieldNameThatIsNotAnAtomRaises() =
        assertEvery(
            "defmodule A do\n  defstruct [\"a\"]\nend",
            "module Elixir.A raised struct_field_not_atom `defstruct [\"a\"]`",
        )

    fun testAnInvalidDefaultRaises() =
        assertEvery(
            "defmodule A do\n  defstruct a: fn -> 1 end\nend",
            "module Elixir.A raised struct_invalid_default `defstruct a: fn -> 1 end`",
        )

    fun testTheStructKeyIsReservedFrom1_18() =
        assertSplit(
            "defmodule A do\n  defstruct [:__struct__]\nend",
            "1.18.0-rc.0",
            "module Elixir.A compiled$TABLE",
            "module Elixir.A raised struct_reserved_key `defstruct [:__struct__]`",
        )

    fun testEnforcedKeysThatAreNotAtomsRaise() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [\"a\"]\n  defstruct [:a]\nend",
            "module Elixir.A raised enforce_key_not_atom `defstruct [:a]`",
        )

    fun testEnforcedKeysThatAreNotInTheFieldsRaiseFrom1_12() =
        assertSplit(
            "defmodule A do\n  @enforce_keys [:b]\n  defstruct [:a]\nend",
            "1.12.0-rc.0",
            "module Elixir.A compiled$TABLE_AT_3",
            "module Elixir.A raised enforce_keys_not_defined `defstruct [:a]`",
        )

    fun testTheEventsOfAPlainStruct() = assertEvents("defmodule A do\n  defstruct [:a]\nend", enforced = false)

    fun testDefaultsAddNoEvents() =
        assertEvents("defmodule A do\n  defstruct a: 1, b: [1, 2], c: %{x: :y}\nend", enforced = false)

    fun testTheEventsOfAStructWithEnforcedKeys() =
        assertEvents("defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a]\nend", enforced = true)

    /** The dispatches of `defstruct`'s expansion and of the two definitions it stores, as Elixir traces them. */
    private fun assertEvents(code: String, enforced: Boolean) {
        val start = if (enforced) StructEvents.AT else StructEvents.DEFSTRUCT

        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it, enforced)}" },
            LEVELS.joinToString("\n") { "$it: " + events(code, it).dropWhile { event -> event != start } },
        )
    }

    private fun expected(version: String, enforced: Boolean) =
        StructEvents.output(version, StructEvents.DEFSTRUCT, emptyList(), enforced) + StructEvents.bodies(version, enforced)

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    private companion object {
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
    }
}
