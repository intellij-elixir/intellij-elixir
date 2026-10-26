package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_DERIVING_MACRO
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A non-empty `@derive`: `Protocol.__derive__/3` runs the first protocol written last, and the module stops at the
 * `defstruct` where Elixir would expand its `__deriving__` macro, with the definitions of the struct kept. The output's
 * statements after the stop are declined, so up to 1.13 both arms of its `case` on `@enforce_keys` store `__struct__/1`.
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

        assertLevels(code, LEVELS) {
            "$STOPPED${stopped(3, it)} | ${if (PROTOCOL_DERIVING_MACRO.isSufficient(ElixirLanguageLevel.of(it))) derivable else any}"
        }
    }

    fun testAnAtomOnItsOwnStopsAtTheImplementationsMacro() =
        assertStopped("defmodule A do\n  @derive Plain\n  defstruct [:a]\nend", 3, PLAIN)

    fun testAProtocolWithOptionsDoes() =
        assertStopped("defmodule A do\n  @derive [{Plain, only: [:a]}]\n  defstruct [:a]\nend", 3, PLAIN)

    fun testTheProtocolWrittenLastRunsFirst() =
        assertStopped("defmodule A do\n  @derive Fallback\n  @derive Plain\n  defstruct [:a]\nend", 4, PLAIN)

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
            "$STOPPED$TABLE_AT_3 | opaque remote_macro Elixir.NoAny.__deriving__/2",
        )

    fun testAnImplementationForAnyThatIsNoImplementationRaises() =
        assertEvery(
            "defmodule A do\n  @derive [Odd]\n  defstruct [:a]\nend",
            "module Elixir.A raised derive_not_an_implementation `defstruct [:a]`",
        )

    /** The `if` on the struct's keys after the stop is declined too, so both arms' definitions are stored. */
    fun testAnExceptionStopsAtItsStructAndKeepsItsOverridableDefinition() {
        val code = "defmodule A do\n  @derive Plain\n  defexception [:reason]\nend"
        val overridable = " | def message/1 line 3 clauses 1 | def exception/1 line 3 clauses 2"

        assertLevels(code, LEVELS) { "module Elixir.A stopped `defexception [:reason]`${stopped(3, it)}$overridable | $PLAIN" }
    }

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { result ->
            (listOf(ModuleRendering.render(code, result)) + result.opaque.map { "opaque ${render(it.dispatch)}" })
                .joinToString(" | ")
        }
    }

    /** [code] stops at its `defstruct` on line [line] at every level, keeping the struct's definitions, with [opaque] after them. */
    private fun assertStopped(code: String, line: Int, opaque: String? = null) =
        assertLevels(code, LEVELS) { "$STOPPED${stopped(line, it)}${opaque?.let { macro -> " | $macro" }.orEmpty()}" }

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
        const val PLAIN = "opaque remote_macro Elixir.Plain.Any.__deriving__/3"
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
    }
}
