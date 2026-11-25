package org.elixir_lang.code_insight.completion.contributor

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completeCandidateAtCaret
import org.elixir_lang.code_insight.completionAttemptAtCaret
import java.io.File

/**
 * A module named with an atom completes to a valid atom naming it: after a bare colon spelt as Elixir parses it there,
 * and between quotes the user typed, kept.
 */
class AtomNamedModuleCompletionTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/beam/quoted_atom_module/ebin").absoluteFile

    fun testUnquotedPrefixCompletesToQuotedAtom() = assertCompletes(
        """
        defmodule :"a.b" do
        end

        defmodule User do
          def f, do: :a<caret>
        end
        """,
        """
        defmodule :"a.b" do
        end

        defmodule User do
          def f, do: :"a.b"
        end
        """,
    )

    fun testQuotedPrefixCompletesToQuotedAtom() = assertCompletes(
        """
        defmodule :"a.b" do
        end

        defmodule User do
          def f, do: :"a<caret>"
        end
        """,
        """
        defmodule :"a.b" do
        end

        defmodule User do
          def f, do: :"a.b"
        end
        """,
    )

    fun testQuotedPrefixCompletesToPlainAtomInItsQuotes() = assertAccepts(
        "plain",
        """
        defmodule :plain do
        end

        defmodule :player do
        end

        defmodule User do
          def f, do: :"pl<caret>"
        end
        """,
        """
        defmodule :plain do
        end

        defmodule :player do
        end

        defmodule User do
          def f, do: :"plain"
        end
        """,
    )

    fun testQuotedPrefixReplacedWithTabCompletesToPlainAtomInItsQuotes() = assertAccepts(
        "plain",
        """
        defmodule :plain do
        end

        defmodule :player do
        end

        defmodule User do
          def f, do: :"pl<caret>"
        end
        """,
        """
        defmodule :plain do
        end

        defmodule :player do
        end

        defmodule User do
          def f, do: :"plain"
        end
        """,
        '\t',
    )

    fun testElixirPrefixedQuotedPrefixCompletesToTheModulesAtomInItsQuotes() = assertAccepts(
        "Elixir.Foo",
        """
        defmodule Foo do
        end

        defmodule Foe do
        end

        defmodule User do
          def f, do: :"Elixir.Fo<caret>"
        end
        """,
        """
        defmodule Foo do
        end

        defmodule Foe do
        end

        defmodule User do
          def f, do: :"Elixir.Foo"
        end
        """,
    )

    fun testElixirPrefixedUnquotedPrefixCompletesToTheModulesQuotedAtom() = assertAccepts(
        "Elixir.Foo",
        """
        defmodule Foo do
        end

        defmodule Foe do
        end

        defmodule User do
          def f, do: :E<caret>
        end
        """,
        """
        defmodule Foo do
        end

        defmodule Foe do
        end

        defmodule User do
          def f, do: :"Elixir.Foo"
        end
        """,
    )

    fun testModuleWhoseNameHasNoValueIsNotOffered() {
        val name = "a".repeat(256)
        val source = """
            defmodule :$name do
            end

            defprotocol P do
            end

            defimpl P do
            end

            defmodule User do
              def f, do: :"Elixir.<caret>"
            end
            """.trimIndent()
        myFixture.configureByText("completion.ex", source)

        val attempt = myFixture.completionAttemptAtCaret()

        assertEquals(
            listOf("Elixir.P", "Elixir.User"),
            attempt.candidates?.filter { it in setOf("Elixir.?", "Elixir.P.?", "Elixir.P", "Elixir.User") }?.sorted()
        )
        assertEquals(source.replace("<caret>", ""), attempt.text)
    }

    fun testPlainAtomCompletesUnquoted() = assertCompletes(
        """
        defmodule :plain do
        end

        defmodule User do
          def f, do: :pl<caret>
        end
        """,
        """
        defmodule :plain do
        end

        defmodule User do
          def f, do: :plain
        end
        """,
    )

    fun testCapitalisedAtomCompletesUnquoted() = assertCompletes(
        """
        defmodule :Foo do
        end

        defmodule User do
          def f, do: :Fo<caret>
        end
        """,
        """
        defmodule :Foo do
        end

        defmodule User do
          def f, do: :Foo
        end
        """,
    )

    fun testDecompiledModuleCompletesToQuotedAtom() = assertCompletes(
        """
        defmodule User do
          def f, do: :fo<caret>
        end
        """,
        """
        defmodule User do
          def f, do: :"foo-bar"
        end
        """,
    )

    private fun assertAccepts(lookupString: String, before: String, after: String, completionChar: Char = '\n') {
        myFixture.configureByText("completion.ex", before.trimIndent())

        assertEquals(after.trimIndent(), myFixture.completeCandidateAtCaret(lookupString, completionChar))
    }

    private fun assertCompletes(before: String, after: String) {
        myFixture.configureByText("completion.ex", before.trimIndent())

        val attempt = myFixture.completionAttemptAtCaret()

        assertNull("Expected a single auto-inserted completion, offered ${attempt.candidates}", attempt.candidates)
        assertEquals(after.trimIndent(), attempt.text)
    }
}
