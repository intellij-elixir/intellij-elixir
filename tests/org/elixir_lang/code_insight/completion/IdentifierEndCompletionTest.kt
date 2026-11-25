package org.elixir_lang.code_insight.completion

import org.elixir_lang.code_insight.completeCandidateAtCaret
import org.elixir_lang.code_insight.completion.code_fragment.CompletionRecorder
import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * What Tab replaces: the rest of the name or operator the caret is in, as far as Elixir reads it, so a name's `?` or
 * `!` goes with it, and so does an operator right after a dot. Both settings agree: Tab's end is set before either
 * source is asked.
 */
class IdentifierEndCompletionTest : ExpanderSettingTestCase() {
    fun testTabReplacesANamesQuestionMark() = assertTabReplacesANamesQuestionMark()

    fun testTabReplacesANamesQuestionMarkWithTheExpanderOn() = withTheExpanderOn(::assertTabReplacesANamesQuestionMark)

    fun testTabReplacesANamesExclamationMark() = assertTabReplacesANamesExclamationMark()

    fun testTabReplacesANamesExclamationMarkWithTheExpanderOn() = withTheExpanderOn(::assertTabReplacesANamesExclamationMark)

    fun testTabBeforeACommentKeepsIt() = assertTabBeforeACommentKeepsIt()

    fun testTabBeforeACommentKeepsItWithTheExpanderOn() = withTheExpanderOn(::assertTabBeforeACommentKeepsIt)

    fun testTabBeforeACommentAfterAStringHoldingAHashKeepsIt() = assertTabBeforeACommentAfterAStringHoldingAHashKeepsIt()

