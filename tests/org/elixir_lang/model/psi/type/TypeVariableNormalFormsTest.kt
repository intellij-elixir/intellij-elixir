package org.elixir_lang.model.psi.type

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.ElixirKeywordKey

/**
 * A type variable and a type are the atoms their names quote to: from Elixir 1.14 `µ` and `μ`, and two normal forms of
 * `café`, are one name, and a quoted `"a":` key of a `when` is `a:`. Before 1.14 each spelling is a name of its own.
 */
class TypeVariableNormalFormsTest : PlatformTestCase() {
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

    // A `when` key declares a type variable

    fun testWhenKeyUsages() = assertUsages("@spec id(a) :: a when <caret>a: term()", 2)
    fun testQuotedWhenKeyUsages() = assertUsages("@spec id(a) :: a when <caret>\"a\": term()", 2)
    fun testMicroSignWhenKeyUsages() = assertUsages("@spec id(µ) :: µ when <caret>µ: term()", 2)
    fun testMicroSignWhenKeyUsagesBefore1_14() = assertUsages("@spec id(µ) :: µ when <caret>µ: term()", 2, elixir("1.13.0"))
    fun testGreekMuWhenKeyUsagesOfMicroSign() = assertUsages("@spec id(µ) :: µ when <caret>μ: term()", 2)

    fun testDecomposedWhenKeyUsagesOfPrecomposed() =
        assertUsages("@spec id($PRECOMPOSED) :: $PRECOMPOSED when <caret>$DECOMPOSED: term()", 2)

    fun testPrecomposedWhenKeyUsagesOfDecomposed() =
        assertUsages("@spec id($DECOMPOSED) :: $DECOMPOSED when <caret>$PRECOMPOSED: term()", 2)

    fun testUseResolvesToItsWhenKey() = assertDeclaration("@spec id(<caret>µ) :: µ when µ: term()", "µ")
    fun testUseResolvesToItsQuotedWhenKey() = assertDeclaration("@spec id(<caret>a) :: a when \"a\": term()", "\"a\"")

    fun testDecomposedUseResolvesToPrecomposedWhenKey() =
        assertDeclaration("@spec id(<caret>$DECOMPOSED) :: $DECOMPOSED when $PRECOMPOSED: term()", PRECOMPOSED)

    // A type head parameter declares a type variable

    fun testHeadParameterUsages() = assertUsages("@type box(<caret>µ) :: {:box, µ}", 1)
    fun testHeadParameterUsagesBefore1_14() = assertUsages("@type box(<caret>µ) :: {:box, µ}", 1, elixir("1.13.0"))
    fun testHeadParameterUsagesInTwoNormalForms() = assertUsages("@type box(<caret>$DECOMPOSED) :: {:box, $PRECOMPOSED}", 1)
    fun testHeadParameterUseResolves() = assertDeclaration("@type box(µ) :: {:box, <caret>µ}", "µ")
    fun testHeadParameterUseResolvesBefore1_14() = assertDeclaration("@type box(µ) :: {:box, <caret>µ}", "µ", elixir("1.13.0"))
    fun testDecomposedHeadParameterUseResolves() = assertDeclaration("@type box($DECOMPOSED) :: {:box, <caret>$DECOMPOSED}", DECOMPOSED)

    fun testPrecomposedHeadParameterUseResolvesToDecomposed() =
        assertDeclaration("@type box($DECOMPOSED) :: {:box, <caret>$PRECOMPOSED}", DECOMPOSED)

    // A type is found by the word its name is written as

    fun testMicroSignTypeUsages() = assertUsages("@type <caret>µ :: atom\n@type t :: µ()", 1)
    fun testGreekMuTypeUsagesOfMicroSign() = assertUsages("@type <caret>µ :: atom\n@type t :: μ()", 1)
    fun testMicroSignTypeUsagesBefore1_14() = assertUsages("@type <caret>µ :: atom\n@type t :: µ()", 1, elixir("1.13.0"))
    fun testDecomposedTypeUsages() = assertUsages("@type <caret>$PRECOMPOSED :: atom\n@type t :: $DECOMPOSED()", 1)
    fun testPrecomposedTypeUsages() = assertUsages("@type <caret>$DECOMPOSED :: atom\n@type t :: $PRECOMPOSED()", 1)

    private fun assertUsages(text: String, expected: Int, level: ElixirLanguageLevel = elixir("1.14.0")) {
        configure(text, level)

        assertEquals(text, expected, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    private fun assertDeclaration(text: String, expected: String, level: ElixirLanguageLevel = elixir("1.14.0")) {
        configure(text, level)

        // Navigation lands on the first leaf of a quoted key, which is its opening quote.
        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        val declaration = generateSequence(destination) { it.parent }.firstOrNull { it is ElixirKeywordKey } ?: destination

        assertEquals(text, expected, declaration?.text)
    }

    private fun configure(text: String, level: ElixirLanguageLevel) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("type_variable.ex", "defmodule T do\n$text\nend\n")
    }

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
    }
}
