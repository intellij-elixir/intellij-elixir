package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The struct a module's `defstruct` records, and which reads answer from it: the module's own function bodies after
 * the `defstruct`, a module nested in it, and a module the file defines later. The module's body never reads it, for
 * Elixir expands the whole body before it evaluates any of it.
 */
class StructRecordTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    fun testAFunctionAfterTheDefstructReadsTheRecord() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  def f, do: %A{a: 1}\nend",
            "module Elixir.A compiled$TABLE | def f/0 line 3 clauses 1",
        )

    fun testAFunctionBeforeTheDefstructFindsNoStruct() =
        assertSplit(
            "defmodule A do\n  def f, do: %A{}\n  defstruct [:a]\nend",
            "1.14.1",
            "module Elixir.A stopped `%A{}` | def f/0 line 2 clauses 1 | def __struct__/0 line 3 clauses 1 | " +
                "def __struct__/1 line 3 clauses 1",
            "module Elixir.A raised undefined_struct `%A{}`",
        )

    fun testTheModuleMacroReadsTheRecord() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  def f, do: %__MODULE__{b: 1}\nend",
            "module Elixir.A raised struct_unknown_key `%__MODULE__{b: 1}`$TABLE",
        )

    fun testAnUnknownKeyRaises() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  def f, do: %A{b: 1}\nend",
            "module Elixir.A raised struct_unknown_key `%A{b: 1}`$TABLE",
        )

    fun testAnEnforcedKeyThatIsMissingRaises() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a, :b]\n  def f, do: %A{b: 1}\nend",
            "module Elixir.A raised struct_missing_enforced_keys `%A{b: 1}`$TABLE_AT_3",
        )

    fun testTheEnforcedKeysGiven() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a, :b]\n  def f, do: %A{a: 1}\nend",
            "module Elixir.A compiled$TABLE_AT_3 | def f/0 line 4 clauses 1",
        )

    fun testTheModuleBodyNeverReadsTheRecord() =
        assertSplit(
            "defmodule A do\n  defstruct [:a]\n  @x %A{}\nend",
            "1.14.1",
            "module Elixir.A stopped `%A{}`$TABLE",
            "module Elixir.A raised inaccessible_struct `%A{}`",
        )

    fun testAModuleNestedAfterTheDefstructReadsTheRecord() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  defmodule B do\n    def f, do: %A{a: 1}\n  end\nend",
            "module Elixir.A compiled$TABLE",
        )

    fun testAModuleNestedAfterTheDefstructChecksTheKeys() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  defmodule B do\n    def f, do: %A{b: 1}\n  end\nend",
            "module Elixir.A raised struct_unknown_key `%A{b: 1}`$TABLE",
        )

    // A copy of the enclosing module loaded by an earlier compile would answer, which the expander can't know.
    fun testAModuleNestedBeforeTheDefstructCannotSayWhatTheStructIs() =
        assertEvery(
            "defmodule A do\n  defmodule B do\n    def f, do: %A{}\n  end\n  defstruct [:a]\nend",
            "module Elixir.A stopped `%A{}`$TABLE_AT_5",
        )

    fun testTheBodyOfAModuleNestedBeforeTheDefstructCannotEither() =
        assertEvery(
            "defmodule A do\n  defmodule B do\n    @x %A{}\n  end\n  defstruct [:a]\nend",
            "module Elixir.A stopped `%A{}`$TABLE_AT_5",
        )

    fun testAModuleDefinedLaterReadsTheRecord() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\nend\ndefmodule B do\n  def f, do: %A{b: 1}\nend",
            "module Elixir.A compiled$TABLE\nmodule Elixir.B raised struct_unknown_key `%A{b: 1}`",
        )

    fun testAModuleDefinedLaterWithoutAStructHasNone() =
        assertEvery(
            "defmodule A do\n  def f, do: 1\nend\ndefmodule B do\n  def f, do: %A{}\nend",
            "module Elixir.A compiled | def f/0 line 2 clauses 1\nmodule Elixir.B raised undefined_struct `%A{}`",
        )

    fun testAStructTheExpanderCannotFollowIsUnreadable() =
        assertEvery(
            "defmodule A do\n  defstruct Foo.fields()\n  def f, do: %A{}\nend",
            "module Elixir.A stopped `defstruct Foo.fields()`$TABLE | def f/0 line 3 clauses 1",
        )

    fun testAModuleAfterARaisedStructIsNotReached() =
        assertEvery(
            "defmodule A do\n  defstruct :a\nend\ndefmodule B do\n  def f, do: %A{}\nend",
            "module Elixir.A raised struct_fields_not_list `defstruct :a`",
        )

    fun testAFunctionOfTheModuleItselfIsNotFollowed() =
        assertEvery(
            "defmodule A do\n  def __struct__(kv), do: kv\n  def f, do: %A{}\nend",
            "module Elixir.A stopped `%A{}` | def __struct__/1 line 2 clauses 1 | def f/0 line 3 clauses 1",
        )

    fun testAFunctionOfAModuleDefinedEarlierIsNotFollowedEither() =
        assertEvery(
            "defmodule A do\n  def __struct__(kv), do: kv\nend\ndefmodule B do\n  def f, do: %A{}\nend",
            "module Elixir.A compiled | def __struct__/1 line 2 clauses 1\n" +
                "module Elixir.B stopped `%A{}` | def f/0 line 5 clauses 1",
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    private companion object {
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
        const val TABLE_AT_5 = " | def __struct__/0 line 5 clauses 1 | def __struct__/1 line 5 clauses 1"
    }
}
