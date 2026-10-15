package org.elixir_lang.model.psi.generic_server

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAtom

/** A request's handler is labelled with its head, so the `handle_call` clauses of one server are told apart. */
class GenServerHandlerLabelTest : PlatformTestCase() {
    fun testHandlerIsLabelledWithItsHead() {
        myFixture.configureByText(
            "stack.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: GenServer.call(pid, :<caret>pop)\n\n" +
                "  @impl true\n  def handle_call(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end\nend\n"
        )

        assertEquals(
            listOf("def handle_call(:pop, _from, [head | tail])"),
            GenServerDispatch
                .handlerTargetsForRequestAtom(
                    PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), ElixirAtom::class.java)!!
                )
                .map { it.computePresentation().presentableText }
        )
    }
}
