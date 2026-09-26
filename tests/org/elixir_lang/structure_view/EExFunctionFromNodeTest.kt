package org.elixir_lang.structure_view

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.EExFunctionFromHead
import org.elixir_lang.structure_view.element.Visible

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

    fun testAFunctionFromCallIsBuiltAsTheHeadOfItsFunction() {
        val calls = configure()

        assertFalse("the fixture must contain function_from_* calls", calls.isEmpty())

        val built = mutableSetOf<PsiElement>()

        fun walk(element: StructureViewTreeElement) {
            if (element is EExFunctionFromHead) {
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

        assertEquals("every function_from_* call must build an EExFunctionFromHead", emptyList<String>(), missing)
    }

    /** The function and its `@spec` are one entry, as a `def` and its `@spec` are, holding the declaration and the spec. */
    fun testAFunctionAndItsSpecAreOneEntry() {
        myFixture.configureByText(
            "spec_host.ex",
            """
            defmodule SpecHost do
              require EEx

              @spec render(term(), term()) :: term()
              EEx.function_from_string(:def, :render, "", [:q, :x])
            end
            """.trimIndent()
        )
        myFixture.copyFileToProject("eex.ex")

        val entries = mutableListOf<Pair<String, Int>>()

        fun walk(element: StructureViewTreeElement) {
            entries.add(element.presentation.presentableText.orEmpty() to element.children.size)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(listOf("render/2" to 2), entries.filter { it.first.contains('/') })
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

        val nodes = mutableListOf<CallDefinition>()

        fun walk(element: StructureViewTreeElement) {
            if (element is CallDefinition) nodes.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(listOf("page/0 PRIVATE"), nodes.map { "${it.name} ${it.visibility()}" })
    }

    /** A `Mix.Generator` embed declares a private function, listed as a `def` is, with the embed as its head. */
    fun testAGeneratorEmbedIsListedAsThePrivateFunctionItDeclares() {
        myFixture.configureByText(
            "generator_embed.ex",
            """
            defmodule Mix.Generator do
              defmacro embed_template(name, contents), do: {name, contents}
              defmacro embed_text(name, contents), do: {name, contents}
            end

            defmodule Embeds do
              require Mix.Generator

              Mix.Generator.embed_text(:banner, "Banner")
              Mix.Generator.embed_template(:log, "Log")
            end
            """.trimIndent()
        )

        val nodes = mutableListOf<String>()

        fun walk(element: StructureViewTreeElement, module: String?) {
            val here = (element as? org.elixir_lang.structure_view.element.modular.Module)?.presentation?.presentableText ?: module
            if (element is CallDefinition && here == "defmodule Embeds") nodes.add("${element.name} ${element.visibility()}")

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child, here)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root, null)

        assertEquals(listOf("banner_text/0 PRIVATE", "log_template/1 PRIVATE"), nodes)
    }

    /** A kind that is not a literal atom says no visibility, and the node and its head still present. */
    fun testAKindThatIsNotALiteralPresentsWithoutAVisibility() {
        myFixture.configureByText(
            "unknown_kind.ex",
            """
            defmodule UnknownKind do
              require EEx

              @kind :def
              EEx.function_from_string(@kind, :page, "")
            end
            """.trimIndent()
        )
        myFixture.copyFileToProject("eex.ex")

        val presented = mutableListOf<String>()

        fun walk(element: StructureViewTreeElement) {
            if (element is CallDefinition || element is EExFunctionFromHead) {
                presented.add("${element.javaClass.simpleName} ${element.presentation.presentableText} ${(element as Visible).visibility()}")
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(
            listOf("CallDefinition page/0 null", "EExFunctionFromHead EEx.function_from_string(@kind, :page, \"\") null"),
            presented
        )
    }
}
