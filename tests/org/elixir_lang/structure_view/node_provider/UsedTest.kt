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

        var user: Module? = null

        fun walk(element: StructureViewTreeElement) {
            if (element is Module && (element.value as? Call)?.let { org.elixir_lang.psi.Module.name(it) } == "User") {
                user = element
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        val listed = Used().provideNodes(user!!).filterIsInstance<CallDefinition>().map { it.name }.sorted()

        assertEquals(listOf("injected_function/0", "injected_macro/0"), listed)
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

        var user: Module? = null

        fun walk(element: StructureViewTreeElement) {
            if (element is Module && (element.value as? Call)?.let { org.elixir_lang.psi.Module.name(it) } == "User") {
                user = element
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(emptyList<String>(), Used().provideNodes(user!!).filterIsInstance<CallDefinition>().map { it.name })
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

        val definitions = mutableListOf<CallDefinition>()

        fun walk(element: StructureViewTreeElement) {
            if (element is CallDefinition) definitions.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(
            listOf("fixed_function/0 false", "overridable_function/0 true", "overridable_macro/0 true"),
            definitions.map { "${it.name} ${it.isOverridable}" }.sorted()
        )
    }
}
