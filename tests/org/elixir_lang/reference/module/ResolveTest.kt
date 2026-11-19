package org.elixir_lang.reference.module

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.__MODULE__
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Module

/**
 * What a module alias's, an atom's, a `__MODULE__`'s and a LEEx assign's reference resolves to: the first valid of its
 * `multiResolve(false)`, else nothing. Unit tests of the overrides: Go To at each reads `multiResolve`.
 */
class ResolveTest : PlatformTestCase() {
    /** The `alias` the use was reached through stays among the results, but is not what the use names. */
    fun testAnAliasedModuleResolvesToItsDefmodule() {
        myFixture.addFileToProject("b.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  alias Foo.Bar\n\n  def run, do: <caret>Bar\nend\n")

        assertResolves(
            reference(PsiTreeUtil.getParentOfType(elementAtCaret(), QualifiableAlias::class.java)!!),
            "b.ex:1: defmodule Foo.Bar do",
            listOf("b.ex:1: defmodule Foo.Bar do (true)", "use.ex:2: alias Foo.Bar (true)")
        )
    }

    fun testAModuleDefinedTwiceResolvesToTheFirst() {
        myFixture.addFileToProject("b1.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.addFileToProject("b2.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: Foo.<caret>Bar\nend\n")

        assertResolves(
            readAction { assertInstanceOf(myFixture.file.findReferenceAt(myFixture.caretOffset), Module::class.java) },
            "b1.ex:1: defmodule Foo.Bar do",
            listOf("b1.ex:1: defmodule Foo.Bar do (true)", "b2.ex:1: defmodule Foo.Bar do (true)")
        )
    }

    fun testAnAtomNamingAModuleDefinedTwiceResolvesToTheFirst() {
        myFixture.addFileToProject("b1.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.addFileToProject("b2.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: <caret>:\"Elixir.Foo.Bar\"\nend\n")

        assertResolves(
            reference(PsiTreeUtil.getParentOfType(elementAtCaret(), ElixirAtom::class.java, false)!!),
            "b1.ex:1: defmodule Foo.Bar do",
            listOf("b1.ex:1: defmodule Foo.Bar do (true)", "b2.ex:1: defmodule Foo.Bar do (true)")
        )
    }

    fun test__MODULE__ResolvesToItsModule() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: <caret>__MODULE__\nend\n")

        val call = readAction {
            PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == "__MODULE__" }
        }

