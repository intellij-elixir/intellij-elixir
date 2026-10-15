package org.elixir_lang.declaration

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.parameterInfoSignaturesAtCaret
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.call.Call

/**
 * `defoverridable` makes overridable only what is already defined when it runs, so a later definition of that name and
 * arity is the user's, and a call names it alone.
 */
class OverridableTest : PlatformTestCase() {
    fun testADefinitionThatDefoverridableFollowsIsReplacedByALaterOne() {
        configure(
            """
            defmodule M do
              def f(original), do: original

              defoverridable [f: 1]

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
    }

    fun testWhatAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition() {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x

                  defoverridable g: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
    }

    fun testADelegationThatDefoverridableFollowsIsReplacedByALaterDefinition() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def id(s), do: s\nend\n")
        configure(
            """
            defmodule M do
              defdelegate id(s), to: T

              defoverridable id: 1

              def id(user), do: user

              def run(y), do: id(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
    }

    fun testADelegationAUseInjectsWithItsDefoverridableIsReplacedByTheUsingModulesDefinition() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def id(s), do: s\nend\n")
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  defdelegate id(s), to: T

                  defoverridable id: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              def id(user), do: user

              def run(y), do: id(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
    }

    /** What a `use` injects is defined where the `use` is, so a `defoverridable` after the `use` takes it. */
    fun testWhatAUseInjectsIsMadeOverridableByADefoverridableAfterTheUse() {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use B

              defoverridable g: 1

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("6 -> []")
    }

    /** A `use` inside a `use`'s `quote` injects where that `quote` is, so a `defoverridable` after it there takes it. */
    fun testWhatANestedUseInjectsIsMadeOverridableByADefoverridableAfterIt() {
        myFixture.addFileToProject(
            "b.ex",
            """
            defmodule B do
              defmacro __using__(_) do
                quote do
                  def g(x), do: x
                end
              end
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "a.ex",
            """
            defmodule A do
              defmacro __using__(_) do
                quote do
                  use B

                  defoverridable g: 1
                end
              end
            end
            """.trimIndent()
        )
        configure(
            """
            defmodule M do
              use A

              def g(user), do: user

              def run(y), do: g(y<caret>)
            end
            """
        )

        assertEquals(listOf("user"), myFixture.parameterInfoSignaturesAtCaret())
        assertStepKeeps("4 -> []")
    }

    fun testDefoverridableOfAnotherArityLeavesTheDefinitionTheUsers() {
        configure(
            """
            defmodule M do
              def f(original), do: original
              def f(a, b), do: {a, b}

              defoverridable [f: 2]

              def f(user), do: user

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("2 -> []", "7 -> []"), kept(incompleteCode = false))
    }

    /** A head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, which Elixir keeps, to the head. */
    fun testDefoverridableOfOneArityOfADefaultHeadLeavesItTheOther() {
        configure(
            """
            defmodule M do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 2

              def f(a, b), do: {b, a}

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("a, b \\\\ 1"), myFixture.parameterInfoSignaturesAtCaret())
        assertEquals(listOf("2 -> []", "6 -> []"), kept(incompleteCode = true))
        assertEquals(listOf("2 -> []"), kept(incompleteCode = false))
    }

    fun testAWrongArityCallOffersADefaultHeadBesideTheOverrideOfOneOfItsArities() {
        configure(
            """
            defmodule M do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 2

              def f(a, b), do: {b, a}

              def run, do: f(<caret>)
            end
            """
        )

        assertEquals(listOf("2 -> []", "6 -> []"), kept(incompleteCode = false))
    }

    /** Replacing every arity a default head declares replaces the head. */
    fun testDefoverridableOfEveryArityOfADefaultHeadReplacesIt() {
        configure(
            """
            defmodule M do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 1, f: 2

              def f(a), do: a
              def f(a, b), do: {b, a}

              def run(y), do: f(y<caret>)
            end
            """
        )

        assertEquals(listOf("6 -> []", "7 -> []"), kept(incompleteCode = true))
    }

    private fun configure(text: String) {
        myFixture.configureByText("preferred.ex", text.trimIndent())
    }

    /** The step keeps [expected] alone, whether or not code is complete. */
    private fun assertStepKeeps(expected: String) {
        for (incompleteCode in listOf(true, false)) {
            assertEquals("incompleteCode $incompleteCode", listOf(expected), kept(incompleteCode))
        }
    }

    /** What the step keeps at the call, as `line -> [lines of what it delegates to]`. */
    private fun kept(incompleteCode: Boolean): List<String> {
        val use = Use.of(call()) as Use.Named

        return preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, true), incompleteCode).map { entry ->
            "${line(entry.found.element)} -> ${entry.delegatedTo.map { line(it.element) }}"
        }
    }

    private fun call(): Call {
        val arguments = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), Arguments::class.java)

        return PsiTreeUtil.getParentOfType(arguments, Call::class.java)!!
    }

    private fun line(element: PsiElement): Int =
        PsiDocumentManager.getInstance(project).getDocument(element.containingFile)!!
            .getLineNumber(element.textRange.startOffset) + 1
}
