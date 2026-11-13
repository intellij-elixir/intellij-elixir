package org.elixir_lang.documentation

/** Quick Documentation (Ctrl+Q) on a module's or protocol's name shows its `@moduledoc`. */
class ModuleNameQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocOnDefprotocolNameShowsModuledoc() =
        assertShowsModuledoc(protocol("defprotocol Descri<caret>bable do"))

    fun testQuickDocOnQualifiedDefprotocolNameShowsModuledoc() =
        assertShowsModuledoc(
            protocol("defprotocol Outer.Descri<caret>bable do", "defimpl Outer.Describable, for: Tuple do")
        )

    fun testQuickDocOnDefimplProtocolAliasShowsModuledoc() =
        assertShowsModuledoc(protocol("defprotocol Describable do", "defimpl Descri<caret>bable, for: Tuple do"))

    fun testQuickDocOnDefmoduleNameShowsModuledoc() =
        assertShowsModuledoc(
            """
            defmodule Docu<caret>mented do
              @moduledoc "Describes a value."
            end
            """.trimIndent()
        )

    fun testQuickDocOnAQualifyingAliasShowsTheModulesModuledoc() =
        assertShowsModuledoc(
            """
            defmodule Documented do
              @moduledoc "Describes a value."

              @doc "Calls f."
              def f, do: :ok
            end

            defmodule Caller do
              def call, do: Docu<caret>mented.f()
            end
            """.trimIndent()
        )

    fun testQuickDocOnDefprotocolNameShowsModuledocWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnDefprotocolNameShowsModuledoc)

    fun testQuickDocOnQualifiedDefprotocolNameShowsModuledocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnQualifiedDefprotocolNameShowsModuledoc)

    fun testQuickDocOnDefimplProtocolAliasShowsModuledocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnDefimplProtocolAliasShowsModuledoc)

    fun testQuickDocOnDefmoduleNameShowsModuledocWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnDefmoduleNameShowsModuledoc)

    fun testQuickDocOnAQualifyingAliasShowsTheModulesModuledocWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocOnAQualifyingAliasShowsTheModulesModuledoc)

    private fun withTheExpanderOn(test: () -> Unit) {
        expanderOn()
        test()
    }

    private fun protocol(
        defprotocolLine: String,
        defimplLine: String = "defimpl Describable, for: Tuple do"
    ): String =
        """
        $defprotocolLine
          @moduledoc "Describes a value."

          def describe(value)
        end

        $defimplLine
          def describe(_value), do: "tuple"
        end
        """.trimIndent()

    private fun assertShowsModuledoc(text: String) {
        myFixture.configureByText("module_name_quick_doc.ex", text)

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a module's name", documentation)
        assertTrue(
            "Expected the @moduledoc in the documentation, got: $documentation",
            documentation!!.contains("Describes a value.")
        )
    }
}
