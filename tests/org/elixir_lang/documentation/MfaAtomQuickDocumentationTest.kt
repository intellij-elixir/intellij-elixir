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

    private fun assertShowsDouble(usage: String, name: String = "double") {
        configure(usage, name)

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for `$usage`", documentation)
        assertTrue(
            "Expected the defining module in the documentation, got: $documentation",
            documentation!!.contains("<b>Documented</b>")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Doubles a number")
        )
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
