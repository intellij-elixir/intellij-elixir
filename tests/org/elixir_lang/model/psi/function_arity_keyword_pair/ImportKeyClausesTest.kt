package org.elixir_lang.model.psi.function_arity_keyword_pair

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.code_insight.searchTargetCountAtCaret
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.model.psi.function.FunctionArityKeywordPairReference
import org.elixir_lang.model.psi.function.FunctionArityKeywordPairReference.Definitions
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/**
 * What a `name: arity` key names: at an `import`'s `only:` or `except:`, what the import brings in, from a source or
 * `.beam` module alike, so a private definition is undefined there; at `@compile inline:` and `@dialyzer`, the
 * enclosing module's definitions, private ones included.
 */
class ImportKeyClausesTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/function_arity_keyword_pair/import_key"

    private val priv = "defmodule Priv do\n  defp f(q), do: q\n  def g(q), do: f(q)\nend\n"

    fun testOnlyKeyNamingDefpNamesNothing() = assertNamesNothing(priv, "import Priv, only: [<caret>f: 1]")

    fun testOnlyKeyNamingDefaultedDefpNamesNothing() =
        assertNamesNothing(
            "defmodule Priv do\n  defp f(q, x \\\\ nil), do: {q, x}\n  def g(q), do: f(q)\nend\n",
            "import Priv, only: [<caret>f: 1]"
        )

    fun testOnlyKeyNamingDefmacropNamesNothing() =
        assertNamesNothing(
            "defmodule Priv do\n  defmacrop f(q), do: q\n  def g(q), do: f(q)\nend\n",
            "import Priv, only: [<caret>f: 1]"
        )

    fun testOnlyKeyNamingDefguardpNamesNothing() =
        assertNamesNothing(
            "defmodule Priv do\n  defguardp f(q) when is_integer(q)\n  def g(q) when f(q), do: q\nend\n",
            "import Priv, only: [<caret>f: 1]"
        )

    fun testExceptKeyNamingDefpNamesNothing() = assertNamesNothing(priv, "import Priv, except: [<caret>f: 1]")

    fun testFindUsagesFromDefpDoesNotListOnlyKey() {
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import Priv, only: [f: 1]\nend\n")
        myFixture.configureByText("priv.ex", "defmodule Priv do\n  defp <caret>f(q), do: q\n  def g(q), do: f(q)\nend\n")

        assertEquals(emptyList<String>(), myFixture.psiUsagesAtCaret(project).map { it.file.name }.filter { it == "caller.ex" })
    }

    fun testBeamOnlyKeyNamingPrivateNamesNothing() = assertNamesNothing(null, "import KeyBeam, only: [<caret>f: 1]")

    fun testBeamOnlyKeyNamingPublicGoesToItsDecompiledDefinition() {
        configure(null, "import KeyBeam, only: [<caret>pub: 1]")

        val destinations = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destination!! }
        assertEquals(1, destinations.size)
        assertTrue(destinations.single().containingFile.name, destinations.single().containingFile.name.startsWith("Elixir.KeyBeam"))
        assertEquals("pub", destinations.single().text)
    }

    fun testFindUsagesFromBeamDefinitionListsOnlyKey() {
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import KeyBeam, only: [pub: 1]\nend\n")
        openBeamAndMoveCaretTo("Elixir.KeyBeam.beam", "def pub")

        assertEquals(listOf("caller.ex"), myFixture.psiUsagesAtCaret(project).map { it.file.name }.filter { it == "caller.ex" })
    }

    fun testOnlyKeyNamingPublicDefResolves() =
        assertNamesDefinitionAtLine("defmodule Pub do\n  def f(q), do: q\nend\n", "  def f(q), do: q")

    fun testOnlyKeyNamingOneArityOfDefaultsResolves() =
        assertNamesDefinitionAtLine(
            "defmodule Pub do\n  def f(q, x \\\\ nil), do: {q, x}\nend\n",
            "  def f(q, x \\\\ nil), do: {q, x}"
        )

    fun testImportOverStaleBeamGoesToTheSourceOnly() {
        myFixture.addFileToProject("module.ex", "defmodule KeyBeam do\n  def pub(x), do: x\nend\n")

        for (module in listOf("KeyBeam", ":\"Elixir.KeyBeam\"")) {
            myFixture.configureByText("caller.ex", "defmodule Caller do\n  import $module, only: [<caret>pub: 1]\nend\n")

            assertEquals(module, listOf("  def pub(x), do: x"), destinationLines())
        }
    }

    fun testCompileInlineKeyNamingDefpResolves() {
        myFixture.configureByText("m.ex", "defmodule M do\n  @compile inline: [<caret>f: 1]\n  defp f(q), do: q\nend\n")

        assertEquals(listOf("  defp f(q), do: q"), destinationLines())
    }

    fun testDialyzerKeyNamingDefpResolves() {
        myFixture.configureByText("m.ex", "defmodule M do\n  @dialyzer {:nowarn_function, <caret>f: 1}\n  defp f(q), do: q\nend\n")

        assertEquals(listOf("  defp f(q), do: q"), destinationLines())
    }

    fun testDefinitionsAtUnresolvedImportAreUnresolved() {
        configure(null, "import Missing, only: [<caret>f: 1]")

        assertEquals(Definitions.UnresolvedModule, definitionsAtCaret())
    }

    fun testDefinitionsAtImportOfOnlyPrivatesAreFoundEmpty() {
        configure("defmodule Priv do\n  defp f(q), do: q\nend\n", "import Priv, only: [<caret>f: 1]")

        assertEquals(Definitions.Found(emptyList()), definitionsAtCaret())
    }

    fun testDefinitionsAtCompileInlineIncludeDelegation() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @compile inline: [<caret>d: 1]\n  defdelegate d(x), to: Other\nend\n"
        )

        val found = definitionsAtCaret() as Definitions.Found

        assertTrue(found.definitions.single().let { it is Call && Delegation.`is`(it) })
    }

    fun testDefinitionsAtBeamImportAreItsExports() {
        configure(null, "import KeyBeam, only: [<caret>pub: 1]")

        val found = definitionsAtCaret() as Definitions.Found

        assertTrue(found.definitions.all { it is BeamCallDefinition })
        assertEquals(listOf("g", "h", "pub"), found.definitions.map { (it as BeamCallDefinition).nameArityInterval.name }.sorted())
    }

    private fun configure(module: String?, directive: String) {
        module?.let { myFixture.addFileToProject("module.ex", it) }
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  $directive\nend\n")
    }

    private fun assertNamesNothing(module: String?, directive: String) {
        configure(module, directive)

        assertEquals("Go to Declaration", emptyList<String>(), destinationLines())
        assertEquals("search targets", 0, myFixture.searchTargetCountAtCaret())
        assertEquals("rename targets", 0, myFixture.renameTargetsAtCaret().size)
    }

    private fun assertNamesDefinitionAtLine(module: String, line: String) {
        configure(module, "import Pub, only: [<caret>f: 1]")

        assertEquals(listOf(line), destinationLines())
    }

    private fun definitionsAtCaret(): Definitions {
        val pair = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), QuotableKeywordPair::class.java)!!

        return FunctionArityKeywordPairReference.definitions(FunctionArityKeywordPair.classify(pair)!!)
    }

    private fun destinationLines(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { target ->
            val destination = target.destination!!
            val document = destination.containingFile.viewProvider.document!!
            val number = document.getLineNumber(destination.textOffset)

            document.charsSequence.subSequence(document.getLineStartOffset(number), document.getLineEndOffset(number)).toString()
        }
}
