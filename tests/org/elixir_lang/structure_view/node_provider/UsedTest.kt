package org.elixir_lang.structure_view.node_provider

import com.intellij.ide.structureView.StructureViewTreeElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.Model
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.modular.Module

/**
 * "Show Used" lists what a `use` injects, less what the module redefines. `defoverridable` accepts a macro as well
 * as a function, so an injected macro is listed too, and hidden only when the module redefines it as a macro.
 */
class UsedTest : PlatformTestCase() {
    fun testInjectedFunctionsAndMacrosAreListedUnlessRedefined() {
        myFixture.configureByText(
            "used.ex",
            """
            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  def injected_function, do: 1
                  defmacro injected_macro, do: 1
                  def overridden_function, do: 1
                  defmacro overridden_macro, do: 1
                  defoverridable overridden_function: 0, overridden_macro: 0
                end
              end
            end

            defmodule User do
              use Injector

              def overridden_function, do: 2
              defmacro overridden_macro, do: 2
            end
            """.trimIndent()
        )

        assertEquals(listOf("injected_function/0", "injected_macro/0"), usedBy("User"))
    }

    /** `use` calls a `defmacro __using__/1`; a `defmacrop` one it cannot call, so Show Used lists nothing from it. */
    fun testAPrivateUsingInjectsNothing() {
        myFixture.configureByText(
            "private_using.ex",
            """
            defmodule Injector do
              defmacrop __using__(_) do
                quote do
                  def injected_function, do: 1
                end
              end
            end

            defmodule User do
              use Injector
            end
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), usedBy("User"))
    }

    /** A `use` naming its module by a dotted alias, with or without options, lists what that module injects. */
    fun testADottedAliasListsWhatItsModuleInjects() {
        myFixture.configureByText(
            "dotted_used.ex",
            """
            defmodule A.Injector do
              defmacro __using__(_) do
                quote do
                  def injected_function, do: 1
                end
              end
            end

            defmodule Plain do
              use A.Injector
            end

            defmodule WithOptions do
              use A.Injector, :controller
            end
            """.trimIndent()
        )

        assertEquals(
            listOf(listOf("injected_function/0"), listOf("injected_function/0")),
            listOf("Plain", "WithOptions").map(::usedBy)
        )
    }

    /** A `use` inside the injected quote injects too, as Elixir expands it in the user. */
    fun testANestedUseListsWhatItInjects() {
        myFixture.configureByText(
            "nested_used.ex",
            """
            defmodule Inner do
              defmacro __using__(_) do
                quote do
                  def chained(x), do: x
                end
              end
            end

            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  use Inner
                  def injected_function, do: 1
                end
              end
            end

            defmodule Plain do
              use Injector
            end
            """.trimIndent()
        )

        assertEquals(listOf("chained/1", "injected_function/0"), usedBy("Plain"))
    }

    /** A `__using__` that applies a function of its module lists what that function quotes. */
    fun testAUsingThatAppliesAFunctionListsWhatItQuotes() {
        myFixture.configureByText(
            "applied_used.ex",
            """
            defmodule Web do
              def view do
                quote do
                  def viewed, do: :view
                end
              end

              defmacro __using__(which) when is_atom(which), do: apply(__MODULE__, which, [])
            end

            defmodule Page do
              use Web, :view
            end
            """.trimIndent()
        )

        assertEquals(listOf("viewed/0"), usedBy("Page"))
    }

    /** A module's redefinition of an overridable a `use` injects is marked an override, a macro's as a function's. */
    fun testARedefinedOverridableMacroIsMarkedAnOverride() {
        myFixture.configureByText(
            "override.ex",
            """
            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  def overridden_function, do: 1
                  defmacro overridden_macro, do: 1
                  defoverridable overridden_function: 0, overridden_macro: 0
                end
              end
            end

            defmodule User do
              use Injector

              def overridden_function, do: 2
              defmacro overridden_macro, do: 2
              def own, do: 3
            end
            """.trimIndent()
        )

        val user = structureElements()
            .filterIsInstance<Module>()
            .single { (it.value as? Call)?.let { call -> org.elixir_lang.psi.Module.name(call) } == "User" }

        assertEquals(
            listOf("overridden_function/0 true", "overridden_macro/0 true", "own/0 false"),
            user.children.filterIsInstance<CallDefinition>().map { "${it.name} ${it.override}" }.sorted()
        )
    }

    /** Every `__using__/1` clause is a way the module can be used, so Show Used lists what each injects. */
    fun testEveryUsingClauseIsListed() {
        myFixture.configureByText(
            "clauses.ex",
            """
            defmodule Injector do
              defmacro __using__(:a) do
                quote do
                  def from_a, do: 1
                end
              end

              defmacro __using__(_) do
                quote do
                  def from_other, do: 1
                end
              end
            end

            defmodule User do
              use Injector
            end
            """.trimIndent()
        )

        assertEquals(listOf("from_a/0", "from_other/0"), usedBy("User"))
    }

    /** `defoverridable` marks a macro overridable as it does a function. */
    fun testDefoverridableMarksMacrosAsWellAsFunctions() {
        myFixture.configureByText(
            "overridable.ex",
            """
            defmodule Overridable do
              def overridable_function, do: 1
              defmacro overridable_macro, do: 1
              def fixed_function, do: 1
              defoverridable overridable_function: 0, overridable_macro: 0
            end
            """.trimIndent()
        )

        assertEquals(
            listOf("fixed_function/0 false", "overridable_function/0 true", "overridable_macro/0 true"),
            structureElements().filterIsInstance<CallDefinition>().map { "${it.name} ${it.isOverridable}" }.sorted()
        )
    }

    /** What Show Used lists for the module named [moduleName], sorted. */
    private fun usedBy(moduleName: String): List<String> {
        val module = structureElements()
            .filterIsInstance<Module>()
            .single { (it.value as? Call)?.let { call -> org.elixir_lang.psi.Module.name(call) } == moduleName }

        return Used().provideNodes(module).filterIsInstance<CallDefinition>().map { it.name }.sorted()
    }

    private fun structureElements(): List<StructureViewTreeElement> {
        val elements = mutableListOf<StructureViewTreeElement>()

        fun walk(element: StructureViewTreeElement) {
            elements.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        return elements
    }
}
