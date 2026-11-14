package org.elixir_lang.documentation

/** Quick Documentation (Ctrl+Q) on the function atom of `apply/3` and an MFA tuple, bare or quoted. */
class MfaAtomQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocOnBareApplyAtomShowsAtDoc() = assertShowsDouble("apply(Documented, :dou<caret>ble, [1])")

    fun testQuickDocOnQuotedApplyAtomShowsAtDoc() = assertShowsDouble("apply(Documented, :\"dou<caret>ble\", [1])")

    fun testQuickDocOnQuotedApplyAtomsOpeningQuoteShowsAtDoc() =
        assertShowsDouble("apply(Documented, :<caret>\"double\", [1])")

    fun testQuickDocOnCharListQuotedApplyAtomShowsAtDoc() = assertShowsDouble("apply(Documented, :'dou<caret>ble', [1])")

    fun testQuickDocOnQuotedMfaTupleAtomShowsAtDoc() = assertShowsDouble("{Documented, :\"dou<caret>ble\", 1}")

    fun testQuickDocOnHexadecimalEscapeInQuotedApplyAtomShowsAtDoc() =
        assertShowsDouble("""apply(Documented, :"dou\x6<caret>2le", [1])""")

    fun testQuickDocOnHexadecimalEscapePrefixInQuotedApplyAtomShowsAtDoc() =
        assertShowsDouble("""apply(Documented, :"dou<caret>\x62le", [1])""")

    fun testQuickDocOnEscapedCharacterInQuotedApplyAtomShowsAtDoc() =
        assertShowsDouble("""apply(Documented, :"dou<caret>\"ble", [1])""", name = """unquote(:"dou\"ble")""")

    fun testQuickDocOnInterpolatedApplyAtomShowsNothing() {
        configure("apply(Documented, :\"dou<caret>ble#{x}\", [1])")
        assertNull(quickDocumentationAtCaret())
    }

    fun testQuickDocInAStringArgumentShowsNothing() {
        configure("Documented.double(\"dou<caret>ble\")")
        assertNull(quickDocumentationAtCaret())
    }

    fun testQuickDocOnEscapedCharacterInAStringArgumentShowsNothing() {
        configure("""Documented.double("dou<caret>\"ble")""")
        assertNull(quickDocumentationAtCaret())
    }

    /** `apply/3` and an MFA tuple call a function, so an atom naming only a macro documents nothing, as it always has. */
    fun testQuickDocOnAnApplyAtomNamingOnlyAMacroShowsNothing() {
        configureMacro("apply(Macros, :tri<caret>ple, [1])")
        assertNull(quickDocumentationAtCaret())
    }

    fun testQuickDocOnAnMfaTupleAtomNamingOnlyAMacroShowsNothing() {
        configureMacro("{Macros, :tri<caret>ple, 1}")
        assertNull(quickDocumentationAtCaret())
    }

    /** `apply/3` and an MFA tuple reach what another module exports, so a `defp` of that name and arity is not documented. */
    fun testQuickDocOnAnApplyAtomNamingOnlyAPrivateFunctionShowsNothing() {
        configurePrivate("apply(Vault, :sec<caret>ret, [1])")
        assertNull(quickDocumentationAtCaret())
    }

    fun testQuickDocOnAnMfaTupleAtomNamingOnlyAPrivateFunctionShowsNothing() {
        configurePrivate("{Vault, :sec<caret>ret, 1}")
        assertNull(quickDocumentationAtCaret())
    }

    // With Run the expander on, each answers as the walk does.

    fun testQuickDocOnBareApplyAtomShowsAtDocWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnBareApplyAtomShowsAtDoc)

    fun testQuickDocOnQuotedApplyAtomShowsAtDocWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnQuotedApplyAtomShowsAtDoc)

    fun testQuickDocOnQuotedApplyAtomsOpeningQuoteShowsAtDocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnQuotedApplyAtomsOpeningQuoteShowsAtDoc)

    fun testQuickDocOnCharListQuotedApplyAtomShowsAtDocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnCharListQuotedApplyAtomShowsAtDoc)

    fun testQuickDocOnQuotedMfaTupleAtomShowsAtDocWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnQuotedMfaTupleAtomShowsAtDoc)

    fun testQuickDocOnHexadecimalEscapeInQuotedApplyAtomShowsAtDocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnHexadecimalEscapeInQuotedApplyAtomShowsAtDoc)

    fun testQuickDocOnHexadecimalEscapePrefixInQuotedApplyAtomShowsAtDocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnHexadecimalEscapePrefixInQuotedApplyAtomShowsAtDoc)

    fun testQuickDocOnEscapedCharacterInQuotedApplyAtomShowsAtDocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnEscapedCharacterInQuotedApplyAtomShowsAtDoc)

    fun testQuickDocOnInterpolatedApplyAtomShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnInterpolatedApplyAtomShowsNothing)

    fun testQuickDocInAStringArgumentShowsNothingWithTheExpanderOn() = withTheExpanderOn(::testQuickDocInAStringArgumentShowsNothing)

    fun testQuickDocOnEscapedCharacterInAStringArgumentShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnEscapedCharacterInAStringArgumentShowsNothing)

    fun testQuickDocOnAnApplyAtomNamingOnlyAMacroShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnAnApplyAtomNamingOnlyAMacroShowsNothing)

    fun testQuickDocOnAnMfaTupleAtomNamingOnlyAMacroShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnAnMfaTupleAtomNamingOnlyAMacroShowsNothing)

    fun testQuickDocOnAnApplyAtomNamingOnlyAPrivateFunctionShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnAnApplyAtomNamingOnlyAPrivateFunctionShowsNothing)

    fun testQuickDocOnAnMfaTupleAtomNamingOnlyAPrivateFunctionShowsNothingWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnAnMfaTupleAtomNamingOnlyAPrivateFunctionShowsNothing)

    /** Past a stop, a call through an alias written below it documents the function the index finds. */
    fun testQuickDocOnACallPastAStopThroughALaterAliasShowsAtDocWithTheExpanderOn() =
        assertPastAStopShowsDouble("Documented.dou<caret>ble(x)")

    /** Past a stop, `apply/3` through an alias written below it documents what the call there does. */
    fun testQuickDocOnAnApplyAtomPastAStopThroughALaterAliasShowsAtDocWithTheExpanderOn() =
        assertPastAStopShowsDouble("apply(Documented, :dou<caret>ble, [x])")

    /** Past a stop, an MFA tuple through an alias written below it documents what the call there does. */
    fun testQuickDocOnAnMfaTupleAtomPastAStopThroughALaterAliasShowsAtDocWithTheExpanderOn() =
        assertPastAStopShowsDouble("{Documented, :dou<caret>ble, 1}")

    /** Past a stop, `apply/3` reaches only what another module exports, so the index's `defp` is not documented. */
    fun testQuickDocOnAnApplyAtomPastAStopNamingOnlyAPrivateFunctionShowsNothingWithTheExpanderOn() {
        configurePastAStop("apply(Documented, :sec<caret>ret, [x])")
        assertNull(quickDocumentationAtCaret())
    }

    /** Past a stop, an MFA tuple calls a function, so the index's macro is not documented. */
    fun testQuickDocOnAnMfaTupleAtomPastAStopNamingOnlyAMacroShowsNothingWithTheExpanderOn() {
        configurePastAStop("{Documented, :tri<caret>ple, 1}")
        assertNull(quickDocumentationAtCaret())
    }

    private fun assertPastAStopShowsDouble(usage: String) {
        configurePastAStop(usage)
        documentedDouble(usage)
    }

    /** `Usage` stops at `1.f()`, above the `alias` that names `Some.Documented`. */
    private fun configurePastAStop(usage: String) {
        expanderOn()
        myFixture.configureByText(
            "usage.ex",
            """
            defmodule Some.Documented do
              @doc "Doubles a number."
              def double(n), do: n * 2

              @doc "Not callable from outside."
              defp secret(n), do: n

              @doc "Triples at compile time."
              defmacro triple(n), do: quote(do: unquote(n) * 3)
            end

            defmodule Usage do
              1.f()
              alias Some.Documented

              def run(x) do
                $usage
              end
            end
            """.trimIndent()
        )
    }

    private fun configurePrivate(usage: String) {
        myFixture.configureByText(
            "usage.ex",
            """
            defmodule Vault do
              @doc "Not callable from outside."
              defp secret(n), do: n
            end

            defmodule Usage do
              def run(x) do
                $usage
              end
            end
            """.trimIndent()
        )
    }

    private fun configureMacro(usage: String) {
        myFixture.configureByText(
            "usage.ex",
            """
            defmodule Macros do
              @doc "Triples at compile time."
              defmacro triple(n), do: quote(do: unquote(n) * 3)
            end

            defmodule Usage do
              def run(x) do
                $usage
              end
            end
            """.trimIndent()
        )
    }

    private fun assertShowsDouble(usage: String, name: String = "double") {
        configure(usage, name)

        val documentation = documentedDouble(usage)

        assertTrue(
            "Expected the defining module in the documentation, got: $documentation",
            documentation.contains("<b>Documented</b>")
        )
        assertFalse("Expected no plain line, got: $documentation", PLAIN_LINE in documentation)
    }

    /** The Quick Documentation at the caret, which must show `double`'s `@doc`. */
    private fun documentedDouble(usage: String): String {
        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for `$usage`", documentation)
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation!!.contains("Doubles a number")
        )

        return documentation
    }

    private fun configure(usage: String, name: String = "double") {
        myFixture.configureByText(
            "usage.ex",
            """
            defmodule Documented do
              @doc "Doubles a number."
              def $name(n), do: n * 2
            end

            defmodule Usage do
              def run(x) do
                $usage
              end
            end
            """.trimIndent()
        )
    }
}
