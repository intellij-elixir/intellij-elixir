package org.elixir_lang.psi.impl.call

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * What a call defines or reaches depends on the atom its callee quotes to: `Kernel."def"(h(), do: 1)` defines `h/0` as
 * `def h, do: 1` does, and `Kernel."defmodule" Inner do ... end` puts its functions in `Outer.Inner`.
 */
class QuotedCallNameResolutionTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testDefinitionByAnUnquotedNameResolves() =
        assertResolves("defmodule Outer do\n  Kernel.def(h(), do: 1)\n  def g, do: <caret>h()\nend\n")

    fun testDefinitionByAQuotedNameResolves() =
        assertResolves("defmodule Outer do\n  Kernel.\"def\"(h(), do: 1)\n  def g, do: <caret>h()\nend\n")

    fun testDefinitionByAQuotedNameWithoutParenthesesResolves() =
        assertResolves("defmodule Outer do\n  Kernel.\"def\" h(), do: 1\n  def g, do: <caret>h()\nend\n")

    /** From Elixir 1.18 a quoted remote name is unescaped; before it, the name keeps its escape as written. */
    fun testDefinitionByAnEscapedQuotedNameResolvesFrom1_18() =
        assertResolves(ESCAPED_DEFINITION, elixir("1.18.0"))

    fun testDefinitionByAnEscapedQuotedNameDoesNotResolveBefore1_18() =
        assertEmpty(validResults(ESCAPED_DEFINITION, elixir("1.17.0")))

    /** An interpolated name quotes to no atom, so the call defines nothing. */
    fun testDefinitionByAnInterpolatedNameDoesNotResolve() =
        assertEmpty(validResults("defmodule Outer do\n  Kernel.\"de#{x}\"(h(), do: 1)\n  def g, do: <caret>h()\nend\n"))

    fun testMacroDefinitionByAQuotedNameResolves() =
        assertResolves("defmodule Outer do\n  Kernel.\"defmacro\"(h(), do: 1)\n  def g, do: <caret>h()\nend\n")

    fun testFunctionOfAModuleDefinedByAQuotedNameIsInThatModule() =
        assertResolves(
            "defmodule Outer do\n  Kernel.\"defmodule\" Inner do\n    def f, do: 1\n  end\nend\n" +
                "defmodule User do\n  def g, do: Outer.Inner.<caret>f()\nend\n"
        )

    /** Before the quoted name was read, the function was credited to the enclosing module. */
    fun testFunctionOfAModuleDefinedByAQuotedNameIsNotInTheEnclosingModule() =
        assertEmpty(
            validResults(
                "defmodule Outer do\n  Kernel.\"defmodule\" Inner do\n    def f, do: 1\n  end\nend\n" +
                    "defmodule User do\n  def g, do: Outer.<caret>f()\nend\n"
            )
        )

    /** A function defined in one file and called from another is found through the index by its atom. */
    fun testMicroSignFunctionIsFoundFromAnotherFile() =
        assertFoundFromAnotherFile("µ", "µ", elixir("1.14.0"))

    fun testMicroSignFunctionIsFoundFromAnotherFileBefore1_14() =
        assertFoundFromAnotherFile("µ", "µ", elixir("1.13.0"))

    fun testNormalFormsOfAFunctionNameAreOneFunction() =
        assertFoundFromAnotherFile(DECOMPOSED, PRECOMPOSED, elixir("1.14.0"))

    fun testAllDecomposedFunctionIsFoundFromAnotherFile() =
        assertFoundFromAnotherFile(DECOMPOSED, DECOMPOSED, elixir("1.14.0"))

    private fun assertFoundFromAnotherFile(defined: String, called: String, level: ElixirLanguageLevel) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("definer.ex", "defmodule M do\n  def $defined, do: 1\nend\n")

        assertResolves("defmodule User do\n  def g, do: M.<caret>$called()\nend\n")
    }

    private fun assertResolves(text: String, level: ElixirLanguageLevel? = null) =
        assertNotEmpty(validResults(text, level))

    private fun validResults(text: String, level: ElixirLanguageLevel? = null): List<ResolveResult> {
        level?.let { ElixirLanguageLevelResolver.overrideLanguageLevel(project, it) }

        return myFixture.configureByText("caller.ex", text).let {
            (myFixture.file.findReferenceAt(myFixture.caretOffset)!! as PsiPolyVariantReference)
                .multiResolve(false)
                .filter { it.isValidResult }
        }
    }

    private companion object {
        const val ESCAPED_DEFINITION =
            "defmodule Outer do\n  Kernel.\"d\\x65f\"(h(), do: 1)\n  def g, do: <caret>h()\nend\n"
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
    }
}
