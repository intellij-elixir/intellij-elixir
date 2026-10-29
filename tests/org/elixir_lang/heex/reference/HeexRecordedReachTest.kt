package org.elixir_lang.heex.reference

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.Reach
import org.elixir_lang.psi.call.Call
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

/** A call in a `~H` sigil is walked from the module holding the sigil, so reach is judged from that module. */
class HeexRecordedReachTest : HeexHostTestCase() {
    fun testDelegationInModuleEnclosingTheSigilsModule() {
        myFixture.configureHeexSigilHost(
            """
            defmodule Outer do
              defdelegate f(x), to: Target

              defmodule Comp do
                def render(assigns) do
                  ~H'''
                  <p>{<caret>f(1)}</p>
                  '''
                end
              end
            end

            defmodule Target do
              def f(x), do: x
            end
            """.trimIndent()
        )
        val elixir = myFixture.file.viewProvider.getPsi(ElixirLanguage)!!
        val call = PsiTreeUtil.getParentOfType(elixir.findElementAt(myFixture.caretOffset), Call::class.java)!!

        val found = LegacyWalkSource.candidates(Use.of(call)!!, false)

        assertEquals(
            listOf(
                "defdelegate f(x), to: Target" to Reach.OUTER,
                "def f(x), do: x" to Reach.UNHELD_DELEGATION_TARGET
            ),
            found.map { it.element.text.lines().first() to it.candidate.reach }
        )
    }
}
