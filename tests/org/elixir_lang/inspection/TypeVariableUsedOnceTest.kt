package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

class TypeVariableUsedOnceTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(TypeVariableUsedOnce::class.java)
    }

    fun testTypeVariableUsedOnceFlagged() {
        myFixture.configureByFiles("type_variable_used_once_flagged.ex")
        myFixture.checkHighlighting()
    }

    fun testTypeVariableUsedTwiceNotFlagged() {
        myFixture.configureByFiles("type_variable_used_twice_not_flagged.ex")
        myFixture.checkHighlighting()
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

    /** From Elixir 1.14 two normal forms of a name are one type variable, so the use is a use of the parameter. */
    fun testPrecomposedUseOfDecomposedParameter() {
        check("@type t($DECOMPOSED) :: $PRECOMPOSED", elixir("1.14.0"))
    }

    fun testDecomposedUseOfPrecomposedParameter() {
        check("@type t($PRECOMPOSED) :: $DECOMPOSED", elixir("1.14.0"))
    }

    fun testGreekMuUseOfMicroSignParameter() {
        check("@type t(µ) :: μ", elixir("1.14.0"))
    }

    /** Before 1.14 `µ` and `μ` are two type variables, so the parameter is used once. */
    fun testGreekMuUseOfMicroSignParameterBefore1_14() {
        check("@type t(<error descr=\"$ONCE\">µ</error>) :: μ", elixir("1.13.0"))
    }

    fun testMicroSignUseOfMicroSignParameterBefore1_14() {
        check("@type t(µ) :: µ", elixir("1.13.0"))
    }

    private fun check(attribute: String, level: ElixirLanguageLevel) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("type_variable.ex", "defmodule M do\n  $attribute\nend\n")
        myFixture.checkHighlighting()
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/inspection/type_variable_used_once"

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
        const val ONCE = "Type variable 'µ' is used only once; reference it at least twice or use term()"
    }
}
