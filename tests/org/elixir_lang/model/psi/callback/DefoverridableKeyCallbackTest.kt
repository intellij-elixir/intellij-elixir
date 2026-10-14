package org.elixir_lang.model.psi.callback

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.model.psi.function.FunctionArityKeywordPairReference
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.QuotableKeywordPair

/**
 * A `defoverridable name: arity` key relates to the callbacks of its arity that the definition it names implements:
 * a public function for a `@callback`, a public macro for a `@macrocallback`, a `defdelegate` as the public function
 * it defines. A key with no definition names nothing. Go To Declaration from the key and Find Usages from the
 * callback agree.
 */
@Suppress("UnstableApiUsage")
class DefoverridableKeyCallbackTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/callback/defoverridable_key"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testKeyOfADelegationInAUsingQuoteNamesItsCallback() {
        myFixture.configureByFile("using.ex")
        caretOn("id: 1", "id")

        assertEquals(listOf("id/1"), keyCallbacks())
    }

    /** The `use`d module's quote supplies both the behaviour and the `def` the key makes overridable. */
    fun testKeyOfAnInjectedFunctionNamesItsCallback() {
        myFixture.configureByFile("injected.ex")
        caretOn("id: 1", "id")

        assertEquals(listOf("id/1"), keyCallbacks())
    }

    /** Every `name: arity` key of a module reads one set of definitions, those a `use` injects included. */
    fun testCompileInlineKeyReadsTheDefinitionsAUseInjects() {
        myFixture.configureByFile("injected.ex")
        caretOn("inline, id: 1", "id")

        assertEquals(listOf("def id(x), do: x"), definitionTexts())
    }

    fun testDialyzerKeyReadsTheDefinitionsAUseInjects() {
        myFixture.configureByFile("injected.ex")
        caretOn("nowarn_function, id: 1", "id")

        assertEquals(listOf("def id(x), do: x"), definitionTexts())
    }

    /** The behaviour is the key's module's own, so the injected `def` implements its callback there. */
    fun testKeyOfAnInjectedFunctionNamesTheCallbackOfItsOwnModulesBehaviour() {
        myFixture.configureByFile("injected_own_behaviour.ex")
        caretOn("id: 1", "id")

        assertEquals(listOf("id/1"), keyCallbacks())
    }

    /** A `use` injects what its `__using__` returns, so a `def` in a quote that is bound and dropped is not injected. */
    fun testKeyOfAFunctionInAnUnreturnedQuoteNamesNothing() {
        myFixture.configureByFile("injected_unreturned_quote.ex")
        caretOn("id: 1", "id")

        assertEquals(emptyList<String>(), keyCallbacks())
    }

    fun testKeyOfAFunctionAUseInjectsThroughAnotherUseNamesItsCallback() {
        myFixture.configureByFile("injected_transitive.ex")
        caretOn("id: 1", "id")

        assertEquals(listOf("id/1"), keyCallbacks())
    }

    /** An `import` in the injected quote brings functions into scope, but defines none in the module. */
    fun testKeyOfAnImportedFunctionNamesNoCallback() {
        myFixture.configureByFile("injected_import.ex")
        caretOn("helper: 1, own: 1", "helper")

        assertEquals(emptyList<String>(), keyCallbacks())
    }

    fun testKeyReadsOnlyTheDefinitionsAUseInjectsNotTheFunctionsItImports() {
        myFixture.configureByFile("injected_import.ex")
        caretOn("inline, helper: 1", "helper")

        assertEquals(listOf("def own(x), do: x"), definitionTexts())
    }

    /** `unquote(definitions)` splices the quote bound above it, so its `def` is a definition of the quote the key is in. */
    fun testKeyOfASplicedFunctionNamesItsCallback() {
        myFixture.configureByFile("injected_spliced.ex")
        caretOn("id: 1", "id")

        assertEquals(listOf("id/1"), keyCallbacks())
    }

    fun testCallbackUsagesListTheKeyOfASplicedFunction() {
        myFixture.configureByFile("injected_spliced.ex")
        caretOn("@callback id", "id")

        assertTrue(usageOffsets().contains(offsetOf("id: 1", "id")))
    }

    fun testCallbackUsagesListTheKeyOfAnInjectedFunction() {
        myFixture.configureByFile("injected.ex")
        caretOn("@callback id", "id")

        assertTrue(usageOffsets().contains(offsetOf("id: 1", "id")))
    }

    fun testKeyOfAMacroNamesItsMacrocallback() {
        myFixture.configureByFile("key.ex")
        caretOn("expand: 1", "expand")

        assertEquals(listOf("expand/1 macro"), keyCallbacks())
    }

    /** `opt(x \\ nil)` defines `opt/0` and `opt/1`; the key's arity picks the callback. */
    fun testKeyNamesOnlyTheCallbackOfItsArity() {
        myFixture.configureByFile("key.ex")
        caretOn("opt: 1", "opt")

        assertEquals(listOf("opt/1"), keyCallbacks())
    }

    fun testKeyOfAMultiClauseFunctionNamesItsCallbackOnce() {
        myFixture.configureByFile("key.ex")
        caretOn("multi: 1", "multi")

        assertEquals(listOf("multi/1"), keyCallbacks())
    }

    fun testKeyOfAFunctionNamesNoMacrocallback() {
        myFixture.configureByFile("key.ex")
        caretOn("perform: 1", "perform")

        assertEquals(emptyList<String>(), keyCallbacks())
    }

    fun testKeyOfAPrivateFunctionNamesNoCallback() {
        myFixture.configureByFile("key.ex")
        caretOn("hidden: 0", "hidden")

        assertEquals(emptyList<String>(), keyCallbacks())
    }

    fun testKeyWithNoDefinitionNamesNothing() {
        myFixture.configureByFile("key.ex")
        caretOn("missing: 0", "missing")

        assertEquals(emptyList<String>(), keyCallbacks())
    }

    fun testMacrocallbackUsagesOmitTheKeyOfAFunction() {
        myFixture.configureByFile("key.ex")
        caretOn("@macrocallback perform", "perform")

        assertFalse(usageOffsets().contains(offsetOf("perform: 1", "perform")))
    }

    fun testMacrocallbackUsagesListTheKeyOfAMacro() {
        myFixture.configureByFile("key.ex")
        caretOn("@macrocallback expand", "expand")

        assertTrue(usageOffsets().contains(offsetOf("expand: 1", "expand")))
    }

    fun testCallbackUsagesOmitTheKeyOfAPrivateFunction() {
        myFixture.configureByFile("key.ex")
        caretOn("@callback hidden", "hidden")

        assertFalse(usageOffsets().contains(offsetOf("hidden: 0", "hidden")))
    }

    fun testCallbackUsagesListTheDelegation() {
        myFixture.configureByFile("key.ex")
        caretOn("@callback id", "id")

        val usages = usageOffsets()

        assertTrue("`defdelegate id` among $usages", usages.contains(offsetOf("defdelegate id", "id")))
        assertTrue("`id: 1` among $usages", usages.contains(offsetOf("id: 1", "id")))
    }

    fun testGotoDeclarationFromADelegationNamesItsCallback() {
        myFixture.configureByFile("key.ex")
        caretOn("defdelegate id", "id")

        val callbacks = PsiSymbolReferenceService.getService()
            .getReferences(myFixture.enclosingCallAtCaret { DefinitionDeclaration.`is`(it) }!!)
            .flatMap { it.resolveReference() }
            .filterIsInstance<Callback>()

        assertEquals(listOf("id/1"), callbacks.map(::describe))
    }

    /** Without `as:` the head names its target's function too, so renaming only the head would break the call. */
    fun testRenamingACallbackLeavesADelegationWithoutAsAlone() {
        myFixture.configureByFile("key.ex")
        caretOn("@callback id", "id")
        myFixture.renameTargetAtCaret("fresh")

        assertTrue(myFixture.file.text.contains("defdelegate id(socket), to: Ids"))
    }

    fun testCtrlClickOnADelegationHeadGoesToItsTarget() {
        myFixture.configureByFile("key.ex")
        caretOn("defdelegate id", "id")

        myFixture.assertGotoDeclarationLandsIn("id", "`Ids.id/1`") { definition ->
            CallDefinitionClause.`is`(definition) &&
                CallDefinitionClause.enclosingModularMacroCall(definition)?.let { Module.name(it) } == "Ids"
        }
    }

    /** The callbacks the `defoverridable` key at the caret resolves to. */
    private fun keyCallbacks(): List<String> {
        val offset = myFixture.caretOffset
        val host = myFixture.enclosingCallAtCaret { it.functionName() == "defoverridable" }!!

        return PsiSymbolReferenceService.getService()
            .getReferences(host)
            .filter { it.absoluteRange.containsOffset(offset) }
            .flatMap { it.resolveReference() }
            .filterIsInstance<Callback>()
            .map(::describe)
    }

    /** The text of the definitions of the module that the key at the caret can name. */
    private fun definitionTexts(): List<String> {
        val pair = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), QuotableKeywordPair::class.java)!!
        val found = FunctionArityKeywordPairReference.definitions(FunctionArityKeywordPair.classify(pair)!!)

        return (found as FunctionArityKeywordPairReference.Definitions.Found).definitions.map { it.text }
    }

    private fun usageOffsets(): List<Int> =
        myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { it.range.startOffset }

    private fun describe(callback: Callback): String =
        "${callback.name}/${callback.arity}" + if (callback.macro) " macro" else ""

    private fun offsetOf(declaration: String, name: String): Int {
        val offset = myFixture.file.text.indexOf(declaration)
        assertTrue("`$declaration` not found in the fixture", offset >= 0)

        return offset + declaration.indexOf(name)
    }

    private fun caretOn(declaration: String, name: String) {
        myFixture.editor.caretModel.moveToOffset(offsetOf(declaration, name) + 1)
    }
}
