package org.elixir_lang.declaration

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.call.Call

/**
 * The preference step over what the walk finds with `incompleteCode` `true`. Each test asserts that input first, as the
 * line of every declaring element, since `candidates(use, false)` stops at the first valid result and cannot show what
 * a stage drops.
 */
class PreferredTest : PlatformTestCase() {
    /** Pairing: the target of a `defdelegate` is paired with it, not an entry of its own. */
    fun testADelegationTargetIsPairedWithItsDelegation() {
        configure(
            """
            defmodule Target do
              def snoc(q, x), do: {q, x}
            end

            defmodule Delegator do
              defdelegate snoc(q, x), to: Target
            end

            defmodule Caller do
              def calls(a), do: Delegator.snoc(a<caret>, a)
            end
            """
        )

        assertEquals(lines(2, 6), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf("6 -> [2]"), preferredLines(incompleteCode))
        }
    }

    /** Pairing never drops: a target whose delegation is not a candidate stands as an entry of its own. */
    fun testATargetWhoseDelegationIsNotACandidateStandsOnItsOwn() {
        configure(
            """
            defmodule Target do
              def snoc(q, x), do: {q, x}
            end

            defmodule Delegator do
              defdelegate snoc(q, x), to: Target
            end

            defmodule Caller do
              def calls(a), do: Delegator.snoc(a<caret>, a)
            end
            """
        )

        val target = candidates().single { line(it.element) == 2 }

        assertEquals(listOf("2 -> []"), preferredLines(incompleteCode = true, found = listOf(target)))
    }

    /** A delegation found twice is paired once: the second finding reaches nothing. */
    fun testADelegationFoundTwiceIsPairedOnce() {
        configure(
            """
            defmodule Target do
              def snoc(q, x), do: {q, x}
            end

            defmodule Delegator do
              defdelegate snoc(q, x), to: Target
            end

            defmodule Caller do
              def calls(a), do: Delegator.snoc(a<caret>, a)
            end
            """
        )

        val found = candidates()
        val delegation = found.single { line(it.element) == 6 }

        assertEquals(
            listOf("6 -> [2]", "6 -> []"),
            preferredLines(incompleteCode = true, found = found + delegation)
        )
    }

    /** Pairing follows a chain of `defdelegate`s to the one the use names. */
    fun testAChainOfDelegationsIsPairedWithTheFirst() {
        configure(
            """
            defmodule C do
              def t(x), do: x
            end

            defmodule B do
              defdelegate t(x), to: C
            end

            defmodule A do
              defdelegate t(x), to: B
            end

            defmodule Caller do
              def calls(a), do: A.t(a<caret>)
            end
            """
        )

        assertEquals(lines(2, 6, 10), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf("10 -> [6, 2]"), preferredLines(incompleteCode))
        }
    }

    /** Admitted: a call reaches the arity that fits, and every arity of the name while it is typed. */
    fun testValidityDropsTheArityThatDoesNotFitOnlyOnCompleteCode() {
        configure(
            """
            defmodule Processor do
              def process(item), do: item
              def process(item, opts), do: {item, opts}

              def calls(x), do: process(x<caret>)
            end
            """
        )

        assertEquals(lines(2, 3), candidateLines())
        assertEquals(listOf("2 -> []", "3 -> []"), preferredLines(incompleteCode = true))
        assertEquals(listOf("2 -> []"), preferredLines(incompleteCode = false))
    }

    /** Overridden: the user's `exception/1` overrides the one `defexception` declares. */
    fun testAUsersDeclarationOverridesWhatDefexceptionDeclares() {
        configure(
            """
            defmodule E do
              defexception [:message]
              def exception(msg), do: %E{message: msg}
            end

            defmodule Caller do
              def calls(x), do: E.exception(x<caret>)
            end
            """
        )

        assertEquals(lines(2, 3), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf("3 -> []"), preferredLines(incompleteCode))
        }
    }

    /** Overridden: a `@callback` of the same name and arity is no user definition, so the generated one stands. */
    fun testACallbackDoesNotOverrideWhatDefexceptionDeclares() {
        configure(
            """
            defmodule E do
              defexception [:message]
              @callback message(term()) :: String.t()
            end

            defmodule Caller do
              def calls(x), do: E.message(x<caret>)
            end
            """
        )

        assertEquals(lines(2, 3), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertTrue(
                "incompleteCode $incompleteCode",
                preferredLines(incompleteCode).any { it.startsWith("2 ") }
            )
        }
    }

    /** Overridden: an imported function is not an override, since only a definition in the module itself is. */
    fun testAnImportedFunctionDoesNotOverrideWhatDefexceptionDeclares() {
        configure(
            """
            defmodule Helper do
              def message(value, opts \\ []), do: {value, opts}
            end

            defmodule E do
              import Helper, only: [message: 2]
              defexception [:message]

              def run(e), do: message(e<caret>)
            end
            """
        )

        assertEquals(lines(2, 7), candidateLines())

        assertEquals(setOf("2 -> []", "7 -> []"), preferredLines(true).toSet())
        // The import brings in `message/2` only, so a complete call of one argument cannot use it.
        assertEquals(listOf("7 -> []"), preferredLines(false))
    }

    /** Overridden: what a `use` injects is the using module's own, so the module's `def` replaces it. */
    fun testADefinitionReplacesWhatDefexceptionDeclaresThroughUse() {
        configure(
            """
            defmodule ErrorBase do
              defmacro __using__(_) do
                quote do
                  defexception [:message]
                end
              end
            end

            defmodule E do
              use ErrorBase

              def message(error), do: error.message

              def run(e), do: message(e<caret>)
            end
            """
        )

        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf("12 -> []"), preferredLines(incompleteCode))
        }
    }

    /** Overridden leaves what `defexception` declares where the user declares nothing of that arity. */
    fun testAGeneratedDeclarationStandsWhereTheUserDeclaresAnotherArity() {
        configure(
            """
            defmodule E do
              defexception [:message]
              def exception(msg, extra), do: {msg, extra}
            end

            defmodule Caller do
              def calls(x), do: E.exception(x<caret>)
            end
            """
        )

        assertEquals(lines(2, 3), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertTrue(
                "incompleteCode $incompleteCode",
                preferredLines(incompleteCode).any { it.startsWith("2 ") }
            )
        }
    }

    /** Admitted inside `delegatedTo`: the target arity the head does not call is dropped on complete code. */
    fun testValidityAlsoNarrowsWhatADelegationReaches() {
        configure(
            """
            defmodule T do
              def snoc(q), do: q
              def snoc(q, x), do: {q, x}
            end

            defmodule D do
              defdelegate snoc(q, x \\ nil), to: T
            end

            defmodule Caller do
              def calls(q), do: D.snoc(q<caret>)
            end
            """
        )

        assertEquals(lines(2, 3, 7), candidateLines())
        assertEquals(listOf("7 -> [2, 3]"), preferredLines(incompleteCode = true))
        assertEquals(listOf("7 -> [3]"), preferredLines(incompleteCode = false))
    }

    /** Overridden inside `delegatedTo`: the user's `exception/1` stands in for the generated one. */
    fun testAUsersDeclarationAlsoOverridesWhatADelegationReaches() {
        configure(
            """
            defmodule E do
              defexception [:message]
              def exception(msg), do: %E{message: msg}
            end

            defmodule D do
              defdelegate exception(msg), to: E
            end

            defmodule Caller do
              def calls(x), do: D.exception(x<caret>)
            end
            """
        )

        assertEquals(lines(2, 3, 7), candidateLines())

        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf("7 -> [3]"), preferredLines(incompleteCode))
        }
    }

    private fun configure(text: String) {
        myFixture.configureByText("preferred.ex", text.trimIndent())
    }

    private fun use(): Use.Named {
        val arguments = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), Arguments::class.java)

        return Use.of(PsiTreeUtil.getParentOfType(arguments, Call::class.java)!!) as Use.Named
    }

    private fun candidates(): List<Found> = sourceFor(Feature.PARAMETER_INFO).candidates(use(), true)

    private fun candidateLines(): Set<Int> = candidates().map { line(it.element) }.toSet()

    /** Each entry as `line -> [lines of what it reaches]`, in the order given. */
    private fun preferredLines(incompleteCode: Boolean, found: List<Found> = candidates()): List<String> =
        preferred(use(), found, incompleteCode).map { entry ->
            "${line(entry.found.element)} -> ${entry.delegatedTo.map { line(it.element) }}"
        }

    private fun lines(vararg lines: Int): Set<Int> = lines.toSet()

    private fun line(element: PsiElement): Int =
        PsiDocumentManager.getInstance(project).getDocument(element.containingFile)!!
            .getLineNumber(element.textRange.startOffset) + 1
}
