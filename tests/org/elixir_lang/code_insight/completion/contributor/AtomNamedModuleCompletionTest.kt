package org.elixir_lang.code_insight.completion.contributor

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completeCandidateAtCaret
import org.elixir_lang.code_insight.completionAttemptAtCaret
import java.io.File

/** A module named with an atom completes to a valid atom naming it, however the typed prefix is quoted. */
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

    fun testQuotedPrefixCompletesToQuotedAtom() = assertAccepts(
        ":\"a.b\"",
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

    fun testQuotedPrefixCompletesToPlainAtomUnquoted() = assertAccepts(
        ":plain",
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
          def f, do: :plain
        end
        """,
    )

    fun testQuotedPrefixReplacedWithTabCompletesToPlainAtomUnquoted() = assertAccepts(
        ":plain",
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
          def f, do: :plain
        end
        """,
        '\t',
    )

    fun testElixirPrefixedQuotedPrefixCompletesToAlias() = assertAccepts(
        "Foo",
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
          def f, do: Foo
        end
        """,
    )

    fun testElixirPrefixedUnquotedPrefixCompletesToAlias() = assertAccepts(
        "Foo",
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
          def f, do: Foo
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

        assertEquals(listOf("P", "User"), attempt.candidates?.filter { it in setOf("?", "P.?", "P", "User") }?.sorted())
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
