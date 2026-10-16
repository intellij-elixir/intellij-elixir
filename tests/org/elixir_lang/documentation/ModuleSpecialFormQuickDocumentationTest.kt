package org.elixir_lang.documentation

/** `__MODULE__` is the special form `Kernel.SpecialForms.__MODULE__/0`, wherever it is written as a call. */
class ModuleSpecialFormQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "special_forms.ex",
            """
            defmodule Kernel.SpecialForms do
              @doc "The current module name as an atom."
              defmacro __MODULE__, do: :ok
            end
            """.trimIndent()
        )
    }

    fun testInAQuoteBody() {
        myFixture.configureByText(
            "quoted.ex",
            """
            defmodule Quoted do
              defmacro name do
                quote do
                  __MOD<caret>ULE__
                end
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the special form, got: $documentation", documentation?.contains("<b>Kernel.SpecialForms</b>") == true)
        assertTrue("Expected its @doc, got: $documentation", documentation?.contains("The current module name as an atom.") == true)
    }

    fun testInAModuleBody() {
        myFixture.configureByText(
            "module_body.ex",
            """
            defmodule ModuleBody do
              @name __MOD<caret>ULE__
            end
            """.trimIndent()
        )

        assertNull(quickDocumentationAtCaret())
    }
}
