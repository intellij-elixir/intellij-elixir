package org.elixir_lang.goto_decompiled

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.runInEdtAndGet
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
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

    fun testDefdelegateListIsRelatedToEachHeadsDecompiledFunction() {
        assertRelated(
            "defmodule GotoRelated.Lib do\n  defdelegate [r<caret>un(list), delegated(list)], to: Enum\nend\n",
            "def run(",
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

    /**
     * A clause after a head with defaults is one function with that head, so it is related to each decompiled clause of
     * it: the ones debug info writes for the defaults as well as its own arity's.
     */
    fun testClauseAfterAHeadWithDefaultsIsRelatedToEachDecompiledClauseOfItsFunction() {
        disableExpander(testRootDisposable)
        assertClauseAfterAHeadWithDefaultsIsRelatedToEachDecompiledClauseOfItsFunction()
    }

    fun testClauseAfterAHeadWithDefaultsIsRelatedToEachDecompiledClauseOfItsFunctionWithTheExpanderOn() {
        enableExpander(module, testRootDisposable)
        assertClauseAfterAHeadWithDefaultsIsRelatedToEachDecompiledClauseOfItsFunction()
    }

    private fun assertClauseAfterAHeadWithDefaultsIsRelatedToEachDecompiledClauseOfItsFunction() {
        assertRelated(
            "defmodule GotoRelated.Defaults do\n" +
                "  def snoc(a \\\\ nil, b \\\\ nil)\n" +
                "  def sn<caret>oc(a, b) when is_nil(a), do: {a, b}\n" +
                "  def snoc(a, b), do: {a, b}\n" +
                "end\n",
            "def snoc() do",
            "def snoc(a) do",
            "def snoc(a, b) when",
            "def snoc(a, b) do"
        )
    }

    /**
     * A `.beam` without debug info or `Docs` does not say which function an export is one of, so a declaration with
     * defaults is related to each decompiled clause at an arity it declares.
     */
    fun testDefaultsAreRelatedToEachDecompiledClauseOfAModuleWithoutDebugInfoOrDocs() {
        disableExpander(testRootDisposable)
        assertDefaultsAreRelatedToEachDecompiledClauseOfAModuleWithoutDebugInfoOrDocs()
    }

    fun testDefaultsAreRelatedToEachDecompiledClauseOfAModuleWithoutDebugInfoOrDocsWithTheExpanderOn() {
        enableExpander(module, testRootDisposable)
        assertDefaultsAreRelatedToEachDecompiledClauseOfAModuleWithoutDebugInfoOrDocs()
    }

    private fun assertDefaultsAreRelatedToEachDecompiledClauseOfAModuleWithoutDebugInfoOrDocs() {
        assertRelated(
            "defmodule GotoRelated.Stripped.Defaults do\n  def sn<caret>oc(q, x \\\\ nil), do: {q, x}\nend\n",
            "def snoc(arg1) do",
            "def snoc(arg1, arg2) do"
        )
    }

    /** An argument list with an open tail declares at least its fixed arguments, so no shorter clause is related. */
    fun testAnOpenTailIsRelatedToNoClauseShorterThanItsFixedArguments() {
        disableExpander(testRootDisposable)
        assertAnOpenTailIsRelatedToNoClauseShorterThanItsFixedArguments()
    }

    fun testAnOpenTailIsRelatedToNoClauseShorterThanItsFixedArgumentsWithTheExpanderOn() {
        enableExpander(module, testRootDisposable)
        assertAnOpenTailIsRelatedToNoClauseShorterThanItsFixedArguments()
    }

    private fun assertAnOpenTailIsRelatedToNoClauseShorterThanItsFixedArguments() {
        assertRelated(
            "defmodule GotoRelated.Stripped.OpenTail do\n" +
                "  require EEx\n" +
                "  EEx.function_from_string(:def, :snoc, \"<%= inspect({}) %>\", [])\n" +
                "  @rest [:x]\n" +
                "  EEx.function_from_string(:def, :sn<caret>oc, \"<%= inspect({q, x}) %>\", [:q | @rest])\n" +
                "end\n",
            "def snoc(arg1, arg2) do"
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
