package org.elixir_lang.documentation

/** Quick Documentation on `__MODULE__` as a struct's name, `%__MODULE__{}`, shows the module it names, as `%Mod{}` does. */
class StructModuleQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testInABody() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              def new, do: %__MOD<caret>ULE__{a: 1}
            end
            """
        )

    fun testInADefPattern() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              def a(%__MOD<caret>ULE__{a: a}), do: a
            end
            """
        )

    fun testInAType() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              @type t :: %__MOD<caret>ULE__{a: term}
            end
            """
        )

    /** A nested module's struct is its own, not the module around it. */
    fun testInANestedModule() =
        assertDocumentsTheModule(
            """
            defmodule Outer do
              @moduledoc "Holds the described module."

              defmodule Described do
                @moduledoc "Describes a value."
                defstruct [:a]

                def new, do: %__MOD<caret>ULE__{a: 1}
              end
            end
            """
        )

    /** In a `quote` the struct is its caller's module, which the `quote` doesn't know, so no module is shown. */
    fun testInAQuoteNotTheMacrosModule() {
        myFixture.configureByText(
            "struct_module.ex",
            """
            defmodule Using do
              @moduledoc "Injects a struct."

              defmacro __using__(_) do
                quote do
                  def new, do: %__MOD<caret>ULE__{a: 1}
                end
              end
            end
            """.trimIndent()
        )

        assertNull(quickDocumentationAtCaret())
    }

    /** A `quote`'s own arguments are evaluated in the macro, outside the `quote`, so the struct there is the macro's module. */
    fun testInAQuotesArgumentsTheMacrosModule() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              defmacro __using__(_) do
                quote bind_quoted: [d: Macro.escape(%__MOD<caret>ULE__{a: 1})] do
                  def default, do: d
                end
              end
            end
            """
        )

    fun testInABodyWithTheExpanderOn() = withTheExpanderOn(::testInABody)

    fun testInADefPatternWithTheExpanderOn() = withTheExpanderOn(::testInADefPattern)

    fun testInATypeWithTheExpanderOn() = withTheExpanderOn(::testInAType)

    fun testInANestedModuleWithTheExpanderOn() = withTheExpanderOn(::testInANestedModule)

    fun testInAQuotesArgumentsTheMacrosModuleWithTheExpanderOn() = withTheExpanderOn(::testInAQuotesArgumentsTheMacrosModule)

    private fun assertDocumentsTheModule(text: String) {
        myFixture.configureByText("struct_module.ex", text.trimIndent())

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected Described's @moduledoc, got: $documentation", documentation?.contains("Describes a value.") == true)
    }
}
