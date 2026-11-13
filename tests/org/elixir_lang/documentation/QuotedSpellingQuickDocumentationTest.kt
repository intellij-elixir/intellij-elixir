package org.elixir_lang.documentation

/**
 * Quick Documentation (Ctrl+Q) on a function's name in an `apply`, an MFA tuple, a remote call and a capture documents
 * the function however its name is spelled: bare, quoted, or quoted with an escape.
 */
class QuotedSpellingQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testApplyBare() = assertDocumentsHello("apply(Q, :hel<caret>lo, [\"a\"])")
    fun testApplyQuoted() = assertDocumentsHello("apply(Q, :\"hel<caret>lo\", [\"a\"])")
    fun testApplyEscaped() = assertDocumentsHello("apply(Q, :\"he<caret>l\\x6co\", [\"a\"])")
    fun testMfaBare() = assertDocumentsHello("{Q, :hel<caret>lo, 1}")
    fun testMfaQuoted() = assertDocumentsHello("{Q, :\"hel<caret>lo\", 1}")
    fun testMfaEscaped() = assertDocumentsHello("{Q, :\"he<caret>l\\x6co\", 1}")
    fun testRemoteCallBare() = assertDocumentsHello("Q.hel<caret>lo(\"b\")")
    fun testRemoteCallQuoted() = assertDocumentsHello("Q.\"hel<caret>lo\"(\"b\")")
    fun testRemoteCallEscaped() = assertDocumentsHello("Q.\"he<caret>l\\x6co\"(\"d\")")
    fun testCaptureBare() = assertDocumentsHello("&Q.hel<caret>lo/1")
    fun testCaptureQuoted() = assertDocumentsHello("&Q.\"hel<caret>lo\"/1")
    fun testCaptureEscaped() = assertDocumentsHello("&Q.\"he<caret>l\\x6co\"/1")
    fun testRemoteCallAtTheEscape() = assertDocumentsHello("Q.\"hel\\x6<caret>co\"(\"d\")")
    fun testMfaAtTheEscape() = assertDocumentsHello("{Q, :\"hel\\x6<caret>co\", 1}")

    fun testApplyBareWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("apply(Q, :hel<caret>lo, [\"a\"])")
    fun testApplyQuotedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("apply(Q, :\"hel<caret>lo\", [\"a\"])")
    fun testApplyEscapedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("apply(Q, :\"he<caret>l\\x6co\", [\"a\"])")
    fun testMfaBareWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("{Q, :hel<caret>lo, 1}")
    fun testMfaQuotedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("{Q, :\"hel<caret>lo\", 1}")
    fun testMfaEscapedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("{Q, :\"he<caret>l\\x6co\", 1}")
    fun testRemoteCallBareWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("Q.hel<caret>lo(\"b\")")
    fun testRemoteCallQuotedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("Q.\"hel<caret>lo\"(\"b\")")
    fun testRemoteCallEscapedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("Q.\"he<caret>l\\x6co\"(\"d\")")
    fun testCaptureBareWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("&Q.hel<caret>lo/1")
    fun testCaptureQuotedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("&Q.\"hel<caret>lo\"/1")
    fun testCaptureEscapedWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("&Q.\"he<caret>l\\x6co\"/1")
    fun testRemoteCallAtTheEscapeWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("Q.\"hel\\x6<caret>co\"(\"d\")")
    fun testMfaAtTheEscapeWithTheExpanderOn() = assertDocumentsHelloWithTheExpanderOn("{Q, :\"hel\\x6<caret>co\", 1}")

    private fun assertDocumentsHelloWithTheExpanderOn(use: String) {
        expanderOn()
        assertDocumentsHello(use)
    }

    private fun assertDocumentsHello(use: String) {
        myFixture.configureByText(
            "quoted_spelling.ex",
            """
            defmodule Q do
              @doc "Says hello."
              def hello(name), do: name
            end

            defmodule Caller do
              def call do
                $use
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should document Q.hello/1 at `$use`", documentation)
        assertTrue("Expected Q.hello/1's @doc at `$use`, got: $documentation", "Says hello." in documentation!!)
    }
}
