package org.elixir_lang.model.psi.generic_server

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret

/** A request reaches its handler whatever the shape of the handler's head. */
class GenServerHandlerHeadTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** A head written `unquote(:handle_call)(...)` is the handler's, and Go To lands on its atom. */
    fun testRequestGoesToAnUnquotedHandlerAtItsAtom() {
        myFixture.configureByText(
            "stack.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: GenServer.call(pid, :<caret>pop)\n\n" +
                "  @impl true\n  def unquote(:handle_call)(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end\nend\n"
        )
        val atom = myFixture.file.text.indexOf(":handle_call")
        val offsets = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destination?.textOffset }

        assertEquals(1, offsets.size)
        assertTrue("landed at $offsets, atom at $atom", offsets.single()!! in atom until atom + ":handle_call".length)
    }

    /** A guard after the head leaves the handler's parameters to match the request. */
    fun testRequestGoesToAGuardedHandler() {
        myFixture.configureByText(
            "stack.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: GenServer.call(pid, :<caret>pop)\n\n" +
                "  @impl true\n  def handle_call(:pop, _from, [head | tail]) when is_list(tail) do\n    {:reply, head, tail}\n  end\nend\n"
        )
        val text = myFixture.file.text
        val head = text.indexOf("handle_call(:pop")
        val offsets = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destination?.textOffset }

        assertEquals(1, offsets.size)
        assertTrue("landed at $offsets, head at $head", offsets.single()!! in head until text.indexOf(" when "))
    }
}
