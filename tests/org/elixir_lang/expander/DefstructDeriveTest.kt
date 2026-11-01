package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A non-empty `@derive`: `Protocol.__derive__/3` runs each protocol, the one written last first. The module goes on past
 * a `__deriving__` macro, whose output isn't followed, and stops at the `defstruct` for a protocol it can't follow, with
 * the definitions of the struct kept. The output's statements after a stop are declined, so up to 1.13 both arms of its
 * `case` on `@enforce_keys` store `__struct__/1`.
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

    /** `defexception`'s output's `Map.has_key?(struct, :message)` runs from `Map`'s clause. */
    override val clauses: ModuleClauses.Lookup by lazy {
        fixtureClauses("defmodule Map do\n  def has_key?(map, key), do: :maps.is_key(key, map)\nend")
    }

    fun testAProtocolWithADerivingMacroGoesOnPastIt() =
        assertEvery("defmodule A do\n  @derive [Derivable]\n  defstruct [:a]\n  def g, do: 1\nend", "$COMPILED$TABLE_AT_3$G_AT_4")

    fun testAnAtomOnItsOwnGoesOnPastTheImplementationsMacro() =
        assertEvery("defmodule A do\n  @derive Plain\n  defstruct [:a]\n  def g, do: 1\nend", "$COMPILED$TABLE_AT_3$G_AT_4")

    fun testAProtocolWithOptionsDoes() =
        assertEvery("defmodule A do\n  @derive [{Plain, only: [:a]}]\n  defstruct [:a]\n  def g, do: 1\nend", "$COMPILED$TABLE_AT_3$G_AT_4")

    fun testTwoProtocolsWithMacrosGoOnPastBoth() =
        assertEvery("defmodule A do\n  @derive Derivable\n  @derive Plain\n  defstruct [:a]\nend", "$COMPILED$TABLE_AT_4")

    fun testALaterImplementationWithoutAMacroStopsWithNoMacroOpaque() =
        assertStopped("defmodule A do\n  @derive Fallback\n  @derive Plain\n  defstruct [:a]\nend", 4)

    fun testALaterValueTheExpanderCannotReadStops() =
        assertStopped("defmodule A do\n  @derive Foo.protocols()\n  @derive Plain\n  defstruct [:a]\nend", 4)

    fun testALaterModuleThatIsNoProtocolRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Enum]\n  @derive Plain\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_a_protocol `defstruct [:a]`",
        )

    fun testALaterProtocolWithoutAnImplementationForAnyRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Lonely]\n  @derive Plain\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_available `defstruct [:a]`",
        )

    fun testTheProtocolWrittenLastRunsFirst() =
        assertEvery(
            "defmodule A do\n  @derive [Enum]\n  @derive [Missing]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_available `defstruct [:a]`",
        )

    fun testTheProtocolWrittenFirstRunsLast() =
        assertEvery(
            "defmodule A do\n  @derive [Missing]\n  @derive [Enum]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_a_protocol `defstruct [:a]`",
        )

    fun testAnImplementationWithoutAMacroIsNotFollowed() =
        assertStopped("defmodule A do\n  @derive Fallback\n  defstruct [:a]\nend", 3)

    fun testAProtocolWhoseExportsCannotBeReadIsNotFollowed() =
        assertStopped("defmodule A do\n  @derive Hidden\n  defstruct [:a]\nend", 3)

    fun testAValueTheExpanderCannotReadIsNotFollowed() =
        assertStopped("defmodule A do\n  @derive Foo.protocols()\n  defstruct [:a]\nend", 3)

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
            "$COMPILED$TABLE_AT_3",
        )

    fun testAnImplementationForAnyThatIsNoImplementationRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Odd]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_an_implementation `defstruct [:a]`",
        )

    fun testAnExceptionGoesOnPastTheMacro() =
        assertEvery(
            "defmodule A do\n  @derive Plain\n  defexception [:reason]\nend",
            "module Elixir.A compiled$TABLE_AT_3 | def exception/1 line 3 clauses 1",
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, NO_APP_ENV, clauses = clauses)

        return file.modules.joinToString("\n") { result ->
            (listOf(ModuleRendering.render(code, result)) + result.opaque.map { "opaque ${render(it.dispatch)}" })
                .joinToString(" | ")
        }
    }

    /** [code] stops at its `defstruct` on line [line] at every level, keeping the struct's definitions. */
    private fun assertStopped(code: String, line: Int) = assertLevels(code, LEVELS) { "$STOPPED${stopped(line, it)}" }

    /** The struct's definitions on [line] in a module that stopped at its `defstruct`, at [version]. */
    private fun stopped(line: Int, version: String): String =
        " | def __struct__/0 line $line clauses 1 | def __struct__/1 line $line clauses ${stoppedStructClauses(ElixirLanguageLevel.of(version))}"

    private fun protocol(macros: String? = null) =
        ModuleExports.Present(nameArities("__protocol__/1"), macros?.let(::nameArities).orEmpty(), hasInfo = true)

    private fun implementation(macros: String? = null) =
        ModuleExports.Present(nameArities("__impl__/1"), macros?.let(::nameArities).orEmpty(), hasInfo = true)

    private fun nameArities(text: String): List<NameArity> =
        text.split(" ").map { NameArity(it.substringBeforeLast('/'), it.substringAfterLast('/').toInt()) }

    private companion object {
        const val STOPPED = "module Elixir.A stopped `defstruct [:a]`"
        const val COMPILED = "module Elixir.A compiled"
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
        const val TABLE_AT_4 = " | def __struct__/0 line 4 clauses 1 | def __struct__/1 line 4 clauses 1"
        const val G_AT_4 = " | def g/0 line 4 clauses 1"
    }
}
