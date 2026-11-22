package org.elixir_lang.model.psi.function_arity_keyword_pair

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.destinationLine
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.CallDefinitionClause
import java.io.File

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

    /** An `only:` key naming a `defprotocol` function resolves to it. */
    fun testImportOnlyKeyResolvesToProtocolFunction() {
        myFixture.addFileToProject("p.ex", "defprotocol P do\n  def f(t)\nend\n")
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  import P, only: [<caret>f: 1]\nend\n")
        val host = myFixture.enclosingCallAtCaret()!!
        val symbols = PsiSymbolReferenceService.getService()
            .getReferences(host)
            .filter { it.absoluteRange.containsOffset(myFixture.caretOffset) }
            .flatMap { it.resolveReference() }

        assertEquals(listOf("P.f/1"), symbols.filterIsInstance<ProtocolFunction>().map { "${it.protocolName}.${it.name}/${it.arity}" })
    }

    /**
     * An `only:` key into a compiled head with defaults lands on each decompiled clause of the export it names: the
     * export's own, and the head with defaults its `Docs` signature writes, as a call at that arity does.
     */
    fun testImportOnlyKeyIntoACompiledHeadWithDefaultsLandsOnEachClause() {
        disableExpander(testRootDisposable)
        assertImportOnlyKeyIntoACompiledHeadWithDefaultsLandsOnEachClause()
    }

    fun testImportOnlyKeyIntoACompiledHeadWithDefaultsLandsOnEachClauseWithTheExpanderOn() {
        enableExpander(module, testRootDisposable)
        assertImportOnlyKeyIntoACompiledHeadWithDefaultsLandsOnEachClause()
    }

    private fun assertImportOnlyKeyIntoACompiledHeadWithDefaultsLandsOnEachClause() {
        addKeyDefaultsLibrary(myFixture)
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  import KeyDefaults, only: [<caret>snoc: 1]\nend\n")

        assertEquals(
            listOf("Elixir.KeyDefaults.beam.decompiled.ex: def snoc(a) do", "Elixir.KeyDefaults.beam.decompiled.ex: def snoc(a \\\\ nil, b \\\\ nil) do"),
            gotoDeclarationLines()
        )
    }

    private fun gotoDeclarationLines(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destinationLine().let { (file, _, text) -> "$file: $text" } }

    /**
     * Configures [files] (caret on a keyword key), then returns the symbols the reference covering the
     * caret resolves to, via the platform [PsiSymbolReferenceService].
     */
    private fun resolvedSymbolsAtCaret(vararg files: String): List<Symbol> {
        myFixture.configureByFiles(*files)
        val offset = myFixture.caretOffset
        val host = myFixture.enclosingCallAtCaret()!!
        return PsiSymbolReferenceService.getService()
            .getReferences(host)
            .filter { it.absoluteRange.containsOffset(offset) }
            .flatMap { it.resolveReference() }
    }
}

/** The `import_key/ebin` directory, whose `KeyDefaults` decompiles from its `Docs` signatures. */
private val KEY_EBIN = File("testData/org/elixir_lang/model/psi/function_arity_keyword_pair/import_key/ebin").absoluteFile

/** Adds `import_key/ebin` to [fixture]'s module as a library, as a project's dependency is. */
internal fun addKeyDefaultsLibrary(fixture: CodeInsightTestFixture) {
    VfsRootAccess.allowRootAccess(fixture.testRootDisposable, KEY_EBIN.path)
    val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(KEY_EBIN)!!

    BeamLibraryFixture.addLibrary(fixture.project, fixture.module, "key-defaults", listOf(ebin))
}
