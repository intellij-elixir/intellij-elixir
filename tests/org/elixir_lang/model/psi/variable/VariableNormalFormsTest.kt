package org.elixir_lang.model.psi.variable

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.call.Call
import java.util.concurrent.Callable

/**
 * A variable is the atom its name quotes to, so from Elixir 1.14 `µ` (the micro sign) and `μ` (the Greek mu), and two
 * normal forms of `café`, are one variable. Before 1.14 each spelling is a variable of its own.
 */
class VariableNormalFormsTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // Resolution: a read finds its declaration

    fun testReadResolvesToItsDeclaration() = assertResolves("a = 1\n<caret>a + 1")
    fun testMicroSignReadResolves() = assertResolves("µ = 1\n<caret>µ + 1", elixir("1.14.0"))
    fun testMicroSignReadResolvesBefore1_14() = assertResolves("µ = 1\n<caret>µ + 1", elixir("1.13.0"))
    fun testDecomposedReadResolves() = assertResolves("$DECOMPOSED = 1\n<caret>$DECOMPOSED + 1", elixir("1.14.0"))

    fun testPrecomposedReadResolvesToDecomposedDeclaration() =
        assertResolves("$DECOMPOSED = 1\n<caret>$PRECOMPOSED + 1", elixir("1.14.0"))

    fun testDecomposedReadResolvesToPrecomposedDeclaration() =
        assertResolves("$PRECOMPOSED = 1\n<caret>$DECOMPOSED + 1", elixir("1.14.0"))

    fun testGreekMuReadResolvesToMicroSignDeclaration() = assertResolves("µ = 1\n<caret>μ + 1", elixir("1.14.0"))

    /** Before 1.14 `µ` and `μ` are two variables. */
    fun testGreekMuReadDoesNotResolveToMicroSignDeclarationBefore1_14() =
        assertEmpty(symbolsAt("µ = 1\n<caret>μ + 1", elixir("1.13.0")))

    fun testPinResolves() = assertResolves("µ = 1\n{^µ, y} = {1, <caret>µ}", elixir("1.14.0"))
    fun testParameterReadResolves() = assertResolves("def f(µ), do: <caret>µ", elixir("1.14.0"))

    // A `bind_quoted:` key binds a variable in the quote

    fun testAsciiBindQuotedKeyResolves() = assertBindQuotedResolves("a", "a")
    fun testMicroSignBindQuotedKeyResolves() = assertBindQuotedResolves("µ", "µ")
    fun testMicroSignBindQuotedKeyResolvesBefore1_14() = assertBindQuotedResolves("µ", "µ", elixir("1.13.0"))
    fun testGreekMuReadResolvesToBindQuotedMicroSignKey() = assertBindQuotedResolves("µ", "μ")
    fun testDecomposedBindQuotedKeyResolves() = assertBindQuotedResolves(DECOMPOSED, DECOMPOSED)
    fun testPrecomposedReadResolvesToDecomposedBindQuotedKey() = assertBindQuotedResolves(DECOMPOSED, PRECOMPOSED)

    /** Before 1.14 `μ` is not the key `µ`. */
    fun testGreekMuReadDoesNotResolveToBindQuotedMicroSignKeyBefore1_14() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText("variable.ex", inFunction("quote bind_quoted: [µ: 1] do\n  <caret>μ\nend"))

        assertEmpty(validCandidates())
    }

    private fun assertBindQuotedResolves(key: String, read: String, level: ElixirLanguageLevel = elixir("1.14.0")) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("variable.ex", inFunction("quote bind_quoted: [$key: 1] do\n  <caret>$read\nend"))

        assertEquals(listOf(key), validCandidates().map { it.text })
    }

    private fun validCandidates(): List<PsiElement> =
        (myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }

    /** A variable bound inside a `quote` elsewhere in the project is a candidate that is never valid. */
    fun testVariableBoundInAQuoteIsACandidate() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText("quoted.ex", "defmodule Q do\n  defmacro m do\n    quote do\n      µ = 1\n    end\n  end\nend\n")

        myFixture.configureByText("reader.ex", inFunction("<caret>µ"))
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        val results = reference.multiResolve(false).toList()

        assertNotEmpty(results)
        assertTrue(results.toString(), results.none { it.isValidResult })
    }

    // Find Usages and identity

    fun testMicroSignUsageIsFound() = assertUsages("<caret>µ = 1\nµ + 1", elixir("1.14.0"))
    fun testMicroSignUsageIsFoundBefore1_14() = assertUsages("<caret>µ = 1\nµ + 1", elixir("1.13.0"))
    fun testDecomposedUsageIsFoundFromPrecomposed() = assertUsages("<caret>$PRECOMPOSED = 1\n$DECOMPOSED + 1", elixir("1.14.0"))
    fun testPrecomposedUsageIsFoundFromDecomposed() = assertUsages("<caret>$DECOMPOSED = 1\n$PRECOMPOSED + 1", elixir("1.14.0"))
    fun testGreekMuUsageIsFoundFromMicroSign() = assertUsages("<caret>µ = 1\nμ + 1", elixir("1.14.0"))

    /** A name that mixes the two spellings is found when it is written the way it was declared. */
    fun testMixedMicroSignAndGreekMuUsageIsFound() = assertUsages("<caret>$MIXED_MU = 1\n$MIXED_MU + 1", elixir("1.14.0"))
    fun testPartlyDecomposedUsageIsFound() = assertUsages("<caret>$PARTLY_DECOMPOSED = 1\n$PARTLY_DECOMPOSED + 1", elixir("1.14.0"))
    fun testPartlyDecomposedUsageIsFoundFromTheOtherMix() =
        assertUsages("<caret>$PARTLY_DECOMPOSED = 1\n$OTHER_PARTLY_DECOMPOSED + 1", elixir("1.14.0"))

    /** A bracket read is a use of the variable it indexes. */
    fun testBracketReadIsAUse() = assertUsages("<caret>µ = [1]\nµ[0]", elixir("1.14.0"))

    fun testDecomposedBracketReadIsAUse() = assertUsages("<caret>$PRECOMPOSED = [1]\n$DECOMPOSED[0]", elixir("1.14.0"))

    /** A dotted read is a use of the variable the map is bound to. */
    fun testDotReadIsAUse() = assertUsages("<caret>µ = %{a: 1}\nµ.a", elixir("1.14.0"))

    fun testDecomposedDotReadIsAUse() = assertUsages("<caret>$PRECOMPOSED = %{a: 1}\n$DECOMPOSED.a", elixir("1.14.0"))

    // Completion offers a rebound name once

    fun testRebindingOffersOneVariable() = assertOffersOnce("xa = 0\nxa = 1\nxb = 2\nx<caret>", setOf("xa"))

    fun testQuoteBindingOffersOneVariable() =
        assertOffersOnce("xa = 0\nxb = 1\nquote bind_quoted: [xa: xa] do\n  x<caret>\nend", setOf("xa"))

    fun testMicroSignQuoteBindingOffersOneVariable() =
        assertOffersOnce("xµ = 0\nxb = 1\nquote bind_quoted: [xμ: xµ] do\n  x<caret>\nend", MICRO_OR_MU)

    fun testDecomposedQuoteBindingOffersOneVariable() =
        assertOffersOnce(
            "x$DECOMPOSED = 0\nxb = 1\nquote bind_quoted: [x$PRECOMPOSED: x$DECOMPOSED] do\n  x<caret>\nend",
            setOf("x$DECOMPOSED", "x$PRECOMPOSED")
        )

    fun testMicroSignQuoteBindingOffersBothBefore1_14() {
        val offered = variableCompletions("xµ = 0\nxb = 1\nquote bind_quoted: [xμ: xµ] do\n  x<caret>\nend", elixir("1.13.0"))

        assertEquals(offered.toString(), 2, offered.count { it in MICRO_OR_MU })
    }

    fun testMicroSignRebindingOffersOneVariable() = assertOffersOnce("xµ = 0\nxμ = 1\nxb = 2\nx<caret>", MICRO_OR_MU)

    fun testMicroSignRebindingOffersBothBefore1_14() {
        val offered = variableCompletions("xµ = 0\nxμ = 1\nxb = 2\nx<caret>", elixir("1.13.0"))

        assertEquals(offered.toString(), 2, offered.count { it in MICRO_OR_MU })
    }

    fun testDecomposedRebindingOffersOneVariable() =
        assertOffersOnce(
            "x$DECOMPOSED = 0\nx$PRECOMPOSED = 1\nxb = 2\nx<caret>",
            setOf("x$DECOMPOSED", "x$PRECOMPOSED")
        )

    /** A name is offered as it is written, so the prefix typed to reach it still matches. */
    fun testCompletionAfterATypedMicroSignOffersTheVariables() =
        assertEquals(listOf("µ", "µb"), variableCompletions("µ = 0\nµb = 1\nµ<caret>", elixir("1.14.0")).sorted())

    fun testCompletionAfterATypedMicroSignOffersTheVariablesBefore1_14() =
        assertEquals(listOf("µ", "µb"), variableCompletions("µ = 0\nµb = 1\nµ<caret>", elixir("1.13.0")).sorted())

    /** Before 1.14 `μb` is not a spelling of `µb`, so a typed `µ` does not offer it. */
    fun testCompletionAfterATypedMicroSignDoesNotOfferTheGreekMuVariableBefore1_14() =
        assertEquals(listOf("µc", "µd"), variableCompletions("μb = 0\nµc = 1\nµd = 2\nµ<caret>", elixir("1.13.0")).sorted())

    fun testCompletionAfterATypedMixedSpellingOffersTheVariable() =
        assertEquals(
            listOf("${MIXED_MU}b", "${MIXED_MU}c"),
            variableCompletions("${MIXED_MU}b = 0\n${MIXED_MU}c = 1\n${MIXED_MU_OTHER}<caret>", elixir("1.14.0")).sorted()
        )

    fun testCompletionAfterATypedDecomposedPrefixOffersTheVariables() =
        assertEquals(
            listOf(DECOMPOSED, "${DECOMPOSED}b"),
            variableCompletions("$DECOMPOSED = 0\n${DECOMPOSED}b = 1\n$DECOMPOSED<caret>", elixir("1.14.0")).sorted()
        )

    /** A prefix typed with one spelling reaches a variable declared with the other. */
    fun testCompletionAfterATypedMicroSignOffersAVariableDeclaredWithTheGreekMu() =
        assertEquals(listOf("μb", "μc"), variableCompletions("μb = 0\nμc = 1\nµ<caret>", elixir("1.14.0")).sorted())

    fun testCompletionAfterATypedMicroSignOffersTheNearestRebinding() {
        val offered = variableCompletions("xµ = 0\nxμ = 1\nxµb = 2\nxµ<caret>", elixir("1.14.0"))

        assertEquals(offered.toString(), 2, offered.size)
        assertTrue(offered.toString(), offered.any { it == "xµ" || it == "xμ" })
    }

    fun testCompletionAfterATypedDecomposedPrefixOffersAVariableDeclaredPrecomposed() =
        assertEquals(
            listOf("${PRECOMPOSED}b", "${PRECOMPOSED}c"),
            variableCompletions("${PRECOMPOSED}b = 0\n${PRECOMPOSED}c = 1\n$DECOMPOSED<caret>", elixir("1.14.0")).sorted()
        )

    /** The pin on the left of a match reads the variable bound before it, however its name is spelled throughout. */
    fun testPinReadAndRebindingAreUsesOfTheMicroSignVariable() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText(
            "variable.ex",
            "defmodule M do\n  def run(parameter) do\n    <caret>µ = parameter\n    ^µ = µ\n    µ = µ + 1\n    µ\n  end\nend\n"
        )

        assertEquals(5, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    fun testPinReadOfAMicroSignParameterIsAUse() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText("variable.ex", "defmodule M do\n  def run(<caret>µ) do\n    ^µ = µ\n  end\nend\n")

        assertEquals(2, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    fun testPinReadOfADecomposedParameterIsAUse() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText(
            "variable.ex",
            "defmodule M do\n  def run(<caret>$DECOMPOSED) do\n    ^$DECOMPOSED = $DECOMPOSED\n  end\nend\n"
        )

        assertEquals(2, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    /** A quoted key binds its atom, as the unquoted one does. */
    fun testReadResolvesToAQuotedBindQuotedKey() = assertBindQuotedResolves("\"a\"", "a")

    fun testQuotedKeyIsNamedByItsAtom() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText("variable.ex", "x = [a: 1, \"a\": 2, \"b\": 3]")

        val keys = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirKeywordKey::class.java)

        assertEquals(listOf("a", "a", "b"), keys.sortedBy { it.textOffset }.map { it.name })
    }

    private fun assertOffersOnce(text: String, names: Set<String>) {
        val offered = variableCompletions(text, elixir("1.14.0"))

        assertEquals(offered.toString(), 1, offered.count { it in names })
    }

    private fun assertResolves(text: String, level: ElixirLanguageLevel? = null) =
        assertNotEmpty(symbolsAt(text, level))

    private fun symbolsAt(text: String, level: ElixirLanguageLevel? = null): List<VariableSymbol> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level ?: elixir("1.14.0"))
        myFixture.configureByText("variable.ex", inFunction(text))
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!

        return ReadAction.nonBlocking(Callable {
            VariableReference.resolveSymbols(hostOf(leaf)).toList()
        }).executeSynchronously()
    }

    private fun hostOf(leaf: PsiElement): PsiElement =
        generateSequence(leaf) { it.parent }.firstOrNull { it is Call || it is ElixirVariable } ?: leaf

    private fun assertUsages(text: String, level: ElixirLanguageLevel) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("variable.ex", text)

        assertEquals(text, 1, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    private fun variableCompletions(text: String, level: ElixirLanguageLevel): List<String> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("variable.ex", inFunction(text))

        return myFixture.completionStringsAtCaret().orEmpty()
    }

    private fun inFunction(text: String) = "defmodule M do\n  def f do\n${text.prependIndent("    ")}\n  end\nend\n"

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
        val MICRO_OR_MU = setOf("xµ", "xμ")
        const val MIXED_MU = "xµμ"
        const val MIXED_MU_OTHER = "xμµ"

        /** The first `é` precomposed and the second decomposed, and the reverse. */
        const val PARTLY_DECOMPOSED = "caféé"
        const val OTHER_PARTLY_DECOMPOSED = "caféé"
    }
}
