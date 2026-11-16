package org.elixir_lang.documentation

import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret

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

    /** Documenting `__MODULE__` as the special form leaves Go to Declaration on the enclosing module. */
    fun testGoToDeclarationInAModuleBodyStillReachesTheEnclosingModule() {
        myFixture.configureByText(
            "module_body.ex",
            """
            defmodule ModuleBody do
              @name __MOD<caret>ULE__
            end
            """.trimIndent()
        )

        assertEnclosingModuleReached("ModuleBody")
    }

    fun testGoToDeclarationInAFunctionBodyReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "FunctionBody",
            """
            defmodule FunctionBody do
              def name do
                __MOD<caret>ULE__
              end
            end
            """
        )

    fun testGoToDeclarationInATupleReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Tagged",
            """
            defmodule Tagged do
              def tag(value) do
                {__MOD<caret>ULE__, value}
              end
            end
            """
        )

    fun testGoToDeclarationAsAnArgumentInAModuleBodyReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Argument",
            """
            defmodule Argument do
              IO.puts(__MOD<caret>ULE__)
            end
            """
        )

    fun testGoToDeclarationInAStructDefaultReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "StructDefault",
            """
            defmodule StructDefault do
              defstruct owner: __MOD<caret>ULE__
            end
            """
        )

    fun testGoToDeclarationAsAnAliasQualifierReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Qualifier",
            """
            defmodule Qualifier do
              alias __MOD<caret>ULE__.Child
            end
            """
        )

    fun testGoToDeclarationInANestedModuleReachesTheInnermostModule() =
        assertEnclosingModuleReached(
            "Inner",
            """
            defmodule Outer do
              defmodule Inner do
                def name do
                  __MOD<caret>ULE__
                end
              end
            end
            """
        )

    fun testGoToDeclarationInAOneLineFunctionBodyReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "OneLine",
            """
            defmodule OneLine do
              def name, do: __MOD<caret>ULE__
            end
            """
        )

    fun testGoToDeclarationOnTheRightOfAMatchReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Match",
            """
            defmodule Match do
              def name do
                module = __MOD<caret>ULE__
                module
              end
            end
            """
        )

    fun testGoToDeclarationUnquotedInAQuoteBodyReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Unquoted",
            """
            defmodule Unquoted do
              defmacro __using__(_) do
                quote do
                  import unquote(__MOD<caret>ULE__)
                end
              end
            end
            """
        )

    /** `unquote` escapes one `quote` only, so one in a nested `quote`'s body is still quoted. */
    fun testGoToDeclarationUnquotedInANestedQuoteBodyReachesTheSpecialForm() {
        myFixture.configureByText(
            "nested_quote.ex",
            """
            defmodule NestedQuote do
              defmacro name do
                quote do
                  quote do
                    unquote(__MOD<caret>ULE__)
                  end
                end
              end
            end
            """.trimIndent()
        )

        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals("special_forms.ex", destination?.containingFile?.name)
    }

    /** `bind_quoted:` turns `unquote` off, so an `unquote` in a `def` is a fragment that runs in the caller. */
    fun testGoToDeclarationUnquotedUnderBindQuotedReachesTheSpecialForm() =
        assertSpecialFormReached(
            """
            defmodule BindQuoted do
              defmacro name(name) do
                quote bind_quoted: [name: name] do
                  def unquote(name)() do
                    unquote(__MOD<caret>ULE__)
                  end
                end
              end
            end
            """
        )

    fun testGoToDeclarationUnquotedUnderUnquoteFalseReachesTheSpecialForm() =
        assertSpecialFormReached(
            """
            defmodule UnquoteFalse do
              defmacro name do
                quote unquote: false do
                  def name do
                    unquote(__MOD<caret>ULE__)
                  end
                end
              end
            end
            """
        )

    /** A one-line `do:` body is a body, as a `do` block is, so its `__MODULE__` in a `quote` is the caller's. */
    fun testGoToDeclarationInAOneLineFunctionBodyInAQuoteReachesTheSpecialForm() =
        assertSpecialFormReached(
            """
            defmodule OneLineQuoted do
              defmacro name do
                quote do
                  def name, do: __MOD<caret>ULE__
                end
              end
            end
            """
        )

    fun testGoToDeclarationUnquotedUnderBindQuotedWithUnquoteTrueReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "UnquoteTrue",
            """
            defmodule UnquoteTrue do
              defmacro __using__(name) do
                quote bind_quoted: [name: name], unquote: true do
                  import unquote(__MOD<caret>ULE__)
                end
              end
            end
            """
        )

    fun testGoToDeclarationInAQuoteBindingReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            "Binding",
            """
            defmodule Binding do
              defmacro name do
                quote bind_quoted: [module: __MOD<caret>ULE__] do
                  module
                end
              end
            end
            """
        )

    fun testGoToDeclarationInAModuleInAQuoteBodyReachesThatModule() =
        assertEnclosingModuleReached(
            "Quoted",
            """
            defmodule Outer do
              defmacro __using__(_) do
                quote do
                  defmodule Quoted do
                    def name do
                      __MOD<caret>ULE__
                    end
                  end
                end
              end
            end
            """
        )

    /** What an `unquote` escapes runs where the `quote` is, not in a module the `quote` writes around it. */
    fun testGoToDeclarationUnquotedInAModuleAQuoteWritesReachesTheMacrosModule() =
        assertEnclosingModuleReached(
            "Outer",
            """
            defmodule Outer do
              defmacro __using__(_) do
                quote do
                  defmodule Inner do
                    import unquote(__MOD<caret>ULE__)
                  end
                end
              end
            end
            """
        )

    /** Outside any module or `quote`, `__MODULE__` names no module, so Go to Declaration reaches the special form. */
    fun testGoToDeclarationOutsideAModuleReachesTheSpecialForm() {
        myFixture.configureByText(
            "script.exs",
            """
            IO.puts(__MOD<caret>ULE__)
            """.trimIndent()
        )

        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals("special_forms.ex", destination?.containingFile?.name)
    }

    fun testInAFunctionBody() {
        myFixture.configureByText(
            "function_body.ex",
            """
            defmodule FunctionBody do
              def name do
                __MOD<caret>ULE__
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the special form, got: $documentation", documentation?.contains("<b>Kernel.SpecialForms</b>") == true)
        assertTrue("Expected its @doc, got: $documentation", documentation?.contains("The current module name as an atom.") == true)
    }

    /** In a `quote` body the enclosing module is not known, so Go to Declaration reaches the special form, as it did. */
    fun testGoToDeclarationInAQuoteBodyStillReachesTheSpecialForm() {
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

        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals("special_forms.ex", destination?.containingFile?.name)
    }

    private fun assertEnclosingModuleReached(moduleName: String, text: String) {
        myFixture.configureByText("enclosing.ex", text.trimIndent())

        assertEnclosingModuleReached(moduleName)
    }

    private fun assertSpecialFormReached(text: String) {
        myFixture.configureByText("quoted.ex", text.trimIndent())

        assertEquals("special_forms.ex", myFixture.gotoDeclarationDestinationAtCaret()?.containingFile?.name)
    }

    private fun assertEnclosingModuleReached(moduleName: String) {
        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals("Expected the enclosing module's name", moduleName, destination?.text)
        assertEquals(myFixture.file.name, destination?.containingFile?.name)
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

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the special form, got: $documentation", documentation?.contains("<b>Kernel.SpecialForms</b>") == true)
        assertTrue("Expected its @doc, got: $documentation", documentation?.contains("The current module name as an atom.") == true)
    }
}
