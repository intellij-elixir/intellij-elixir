package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The struct a module's `defstruct` records, and which reads answer from it: the module's own function bodies after
 * the `defstruct`, a module nested in it, and a module the file defines later. The module's body never reads it, for
 * Elixir expands the whole body before it evaluates any of it. A module that stopped keeps both arms' `__struct__/1`
 * up to 1.13: the output's `case` on `@enforce_keys` is declined after the stop.
 */
class StructRecordTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    /** Whether Elixir at [version] loads the module being defined where a struct of it is read, as it did before 1.14.1. */
    private fun loadsItself(version: String): Boolean =
        !STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED.isSufficient(ElixirLanguageLevel.of(version))

    fun testAFunctionAfterTheDefstructReadsTheRecord() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  def f, do: %A{a: 1}\nend",
            "module Elixir.A compiled$TABLE | def f/0 line 3 clauses 1",
        )

    fun testAFunctionBeforeTheDefstructFindsNoStruct() =
        assertLevels("defmodule A do\n  def f, do: %A{}\n  defstruct [:a]\nend", LEVELS) {
            if (loadsItself(it)) "module Elixir.A stopped `%A{}` | def f/0 line 2 clauses 1${stopped(3, it)}"
            else "module Elixir.A raised undefined_struct `%A{}`"
        }

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
        assertLevels("defmodule A do\n  defstruct [:a]\n  @x %A{}\nend", LEVELS) {
            if (loadsItself(it)) "module Elixir.A stopped `%A{}`${stopped(2, it)}"
            else "module Elixir.A raised inaccessible_struct `%A{}`"
        }

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
        assertLevels("defmodule A do\n  defmodule B do\n    def f, do: %A{}\n  end\n  defstruct [:a]\nend", LEVELS) {
            "module Elixir.A stopped `%A{}`${stopped(5, it)}"
        }

    fun testTheBodyOfAModuleNestedBeforeTheDefstructCannotEither() =
        assertLevels("defmodule A do\n  defmodule B do\n    @x %A{}\n  end\n  defstruct [:a]\nend", LEVELS) {
            "module Elixir.A stopped `%A{}`${stopped(5, it)}"
        }

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
        assertLevels("defmodule A do\n  defstruct Foo.fields()\n  def f, do: %A{}\nend", LEVELS) {
            "module Elixir.A stopped `defstruct Foo.fields()`${stopped(2, it)} | def f/0 line 3 clauses 1"
        }

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
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, NO_APP_ENV)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    /** The struct's definitions on [line] in a module that stopped, at [version]. */
    private fun stopped(line: Int, version: String): String =
        " | def __struct__/0 line $line clauses 1 | def __struct__/1 line $line clauses ${stoppedStructClauses(ElixirLanguageLevel.of(version))}"

    private companion object {
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
    }
}
