package org.elixir_lang

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.code_insight.GotoSuper
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.parameterInfoPopupAfterTyping
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.model.psi.variable.VariableReference
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call

/**
 * Elixir normalizes every identifier to NFC, so a declaration written with a base character plus a
 * combining mark (`c` + COMBINING ACUTE ACCENT) and a use written with the precomposed character (`ć`)
 * name the same thing. Each test pairs the two spellings through a different gesture or entry into
 * resolution, since each used to make its own name comparison.
 */
class NormalizedIdentifierTest : PlatformTestCase() {
    fun testVariableReadReachesADecomposedBinding() {
        myFixture.configureByFile("variable.ex")

        val validElements = callableReferenceAtCaret().multiResolve(false).filter { it.isValidResult }.map { it.element }

        assertTrue(
            "Expected the read to resolve to the decomposed binding, got: ${validElements.map { it?.text }}",
            validElements.any { it?.text == DECOMPOSED }
        )
    }

    fun testTypeUseReachesADecomposedDeclaration() {
        myFixture.configureByFile("type.ex")

        assertEquals(DECOMPOSED, myFixture.gotoDeclarationDestinationAtCaret()?.text)
    }

    fun testModuleAttributeReadReachesADecomposedDeclaration() {
        myFixture.configureByFile("module_attribute.ex")

        assertEquals(DECOMPOSED, myFixture.gotoDeclarationDestinationAtCaret()?.text)
    }

    fun testSpecReachesADecomposedDefinition() {
        myFixture.configureByFile("spec.ex")

        assertTrue(callableReferenceAtCaret().multiResolve(false).any { it.isValidResult })
    }

    fun testPrecomposedAtomReachesADecomposedDeclaration() {
        myFixture.configureByFile("atom_precomposed.ex")

        assertTrue(atomReferenceAtCaret().multiResolve(false).any { it.isValidResult })
    }

    /** An atom enters resolution without passing through the call resolver, so it must be normalized too. */
    fun testDecomposedAtomReachesADecomposedDeclaration() {
        myFixture.configureByFile("atom_decomposed.ex")

        assertTrue(atomReferenceAtCaret().multiResolve(false).any { it.isValidResult })
    }

    fun testParameterInfoForAPrecomposedCallShowsADecomposedDeclaration() {
        myFixture.configureByFile("parameter_info.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping('(')

        assertNotNull("Typing an opening parenthesis should pop up the parameter hint", popup)
        assertEquals(listOf("augend, addend"), popup!!.signatures)
    }

    fun testQuickDocForAWrongArityPrecomposedCallShowsADecomposedDeclaration() {
        myFixture.configureByFile("quick_doc_wrong_arity.ex")

        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertTrue(
            "Expected the declaration's @doc, got: $documentation",
            documentation.orEmpty().contains("Snocs an element onto a list.")
        )
    }

    fun testPrecomposedCallReachesADecomposedEExFunction() {
        myFixture.configureByFiles("eex_function.ex", "eex_stub.ex")

        assertTrue(callableReferenceAtCaret().multiResolve(false).any { it.isValidResult })
    }

    fun testPrecomposedCallReachesADecomposedMixGeneratorEmbed() {
        myFixture.configureByFiles("mix_generator_embed.ex", "mix_generator_stub.ex")

        assertTrue(callableReferenceAtCaret().multiResolve(false).any { it.isValidResult })
    }

    fun testParameterUseReachesADecomposedParameter() {
        myFixture.configureByFile("parameter.ex")

        val symbols = ApplicationManager.getApplication().runReadAction(Computable {
            val host = generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
                .first { it is Call || it is ElixirVariable }
            VariableReference.resolveSymbols(host).toList()
        })

        assertEquals("Expected the decomposed parameter, got $symbols", listOf(VariableSymbol.Kind.PARAMETER), symbols.map { it.kind })
    }

    fun testGotoSuperReachesADecomposedProtocolFunction() {
        myFixture.configureByFile("goto_super.ex")

        GotoSuper().invoke(project, myFixture.editor, myFixture.file)

        val clause = generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }
        val modular = clause?.let { CallDefinitionClause.enclosingModularMacroCall(it) }

        assertTrue("Go To Super should land in the protocol, not stay in the implementation", modular != null && Protocol.`is`(modular))
    }

    fun testGotoImplementationReachesAPrecomposedImplementation() {
        myFixture.configureByFile("goto_implementation.ex")

        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, ImplementationSearcher.getFlags(), myFixture.caretOffset)
        assertNotNull("Go To Implementation must resolve a source element", source)

        assertTrue(implementationNames(DefinitionsScopedSearch.search(source!!).findAll()).contains(PRECOMPOSED))
    }

    fun testProtocolGutterReachesAPrecomposedImplementation() {
        myFixture.configureByFile("protocol_gutter.ex")

        val targets = gutterTargets(org.elixir_lang.code_insight.line_marker_provider.Protocol())

        assertTrue("Expected the precomposed implementation, got $targets", implementationNames(targets).contains(PRECOMPOSED))
    }

    fun testImplementationGutterReachesADecomposedProtocolFunction() {
        myFixture.configureByFile("protocol_gutter.ex")

        val targets = gutterTargets(org.elixir_lang.code_insight.line_marker_provider.Implementation())

        assertTrue("Expected the decomposed protocol function, got $targets", implementationNames(targets).contains(DECOMPOSED))
    }

    fun testGenServerRequestReachesADecomposedHandlerMessage() {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        myFixture.configureByFile("genserver_message.ex")

        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        val clause = generateSequence(destination) { it.parent }.filterIsInstance<Call>().firstOrNull { CallDefinitionClause.`is`(it) }

        assertEquals("handle_call", clause?.let { CallDefinitionClause.nameIdentifier(it)?.text })
    }

    /** The targets of the gutter marker [provider] puts on a function `def`, rather than on its module. */
    private fun gutterTargets(provider: LineMarkerProvider): Collection<PsiElement> {
        val leaves = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
        val marker = leaves
            .mapNotNull { leaf -> provider.getLineMarkerInfo(leaf) }
            .firstOrNull { marker ->
                generateSequence(marker.element) { it.parent }.filterIsInstance<Call>().firstOrNull()
                    ?.let { CallDefinitionClause.`is`(it) } == true
            }
            ?: error("No gutter marker on a function def")

        return (marker as RelatedItemLineMarkerInfo<*>).createGotoRelatedItems().mapNotNull { it.element }
    }

    private fun implementationNames(elements: Collection<PsiElement>): kotlin.collections.List<String?> =
        elements.filterIsInstance<Call>().filter { CallDefinitionClause.`is`(it) }.map { CallDefinitionClause.nameIdentifier(it)?.text }

    private fun callableReferenceAtCaret(): PsiPolyVariantReference =
        myFixture.file.findReferenceAt(myFixture.caretOffset) as? PsiPolyVariantReference
            ?: error("Expected a poly-variant reference at the caret")

    private fun atomReferenceAtCaret(): AtomReference {
        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset) ?: error("No element at the caret")
        val atom = PsiTreeUtil.getParentOfType(elementAtCaret, ElixirAtom::class.java, false)
            ?: error("No atom at the caret")

        return PsiSymbolReferenceService.getService().getReferences(atom).filterIsInstance<AtomReference>().single()
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/normalized_identifier"

    private companion object {
        val DECOMPOSED = "snoc" + 0x301.toChar()
        val PRECOMPOSED = "sno" + 0x107.toChar()
    }
}
