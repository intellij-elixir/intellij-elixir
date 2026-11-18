package org.elixir_lang.code_insight

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.PLACED_LIB
import org.elixir_lang.documentation.UNQUOTED_NAME_MACRO_LIB
import org.elixir_lang.documentation.addCompiledLibrary
import org.elixir_lang.documentation.unquotedNameMacroCaller
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch

/**
 * A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it.
 * `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub.
 * Completion reads the expander only behind the harness's switch.
 */
class PlacedHeadCompletionTest : ExpanderSettingTestCase() {
    fun testAHeadAMacroPlacedRendersTheStubWithTheExpanderBehindCompletion() = withTheExpanderBehindCompletion {
        assertEquals("(arg1)", LookupElementPresentation.renderElement(completed(STOP, "stop")).tailText)
    }

    fun testAHeadAMacroPlacedIsInsertedWithTheStubsParametersWithTheExpanderBehindCompletion() = withTheExpanderBehindCompletion {
        val document = inserted(STOP, "stop")

        assertTrue("Expected `stop(arg1)`, got: $document", "stop(arg1)" in document)
    }

    /**
     * A head a compiled macro places in the module that uses it is shown at the arity its quote writes, not at the arity
     * of the export of that name in the macro's `.beam`.
     */
    fun testAHeadACompiledMacroPlacedRendersItsOwnArityWithTheExpanderBehindCompletion() = withTheExpanderBehindCompletion {
        myFixture.addCompiledLibrary(PLACED_LIB, CompiledBuild("CompiledPlacedCompletion"))
        val f = completed("defmodule Placed do\n  use CompiledPlacedCompletion\n\n  def calls(y), do: f<caret>\nend\n", "f")

        assertEquals("(arg1)", LookupElementPresentation.renderElement(f).tailText)
    }

    /**
     * A head a compiled macro places at its own clause, whose name the decompiler unquotes, is not that clause's: `g/1`
     * renders the stub, not the parameter of the macro's clause, `n`.
     */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedRendersTheStubWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledUnquotedNameCompletion"))
            val g = completed(unquotedNameMacroCaller("CompiledUnquotedNameCompletion", "build g", "g<caret>", "1"), "g")

