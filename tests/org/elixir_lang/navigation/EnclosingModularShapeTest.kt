package org.elixir_lang.navigation

import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionClause as StructureCallDefinitionClause

/**
 * Pins which nestings of a `def` still find their enclosing Modular. Where they don't,
 * `ChooseByNameContributor` reports "Cannot find enclosing Modular" - issues #1108 and #1695.
 *
 * `enclosingMacroCall` walks outwards through a fixed list of transparent PSI types in
 * [org.elixir_lang.psi.impl.selfOrEnclosingMacroCall], and `enclosingModularMacroCall` then walks past
 * `alias`, `require` and `for`. Anything else ends the walk on a non-Modular call. That list has grown
 * once per report - a 2018 cons-list/pipe fix for #1141, a 2021 `for` fix, a 2021 alias/require fix -
 * and #1695 is the next shape it never covered: a `quote` held as a value in a map literal, where the
 * walk stopped at `ElixirContainerAssociationOperation` and the map internals above it.
 *
 * #1108's own reported code is already covered by `GotoSymbolContributorTest.testIssue1141`, whose
 * fixture is that code verbatim.
 */
class EnclosingModularShapeTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/navigation/goto_symbol_contributor"

    private fun defCallAtCaret(): Call? {
        var element: PsiElement? = myFixture.file.findElementAt(myFixture.editor.caretModel.offset)

        while (element != null) {
            if (element is Call && CallDefinitionClause.`is`(element)) return element
            element = element.parent
        }

        return null
    }

    private fun enclosingModularOf(label: String, source: String): String {
        myFixture.configureByText("enclosing_modular_$label.ex", source)

        val call = defCallAtCaret() ?: return "$label: no call definition clause at caret"

        return if (StructureCallDefinitionClause.enclosingModular(call) == null) {
            "$label: no enclosing Modular"
        } else {
            "$label: ok"
        }
    }

    /**
     * The file #1695 names, taken unmodified from am-kantox/elixir-iteraptor, with the caret on the
     * `def get/3` at line 110 - the line the report gives. Unmodified on purpose: a hand-simplified
     * version of this shape did not parse into a call definition clause at all, so it would have
     * proved nothing about the real one.
     *
     * Before the map internals were made transparent this found
     * `ElixirUnmatchedUnqualifiedNoParenthesesCallImpl` with a null Modular - the exact element class
     * #1695's report names.
     */
    fun testIssue1695RealIteraptableFile() {
        myFixture.configureByFile("issue_1695_iteraptable.ex")

        val offset = myFixture.file.text.indexOf("def get(term, key, default")
        assertTrue("fixture no longer contains the def #1695 reports", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + "def g".length)

        val call = defCallAtCaret()
        assertNotNull("the reported def is not a call definition clause", call)

        assertNotNull(
            "no enclosing Modular for the def at iteraptable.ex:110 - the walk stopped inside the map " +
                "holding its quote, which is what #1695 reports",
            StructureCallDefinitionClause.enclosingModular(call!!)
        )
    }

    /**
     * The minimal form of the same shape, so a regression names the construct rather than a library.
     */
    fun testQuoteInMapValueFindsEnclosingModular() {
        val source = "defmodule A do\n" +
            "  @impls %{\n" +
            "    Access =>\n" +
            "      quote location: :keep do\n" +
            "        def get(term, key, default \\\\ nil), do: default\n" +
            "      end\n" +
            "  }\n" +
            "end\n"

        myFixture.configureByText("quote_in_map_value.ex", source)

        val offset = myFixture.file.text.indexOf("def get(term")
        myFixture.editor.caretModel.moveToOffset(offset + "def g".length)

        defCallAtCaret()?.let { call ->
            assertNotNull(
                "no enclosing Modular for a def inside a quote held as a map value",
                StructureCallDefinitionClause.enclosingModular(call)
            )
        }
    }

    /**
     * The nestings that already worked, so widening the transparent list does not quietly break them.
     */
    fun testExistingNestingsStillFindTheirModular() {
        val shapes = listOf(
            "plain" to "defmodule A do\n  def f<caret>oo(a), do: a\nend\n",
            "inside_alias" to "defmodule A do\n  alias B\n  def f<caret>oo(a), do: a\nend\n",
            "inside_for" to "defmodule A do\n  for n <- [1, 2] do\n    def f<caret>oo(unquote(n)), do: unquote(n)\n  end\nend\n",
            "quote_in_defmacro" to
                "defmodule A do\n  defmacro __using__(_) do\n    quote do\n      def f<caret>oo(a), do: a\n    end\n  end\nend\n",
            "defimpl_in_quote" to
                "defmodule A do\n  defmacro __using__(_) do\n    quote do\n      defimpl Access, for: __MODULE__ do\n        def f<caret>oo(a), do: a\n      end\n    end\n  end\nend\n",
            "inside_if" to "defmodule A do\n  if true do\n    def f<caret>oo(a), do: a\n  end\nend\n",
            "inside_case" to
                "defmodule A do\n  case :ok do\n    :ok ->\n      def f<caret>oo(a), do: a\n  end\nend\n",
            "top_level_defimpl" to "defimpl Access, for: Foo do\n  def g<caret>et(a, b, c), do: a\nend\n",
            "inside_enum_each" to "defmodule A do\n  Enum.each([1], fn _ ->\n    def f<caret>oo(a), do: a\n  end)\nend\n",
            "inside_piped_enum_each" to "defmodule A do\n  [1] |> Enum.each(fn _ ->\n    def f<caret>oo(a), do: a\n  end)\nend\n",
            "inside_task_async" to "defmodule A do\n  Task.async(fn -> def f<caret>oo(a), do: a end) |> Task.await()\nend\n",
            "inside_keyword_callback" to "defmodule A do\n  opts = [callback: fn -> def f<caret>oo(a), do: a end]\n  opts[:callback].()\nend\n",
            "bound_fn_in_quote" to
                "defmodule A do\n  defmacro __using__(_) do\n    quote do\n      helper = fn -> def f<caret>oo(a), do: a end\n    end\n  end\nend\n",
        )

        val failures = shapes
            .map { (label, source) -> enclosingModularOf(label, source) }
            .filterNot { it.endsWith(": ok") }

        assertEquals("lost the enclosing Modular for:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    /**
     * #1438: `@callback unquote(name)(...) :: term` as the right operand of a `||`, inside a `quote` in
     * `defmacro`, in Elixir's own protocol.ex at the version the report names.
     *
     * The `||` was the whole blocker. `Match` and `Pipe` were in the transparent list; every other
     * subtype of [org.elixir_lang.psi.operation.Infix] was not, because each had been added one report
     * at a time. Before widening that to `Infix` this found the reported
     * `ElixirUnmatchedAtUnqualifiedNoParenthesesCall` with a null Modular.
     */
    fun testIssue1438ProtocolCallback() {
        myFixture.configureByFile("issue_1438_protocol.ex")

        val offset = myFixture.file.text.indexOf("@callback unquote(name)(unquote_splicing(type_args))")
        assertTrue("fixture no longer contains the @callback #1438 reports", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + "@call".length)

        var element: PsiElement? = myFixture.file.findElementAt(myFixture.editor.caretModel.offset)
        var call: Call? = null

        while (element != null) {
            if (element is Call) {
                call = element
                break
            }
            element = element.parent
        }

        assertNotNull("no call at the reported @callback", call)

        assertNotNull(
            "no enclosing Modular for the @callback at protocol.ex:47 - the walk stopped at the || " +
                "between it and Module.spec_to_callback, which is what #1438 reports",
            StructureCallDefinitionClause.enclosingModular(call!!)
        )
    }
}
