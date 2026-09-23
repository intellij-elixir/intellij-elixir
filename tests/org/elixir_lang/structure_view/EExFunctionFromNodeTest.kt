package org.elixir_lang.structure_view

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.EExFunctionFrom

/**
 * `EEx.function_from_*` builds a node but was absent from [Model.isSuitable].
 *
 * The call is qualified and [Model.isSuitable] answers from a bare `ResolveState`, a combination
 * `build` never produces - it threads an entrance of its own.
 */
class EExFunctionFromNodeTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/structure_view/ex_unit"

    private fun configure(): List<Call> {
        myFixture.configureByFiles("eex_host.ex", "eex.ex")

        return PsiTreeUtil
            .findChildrenOfType(myFixture.file, Call::class.java)
            .filter { call -> call.functionName()?.startsWith("function_from_") == true }
    }

    /**
     * Calls [Model.isSuitable] per call because it is also the crash guard: a null `ENTRANCE` reaching
     * `isAncestor` used to throw here.
     */
    fun testAFunctionFromCallIsACaretSyncTarget() {
        val calls = configure()

        assertFalse("the fixture must contain function_from_* calls", calls.isEmpty())

        val unclaimed = calls.filterNot { call -> Model.isSuitable(call) }.map { it.text.lineSequence().first() }

        assertEquals(
            "Model.isSuitable must claim an EEx.function_from_* call, or its node cannot follow the caret",
            emptyList<String>(),
            unclaimed
        )
    }

    fun testAFunctionFromCallIsBuiltAsItsOwnNode() {
        val calls = configure()

        assertFalse("the fixture must contain function_from_* calls", calls.isEmpty())

        val built = mutableSetOf<PsiElement>()

        fun walk(element: StructureViewTreeElement) {
            if (element is EExFunctionFrom) {
                (element.value as? PsiElement)?.let { built.add(it) }
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) {
                    walk(child)
                }
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        val missing = calls.filterNot { call -> call in built }.map { it.text.lineSequence().first() }

        assertEquals("every function_from_* call must build an EExFunctionFrom node", emptyList<String>(), missing)
    }

    /** `:"defp"` is `:defp`, so the kind is read as Elixir reads it, as the name is. */
    fun testAQuotedKindGivesTheBareKindsVisibility() {
        myFixture.configureByText(
            "quoted_kind.ex",
            """
            defmodule QuotedKind do
              require EEx

              EEx.function_from_string(:"defp", :"page", "")
            end
            """.trimIndent()
        )
        myFixture.copyFileToProject("eex.ex")

        val nodes = mutableListOf<EExFunctionFrom>()

        fun walk(element: StructureViewTreeElement) {
            if (element is EExFunctionFrom) nodes.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(listOf("page/0 PRIVATE"), nodes.map { "${it.name} ${it.visibility()}" })
    }
}
