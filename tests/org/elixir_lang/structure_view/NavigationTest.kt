package org.elixir_lang.structure_view

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElementWrapper
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.pom.Navigatable
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.psi.PsiElement
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.CallDefinitionClause
import org.elixir_lang.structure_view.element.EExFunctionFrom

/**
 * Activating a structure view element opens its target at the target's offset, by each route the platform takes:
 * the tree's wrapper node, which forwards to the element's `navigate(boolean)`, that method called directly, as the
 * File Structure popup's backend does, and the element's `navigationRequest()` handed to the navigation service.
 */
class NavigationTest : LightTestCase() {
    private lateinit var model: Model

    private fun elements(): List<StructureViewTreeElement> {
        val found = mutableListOf<StructureViewTreeElement>()

        fun walk(element: StructureViewTreeElement) {
            found.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        model = Model(myFixture.file as ElixirFile, null)
        walk(model.root)

        return found
    }

    private enum class Route { WRAPPER_NODE, DIRECT, NAVIGATION_SERVICE }

    private fun navigate(element: StructureViewTreeElement, route: Route) {
        when (route) {
            Route.WRAPPER_NODE -> TreeElementWrapper(project, element, model).navigate(true)
            Route.DIRECT -> (element as Navigatable).navigate(true)
            Route.NAVIGATION_SERVICE -> runWithModalProgressBlocking(project, "navigate") {
                val request = readAction { (element as Navigatable).navigationRequest() }

                project.service<NavigationService>().navigate(request!!, NavigationOptions.defaultOptions())
            }
        }
    }

    private fun assertNavigatesTo(element: StructureViewTreeElement, target: PsiElement) {
        for (route in Route.entries) {
            // Start somewhere else so that arriving at the target is a change.
            myFixture.editor.caretModel.moveToOffset(0)

            navigate(element, route)

            // From 2026.3 the platform opens the editor on a coroutine.
            PlatformTestUtil.waitWithEventsDispatching(
                "${element.javaClass.simpleName} must navigate by $route to the offset of its target",
                { FileEditorManager.getInstance(project).selectedTextEditor?.caretModel?.offset == target.textOffset },
                10
            )
        }
    }

    fun testACallDefinitionClauseNavigatesToItsCall() {
        myFixture.configureByText(
            "clause_navigation.ex",
            "defmodule M do\n  def first(a), do: a\n\n  def second(b), do: b\nend\n"
        )

        val clauses = elements().filterIsInstance<CallDefinitionClause>()

        assertEquals(2, clauses.size)

        for (clause in clauses) {
            assertNavigatesTo(clause, clause.value as PsiElement)
        }
    }

    fun testACallDefinitionNavigatesToItsFirstClause() {
        myFixture.configureByText(
            "definition_navigation.ex",
            "defmodule M do\n  def first(a), do: a\n\n  def second(b), do: b\n  def second(b, c), do: {b, c}\n\n" +
                "  def second(:last), do: :last\nend\n"
        )

        val definitions = elements().filterIsInstance<CallDefinition>()

        assertEquals(3, definitions.size)

        for (definition in definitions) {
            val firstClause = definition.children.filterIsInstance<CallDefinitionClause>().first()

            assertNavigatesTo(definition, firstClause.value as PsiElement)
        }
    }

    fun testAnEExFunctionFromNavigatesToItsCall() {
        myFixture.configureByFiles("eex_host.ex", "eex.ex")

        val functionFroms = elements().filterIsInstance<EExFunctionFrom>()

        assertEquals(2, functionFroms.size)

        for (functionFrom in functionFroms) {
            assertNavigatesTo(functionFrom, functionFrom.call)
        }
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/structure_view/ex_unit"
}
