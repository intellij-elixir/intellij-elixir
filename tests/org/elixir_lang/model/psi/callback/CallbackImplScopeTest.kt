package org.elixir_lang.model.psi.callback

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * A clause in a module that implements a behaviour gets Go To Declaration to a callback only when it
 * implements one. Any other function keeps its own declaration, so Ctrl+Click on its name shows usages
 * and the calls to it are among them.
 */
@Suppress("UnstableApiUsage")
class CallbackImplScopeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testPrivateFunctionShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "defp helper", name = "helper", call = "helper() +")
    }

    fun testPublicNonCallbackFunctionShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def public_helper", name = "public_helper", call = "public_helper()")
    }

    /** A private function cannot implement a callback, even when its name and arity match one. */
    fun testPrivateFunctionNamedLikeACallbackShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "defp handle(", name = "handle", call = "handle(1)")
    }

    fun testPublicFunctionOfADifferentArityShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def perform(", name = "perform", call = "perform(1)")
    }

    /** `expand/0` is a macro callback, so a function of that name implements nothing. */
    fun testFunctionNamedLikeAMacroCallbackShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def expand,", name = "expand", call = "run, do: expand()")
    }

    fun testNonCallbackFunctionInAUseInjectedBehaviourModuleShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def injected_helper", name = "injected_helper", call = "injected_helper()")
    }

    fun testImplementedFunctionStillGoesToItsCallback() {
        configure()
        caretOn("def perform,", "perform")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("perform"), callbacksAtCaret().map { it.name })
    }

    fun testImplementedMacroStillGoesToItsMacroCallback() {
        configure()
        caretOn("defmacro expand", "expand")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("expand"), callbacksAtCaret().map { it.name })
    }

    /** A guard is a macro, so it implements a macro callback and not a function callback. */
    fun testGuardNamedLikeAFunctionCallbackShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "defguard valid", name = "valid", call = "def check, do: valid(1)")
    }

    fun testGuardImplementingAMacroCallbackGoesToIt() {
        configure()
        caretOn("defguard guarded", "guarded")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("guarded"), callbacksAtCaret().map { it.name })
    }

    /** A default implementation in the behaviour's `__using__` quote implements the callback. */
    fun testDefaultImplementationInUsingGoesToItsCallback() {
        configure()
        caretOn("def defaulted", "defaulted")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("defaulted"), callbacksAtCaret().map { it.name })
    }

    /** A module nested in the `__using__` quote implements only the behaviours it declares itself. */
    fun testFunctionInAModuleNestedInUsingShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def nested,", name = "nested", call = "call_nested, do: nested()")
    }

    fun testFunctionInANestedModuleThatDeclaresTheBehaviourGoesToItsCallback() {
        configure()
        caretOn("def nested_impl", "nested_impl")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("nested_impl"), callbacksAtCaret().map { it.name })
    }

    /** The quote of the behaviour's own `__using__` implements it, with or without an `@behaviour` in the quote. */
    fun testDefaultImplementationInAQuoteWithoutBehaviourGoesToItsCallback() {
        configure()
        caretOn("def plain_default,", "plain_default")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("plain_default"), callbacksAtCaret().map { it.name })
    }

    fun testFindUsagesOnACallbackListsADefaultImplementationInAQuoteWithoutBehaviour() =
        assertImplementationListed("@callback plain_default", "plain_default", "def plain_default,", listed = true)

    fun testDefaultImplementationInAQuoteOfUnquotedModuleBehaviourGoesToItsCallback() {
        configure()
        caretOn("def unquoted_default,", "unquoted_default")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("unquoted_default"), callbacksAtCaret().map { it.name })
    }

    fun testFindUsagesOnACallbackListsADefaultImplementationInAQuoteOfUnquotedModuleBehaviour() =
        assertImplementationListed("@callback unquoted_default", "unquoted_default", "def unquoted_default,", listed = true)

    /** `use` calls only a macro `__using__/1`, so a quote in a `def __using__` implements nothing. */
    fun testFunctionInTheQuoteOfAFunctionUsingGetsNoCallbackReference() {
        configure()
        caretOn("def fn_default,", "fn_default")
        assertEquals(emptyList<PsiSymbolReference>(), callbackImplReferencesAtCaret())
    }

    fun testFindUsagesOnACallbackDoesNotListAFunctionInTheQuoteOfAFunctionUsing() =
        assertImplementationListed("@callback fn_default", "fn_default", "def fn_default,", listed = false)

    /** A behaviour declared by a module nested in the quote is implemented by that module, not by the quote's own functions. */
    fun testFunctionInTheQuoteBesideANestedModuleThatDeclaresABehaviourGoesToNoCallback() {
        configure()
        caretOn("def outer_fn,", "outer_fn")
        assertEquals(emptyList<PsiSymbolReference>(), callbackImplReferencesAtCaret())
    }

    fun testFindUsagesOnACallbackDoesNotListAFunctionBesideANestedModuleThatDeclaresItsBehaviour() =
        assertImplementationListed("@callback outer_fn", "outer_fn", "def outer_fn,", listed = false)

    /** A behaviour injected by a `__using__` nested in the quote is implemented by whoever `use`s that one, not by the quote's own functions. */
    fun testFunctionInTheQuoteBesideANestedUsingThatInjectsABehaviourGetsNoCallbackReference() {
        configure()
        caretOn("def outer,", "outer")
        assertEquals(emptyList<PsiSymbolReference>(), callbackImplReferencesAtCaret())
    }

    fun testFindUsagesOnACallbackDoesNotListAFunctionBesideANestedUsingThatInjectsItsBehaviour() =
        assertImplementationListed("@callback outer()", "outer", "def outer,", listed = false)

    /** `use` brings in the quote's own behaviours only, not those of a module nested in it. */
    fun testFunctionOfAModuleThatUsesAQuoteWithANestedBehaviourDeclarationShowsUsagesAndListsItsCall() {
        configure()
        assertShowsUsagesWithCall(declaration = "def via_use,", name = "via_use", call = "call_via_use, do: via_use()")
    }

    fun testFindUsagesOnACallbackDoesNotListAFunctionOfAModuleThatUsesAQuoteWithANestedBehaviourDeclaration() =
        assertImplementationListed("@callback via_use", "via_use", "def via_use,", listed = false)

    /** A quote bound to a variable in `__using__` is still what `use` injects. */
    fun testFunctionOfAModuleThatUsesAnAssignedQuoteGoesToItsCallback() {
        configure()
        caretOn("def assigned_fn,", "assigned_fn")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("assigned_fn"), callbacksAtCaret().map { it.name })
    }

    fun testFindUsagesOnACallbackListsAFunctionOfAModuleThatUsesAnAssignedQuote() =
        assertImplementationListed("@callback assigned_fn", "assigned_fn", "def assigned_fn,", listed = true)

    fun testFindUsagesOnACallbackListsADefaultImplementationInAnAssignedQuote() =
        assertImplementationListed("@callback assigned_default", "assigned_default", "def assigned_default,", listed = true)

    /** A quote handed to another call is still what `use` injects, whatever that call does with it. */
    fun testFunctionOfAModuleThatUsesAQuotePassedToACallGoesToItsCallback() {
        configure()
        caretOn("def passed_fn,", "passed_fn")
        myFixture.assertGotoDeclarationChosenAtCaret()
        assertEquals(listOf("passed_fn"), callbacksAtCaret().map { it.name })
    }

    fun testFindUsagesOnACallbackListsAFunctionOfAModuleThatUsesAQuotePassedToACall() =
        assertImplementationListed("@callback passed_fn", "passed_fn", "def passed_fn,", listed = true)

    fun testDefoverridableKeyDirectlyInTheUsingQuoteNamesItsCallback() {
        configure()
        caretOn("overridable_here: 0", "overridable_here")
        assertEquals(listOf("overridable_here"), callbacksAtCaret { it.functionName() == "defoverridable" }.map { it.name })
    }

    fun testDefoverridableKeyInAModuleNestedInUsingNamesNoCallback() {
        configure()
        caretOn("overridable_nested: 0", "overridable_nested")
        assertEquals(emptyList<String>(), callbacksAtCaret { it.functionName() == "defoverridable" }.map { it.name })
    }

    fun testFindUsagesOnACallbackDoesNotListAFunctionOfAModuleNestedInUsing() =
        assertImplementationListed("@callback nested()", "nested", "def nested,", listed = false)

    fun testFindUsagesOnACallbackListsAFunctionOfANestedModuleThatDeclaresTheBehaviour() =
        assertImplementationListed("@callback nested_impl", "nested_impl", "def nested_impl", listed = true)

    /**
     * Find Usages on a callback lists the clauses that Go To Declaration sends to it, so the two directions agree:
     * a function, a default implementation and a guard against a macro callback are listed, and a private function
     * or a guard against a function callback is not.
     */
    fun testFindUsagesOnACallbackListsItsDefaultImplementation() =
        assertImplementationListed("@callback defaulted", "defaulted", "def defaulted", listed = true)

    fun testFindUsagesOnAMacroCallbackListsTheGuardImplementingIt() =
        assertImplementationListed("@macrocallback guarded", "guarded", "defguard guarded", listed = true)

    fun testFindUsagesOnACallbackDoesNotListAPrivateFunctionOfItsName() =
        assertImplementationListed("@callback handle", "handle", "defp handle(", listed = false)

    fun testFindUsagesOnAFunctionCallbackDoesNotListAGuardOfItsName() =
        assertImplementationListed("@callback valid", "valid", "defguard valid", listed = false)

    private fun configure() {
        myFixture.configureByText("m.ex", TEXT)
    }

    private fun caretOn(declaration: String, name: String) {
        val offset = TEXT.indexOf(declaration)
        assertTrue("`$declaration` not found in the fixture", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + declaration.indexOf(name) + 1)
    }

    private fun callbacksAtCaret(enclosing: (Call) -> Boolean = { CallDefinitionClause.`is`(it) }): List<Callback> =
        PsiSymbolReferenceService.getService()
            .getReferences(myFixture.enclosingCallAtCaret(enclosing)!!)
            .flatMap { it.resolveReference() }
            .filterIsInstance<Callback>()

    /** A reference that resolves to nothing would hide the clause's own declaration from Go To and Find Usages. */
    private fun callbackImplReferencesAtCaret(): List<PsiSymbolReference> =
        PsiSymbolReferenceService.getService()
            .getReferences(myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!)
            .filterIsInstance<CallbackImplReference>()

    private fun assertImplementationListed(callback: String, callbackName: String, clause: String, listed: Boolean) {
        configure()
        caretOn(callback, callbackName)

        val clauseName = TEXT.indexOf(clause) + clause.lastIndexOf(" ") + 1
        val usageOffsets = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { it.range.startOffset }

        assertEquals("`$clause` at $clauseName among the usages $usageOffsets of `$callback`", listed, clauseName in usageOffsets)
    }

    private fun assertShowsUsagesWithCall(declaration: String, name: String, call: String) {
        caretOn(declaration, name)
        myFixture.assertShowUsagesChosenAtCaret()

        val snippetOffset = TEXT.indexOf(call)
        assertTrue("`$call` not found in the fixture", snippetOffset >= 0)
        val callOffset = snippetOffset + call.lastIndexOf(name)
        val usageOffsets = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { it.range.startOffset }
        assertTrue("Expected the call `$call` at $callOffset among the usages $usageOffsets", callOffset in usageOffsets)
    }

    private companion object {
        val TEXT = """
            defmodule Worker do
              @callback perform() :: any
              @callback handle(term) :: any
              @macrocallback expand() :: Macro.t()
              @callback valid(term) :: any
              @macrocallback guarded(term) :: Macro.t()
              @callback defaulted() :: any
              @callback nested() :: any
              @callback nested_impl() :: any
              @callback overridable_here() :: any
              @callback overridable_nested() :: any

              defmacro __using__(_) do
                quote do
                  @behaviour Worker

                  def defaulted, do: :default

                  def overridable_here, do: :ok
                  defoverridable overridable_here: 0

                  defmodule Inner do
                    def nested, do: :ok
                    def call_nested, do: nested()
                    def overridable_nested, do: :ok
                    defoverridable overridable_nested: 0
                  end

                  defmodule InnerImpl do
                    @behaviour Worker
                    def nested_impl, do: :ok
                  end
                end
              end
            end

            defmodule Plain do
              @callback plain_default() :: any

              defmacro __using__(_) do
                quote do
                  def plain_default, do: :ok
                end
              end
            end

            defmodule Unquoted do
              @callback unquoted_default() :: any

              defmacro __using__(_) do
                quote do
                  @behaviour unquote(__MODULE__)
                  def unquoted_default, do: :ok
                end
              end
            end

            defmodule FunctionUsing do
              @callback fn_default() :: any

              def __using__(_) do
                quote do
                  def fn_default, do: :ok
                end
              end
            end

            defmodule Other do
              @callback outer_fn() :: any
              @callback via_use() :: any
            end

            defmodule Host do
              defmacro __using__(_) do
                quote do
                  def outer_fn, do: :ok

                  defmodule InnerOther do
                    @behaviour Other
                  end
                end
              end
            end

            defmodule Third do
              @callback outer() :: any
            end

            defmodule NestedUsing do
              defmacro __using__(_) do
                quote do
                  def outer, do: :ok

                  defmacro __using__(_) do
                    quote do
                      @behaviour Third
                    end
                  end
                end
              end
            end

            defmodule Assigned do
              @callback assigned_fn() :: any
              @callback assigned_default() :: any

              defmacro __using__(_) do
                ast =
                  quote do
                    @behaviour Assigned

                    def assigned_default, do: :ok
                  end

                ast
              end
            end

            defmodule AssignedUser do
              use Assigned

              def assigned_fn, do: :ok
            end

            defmodule Passed do
              @callback passed_fn() :: any

              defmacro __using__(_) do
                Macro.prewalk(
                  quote do
                    @behaviour Passed
                  end,
                  & &1
                )
              end
            end

            defmodule PassedUser do
              use Passed

              def passed_fn, do: :ok
            end

            defmodule UserHost do
              use Host

              def via_use, do: :ok
              def call_via_use, do: via_use()
            end

            defmodule WorkerImpl do
              @behaviour Worker

              def perform, do: helper() + public_helper() + perform(1) + handle(1)
              def perform(x), do: x
              defp helper, do: 1
              def public_helper, do: 2
              defp handle(x), do: x
              defmacro expand, do: :ok
              defguard valid(x) when is_integer(x)
              defguard guarded(x) when is_integer(x)
              def check, do: valid(1)
            end

            defmodule FunctionImpl do
              @behaviour Worker

              def expand, do: 1
              def run, do: expand()
            end

            defmodule UseImpl do
              use Worker

              def injected_helper, do: 1
              def run, do: injected_helper()
            end
        """.trimIndent() + "\n"
    }
}