            assertEquals("(arg1)", LookupElementPresentation.renderElement(g).tailText)
        }

    /** A `def unquote(name)(x)` a macro's quote writes is shown with the parameters its quote writes. */
    fun testADefAQuoteWritesWithOnlyItsNameUnquotedRendersItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            myFixture.addFileToProject("gen.ex", GEN)
            assertEquals("(x)", LookupElementPresentation.renderElement(completed(NAMED, "hello")).tailText)
        }

    fun testADefAQuoteWritesWithOnlyItsNameUnquotedIsInsertedWithItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            myFixture.addFileToProject("gen.ex", GEN)
            assertInserted("hello(x)", NAMED)
        }

    /**
     * The macro and its call are in the file completed in, which completion expands as a copy with no file of its own:
     * a head the macro's quote writes keeps its parameters, whether the call that generates it is written above the
     * use or below it.
     */
    fun testADefAQuoteWritesInTheCompletedFileAboveItsUseIsInsertedWithItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            assertInserted("hello(x)", GEN + "\n\n" + NAMED)
        }

    fun testADefAQuoteWritesInTheCompletedFileBelowItsUseIsInsertedWithItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            assertInserted("hello(x)", GEN + "\n\n" + NAMED_BELOW)
        }

    fun testADefAQuoteWritesInTheCompletedFileAboveItsUseRendersItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            assertEquals("(x)", LookupElementPresentation.renderElement(completed(GEN + "\n\n" + NAMED, "hello")).tailText)
        }

    /** A default is legal only in a definition's head, so the call is inserted without it. */
    fun testAnUnquoteFragmentsDefWithADefaultIsInsertedWithoutItWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            assertInserted("hello(a, b)", FRAGMENT.replace("CALLER", "def calls, do: hel<caret>"))
        }

    /** An `import` of the arity a default adds brings in the function, whose parameters at that arity are inserted. */
    fun testAnImportedArityAFragmentsDefaultAddsIsInsertedWithItsParametersWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            assertInserted(
                "hello(a)",
                FRAGMENT.replace("CALLER", "") + "\n\ndefmodule Importing do\n  import Fragment, only: [hello: 1]\n\n  def calls, do: hel<caret>\nend\n"
            )
        }

    /**
     * An unquote fragment's head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, which is shown
     * with the default, as the same head written without `unquote` is, not by the stub.
     */
    fun testAnUnquoteFragmentsDefaultHeadWhoseFullArityIsOverriddenRendersTheDefaultWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            myFixture.configureByText(
                "placed.ex",
                """
                defmodule Overridden do
                  for name <- [:f] do
                    def unquote(name)(a, b \\ 1), do: {a, b}
                  end

                  defoverridable f: 2

                  def f(a, b), do: {b, a}

                  def run(y), do: f<caret>
                end
                """.trimIndent()
            )
            myFixture.complete(CompletionType.BASIC, 1)

            assertEquals(
                listOf("(a, b \\\\ 1)", "(a, b) (placed.ex defmodule Overridden)"),
                myFixture.lookupElements.orEmpty()
                    .map { LookupElementPresentation.renderElement(it) }
                    .filter { it.itemText == "f" }
                    .map { it.tailText }
                    .sortedBy { it.orEmpty() }
            )
        }

    /**
     * As above, for a head whose default's value is unquoted: its parameters are the stub's, which mark no default, so an
     * `import` of `f/1` inserts the stub at that arity, not at the function's full arity.
     */
    fun testAnImportedArityOfAnUnquoteFragmentsHeadWithAnUnquotedDefaultIsInsertedAtThatArityWithTheExpanderBehindCompletion() =
        withTheExpanderBehindCompletion {
            val document = inserted(
                """
                defmodule Overridden do
                  for {name, default} <- [f: 1] do
                    def unquote(name)(a, b \\ unquote(default)), do: {a, b}
                  end

                  defoverridable f: 2

                  def f(a, b), do: {b, a}
                end

                defmodule Importing do
                  import Overridden, only: [f: 1]

                  def calls, do: f<caret>
                end
                """.trimIndent(),
                "f"
            )
            val call = document.substringAfterLast("def calls")

            assertTrue("Expected `f(arg1)`, got: $document", "f(arg1)" in call)
            assertFalse("Expected one argument, got: $document", "arg2" in call)
        }

    private fun assertInserted(inserted: String, text: String) {
        val document = inserted(text, "hello")

        assertTrue("Expected `$inserted`, got: $document", inserted in document)
        assertFalse("Expected no default, got: $document", "\\\\" in document.substringAfterLast("def calls"))
    }

    /** The lookup named [name] that completion offers at the caret in [text]. */
    private fun completed(text: String, name: String) = run {
        myFixture.configureByText("placed.ex", text)
        myFixture.complete(CompletionType.BASIC, 1)

        val lookup = myFixture.lookupElements.orEmpty().firstOrNull { LookupElementPresentation.renderElement(it).itemText == name }
        assertNotNull("Expected `$name` among the lookups, got ${myFixture.lookupElementStrings} in ${myFixture.editor.document.text}", lookup)

        lookup!!
    }

    /** The document once the lookup named [name] that completion offers at the caret in [text] is chosen and inserted. */
    private fun inserted(text: String, name: String): String {
        myFixture.configureByText("placed.ex", text)

        return myFixture.completeCandidateAtCaret(name, '\n')
    }

    /** [test] with the expander behind completion too, and a lone lookup shown rather than inserted. */
    private fun withTheExpanderBehindCompletion(test: () -> Unit) = withTheExpanderOn {
        CodeInsightSettings.runWithTemporarySettings<Unit, Throwable> { settings ->
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
            withExpanderSwitch(test)
        }
    }

    private companion object {
        /** A module that uses `Application`, completed at `sto`. */
        val STOP =
            """
            defmodule Placed do
              use Application

              def calls, do: sto<caret>
            end
            """.trimIndent()

        /** `Gen.named`, whose quote writes a `def` with only its name unquoted. */
        val GEN =
            """
            defmodule Gen do
              defmacro named(name) do
                quote do
                  def unquote(name)(x), do: x
                end
              end
            end
            """.trimIndent()

        /** `hello/1`, which [GEN]'s `Gen.named` defines, completed at `hel`. */
        val NAMED =
            """
            defmodule Named do
              require Gen

              Gen.named(:hello)

              def calls, do: hel<caret>
            end
            """.trimIndent()

        /** As [NAMED], with the `Gen.named` call written below the use. */
        val NAMED_BELOW =
            """
            defmodule Named do
              require Gen

              def calls, do: hel<caret>

              Gen.named(:hello)
            end
            """.trimIndent()

        /** `hello/2` and the `hello/1` its default adds, an unquote fragment's, with `CALLER` written after it. */
        val FRAGMENT =
            """
            defmodule Fragment do
              for name <- [:hello] do
                def unquote(name)(a, b \\ 1), do: {a, b}
              end

              CALLER
            end
            """.trimIndent()
    }
}
