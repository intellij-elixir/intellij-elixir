package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.breadcrumbs.Provider
import org.elixir_lang.navigation.item_presentation.modular.Module as ModulePresentation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.ModuleQualifiedName

/** `Outer |> defmodule do ... end` defines `Outer`, as `defmodule Outer do ... end` does. */
class ModuleNameTest : PlatformTestCase() {
    fun testNameOfAWrittenModule() = assertEquals("Outer", Module.name(module(WRITTEN)))

    fun testNameOfAPipedModule() = assertEquals("Outer", Module.name(module(PIPED)))

    fun testBreadcrumbOfAWrittenModule() = assertEquals("Outer", Provider().getElementInfo(module(WRITTEN)))

    fun testBreadcrumbOfAPipedModule() = assertEquals("Outer", Provider().getElementInfo(module(PIPED)))

    fun testPresentationOfAWrittenModule() =
        assertEquals("defmodule Outer", ModulePresentation.presentableText("defmodule", module(WRITTEN)))

    fun testPresentationOfAPipedModule() =
        assertEquals("defmodule Outer", ModulePresentation.presentableText("defmodule", module(PIPED)))

    fun testStructOfAWrittenModule() = assertEquals(ModuleQualifiedName(null, "Outer"), struct(WRITTEN))

    fun testStructOfAPipedModule() = assertEquals(ModuleQualifiedName(null, "Outer"), struct(PIPED))

    private fun module(text: String): Call = calls(text).first { Module.`is`(it) }

    private fun struct(text: String): ModuleQualifiedName =
        ModuleQualifiedName.of(calls(text).first { it.functionName() == "defstruct" }, "struct")

    private fun calls(text: String): Collection<Call> {
        myFixture.configureByText("outer.ex", text)

        return PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
    }

    private companion object {
        const val WRITTEN = "defmodule Outer do\n  defstruct [:a]\nend\n"
        const val PIPED = "Outer |> defmodule do\n  defstruct [:a]\nend\n"
    }
}
