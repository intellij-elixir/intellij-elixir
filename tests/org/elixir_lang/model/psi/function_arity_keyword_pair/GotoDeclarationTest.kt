package org.elixir_lang.model.psi.function_arity_keyword_pair

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.CallDefinitionClause

/**
 * Behavior-level tests for forward navigation on the `name: arity` keyword-pair construct: "Go To
 * Declaration" (and Ctrl-Click) on the keyword **key** of a `defoverridable`/`import :only`/`:except`/
 * `@compile :inline`/`@dialyzer` directive navigates to the function/macro (or, for `defoverridable` in
 * a behaviour scope, the `@callback`) it names.
 *
 * Assertions are on IDE behavior only - the Go To Declaration action and resolution through the
 * platform [PsiSymbolReferenceService] - never on internal resolver classes, so they survive
 * refactoring of the implementation.
 */
@Suppress("UnstableApiUsage")
class GotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/model/psi/function_arity_keyword_pair"

    override fun setUp() {
        super.setUp()
        // The Go To Declaration action needs a real DataContext (see platform SearchTargetTest).
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testImportOnlyKeyResolvesToFunction() {
        val symbols = resolvedSymbolsAtCaret("goto_import_only.ex")
        assertTrue(
            "Expected the `only:` key to resolve to KeywordPairExporter.perform/0, got $symbols",
            symbols.filterIsInstance<FunctionSymbol>()
                .any { it.name == "perform" && it.moduleName == "KeywordPairExporter" }
        )
    }

    fun testCompileInlineKeyResolvesToFunction() {
        val symbols = resolvedSymbolsAtCaret("goto_compile_inline.ex")
        assertTrue(
            "Expected the `inline:` key to resolve to KeywordPairInline.perform/0, got $symbols",
            symbols.filterIsInstance<FunctionSymbol>()
                .any { it.name == "perform" && it.moduleName == "KeywordPairInline" }
        )
    }

    fun testCompileInlineTupleKeyResolvesToFunction() {
        val symbols = resolvedSymbolsAtCaret("goto_compile_inline_tuple.ex")
        assertTrue(
            "Expected the `{:inline, perform: 0}` tuple key to resolve to KeywordPairInline.perform/0, got $symbols",
            symbols.filterIsInstance<FunctionSymbol>()
                .any { it.name == "perform" && it.moduleName == "KeywordPairInline" }
        )
    }

    fun testDialyzerNowarnKeyResolvesToFunction() {
        val symbols = resolvedSymbolsAtCaret("goto_dialyzer_nowarn.ex")
        assertTrue(
            "Expected the `nowarn_function` key to resolve to KeywordPairDialyzer.perform/0, got $symbols",
            symbols.filterIsInstance<FunctionSymbol>()
                .any { it.name == "perform" && it.moduleName == "KeywordPairDialyzer" }
        )
    }

    fun testDefoverridableKeyResolvesToCallback() {
        val symbols = resolvedSymbolsAtCaret("goto_defoverridable.ex", "kernel.ex")
        assertTrue(
            "Expected the `defoverridable` key to resolve to GotoOverridableBehaviour.perform callback, got $symbols",
            symbols.filterIsInstance<Callback>()
                .any { it.name == "perform" && it.moduleName == "GotoOverridableBehaviour" }
        )
    }

    /**
     * `use` runs only a `defmacro __using__`: a `def __using__` injects nothing and `use` cannot call a
     * `defmacrop __using__`. So when the quote declares no `@behaviour` of its own, a `defoverridable` key names the
     * defining module's callback only inside the first.
     */
    fun testDefoverridableKeyResolvesToCallbackOnlyInsideADefmacroUsing() {
        myFixture.copyFileToProject("kernel.ex")
        val source = java.io.File(testDataPath, "goto_defoverridable.ex").readText()
            .replace("      @behaviour GotoOverridableBehaviour\n\n", "")
        assertFalse(source.contains("@behaviour"))

        fun callbacksInside(definer: String): List<String> {
            myFixture.configureByText("goto_defoverridable.ex", source.replace("defmacro __using__", definer))

            return resolvedSymbolsAtCaret().filterIsInstance<Callback>().map { "${it.moduleName}.${it.name}" }
        }

        assertEquals(
            listOf(listOf("GotoOverridableBehaviour.perform"), emptyList(), emptyList()),
            listOf("defmacro __using__", "def __using__", "defmacrop __using__").map(::callbacksInside)
        )
    }

    /** Ctrl-Click on a keyword key - an element with a symbol reference - should choose Go To Declaration. */
    fun testCtrlClickOnImportOnlyKeyChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_import_only.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    /** The Go To Declaration action moves the caret onto the `def` name the keyword key references. */
    fun testGoToDeclarationFromImportOnlyKeyNavigatesToDef() {
        myFixture.configureByFiles("goto_import_only.ex")
        myFixture.assertGotoDeclarationLandsIn("perform", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /**
     * Configures [files], if any (caret on a keyword key), then returns the symbols the reference covering the
     * caret resolves to, via the platform [PsiSymbolReferenceService].
     */
    private fun resolvedSymbolsAtCaret(vararg files: String): List<Symbol> {
        if (files.isNotEmpty()) myFixture.configureByFiles(*files)
        val offset = myFixture.caretOffset
        val host = myFixture.enclosingCallAtCaret()!!
        return PsiSymbolReferenceService.getService()
            .getReferences(host)
            .filter { it.absoluteRange.containsOffset(offset) }
            .flatMap { it.resolveReference() }
    }
}
