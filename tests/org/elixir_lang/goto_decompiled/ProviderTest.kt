package org.elixir_lang.goto_decompiled

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.runInEdtAndGet
import org.elixir_lang.beam.BeamLibraryTestCase
import java.io.File

/**
 * Go To Related from a declaration lands on the decompiled definitions it declares, whichever form declares them.
 *
 * `testData/.../ebin` is a library root, as a dependency's or the SDK's `.beam`s are, not project content.
 */
class ProviderTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/goto_decompiled"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    fun testDefIsRelatedToTheDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  def r<caret>un(list), do: list\nend\n",
            "def run("
        )
    }

    fun testDefdelegateIsRelatedToTheDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  defdelegate deleg<caret>ated(list), to: :erlang, as: :hd\nend\n",
            "def delegated("
        )
    }

    fun testEExFunctionFromFileIsRelatedToTheDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  require EEx\n  EEx.function_from_file(:def, :ban<caret>ner, \"banner.eex\", [:name])\nend\n",
            "def banner("
        )
    }

    fun testEExFunctionFromFileWithANonLiteralArityIsRelatedByName() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  require EEx\n  EEx.function_from_file(:def, :pa<caret>ge, \"page.eex\", args())\nend\n",
            "def page("
        )
    }

    fun testEmbedTemplateIsRelatedToTheDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  require Mix.Generator\n  Mix.Generator.embed_template(:gree<caret>ting, \"hi\")\nend\n",
            "defp greeting_template("
        )
    }

    fun testEmbedTextIsRelatedToTheDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  require Mix.Generator\n  Mix.Generator.embed_text(:n<caret>ote, \"hi\")\nend\n",
            "defp note_text("
        )
    }

    fun testDefimplFunctionIsRelatedToTheImplementationsDecompiledFunction() {
        assertRelated(
            "defimpl String.Chars, for: GotoRelated.Struct do\n  def to_<caret>string(struct), do: struct.value\nend\n",
            "def to_string("
        )
    }

    fun testDefmoduleIsRelatedToTheDecompiledModule() {
        assertRelated("defmodule<caret> GotoRelated.Lib do\nend\n", "defmodule GotoRelated.Lib")
    }

    fun testDefexceptionIsRelatedToTheEnclosingModulesDecompiledFunctions() {
        assertRelated(
            "defmodule GotoRelated.Error do\n  defexc<caret>eption [:message]\nend\n",
            "def exception(",
            "def message("
        )
    }

    fun testFunctionWithoutADecompiledDefinitionIsRelatedToNothing() {
        assertRelated("defmodule GotoRelated.Lib do\n  def lon<caret>ely(list), do: list\nend\n")
    }

    fun testCallbackIsRelatedToNothing() {
        assertRelated("defmodule GotoRelated.Behaviour do\n  @callback per<caret>form(term) :: term\nend\n")
    }

    fun testFunctionInANonDefmoduleModularIsRelatedToNothing() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  defmacro __using__(_) do\n    quote do\n      def r<caret>un(list), do: list\n    end\n  end\nend\n"
        )
    }

    /** The decompiled definitions, one per opening line, Go To Related offers from the caret of [source] are [expected]. */
    private fun assertRelated(source: String, vararg expected: String) {
        myFixture.configureByText("source.ex", source)

        val related = runInEdtAndGet {
            ReadAction.computeBlocking<List<String>, Throwable> {
                Provider().getItems(myFixture.file.findElementAt(myFixture.caretOffset)!!)
                    .mapNotNull { it.element?.text?.lineSequence()?.first()?.trim() }
            }
        }

        assertEquals(
            "Go To Related from $source",
            expected.toSortedSet(),
            related.mapTo(sortedSetOf()) { line -> expected.firstOrNull { line.startsWith(it) } ?: line }
        )
    }
}
