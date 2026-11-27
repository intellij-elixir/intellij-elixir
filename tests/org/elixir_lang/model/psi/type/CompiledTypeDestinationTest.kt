package org.elixir_lang.model.psi.type

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiElement
import org.elixir_lang.code_insight.enclosingCall
import org.elixir_lang.declaration.eachExpanderSetting
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.addOtpErts
import org.elixir_lang.expander.addOtpStdlib
import org.elixir_lang.expander.attachSdkSources
import org.elixir_lang.expander.legLevel
import org.elixir_lang.structure_view.element.Type as TypeElement

/**
 * Go to Declaration at a type of a module with only its `.beam`: it lands on the decompiled mirror's `@type`, the
 * same whether or not the expander runs, since the type stubs are read from the beam either way.
 */
class CompiledTypeDestinationTest : LegSdkFixtureTestCase() {
    fun testAnElixirModulesTypeWithNoSourceIsTheMirrorsTypeUnderEitherSetting() {
        caller("defmodule Caller do\n  @type t :: String.t()\nend")

        assertUnderEitherSetting(
            "Elixir.String.beam.decompiled.ex: @type t :: binary()",
            "@type t :: String.<caret>t()",
        )
    }

    fun testAProtocolsDefaultTypeWithNoSourceIsTheMirrorsTypeUnderEitherSetting() {
        caller("defmodule Caller do\n  @type t :: String.Chars.t()\nend")

        assertUnderEitherSetting(
            "Elixir.String.Chars.beam.decompiled.ex: @type t :: term()",
            "@type t :: String.Chars.<caret>t()",
        )
    }

    /** The source's `defprotocol` doesn't write `t`, so the beam's `t/0` is the only declaration. */
    fun testAProtocolsDefaultTypeWithItsSourceAttachedIsTheMirrorsTypeUnderEitherSetting() {
        attachSdkSources(module)
        caller("defmodule Caller do\n  @type t :: String.Chars.t()\nend")

        assertUnderEitherSetting(
            "Elixir.String.Chars.beam.decompiled.ex: @type t :: term()",
            "@type t :: String.Chars.<caret>t()",
        )
    }

    /**
     * OTP 28's `-nominal` type, which `timer` declares as `time/0` and exports. Before OTP 28 `time/0` is a private
     * `-type`, so the mirror declares it with `@typep`.
     */
    fun testANominalTypeOfAnErlangModuleIsTheMirrorsTypeUnderEitherSetting() {
        addOtpStdlib(module, myFixture.testRootDisposable)
        caller("defmodule Caller do\n  @type t :: :timer.time()\nend")

        assertUnderEitherSetting(
            "timer.beam.decompiled.ex: ${if (otpMajor() >= 28) "@type" else "@typep"} time :: non_neg_integer()",
            "@type t :: :timer.<caret>time()",
        )
    }

    /** `erlang` declares `timestamp/0` itself, so the mirror has the `@type`. */
    fun testAnErlangTypeOfErlangIsTheMirrorsTypeUnderEitherSetting() {
        addOtpErts(module, myFixture.testRootDisposable)
        caller("defmodule Caller do\n  @type t :: :erlang.timestamp()\nend")

        assertUnderEitherSetting(
            "erlang.beam.decompiled.ex: @type timestamp :: {megaSecs :: non_neg_integer(), secs :: non_neg_integer(), microSecs :: non_neg_integer()}",
            "@type t :: :erlang.<caret>timestamp()",
        )
    }

    private fun otpMajor(): Int = legLevel().otp!!.otpMajor.toInt()

    private fun assertUnderEitherSetting(expected: String, marked: String) =
        eachExpanderSetting(myFixture.testRootDisposable) { setting ->
            assertEquals("expander $setting", expected, destinations(marked, ::typeOf))
        }

    /** [destination]'s file and the first line of the type that declares it. */
    private fun typeOf(destination: PsiElement): String {
        val type = destination.enclosingCall { TypeElement.`is`(it) }

        return "${destination.containingFile.name}: ${(type ?: destination).text.lineSequence().first()}"
    }
}
