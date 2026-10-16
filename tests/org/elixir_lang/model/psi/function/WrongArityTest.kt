package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.navigation.SymbolNavigationService
import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret

/**
 * A use that no declaration admits names each head that declares its name at other arities, once, at the lowest arity
 * the path that reached it admits: Go To lands on that head, and rename refuses it.
 */
@Suppress("UnstableApiUsage")
class WrongArityTest : PlatformTestCase() {
    fun testUnqualified() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a, b), do: {a, b}\n\n  def run, do: <caret>f(1)\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(M.f/2, macro=false))"), listOf("m.ex:2: def f(a, b), do: {a, b}"))
    }

    fun testNearMissIsNotRenamedFromTheCall() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a, b), do: {a, b}\n\n  def run, do: <caret>f(1)\nend\n")

        assertEquals(emptyList<String>(), myFixture.renameTargetsAtCaret().map { it.toString() })
        assertEquals(listOf("m.ex:2: def f(a, b), do: {a, b}"), goToAtCaret())
    }

    fun testAdmittedCallIsRenamedFromTheCall() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a, b), do: {a, b}\n\n  def run, do: <caret>f(1, 2)\nend\n")

        assertEquals(listOf("FunctionSymbol(M.f/2, macro=false)"), myFixture.renameTargetsAtCaret().map { it.toString() })
    }

    fun testDefaultsOfferTheHeadOnce() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def g(a, b \\\\ 1), do: {a, b}\n\n  def run, do: <caret>g()\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(M.g/1, macro=false))"), listOf("m.ex:2: def g(a, b \\\\ 1), do: {a, b}"))
    }

    fun testDefaultsUnderImportOnlyTakeTheLowestAdmitted() {
        myFixture.addFileToProject("n.ex", "defmodule N do\n  def pad(a, b \\\\ 1), do: {a, b}\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  import N, only: [pad: 2]\n\n  def run, do: <caret>pad()\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(N.pad/2, macro=false))"), listOf("n.ex:2: def pad(a, b \\\\ 1), do: {a, b}"))
    }

    fun testAnAdmittedArityHidesTheNearMisses() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def h(a), do: a\n  def h(a, b), do: {a, b}\n\n  def run, do: <caret>h(1)\nend\n"
        )

        assertOffers(listOf("FunctionSymbol(M.h/1, macro=false)"), listOf("m.ex:2: def h(a), do: a"))
    }

    fun testRemote() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  def run, do: M.<caret>f(1)\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(M.f/2, macro=false))"), listOf("m.ex:2: def f(a, b), do: {a, b}"))
    }

    fun testRemotePrivateIsNoNearMiss() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defp p(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  def run, do: M.<caret>p(1)\nend\n")

        assertOffers(emptyList(), emptyList())
    }

    fun testRemotePrivateCaptureIsNoNearMiss() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defp p(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  def run, do: &M.<caret>p/1\nend\n")

        assertEquals(emptyList<String>(), symbolsAtCaret<CaptureFunctionReference>())
        assertEquals(emptyList<String>(), goToAtCaret())
    }

    fun testImportOnlyAdmitsItsArities() {
        importing("only: [f: 2]")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(N.f/2, macro=false))"), listOf("n.ex:2: def f(a, b), do: {a, b}"))
    }

    fun testImportExceptAdmitsTheOthers() {
        importing("except: [f: 2]")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(N.f/3, macro=false))"), listOf("n.ex:3: def f(a, b, c), do: {a, b, c}"))
    }

    /** A re-import with `except:` takes from what the import before it brought in, so `f/1` stays out. */
    fun testReimportExceptTakesFromThePriorImport() {
        myFixture.addFileToProject("n.ex", "defmodule N do\n  def f(a, b \\\\ 1), do: {a, b}\nend\n")
        myFixture.configureByText(
            "u.ex",
            "defmodule U do\n  import N, only: [f: 2]\n  import N, except: [g: 1]\n\n  def run, do: <caret>f(1)\nend\n"
        )

        assertOffers(listOf("NearMiss(target=FunctionSymbol(N.f/2, macro=false))"), listOf("n.ex:2: def f(a, b \\\\ 1), do: {a, b}"))
    }

    fun testCapture() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a, b), do: {a, b}\n\n  def run, do: &<caret>f/1\nend\n")

        assertEquals(listOf("NearMiss(target=FunctionSymbol(M.f/2, macro=false))"), symbolsAtCaret<CaptureFunctionReference>())
        assertEquals(listOf("m.ex:2: def f(a, b), do: {a, b}"), goToAtCaret())
    }

    /** The near miss is the delegation, whose Go To lands on what it delegates to, under that declaration's label. */
    fun testDelegationIsTheNearMiss() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def d(a), do: a\n  def d(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate d(a), to: T\n\n  def run, do: <caret>d(1, 2)\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(M.d/1, macro=false))"), listOf("t.ex:2: def d(a), do: a"))
        assertEquals(listOf("def d(a)"), labelsAtCaret())
    }

    /** A target only at another arity is not what the delegation delegates to, so Go To lands on the delegation. */
    fun testDelegationWhoseTargetTakesAnotherArity() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def d(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate d(a), to: T\n\n  def run, do: <caret>d(1)\nend\n")

        assertOffers(listOf("FunctionSymbol(M.d/1, macro=false)"), listOf("m.ex:2: defdelegate d(a), to: T"))
    }

    /** Quick Documentation at that delegation's head does not document the target at another arity. */
    fun testDelegationWhoseTargetTakesAnotherArityDocumentsNoTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  @doc \"Pairs.\"\n  def d(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate <caret>d(a), to: T\nend\n")

        val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()

        assertFalse("Expected no target at another arity, got: $documentation", documentation.contains("d(a, b)"))
        assertFalse("Expected no target's @doc, got: $documentation", documentation.contains("Pairs."))
    }

    /** Nor does Quick Documentation at a call of that delegation. */
    fun testACallOfADelegationWhoseTargetTakesAnotherArityDocumentsNoTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  @doc \"Pairs.\"\n  def d(a, b), do: {a, b}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate d(a), to: T\n\n  def run, do: <caret>d(1)\nend\n")

        val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()

        assertFalse("Expected no target at another arity, got: $documentation", documentation.contains("d(a, b)"))
        assertFalse("Expected no target's @doc, got: $documentation", documentation.contains("Pairs."))
    }

    fun testDelegationToNothingIsTheNearMiss() {
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate d(a), to: Nowhere\n\n  def run, do: <caret>d(1, 2)\nend\n")

        assertOffers(listOf("NearMiss(target=FunctionSymbol(M.d/1, macro=false))"), listOf("m.ex:2: defdelegate d(a), to: Nowhere"))
    }

    private fun importing(options: String) {
        myFixture.addFileToProject("n.ex", "defmodule N do\n  def f(a, b), do: {a, b}\n  def f(a, b, c), do: {a, b, c}\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  import N, $options\n\n  def run, do: <caret>f(1)\nend\n")
    }

    private fun assertOffers(symbols: List<String>, goTo: List<String>) {
        assertEquals(symbols, symbolsAtCaret<FunctionCallReference>())
        assertEquals(goTo, goToAtCaret())
    }

    private inline fun <reified R : PsiSymbolReference> symbolsAtCaret(): List<String> =
        resolvedAtCaret<R>().map(Symbol::toString)

    /** What the one [R] on the innermost element at the caret that has one resolves to. */
    private inline fun <reified R : PsiSymbolReference> resolvedAtCaret(): Collection<Symbol> =
        generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .map { element -> PsiSymbolReferenceService.getService().getReferences(element).filterIsInstance<R>() }
            .first { it.isNotEmpty() }
            .single()
            .resolveReference()

    /** The chooser row of each declaration the call's Symbols navigate to. */
    // NavigationTarget is @ApiStatus.OverrideOnly, and the platform offers no public call for its presentation.
    @Suppress("OverrideOnly")
    private fun labelsAtCaret(): List<String> =
        resolvedAtCaret<FunctionCallReference>()
            .flatMap { SymbolNavigationService.getInstance().getNavigationTargets(project, it) }
            .map { it.computePresentation().presentableText }

    private fun goToAtCaret(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { it.destination?.let(::describe) }.sorted()

    /** [element] as `file:line: text of that line`. */
    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val text = file.text
        val offset = element.textOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }

        return "${file.name}:${text.substring(0, offset).count { it == '\n' } + 1}: ${text.substring(lineStart, lineEnd).trim()}"
    }
}
