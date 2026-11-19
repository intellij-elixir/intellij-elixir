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

    /** What a `quote` unquotes is evaluated in the macro, outside the `quote`, so the struct there is the macro's module. */
    fun testInAnUnquoteInAQuoteTheMacrosModule() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              defmacro default do
                quote do: unquote(Macro.escape(%__MOD<caret>ULE__{a: 1}))
              end
            end
            """
        )

    /** `unquote: false` keeps `unquote` as code in the `quote`, so the struct there is its caller's module. */
    fun testInAnUnquoteInAQuoteThatDoesNotUnquoteNotTheMacrosModule() = assertDocumentsNoModule("quote unquote: false do")

    /** `bind_quoted:` turns `unquote` off, so the struct in an `unquote` there is its caller's module. */
    fun testInAnUnquoteInABindQuotedQuoteNotTheMacrosModule() = assertDocumentsNoModule("quote bind_quoted: [b: 1] do")

    /** `quote`'s options are its first argument where `do:` is its second, so `unquote: false` there is read too. */
    fun testInAnUnquoteInAQuoteWhoseOptionsArgumentDoesNotUnquoteNotTheMacrosModule() =
        assertDocumentsNoModuleIn(describedWith("quote([unquote: false], do: unquote(Macro.escape(%__MOD<caret>ULE__{a: 1})))"))

    /** As above, for `bind_quoted:`, which turns `unquote` off. */
    fun testInAnUnquoteInABindQuotedQuoteWhoseOptionsArgumentNotTheMacrosModule() =
        assertDocumentsNoModuleIn(describedWith("quote([bind_quoted: [b: 1]], do: unquote(Macro.escape(%__MOD<caret>ULE__{a: 1})))"))

    /** `unquote: true` turns `unquote` back on beside `bind_quoted:`, so the struct there is the macro's module. */
    fun testInAnUnquoteInABindQuotedQuoteThatUnquotesTheMacrosModule() =
        assertDocumentsTheModule(described("quote bind_quoted: [b: 1], unquote: true do"))

    /** The outer `quote` quotes a nested `quote`'s options as its own code, so it unquotes the struct there. */
    fun testInAnUnquoteInANestedQuotesOptionsTheMacrosModule() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              defmacro default do
                quote do
                  quote bind_quoted: [s: unquote(Macro.escape(%__MOD<caret>ULE__{a: 1}))] do
                    s
                  end
                end
              end
            end
            """
        )

    /** What an `unquote` escapes runs where the `quote` is, not in a module the `quote` writes around it. */
    fun testInAnUnquoteInAModuleAQuoteWritesTheMacrosModule() =
        assertDocumentsTheModule(
            """
            defmodule Described do
              @moduledoc "Describes a value."
              defstruct [:a]

              defmacro __using__(_) do
                quote do
                  defmodule Inner do
                    def owner, do: unquote(Macro.escape(%__MOD<caret>ULE__{a: 1}))
                  end
                end
              end
            end
            """
        )

    fun testInAnUnquoteInAModuleAQuoteWritesTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInAModuleAQuoteWritesTheMacrosModule)

    fun testInAnUnquoteInANestedQuotesOptionsTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInANestedQuotesOptionsTheMacrosModule)

    fun testInAnUnquoteInAQuoteTheMacrosModuleWithTheExpanderOn() = withTheExpanderOn(::testInAnUnquoteInAQuoteTheMacrosModule)

    fun testInAnUnquoteInAQuoteThatDoesNotUnquoteNotTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInAQuoteThatDoesNotUnquoteNotTheMacrosModule)

    fun testInAnUnquoteInABindQuotedQuoteNotTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInABindQuotedQuoteNotTheMacrosModule)

    fun testInAnUnquoteInABindQuotedQuoteThatUnquotesTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInABindQuotedQuoteThatUnquotesTheMacrosModule)

    fun testInAnUnquoteInAQuoteWhoseOptionsArgumentDoesNotUnquoteNotTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInAQuoteWhoseOptionsArgumentDoesNotUnquoteNotTheMacrosModule)

    fun testInAnUnquoteInABindQuotedQuoteWhoseOptionsArgumentNotTheMacrosModuleWithTheExpanderOn() =
        withTheExpanderOn(::testInAnUnquoteInABindQuotedQuoteWhoseOptionsArgumentNotTheMacrosModule)

    fun testInABodyWithTheExpanderOn() = withTheExpanderOn(::testInABody)

    fun testInADefPatternWithTheExpanderOn() = withTheExpanderOn(::testInADefPattern)

    fun testInATypeWithTheExpanderOn() = withTheExpanderOn(::testInAType)

    fun testInANestedModuleWithTheExpanderOn() = withTheExpanderOn(::testInANestedModule)

    fun testInAQuotesArgumentsTheMacrosModuleWithTheExpanderOn() = withTheExpanderOn(::testInAQuotesArgumentsTheMacrosModule)

    private fun assertDocumentsTheModule(text: String) {
        val documentation = quickDocumentationIn("struct_module.ex", text.trimIndent())

        assertTrue("Expected Described's @moduledoc, got: $documentation", "Describes a value." in documentation)
    }

    /** No module at a struct's `__MODULE__` in an `unquote` in a `quote` opened by [quoteDo], which keeps it as code. */
    private fun assertDocumentsNoModule(quoteDo: String) = assertDocumentsNoModuleIn(described(quoteDo))

    /** No module at the caret's struct `__MODULE__` in [text]. */
    private fun assertDocumentsNoModuleIn(text: String) {
        myFixture.configureByText("struct_module.ex", text.trimIndent())

        assertNull(quickDocumentationAtCaret())
    }

    /** `Described`, whose macro's body is [body]. */
    private fun describedWith(body: String): String =
        """
        defmodule Described do
          @moduledoc "Describes a value."
          defstruct [:a]

          defmacro default do
            $body
          end
        end
        """

    /** `Described`, whose macro has a struct's `__MODULE__` in an `unquote` in a `quote` opened by [quoteDo]. */
    private fun described(quoteDo: String): String =
        describedWith("$quoteDo\n              unquote(Macro.escape(%__MOD<caret>ULE__{a: 1}))\n            end")
}
