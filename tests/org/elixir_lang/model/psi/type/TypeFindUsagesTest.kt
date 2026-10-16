package org.elixir_lang.model.psi.type

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

class TypeFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    fun testCtrlClickOnTypeDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_basic.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testFindUsagesOnTypeDeclarationFindsSpecSites() {
        assertEquals(2, nonDeclarationUsageCount("usages_basic.ex"))
    }

    fun testFindUsagesIgnoresSameNamedTypeInAnotherModule() {
        assertEquals(1, nonDeclarationUsageCount("usages_cross_module.ex"))
    }

    fun testFindUsagesDoesNotTreatFunctionHeadVariableAsTypeUsage() {
        assertEquals(0, nonDeclarationUsageCount("usages_variable_name_not_type_usage.ex"))
    }

    /** `@type unquote(:t)()` defines `t/0`. */
    fun testFindUsagesAtAnUnquotedTypeNameFindsItsSpecUse() {
        myFixture.configureByText(
            "unquoted.ex",
            "defmodule M do\n  @type <caret>unquote(:t)() :: integer\n  @spec f() :: t()\n  def f, do: 1\nend\n"
        )

        val usages = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }

        assertEquals(listOf("t"), usages.map { it.file.text.substring(it.range.startOffset, it.range.endOffset) })
        assertEquals(listOf(3), usages.map { myFixture.editor.document.getLineNumber(it.range.startOffset) + 1 })
    }

    /** Renaming `t/0` rewrites the atom `unquote` names it by, and its uses. */
    fun testRenameAtAUseOfAnUnquotedTypeNameRewritesItsAtom() {
        myFixture.configureByText(
            "unquoted.ex",
            "defmodule M do\n  @type unquote(:t)() :: integer\n  @spec f() :: <caret>t()\n  def f, do: 1\nend\n"
        )

        myFixture.renameTargetAtCaret("u")

        assertEquals(
            "defmodule M do\n  @type unquote(:u)() :: integer\n  @spec f() :: u()\n  def f, do: 1\nend\n",
            myFixture.editor.document.text
        )
    }

    private fun nonDeclarationUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.nonDeclarationUsageCountAtCaret(project)
    }
}
