package org.elixir_lang.inspection

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiFile
import org.elixir_lang.declaration.eachExpanderSetting
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.TypeQualifierShapes.ACTUAL
import org.elixir_lang.expander.TypeQualifierShapes.FALLBACK
import org.elixir_lang.expander.TypeQualifierShapes.MISSING
import org.elixir_lang.expander.TypeQualifierShapes.MISSING_UNDER_IF
import org.elixir_lang.expander.TypeQualifierShapes.MODULE_CREATE
import org.elixir_lang.expander.TypeQualifierShapes.NESTED_BEFORE_AND_AFTER
import org.elixir_lang.expander.TypeQualifierShapes.PAST_A_STOP
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_TARGETS
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_USER
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_USING
import org.elixir_lang.expander.TypeQualifierShapes.STOPS
import org.elixir_lang.expander.TypeQualifierShapes.STRING_TYPES
import org.elixir_lang.expander.addOtpErts
import org.elixir_lang.expander.addOtpStdlib
import org.elixir_lang.expander.attachSdkSources

/**
 * With Run the expander on, the undefined-type inspection reports a qualified type only where the module the expander
 * finds is held, its types were read, and none of them is the one named.
 */
class UnresolvableTypeExpanderTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        enableExpander(myFixture.testRootDisposable)
        myFixture.enableInspections(UnresolvableType::class.java)
    }

    /** An Elixir-compiled module's types are read from its `.beam`, so one it doesn't define is reported. */
    fun testATypeOfAnElixirCompiledModuleWithNoSourceIsReportedWhenUndefinedUnderEitherSetting() {
        val file = caller(STRING_TYPES, "lib/s1.ex")

        underEitherSetting { setting ->
            assertEquals("expander $setting", listOf(undefined("nope", "String.nope/0")), reports(file))
        }
    }

    /** The protocol's `t` is in its beam's debug info though its source doesn't write it. */
    fun testTheDefaultTypeOfAProtocolWithNoSourceIsNotReported() {
        val file = caller("defmodule P do\n  @type a :: String.Chars.t()\n  @type b :: String.Chars.nope()\nend\n", "lib/p.ex")

        underEitherSetting { setting ->
            assertEquals("expander $setting", listOf(undefined("nope", "String.Chars.nope/0")), reports(file))
        }
    }

    fun testATypeOfAnElixirModuleWithItsSourceAttachedIsNotReported() {
        attachSdkSources(module)

        assertEmpty(reports("lib/s1.ex" to STRING_TYPES.replace("  @type nope :: String.nope()\n", "")))
    }

    fun testAnUndefinedTypeOfAnElixirModuleWithItsSourceAttachedIsReported() {
        attachSdkSources(module)

        assertEquals(listOf(undefined("nope", "String.nope/0")), reports("lib/s1.ex" to STRING_TYPES))
    }

    fun testAnUndefinedTypeOfAnErlangModuleIsReported() {
        addOtpStdlib(module, myFixture.testRootDisposable)

        assertEquals(
            listOf(undefined("missing", ":queue.missing/0")),
            reports("lib/s11.ex" to "defmodule S11 do\n  @type t :: :queue.missing()\nend\n"),
        )
    }

    /**
     * OTP 28's `-nominal` declares a type like `-type`: `timer`'s `time/0` is one. A type `timer` doesn't declare is
     * still reported, so `time/0` is not silent only because `timer`'s types are not read.
     */
    fun testANominalTypeOfAnErlangModuleIsNotReported() {
        addOtpStdlib(module, myFixture.testRootDisposable)
        val declared = caller("defmodule N do\n  @type t :: :timer.time()\nend\n", "lib/n.ex")
        val undeclared = caller("defmodule M do\n  @type t :: :timer.nope()\nend\n", "lib/m.ex")

        underEitherSetting { setting ->
            assertEmpty("expander $setting", reports(declared))
            assertEquals("expander $setting", listOf(undefined("nope", ":timer.nope/0")), reports(undeclared))
        }
    }

    /** `erlang` declares `timestamp/0` itself: with erts added, only a type it doesn't declare is reported. */
    fun testAnErlangTypeOfErlangIsNotReported() {
        addOtpErts(module, myFixture.testRootDisposable)
        val declared = caller("defmodule E do\n  @spec f() :: :erlang.timestamp()\n  def f, do: :ok\nend\n", "lib/e.ex")
        val undeclared = caller("defmodule U do\n  @spec f() :: :erlang.nope()\n  def f, do: :ok\nend\n", "lib/u.ex")

        underEitherSetting { setting ->
            assertEmpty("expander $setting", reports(declared))
            assertEquals("expander $setting", listOf(undefined("nope", ":erlang.nope/0")), reports(undeclared))
        }
    }

    fun testATypeOfANestedModuleNamedBeforeItIsDefinedIsNotReported() = assertEmpty(reports("lib/s3.ex" to NESTED_BEFORE_AND_AFTER))

    /** The module is under an `if` the expansion assumed, so whether it is defined isn't known. */
    fun testATypeOfAModuleDefinedUnderAnAssumedConditionIsNotReported() =
        assertEmpty(reports("lib/other.ex" to MISSING_UNDER_IF, "lib/s10.ex" to MISSING))

    /** No module is defined, which `UnresolvableModuleQualifier` reports. */
    fun testATypeOfAnUndefinedModuleIsNotReported() = assertEmpty(reports("lib/s10.ex" to MISSING))

    fun testATypeOfAModuleAnAliasInTheSameQuoteNamesIsNotReported() =
        assertEmpty(reports("lib/targets12.ex" to QUOTED_ALIAS_TARGETS, "lib/s12.ex" to QUOTED_ALIAS_USER, "lib/quoting12.ex" to QUOTED_ALIAS_USING))

    /** Elixir's answer: `Module.create` defines the module and its type, and writes no `defmodule` to read them from. */
    fun testATypeOfAModuleModuleCreateMakesIsNotReported() = assertEmpty(reports("lib/s9.ex" to MODULE_CREATE))

    /**
     * Past a stop, the env misses an `alias` written after it, so `Fallback` there may be `Actual`: a type `Fallback`
     * doesn't define isn't reported.
     */
    fun testPastAStopATypeOfAModuleAnAliasAfterTheStopMayNameIsNotReported() =
        assertEmpty(
            reports("lib/m.ex" to STOPS, "lib/actual.ex" to ACTUAL, "lib/fallback.ex" to FALLBACK, "lib/caller.ex" to PAST_A_STOP),
        )

    /** [block] with Run the expander off, then on, told which. */
    private fun underEitherSetting(block: (setting: String) -> Unit) = eachExpanderSetting(myFixture.testRootDisposable, block)

    /** The inspection's reports in the last of [files], each as `<reported text>: <message>`. */
    private fun reports(vararg files: Pair<String, String>): List<String> =
        reports(files.map { (path, text) -> caller(text, path) }.last())

    /** The inspection's reports in [file], each as `<reported text>: <message>`. */
    private fun reports(file: PsiFile): List<String> {
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        return myFixture.doHighlighting()
            .filter { it.inspectionToolId == "UnresolvableType" }
            .map { "${it.text}: ${it.description}" }
    }

    private companion object {
        fun undefined(text: String, type: String) = "$text: Type '$type' is not defined"
    }
}