        assertResolves(
            readAction { __MODULE__.reference(call) as PsiPolyVariantReference },
            "m.ex:1: defmodule M do",
            listOf("m.ex:1: defmodule M do (true)")
        )
    }

    /** The `unquote` runs where the `quote` is, past the module the `quote` writes around it. */
    fun test__MODULE__UnquotedInAModuleInAQuoteResolvesToTheQuotesModule() {
        myFixture.configureByText(
            "m.ex",
            """
            defmodule Outer do
              defmacro __using__(_) do
                quote do
                  defmodule Inner do
                    def owner, do: unquote(__MODULE__)
                  end
                end
              end
            end
            """.trimIndent()
        )

        assertResolves(currentModuleReference(), "m.ex:1: defmodule Outer do", listOf("m.ex:1: defmodule Outer do (true)"))
    }

    /** A module defined in an `unquote` is defined where the `quote` is, so its body is its own. */
    fun test__MODULE__InAModuleInAnUnquoteResolvesToThatModule() {
        myFixture.configureByText(
            "m.ex",
            """
            defmodule Outer do
              quote(do: unquote((defmodule Inner do
                def owner, do: __MODULE__
              end; :ok)))
            end
            """.trimIndent()
        )

        val inner = "m.ex:2: quote(do: unquote((defmodule Inner do"

        assertResolves(currentModuleReference(), inner, listOf("$inner (true)"))
    }

    fun testAnAssignSetInTwoFunctionsResolvesToTheFirst() {
        myFixture.addFileToProject(
            "lib/app_web/live/count_live.ex",
            """
            defmodule CountLive do
              def mount(_params, _session, socket) do
                assign(socket, :count, 0)
              end

              def handle_event("inc", _params, socket) do
                assign(socket, :count, 1)
              end
            end
            """.trimIndent()
        )
        val template = myFixture.addFileToProject("lib/app_web/live/count_live.html.leex", "<%= @count %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)

        val atOperation = readAction {
            val elixirRoot = myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" }

            PsiTreeUtil.findChildOfType(elixirRoot, AtOperation::class.java)!!
        }

        assertResolves(
            reference(atOperation),
            "count_live.ex:3: assign(socket, :count, 0)",
            listOf("count_live.ex:3: assign(socket, :count, 0) (true)", "count_live.ex:7: assign(socket, :count, 1) (true)")
        )
    }

    /** The alias a `defmodule` in a call's arguments sets holds for the expressions after the call. */
    fun testAModuleDefinedInACallsArgumentsResolvesAfterTheCall() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                write_beam(defmodule WithoutDocs do
                end)

                Code.fetch_docs(<caret>WithoutDocs)
              end
            end
            """,
            "use.ex:3: write_beam(defmodule WithoutDocs do"
        )
    }

    fun testAModuleDefinedInTheArgumentsOfACallsArgumentResolvesAfterTheCall() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                elem(IO.inspect(defmodule Nested do
                end), 0)

                <caret>Nested
              end
            end
            """,
            "use.ex:3: elem(IO.inspect(defmodule Nested do"
        )
    }

    /** A `do` block's alias holds only in that block. */
    fun testAModuleDefinedInADoBlockDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                if true do
                  defmodule InIf do
                  end
                end

                <caret>InIf
              end
            end
            """,
            null
        )
    }

    fun testAModuleDefinedInADoKeywordDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                if(true, do: defmodule(InIf, do: nil))

                <caret>InIf
              end
            end
            """,
            null
        )
    }

    /** A `fn`'s alias holds only in its body. */
    fun testAModuleDefinedInAFnDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                Task.async(fn -> defmodule InFn do end end)

                <caret>InFn
              end
            end
            """,
            null
        )
    }

    /** A capture is an anonymous function, as `fn` is. */
    fun testAModuleDefinedInACaptureDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                IO.inspect(&{&1, defmodule(InCapture, do: nil)})

                <caret>InCapture
              end
            end
            """,
            null
        )
    }

    fun testAModuleDefinedInACapturedCallDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                IO.inspect(&IO.inspect(defmodule(InCapturedCall, do: nil), label: &1))

                <caret>InCapturedCall
              end
            end
            """,
            null
        )
    }

    /** A `for`'s generators and filters are its own scope, as its body is. */
    fun testAModuleDefinedInAForGeneratorDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                for x <- [defmodule(InFor, do: nil)], do: x

                <caret>InFor
              end
            end
            """,
            null
        )
    }

    /** Elixir expands a `for`'s generators in order, so one sees the aliases of those before it only. */
    fun testAModuleDefinedInALaterForGeneratorDoesNotResolveInAnEarlierOne() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                for x <- [<caret>InFor], _y <- [defmodule(InFor, do: nil)], do: x
              end
            end
            """,
            null
        )
    }

    fun testAModuleDefinedInAnEarlierForGeneratorResolvesInALaterOne() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                for _x <- [defmodule(InEarlier, do: nil)], y <- [<caret>InEarlier], do: y
              end
            end
            """,
            "use.ex:3: for _x <- [defmodule(InEarlier, do: nil)], y <- [InEarlier], do: y"
        )
    }

    /** A call's arguments are expanded in order too, so one sees the aliases of those before it. */
    fun testAModuleDefinedInAnEarlierArgumentResolvesInALaterOne() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                foo(defmodule(InEarlierArgument, do: nil), <caret>InEarlierArgument)
              end
            end
            """,
            "use.ex:3: foo(defmodule(InEarlierArgument, do: nil), InEarlierArgument)"
        )
    }

    fun testAModuleDefinedInAnEarlierArgumentsCallResolvesInALaterArgumentsList() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                foo(elem(defmodule(InEarlierCall, do: nil), 0), [<caret>InEarlierCall])
              end
            end
            """,
            "use.ex:3: foo(elem(defmodule(InEarlierCall, do: nil), 0), [InEarlierCall])"
        )
    }

    fun testAModuleDefinedInALaterArgumentDoesNotResolveInAnEarlierOne() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                foo(<caret>InLaterArgument, defmodule(InLaterArgument, do: nil))
              end
            end
            """,
            null
        )
    }

    fun testAModuleDefinedInAForInACallsArgumentsDoesNotResolveAfterTheCall() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                IO.inspect(for(_x <- [defmodule(InNestedFor, do: nil)], do: :ok))

                <caret>InNestedFor
              end
            end
            """,
            null
        )
    }

    fun testAModuleDefinedInAWithInACallsArgumentsDoesNotResolveAfterTheCall() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                IO.inspect(with(_x <- defmodule(InNestedWith, do: nil), do: :ok))

                <caret>InNestedWith
              end
            end
            """,
            null
        )
    }

    /** An `if`'s condition, unlike its clauses, is expanded where the `if` is. */
    fun testAModuleDefinedInAnIfConditionInACallsArgumentsResolvesAfterTheCall() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                IO.inspect(if(defmodule(InNestedIf, do: nil), do: :ok))

                <caret>InNestedIf
              end
            end
            """,
            "use.ex:3: IO.inspect(if(defmodule(InNestedIf, do: nil), do: :ok))"
        )
    }

    fun testAModuleDefinedInAWithClauseDoesNotResolveAfterIt() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                with x <- defmodule(InWith, do: nil), do: x

                <caret>InWith
              end
            end
            """,
            null
        )
    }

    /** A `case`'s subject is expanded in the caller's env, unlike its clauses. */
    fun testAModuleDefinedInACaseSubjectResolvesAfterTheCase() {
        assertResolvesAtCaret(
            """
            defmodule U do
              def run do
                case defmodule(InCase, do: nil) do
                  _ -> :ok
                end

                <caret>InCase
              end
            end
            """,
            "use.ex:3: case defmodule(InCase, do: nil) do"
        )
    }

    private fun assertResolvesAtCaret(text: String, resolved: String?) {
        myFixture.configureByText("use.ex", text.trimIndent())

        assertResolves(
            readAction { assertInstanceOf(myFixture.file.findReferenceAt(myFixture.caretOffset), Module::class.java) },
            resolved,
            listOfNotNull(resolved?.let { "$it (true)" })
        )
    }

    private fun currentModuleReference(): PsiPolyVariantReference =
        readAction {
            PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
                .single { it.text == "__MODULE__" }
                .let { __MODULE__.reference(it) as PsiPolyVariantReference }
        }

    private fun elementAtCaret(): PsiElement = readAction { myFixture.file.findElementAt(myFixture.caretOffset)!! }

    private fun reference(element: PsiElement): PsiPolyVariantReference =
        readAction { element.references.filterIsInstance<PsiPolyVariantReference>().single() }

    private fun assertResolves(reference: PsiPolyVariantReference, resolved: String?, results: List<String>) {
        readAction {
            assertEquals(
                "multiResolve(false)",
                results,
                reference.multiResolve(false).map { "${describe(it.element!!)} (${it.isValidResult})" }
            )
            assertEquals("resolve()", resolved, reference.resolve()?.let(::describe))
        }
    }

    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val line = document.getLineNumber(element.textRange.startOffset)
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

        return "${file.name}:${line + 1}: ${text.trim()}"
    }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }
}