    fun testTabBeforeACommentAfterAStringHoldingAHashKeepsItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabBeforeACommentAfterAStringHoldingAHashKeepsIt)

    fun testTabBeforeACommentAfterAHashCharacterKeepsIt() = assertTabBeforeACommentAfterAHashCharacterKeepsIt()

    fun testTabBeforeACommentAfterAHashCharacterKeepsItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabBeforeACommentAfterAHashCharacterKeepsIt)

    fun testTabInAnAtomWithAnAtSignReplacesItsRest() = assertTabInAnAtomWithAnAtSignReplacesItsRest()

    fun testTabInAnAtomWithAnAtSignReplacesItsRestWithTheExpanderOn() =
        withTheExpanderOn(::assertTabInAnAtomWithAnAtSignReplacesItsRest)

    fun testTabInAnOperatorStartingWithAnExclamationMarkReplacesItsRest() =
        assertTabInAnOperatorStartingWithAnExclamationMarkReplacesItsRest()

    fun testTabInAnOperatorStartingWithAnExclamationMarkReplacesItsRestWithTheExpanderOn() =
        withTheExpanderOn(::assertTabInAnOperatorStartingWithAnExclamationMarkReplacesItsRest)

    fun testTabBeforeAnInterpolationKeepsIt() = assertTabBeforeAnInterpolationKeepsIt()

    fun testTabBeforeAnInterpolationKeepsItWithTheExpanderOn() = withTheExpanderOn(::assertTabBeforeAnInterpolationKeepsIt)

    fun testTabBeforeADotAfterTheCaretKeepsIt() = assertTabBeforeADotAfterTheCaretKeepsIt()

    fun testTabBeforeADotAfterTheCaretKeepsItWithTheExpanderOn() = withTheExpanderOn(::assertTabBeforeADotAfterTheCaretKeepsIt)

    fun testTabReplacesANamesExclamationMarkBeforeAComment() = assertTabReplacesANamesExclamationMarkBeforeAComment()

    fun testTabReplacesANamesExclamationMarkBeforeACommentWithTheExpanderOn() =
        withTheExpanderOn(::assertTabReplacesANamesExclamationMarkBeforeAComment)

    fun testTabInAnOperatorReplacesItsRest() = assertTabInAnOperatorReplacesItsRest()

    fun testTabInAnOperatorReplacesItsRestWithTheExpanderOn() = withTheExpanderOn(::assertTabInAnOperatorReplacesItsRest)

    fun testTabAfterADotReplacesTheOperatorAfterIt() = assertTabAfterADotReplacesTheOperatorAfterIt()

    fun testTabAfterADotReplacesTheOperatorAfterItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabAfterADotReplacesTheOperatorAfterIt)

    fun testTabAfterAnOperatorReplacesTheNameAfterIt() = assertTabAfterAnOperatorReplacesTheNameAfterIt()

    fun testTabAfterAnOperatorReplacesTheNameAfterItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabAfterAnOperatorReplacesTheNameAfterIt)

    fun testTabAfterAMinusReplacesTheNameAfterIt() = assertTabAfterAMinusReplacesTheNameAfterIt()

    fun testTabAfterAMinusReplacesTheNameAfterItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabAfterAMinusReplacesTheNameAfterIt)

    fun testTabAfterACapturesAmpersandReplacesTheNameAfterIt() = assertTabAfterACapturesAmpersandReplacesTheNameAfterIt()

    fun testTabAfterACapturesAmpersandReplacesTheNameAfterItWithTheExpanderOn() =
        withTheExpanderOn(::assertTabAfterACapturesAmpersandReplacesTheNameAfterIt)

    fun testTabBeforeACapturedOperatorsArityKeepsTheArity() = assertTabBeforeACapturedOperatorsArityKeepsTheArity()

    fun testTabBeforeACapturedOperatorsArityKeepsTheArityWithTheExpanderOn() =
        withTheExpanderOn(::assertTabBeforeACapturedOperatorsArityKeepsTheArity)

    private fun assertTabReplacesANamesQuestionMark() =
        assertTab(
            "  def valid?(x), do: x\n  def validate(x), do: x\n  def f, do: val<caret>id?(1)",
            "validate",
            "  def valid?(x), do: x\n  def validate(x), do: x\n  def f, do: validate(1)",
        )

    private fun assertTabReplacesANamesExclamationMark() =
        assertTab(
            "  def run!(x), do: x\n  def running(x), do: x\n  def f, do: ru<caret>n!(1)",
            "running",
            "  def run!(x), do: x\n  def running(x), do: x\n  def f, do: running(1)",
        )

    private fun assertTabBeforeACommentKeepsIt() =
        assertTab("  def f(running), do: ru<caret>n# keep", "running", "  def f(running), do: running# keep")

    private fun assertTabBeforeACommentAfterAStringHoldingAHashKeepsIt() =
        assertTab(
            "  def f(running), do: \"#\" <> ru<caret>n# keep",
            "running",
            "  def f(running), do: \"#\" <> running# keep",
        )

    private fun assertTabBeforeACommentAfterAHashCharacterKeepsIt() =
        assertTab("  def f(running), do: ?# + ru<caret>n# keep", "running", "  def f(running), do: ?# + running# keep")

    private fun assertTabInAnAtomWithAnAtSignReplacesItsRest() {
        myFixture.addFileToProject("lib/atoms.ex", "defmodule :food do\nend\n\ndefmodule :fond do\nend\n")
        assertTab("  def f, do: :fo<caret>o@bar", ":food", "  def f, do: :food")
    }

    private fun assertTabInAnOperatorStartingWithAnExclamationMarkReplacesItsRest() =
        assertTab("  def f(running), do: x !<caret>== y", "running", "  def f(running), do: x !running y")

    private fun assertTabBeforeAnInterpolationKeepsIt() {
        CompletionRecorder.register(testRootDisposable)
        configure("  def f(id), do: ~p\"/us<caret>ers#{id}\"")
        val hash = myFixture.editor.document.text.indexOf('#')

        assertEquals(hash, CompletionRecorder.record("~p\"/us|ers#{id}\"") { myFixture.completeBasic() }.identifierEnd)
    }

    /** The caret before a dot is in no name, so Tab replaces nothing after it. */
    private fun assertTabBeforeADotAfterTheCaretKeepsIt() =
        assertTab(
            "  def f(target, tango), do: target <caret>.field",
            "tango",
            "  def f(target, tango), do: target tango.field",
        )

    private fun assertTabReplacesANamesExclamationMarkBeforeAComment() =
        assertTab(
            "  def run!(x), do: x\n  def running(x), do: x\n  def f, do: ru<caret>n!# keep",
            "running",
            "  def run!(x), do: x\n  def running(x), do: x\n  def f, do: running(x)# keep",
        )

    private fun assertTabInAnOperatorReplacesItsRest() =
        assertTab("  def f(running), do: x -<caret>- y", "running", "  def f(running), do: x -running y")

    private fun assertTabAfterADotReplacesTheOperatorAfterIt() {
        myFixture.addFileToProject(
            "lib/tab_target.ex",
            "defmodule TabTarget do\n  def plus(a, b), do: a + b\n  def pair(a, b), do: {a, b}\nend\n",
        )
        assertTab("  def f, do: TabTarget.<caret>+(1, 2)", "pair", "  def f, do: TabTarget.pair(1, 2)")
    }

    /**
     * Elixir reads no context at an operator its arity's `/` follows, so Tab's end is the platform's, the reference's:
     * the operator, never the arity after it.
     */
    private fun assertTabBeforeACapturedOperatorsArityKeepsTheArity() {
        CompletionRecorder.register(testRootDisposable)
        configure("  def f, do: &Kernel.<caret>+/2")
        val arity = myFixture.caretOffset + "+".length

        assertEquals(arity, CompletionRecorder.record("&Kernel.|+/2") { myFixture.completeBasic() }.identifierEnd)
    }

    private fun assertTabAfterAnOperatorReplacesTheNameAfterIt() =
        assertTab(
            "  def valid?(x), do: x\n  def validate(x), do: x\n  def f(x), do: !<caret>valid?(x)",
            "validate",
            "  def valid?(x), do: x\n  def validate(x), do: x\n  def f(x), do: !validate(x)",
        )

    private fun assertTabAfterAMinusReplacesTheNameAfterIt() =
        assertTab(
            "  def size(x), do: x\n  def sized(x), do: x\n  def f(x), do: -<caret>size(x)",
            "sized",
            "  def size(x), do: x\n  def sized(x), do: x\n  def f(x), do: -sized(x)",
        )

    private fun assertTabAfterACapturesAmpersandReplacesTheNameAfterIt() =
        assertTab(
            "  def local(x), do: x\n  def locally(x), do: x\n  def f, do: &<caret>local(&1)",
            "locally",
            "  def local(x), do: x\n  def locally(x), do: x\n  def f, do: &locally(&1)",
        )

    /** Tab on [candidate] at [before]'s caret leaves [after]. */
    private fun assertTab(before: String, candidate: String, after: String) {
        configure(before)

        assertEquals(module(after), myFixture.completeCandidateAtCaret(candidate))
    }

    private fun configure(body: String) {
        myFixture.configureByText("tab.ex", module(body))
    }

    private fun module(body: String) = "defmodule Tab do\n$body\nend\n"
}
