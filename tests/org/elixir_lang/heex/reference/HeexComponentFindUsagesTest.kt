package org.elixir_lang.heex.reference

import com.intellij.find.usages.api.PsiUsage
import com.intellij.psi.PsiManager
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret

/**
 * Find Usages and Rename on a `def`/`defp` declaration include the HEEx component tags that name it,
 * in a `.heex` file and inside a `~H` sigil. A tag has two name tokens (`<.button>` and `</.button>`),
 * so each tag is two usages.
 */
class HeexComponentFindUsagesTest : HeexHostTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/heex/reference/component_find_usages"

    fun testLocalComponentTagIsFoundAsUsage() {
        assertEquals(2, nonDeclarationUsageCount("local/page_live.ex", "local/page_live.html.heex"))
    }

    fun testRemoteComponentTagIsFoundAsUsage() {
        assertEquals(2, nonDeclarationUsageCount("remote/core_components.ex", "remote/page_live.html.heex"))
    }

    /** The tag goes into a minimal synthetic module; qualified resolution needs no particular entrance. */
    fun testRemoteComponentTagInsideHSigilIsFoundAsUsage() {
        myFixture.configureByText(
            "test.ex",
            heexSigilModuleText(
                entranceModuleText = "defmodule Test do\nend",
                heexBody = myFixture.fixtureText("remote/page_live.html.heex"),
                myFixture.fixtureText("remote/core_components.ex")
            )
        )
        assertEquals(2, psiUsagesAtCaret().count { !it.declaration })
    }

    /** A `<:button>` slot tag is not a usage, even though its name matches the function's name. */
    fun testSlotTagWithMatchingNameIsNotFoundAsUsage() {
        assertEquals(0, nonDeclarationUsageCount("slot_negative/page_live.ex", "slot_negative/page_live.html.heex"))
    }

    fun testSlotTagWithMatchingNameInsideHSigilIsNotFoundAsUsage() {
        myFixture.configureByText(
            "test.ex",
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("slot_negative/page_live.ex"),
                heexBody = myFixture.fixtureText("slot_negative/page_live.html.heex")
            )
        )
        assertEquals(0, psiUsagesAtCaret().count { !it.declaration })
    }

    fun testComponentTagInsideHSigilIsFoundAsUsage() {
        myFixture.configureByText(
            "test.ex",
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("local/page_live.ex"),
                heexBody = myFixture.fixtureText("local/page_live.html.heex")
            )
        )
        assertEquals(2, psiUsagesAtCaret().count { !it.declaration })
    }

    /** `def src_µ` quotes to `src_μ`; Elixir 1.14 reads `<.src_µ>` as that component, so it is a usage. */
    fun testMicroSignComponentTagIsFoundAsUsage() {
        assertEquals(2, nonDeclarationUsageCount("micro/page_live.ex", "micro/page_live.html.heex"))
    }

    fun testMicroSignComponentTagInsideHSigilIsFoundAsUsage() {
        myFixture.configureByText(
            "test.ex",
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("micro/page_live.ex"),
                heexBody = myFixture.fixtureText("micro/page_live.html.heex")
            )
        )
        assertEquals(2, psiUsagesAtCaret().count { !it.declaration })
    }

    /** `def button(assigns \\ %{})` is one symbol family (`button/0`, `button/1`); the tag is found from it. */
    fun testComponentTagIsFoundFromDeclarationWithDefaultArgument() {
        assertEquals(2, nonDeclarationUsageCount("default_arity/page_live.ex", "default_arity/page_live.html.heex"))
    }

    /** Renaming `def button` rewrites `<.button>` keeping its leading `.`. */
    fun testRenamingDefinitionRewritesLocalComponentTagWithDotPrefix() {
        val files = myFixture.configureByFiles("local/page_live.ex", "local/page_live.html.heex")
        val heexFile = files[1]

        myFixture.renameTargetAtCaret("submit_button")

        val renamed = PsiManager.getInstance(project).findFile(heexFile.virtualFile)
        assertNotNull(renamed)
        assertTrue(
            "Expected the renamed tag to read <.submit_button>, got: ${renamed!!.text}",
            renamed.text.contains(".submit_button")
        )
    }

    fun testRenamingDefinitionRewritesLocalComponentTagInsideHSigilWithDotPrefix() {
        myFixture.configureByText(
            "test.ex",
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("local/page_live.ex"),
                heexBody = myFixture.fixtureText("local/page_live.html.heex")
            )
        )

        myFixture.renameTargetAtCaret("submit_button")

        assertTrue(
            "Expected the renamed tag to read <.submit_button>, got: ${myFixture.file.text}",
            myFixture.file.text.contains(".submit_button")
        )
    }

    private fun nonDeclarationUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return psiUsagesAtCaret().count { !it.declaration }
    }

    private fun psiUsagesAtCaret(): List<PsiUsage> {
        myFixture.assertShowUsagesChosenAtCaret()
        return myFixture.singleTargetPsiUsagesAtCaret(project)
    }
}
