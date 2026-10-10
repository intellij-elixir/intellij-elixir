package org.elixir_lang.model.psi.type

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.inspection.UnresolvableType
import org.elixir_lang.structure_view.element.Type as TypeElement

/**
 * A type named like the function whose `@spec` (or `@callback`) uses it still resolves to the `@type`: the head
 * of a `@spec` names a function, not a type.
 */
@Suppress("UnstableApiUsage")
class SpecTypeSameNameTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testGoToFromTheCallFormReachesTheType() = assertGoToReachesTheType(module("@spec capabilities :: cap<caret>abilities()"))

    fun testGoToFromTheBareFormReachesTheType() = assertGoToReachesTheType(module("@spec capabilities :: cap<caret>abilities"))

    fun testGoToFromACallbackReachesTheType() =
        assertGoToReachesTheType(module("@callback capabilities :: cap<caret>abilities()", "def capabilities, do: %{a: 1}"))

    fun testGoToFromASpecOfADifferentNameReachesTheType() =
        assertGoToReachesTheType(module("@spec caps :: cap<caret>abilities()", "def caps, do: %{a: 1}"))

    fun testGoToFromASpecOfADifferentArityReachesTheType() =
        assertGoToReachesTheType(module("@spec capabilities(t) :: cap<caret>abilities()", "def capabilities(_), do: %{a: 1}"))

    fun testFindUsagesFromTheTypeListsTheCallForm() = assertFindUsagesListsTheSpecUsage("@spec capabilities :: capabilities()")

    fun testFindUsagesFromTheTypeListsTheBareForm() = assertFindUsagesListsTheSpecUsage("@spec capabilities :: capabilities")

    fun testTheCallFormIsNotReportedUnresolvable() = assertNotReportedUnresolvable("@spec capabilities :: capabilities()")

    fun testTheBareFormIsNotReportedUnresolvable() = assertNotReportedUnresolvable("@spec capabilities :: capabilities")

    private fun assertGoToReachesTheType(text: String) {
        myFixture.configureByText("m.ex", text)
        myFixture.assertGotoDeclarationChosenAtCaret()
        myFixture.assertGotoDeclarationLandsIn("capabilities", "a @type/@typep/@opaque declaration") { TypeElement.`is`(it) }
    }

    private fun assertFindUsagesListsTheSpecUsage(spec: String) {
        val text = module(spec)
        myFixture.configureByText("m.ex", text.replace("@type capabilities", "@type cap<caret>abilities"))

        val usageOffset = myFixture.editor.document.text.indexOf(":: capabilities") + ":: ".length
        val usageOffsets = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { it.range.startOffset }

        assertTrue("Expected the usage at $usageOffset among $usageOffsets", usageOffset in usageOffsets)
    }

    private fun assertNotReportedUnresolvable(spec: String) {
        myFixture.enableInspections(UnresolvableType::class.java)
        myFixture.configureByText("m.ex", module(spec))
        myFixture.checkHighlighting()
    }

    private fun module(spec: String, definition: String = "def capabilities, do: %{a: 1}"): String =
        "defmodule M do\n  @type capabilities :: %{a: 1}\n\n  $spec\n  $definition\nend\n"
}
