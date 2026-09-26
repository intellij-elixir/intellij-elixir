package org.elixir_lang.model.psi.callback

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.impl.ElixirPsiImplUtil

/**
 * Behavior-level tests for reverse navigation: "Go To Declaration" on an implementing `def`/`defmacro`
 * navigates to the `@callback` it implements.
 *
 * Assertions are on IDE behavior only (the Go To Declaration action, and resolution through the
 * platform's [PsiSymbolReferenceService]) - never on internal resolver classes - so they survive
 * refactoring of the implementation.
 */
@Suppress("UnstableApiUsage")
class CallbackImplGotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/callback"

    override fun setUp() {
        super.setUp()
        // The Go To Declaration action needs a real DataContext (see platform SearchTargetTest).
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** Flagship end-to-end: the action moves the caret onto the `@callback` name. */
    fun testGoToDeclarationNavigatesToCallback() {
        myFixture.configureByFiles("goto_use_injected.ex", "kernel.ex")

        val target = myFixture.gotoDeclarationDestinationAtCaret()

        assertNotNull("Go To Declaration should navigate somewhere", target)
        assertEquals("perform", target!!.text)
        val enclosingAttribute = generateSequence(target) { it.parent }
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull()
        assertNotNull("Expected the caret to land inside the @callback", enclosingAttribute)
        assertEquals("@callback", ElixirPsiImplUtil.moduleAttributeName(enclosingAttribute!!))
    }

    /**
     * Ctrl-Click decision: on an implementing `def`, "Go To Declaration or Usages" (the very handler
     * Ctrl-Click uses - `GotoDeclarationAction implements CtrlMouseAction` -> `GotoDeclarationOrUsageHandler2`)
     * chooses **Go To Declaration** (navigate to the `@callback`), not Show Usages.
     */
    fun testCtrlClickOnImplementingDefChoosesGoToDeclaration() {
        myFixture.configureByFiles("goto_use_injected.ex", "kernel.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testResolvesToCallbackViaUseInjectedBehaviour() {
        val callbacks = resolvedCallbacksAtCaretDef("goto_use_injected.ex", "kernel.ex")
        assertTrue(
            "Expected the impl to resolve to GotoUseInjectedBehaviour.perform, got $callbacks",
            callbacks.any { it.name == "perform" && it.moduleName == "GotoUseInjectedBehaviour" }
        )
    }

    fun testResolvesToCallbackViaLiteralBehaviour() {
        val callbacks = resolvedCallbacksAtCaretDef("goto_literal_behaviour.ex", "kernel.ex")
        assertTrue(
            "Expected the impl to resolve to GotoLiteralBehaviour.perform, got $callbacks",
            callbacks.any { it.name == "perform" && it.moduleName == "GotoLiteralBehaviour" }
        )
    }

    fun testNonImplementingDefResolvesNoCallback() {
        val callbacks = resolvedCallbacksAtCaretDef("goto_non_implementing.ex", "kernel.ex")
        assertEmpty(callbacks)
    }

    /** A `defguard` defines a macro, so it implements a `@macrocallback` and not a `@callback` of its name. */
    fun testAGuardImplementsAMacroCallbackOnly() {
        myFixture.copyFileToProject("kernel.ex")
        myFixture.configureByText(
            "guard_impl.ex",
            """
            defmodule GuardBehaviour do
              @callback is_f(term) :: boolean
              @macrocallback is_m(term) :: Macro.t()
            end

            defmodule GuardImpl do
              @behaviour GuardBehaviour

              defguard is_f(t) when is_integer(t)
              defguard is_m(t) when is_integer(t)
            end
            """.trimIndent()
        )

        val implemented = listOf("is_f", "is_m").associateWith { name ->
            val offset = myFixture.file.text.indexOf("defguard $name")
            val guard = generateSequence(myFixture.file.findElementAt(offset)) { it.parent }
                .filterIsInstance<org.elixir_lang.psi.call.Call>()
                .first { CallDefinitionClause.`is`(it) }

            PsiSymbolReferenceService.getService()
                .getReferences(guard)
                .flatMap { it.resolveReference() }
                .filterIsInstance<Callback>()
                .map { it.name }
        }

        assertEquals(mapOf("is_f" to emptyList(), "is_m" to listOf("is_m")), implemented)
    }

    /**
     * Configures [files] (caret on an implementing `def` name), then returns the [Callback] symbols the
     * def's symbol reference resolves to, via the platform [PsiSymbolReferenceService].
     */
    private fun resolvedCallbacksAtCaretDef(vararg files: String): List<Callback> {
        myFixture.configureByFiles(*files)
        val defClause = myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!
        return PsiSymbolReferenceService.getService()
            .getReferences(defClause)
            .flatMap { it.resolveReference() }
            .filterIsInstance<Callback>()
    }
}
