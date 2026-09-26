package org.elixir_lang.generic_server

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.psi.call.Call

/**
 * Behavioural tests for **GenServer message dispatch**.
 *
 * A GenServer's client API sends a message that the runtime routes to a server callback by matching
 * on the message term:
 *
 *  - `GenServer.call(pid, request)` -> `handle_call(request, from, state)`,
 *  - `GenServer.cast(pid, request)` -> `handle_cast(request, state)`,
 *  - `Process.send(pid, message, opts)` / `Process.send_after(pid, message, time)` ->
 *    `handle_info(message, state)`.
 *
 * That semantic link drives navigation, exercised here through the real IDE gesture rather than
 * hand-resolved PSI:
 *
 *  - **Navigation:** Ctrl+Click on the message at the send site should jump to the matching handler
 *    clause (e.g. `Process.send(pid, :tick, [])` -> `handle_info(:tick, ...)`).
 */
class GenServerDispatchTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    // ---- Navigation: client request -> handler clause ----

    fun testGenServerCallRequestNavigatesToHandleCallClause() {
        myFixture.configureByText(
            "stack.ex",
            """
                defmodule Stack do
                  use GenServer

                  def pop(pid), do: GenServer.call(pid, :<caret>pop)

                  @impl true
                  def handle_call(:pop, _from, [head | tail]) do
                    {:reply, head, tail}
                  end

                  @impl true
                  def handle_cast(:clear, _state) do
                    {:noreply, []}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_call", ":pop")
    }

    /** `:"pop"` is the same atom as `:pop`, so it reaches the same handler. */
    fun testQuotedRequestNavigatesToTheBareAtomsHandler() {
        myFixture.configureByText(
            "stack.ex",
            """
                defmodule Stack do
                  use GenServer

                  def pop(pid), do: GenServer.call(pid, :"<caret>pop")

                  @impl true
                  def handle_call(:pop, _from, [head | tail]) do
                    {:reply, head, tail}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_call", ":pop")
    }

    fun testGenServerCastRequestNavigatesToHandleCastClause() {
        myFixture.configureByText(
            "stack.ex",
            """
                defmodule Stack do
                  use GenServer

                  def clear(pid), do: GenServer.cast(pid, :<caret>clear)

                  @impl true
                  def handle_call(:pop, _from, [head | tail]) do
                    {:reply, head, tail}
                  end

                  @impl true
                  def handle_cast(:clear, _state) do
                    {:noreply, []}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_cast", ":clear")
    }

    fun testProcessSendMessageNavigatesToHandleInfoClause() {
        myFixture.configureByText(
            "worker.ex",
            """
                defmodule Worker do
                  use GenServer

                  def ping(pid), do: Process.send(pid, :<caret>tick, [])

                  @impl true
                  def handle_info(:tick, state) do
                    {:noreply, state}
                  end

                  @impl true
                  def handle_info(:refresh, state) do
                    {:noreply, state}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_info", ":tick")
    }

    fun testProcessSendAfterMessageNavigatesToHandleInfoClause() {
        myFixture.configureByText(
            "worker.ex",
            """
                defmodule Worker do
                  use GenServer

                  def schedule(pid), do: Process.send_after(pid, :<caret>timeout, 1000)

                  @impl true
                  def handle_info(:tick, state) do
                    {:noreply, state}
                  end

                  @impl true
                  def handle_info(:timeout, state) do
                    {:noreply, state}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_info", ":timeout")
    }

    /**
     * Asserts that Ctrl+Click on the GenServer request under the caret chooses "go to declaration"
     * and lands **inside the specific handler clause that matches the request** - i.e. the
     * `def handlerName(request, ...)` call-definition clause, verified by PSI ancestry rather than a
     * loose "somewhere after" offset comparison.
     */
    private fun assertRequestNavigatesToHandler(handlerName: String, request: String) {
        // Independently locate the exact call-definition clause that handles this request.
        val expectedClause = expectedHandlerClause(handlerName, request)

        myFixture.assertGotoDeclarationChosenAtCaret(
            "Ctrl+Click on the GenServer request should go to the $handlerName clause"
        )

        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration produced no destination element", destination)
        assertTrue(
            "Go To Declaration must land inside the $handlerName($request, ...) clause, " +
                "landed on: '${destination?.text}'",
            PsiTreeUtil.isAncestor(expectedClause, destination!!, false)
        )
    }

    /**
     * The `def <handlerName>(<request>, ...) do ... end` call-definition clause: found by locating the
     * inner `handlerName(<request>, ...)` head call whose first primary argument is [request], then
     * taking its enclosing `def` call.
     */
    private fun expectedHandlerClause(handlerName: String, request: String): PsiElement {
        val head = PsiTreeUtil.collectElementsOfType(myFixture.file, Call::class.java)
            .firstOrNull { call ->
                val args = call.primaryArguments()
                call.functionName() == handlerName && args != null && args.isNotEmpty() && args[0]?.text == request
            }
        assertNotNull("Fixture must define $handlerName($request, ...)", head)
        val clause = PsiTreeUtil.getParentOfType(head, Call::class.java, true)
        assertNotNull("Handler head $handlerName($request, ...) must be inside a def clause", clause)
        return clause!!
    }
}
