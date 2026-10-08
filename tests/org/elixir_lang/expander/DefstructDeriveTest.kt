package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A non-empty `@derive`: `Protocol.__derive__/3` runs the first protocol written last, and the module stops at the
 * `defstruct` where Elixir would expand its `__deriving__` macro, with the definitions of the struct kept.
 */
class DefstructDeriveTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            "Elixir.Derivable" -> protocol("__deriving__/2")
            "Elixir.Derivable.Any" -> implementation("__deriving__/3")
            "Elixir.Plain" -> protocol()
            "Elixir.Plain.Any" -> implementation("__deriving__/3")
            "Elixir.Fallback" -> protocol()
            "Elixir.Fallback.Any" -> implementation()
            "Elixir.Lonely" -> protocol()
            "Elixir.NoAny" -> protocol("__deriving__/2")
            "Elixir.Odd" -> protocol()
            "Elixir.Odd.Any" -> ModuleExports.Present(emptyList(), emptyList(), hasInfo = true)
            "Elixir.Hidden" -> ModuleExports.Unreadable
            else -> DefstructFixtures.EXPORTS.of(module)
        }
    }
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    fun testAProtocolWithADerivingMacroStopsAtItFromTheRelease() {
        val code = "defmodule A do\n  @derive [Derivable]\n  defstruct [:a]\nend"
        val derivable = "opaque remote_macro Elixir.Derivable.__deriving__/2"
        val any = "opaque remote_macro Elixir.Derivable.Any.__deriving__/3"

        assertSplit(code, "1.18.0-rc.0", "$STOPPED$TABLE_AT_3 | $any", "$STOPPED$TABLE_AT_3 | $derivable")
    }

    fun testAnAtomOnItsOwnStopsAtTheImplementationsMacro() =
        assertEvery("defmodule A do\n  @derive Plain\n  defstruct [:a]\nend", "$STOPPED$TABLE_AT_3 | $PLAIN")

    fun testAProtocolWithOptionsDoes() =
        assertEvery("defmodule A do\n  @derive [{Plain, only: [:a]}]\n  defstruct [:a]\nend", "$STOPPED$TABLE_AT_3 | $PLAIN")

    fun testTheProtocolWrittenLastRunsFirst() =
        assertEvery(
            "defmodule A do\n  @derive Fallback\n  @derive Plain\n  defstruct [:a]\nend",
            "$STOPPED$TABLE_AT_4 | $PLAIN",
        )

    fun testAnImplementationWithoutAMacroIsNotFollowed() =
        assertEvery(
            "defmodule A do\n  @derive Fallback\n  defstruct [:a]\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testAProtocolWhoseExportsCannotBeReadIsNotFollowed() =
        assertEvery(
            "defmodule A do\n  @derive Hidden\n  defstruct [:a]\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testAValueTheExpanderCannotReadIsNotFollowed() =
        assertEvery(
            "defmodule A do\n  @derive Foo.protocols()\n  defstruct [:a]\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testNothingToDeriveCompiles() =
        assertEvery("defmodule A do\n  @derive []\n  defstruct [:a]\nend", "module Elixir.A compiled$TABLE_AT_3")

    fun testADeriveWrittenAfterTheStructIsNotRun() =
        assertEvery("defmodule A do\n  defstruct [:a]\n  @derive Plain\nend", "module Elixir.A compiled$TABLE")

    fun testAModuleThatIsNoProtocolRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Enum]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_a_protocol `defstruct [:a]`",
        )

    fun testAProtocolThatIsNotAvailableRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Missing]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_available `defstruct [:a]`",
        )

    fun testAProtocolWithoutAnImplementationForAnyRaisesWhereItIsNeeded() =
        assertEvery(
            "defmodule A do\n  @derive [Lonely]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_available `defstruct [:a]`",
        )

    fun testADerivingMacroNeedsNoImplementationForAnyFromTheRelease() =
        assertSplit(
            "defmodule A do\n  @derive [NoAny]\n  defstruct [:a]\nend",
            "1.18.0-rc.0",
            "module Elixir.A raised derive_not_available `defstruct [:a]`",
            "$STOPPED$TABLE_AT_3 | opaque remote_macro Elixir.NoAny.__deriving__/2",
        )

    fun testAnImplementationForAnyThatIsNoImplementationRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Odd]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_an_implementation `defstruct [:a]`",
        )

    fun testAnExceptionStopsAtItsStructAndLeavesItsOverridableOutOfTheTable() {
        val code = "defmodule A do\n  @derive Plain\n  defexception [:reason]\nend"
        val table = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"

        assertEvery(code, "module Elixir.A stopped `defexception [:reason]`$table | $PLAIN")
    }

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { result ->
            (listOf(ModuleRendering.render(code, result)) + result.opaque.map { "opaque ${render(it.dispatch)}" })
                .joinToString(" | ")
        }
    }

    private fun protocol(macros: String? = null) =
        ModuleExports.Present(nameArities("__protocol__/1"), macros?.let(::nameArities).orEmpty(), hasInfo = true)

    private fun implementation(macros: String? = null) =
        ModuleExports.Present(nameArities("__impl__/1"), macros?.let(::nameArities).orEmpty(), hasInfo = true)

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }

    private companion object {
        const val STOPPED = "module Elixir.A stopped `defstruct [:a]`"
        const val PLAIN = "opaque remote_macro Elixir.Plain.Any.__deriving__/3"
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
        const val TABLE_AT_4 = " | def __struct__/0 line 4 clauses 1 | def __struct__/1 line 4 clauses 1"
    }
}
