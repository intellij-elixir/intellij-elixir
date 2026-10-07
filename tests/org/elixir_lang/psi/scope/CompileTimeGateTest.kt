package org.elixir_lang.psi.scope

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport.location

/**
 * A call at compile-time level of its module's body, directly or under module-level `if`s and `unless`es, runs before
 * any of the module's functions exist, so none of them resolve; a call inside a definition sees the whole module.
 */
class CompileTimeGateTest : PlatformTestCase() {
    fun testDirectChildResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          def earlier, do: :ok
          earlier()
          later()
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideIfResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true do
            def earlier, do: :ok
            earlier()
            later()
          end
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideNestedIfAndUnlessResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true do
            unless false do
              def earlier, do: :ok
              earlier()
              later()
            end
          end
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideElseResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          def earlier, do: :ok
          if true do
            :ok
          else
            earlier()
            later()
          end
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideKeywordIfResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          def earlier, do: :ok
          if true, do: earlier(), else: later()
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideDefinitionSeesTheWholeModule() = assertResolves(
        """
        defmodule Gate do
          def caller, do: later()
          def later, do: :ok
        end
        """,
        "later()" to listOf("gate.ex:3:3")
    )

    fun testInsideDefinitionUnderIfSeesTheWholeModule() = assertResolves(
        """
        defmodule Gate do
          if true do
            def caller, do: later()
          end
          def later, do: :ok
        end
        """,
        "later()" to listOf("gate.ex:5:3")
    )

    fun testInsideListResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          [later()]
          def later, do: :ok
        end
        """,
        "later()" to emptyList()
    )

    fun testInsideListInIfResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true do
            def earlier, do: :ok
            [earlier()]
            [1, later()]
          end
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideListInUnlessResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          unless false do
            def earlier, do: :ok
            [earlier()]
            [1, later()]
          end
          def later, do: :ok
        end
        """,
        "earlier()" to emptyList(),
        "later()" to emptyList()
    )

    fun testInsideTupleAndMapsInIfResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true do
            {tuple()}
            %{a: keyword_map()}
            %{assoc() => 1}
            %Mod{a: struct()}
            %{m | a: updated()}
          end
          def tuple, do: :ok
          def keyword_map, do: :ok
          def assoc, do: :ok
          def struct, do: :ok
          def updated, do: :ok
        end
        """,
        "tuple()" to emptyList(),
        "keyword_map()" to emptyList(),
        "assoc()" to emptyList(),
        "struct()" to emptyList(),
        "updated()" to emptyList()
    )

    fun testInsideNestedContainersInIfResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true do
            [{nested_list()}]
            %{a: [nested_map()]}
          end
          def nested_list, do: :ok
          def nested_map, do: :ok
        end
        """,
        "nested_list()" to emptyList(),
        "nested_map()" to emptyList()
    )

    fun testInsideKeywordIfContainerResolvesNoneOfItsModulesFunctions() = assertResolves(
        """
        defmodule Gate do
          if true, do: [do_list()], else: {else_tuple()}
          def do_list, do: :ok
          def else_tuple, do: :ok
        end
        """,
        "do_list()" to emptyList(),
        "else_tuple()" to emptyList()
    )

    fun testInsideListInDefinitionSeesTheWholeModule() = assertResolves(
        """
        defmodule Gate do
          def caller, do: [later()]
          def later, do: :ok
        end
        """,
        "later()" to listOf("gate.ex:3:3")
    )

    fun testInsideListInDefinitionUnderIfSeesTheWholeModule() = assertResolves(
        """
        defmodule Gate do
          if true do
            def caller, do: [later()]
          end
          def later, do: :ok
        end
        """,
        "later()" to listOf("gate.ex:5:3")
    )

    /** Parentheses hide a call from the module's direct children, so it is walked as a definition's body is. */
    fun testInsideParenthesesSeesTheWholeModule() = assertResolves(
        """
        defmodule Gate do
          (later())
          def later, do: :ok
        end
        """,
        "later()" to listOf("gate.ex:3:3")
    )

    private fun assertResolves(source: String, vararg expected: Pair<String, List<String>>) {
        myFixture.configureByText("gate.ex", source.trimIndent())

        for ((text, locations) in expected) {
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == text }
            val results = (call.reference as PsiPolyVariantReference).multiResolve(true)

            assertEquals(text, locations, results.map { location(it.element) + if (it.isValidResult) "" else " (invalid)" })
        }
    }
}
