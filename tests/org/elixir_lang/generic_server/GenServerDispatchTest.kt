package org.elixir_lang.generic_server

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
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

    // ---- Only the message argument itself is the message ----

    fun testAtomInsideATupleMessageDoesNotNavigateToTheHandler() {
        configureCall("{:<caret>ping, 1}", ":ping")
        myFixture.assertShowUsagesChosenAtCaret("An atom inside a tuple message is a bare atom, not the message")
    }

    fun testAtomInsideACallInTheMessageDoesNotNavigateToTheHandler() {
        configureCall("Keyword.get(opts, :<caret>ping)", ":ping")
        myFixture.assertShowUsagesChosenAtCaret("An atom inside a call in the message is a bare atom, not the message")
    }

    fun testQuotedAtomInsideACallInTheMessageDoesNotNavigateToTheHandler() {
        configureCall("Keyword.get(opts, :\"<caret>ping\")", ":ping")
        myFixture.assertNoNavigationAtCaret("An atom inside a call in the message is not the message")
    }

    /** Parentheses leave no trace in the quoted message, so `(:ping)` is `:ping`. */
    fun testParenthesisedRequestNavigatesToTheHandler() {
        configureCall("(:<caret>ping)", ":ping")
        assertRequestNavigatesToHandler("handle_call", ":ping")
    }

    fun testAtomInsideAParenthesisedTupleMessageDoesNotNavigateToTheHandler() {
        configureCall("({:<caret>ping, 1})", ":ping")
        myFixture.assertShowUsagesChosenAtCaret("An atom inside a tuple message is a bare atom, not the message")
    }

    // ---- The atoms match by value, however each is written ----

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

    fun testBareRequestNavigatesToTheQuotedAtomsHandler() {
        configureCall(":<caret>ping", ":\"ping\"")
        assertRequestNavigatesToHandler("handle_call", ":\"ping\"")
    }

    fun testQuotedRequestNavigatesToTheQuotedAtomsHandler() {
        configureCall(":\"<caret>ping\"", ":\"ping\"")
        assertRequestNavigatesToHandler("handle_call", ":\"ping\"")
    }

    fun testSingleQuotedCastRequestNavigatesToHandleCastClause() {
        myFixture.configureByText(
            "stack.ex",
            """
                defmodule Stack do
                  use GenServer

                  def ping(pid), do: GenServer.cast(pid, :'<caret>ping')

                  @impl true
                  def handle_cast(:ping, state) do
                    {:noreply, state}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_cast", ":ping")
    }

    /** The escape tells the atom's value from its content with the quotes stripped. */
    fun testEscapedProcessSendMessageNavigatesToHandleInfoClause() {
        myFixture.configureByText(
            "worker.ex",
            """
                defmodule Worker do
                  use GenServer

                  def ping(pid), do: Process.send(pid, :"<caret>ping\x20pong", [])

                  @impl true
                  def handle_info(:"ping pong", state) do
                    {:noreply, state}
                  end
                end
            """.trimIndent()
        )
        assertRequestNavigatesToHandler("handle_info", ":\"ping pong\"")
    }

    /** From Elixir 1.14 a bare atom's `µ` (micro sign) is `μ` (Greek mu). */
    fun testBareRequestNavigatesToTheHandlerOfTheAtomItNormalizesTo() {
        configureCall(":<caret>${"μ"}", ":${"µ"}")
        assertRequestNavigatesToHandler("handle_call", ":${"µ"}")
    }

    /** A quoted atom is not normalized, so `:"µ"` is not `:µ`. */
    fun testQuotedMicroSignRequestDoesNotNavigateToTheBareMicroSignHandler() {
        configureCall(":\"<caret>${"µ"}\"", ":${"µ"}")
        myFixture.assertNoNavigationAtCaret("A quoted atom is not normalized")
    }

    fun testComposedRequestNavigatesToTheDecomposedAtomsHandler() {
        configureCall(":caf<caret>${"é"}", ":caf${"é"}")
        assertRequestNavigatesToHandler("handle_call", ":caf${"é"}")
    }

    fun testInterpolatedRequestDoesNotNavigateToTheHandler() {
        configureCall(":\"<caret>ping#{n}\"", ":ping")
        myFixture.assertNoNavigationAtCaret("An interpolated atom has no value")
    }

    /**
     * A module with `ping/3` sending [message] by `GenServer.call/2`, and `handle_call/3` clauses for
     * [handler] and `:pong`.
     */
    private fun configureCall(message: String, handler: String) {
        myFixture.configureByText(
            "stack.ex",
            """
                defmodule Stack do
                  use GenServer

                  def ping(pid, opts, n), do: GenServer.call(pid, $message)

                  @impl true
                  def handle_call($handler, _from, state) do
                    {:reply, :ok, state}
                  end

                  @impl true
                  def handle_call(:pong, _from, state) do
                    {:reply, :ok, state}
                  end
                end
            """.trimIndent()
        )
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
