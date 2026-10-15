package org.elixir_lang.model.psi.protocol

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.line_marker_provider.Implementation as ImplementationMarker
import org.elixir_lang.code_insight.line_marker_provider.Protocol as ProtocolMarker
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.navigation.ElixirGotoTargetPresentationProvider
import org.elixir_lang.navigation.ElixirImplementationCellRenderer
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call

/**
 * Every place that answers "does this definition implement that protocol function?" gives the same answer: a public
 * `def`, or a `defdelegate`, of the function's name and arity, and nothing else. Each test names the `defimpl`
 * modules the reader links to the protocol function, which is what a user sees in its popup or its renamed text.
 */
@Suppress("UnstableApiUsage")
class ProtocolImplementationReadersTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/protocol/implementation_rule"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testFindUsagesListsPublicFunctionImplementations() = assertEquals(PUBLIC, findUsages(SIZE))

    fun testRenameRenamesPublicFunctionImplementations() = assertEquals(PUBLIC, renamed(SIZE))

    /** A delegation's head is the delegation's own declaration, so only the `def` refers to the protocol function. */
    fun testGotoDeclarationFromEachImplementation() = assertEquals(setOf("List"), gotoDeclaration(SIZE))

    fun testCtrlClickOnImplementingDefLandsOnTheProtocolFunction() {
        myFixture.configureByFile(SIZE.file)
        moveCaretToImplementer(SIZE, "List")

        myFixture.assertGotoDeclarationLandsIn("size", "a function of the `Size` protocol") { definition ->
            CallDefinitionClause.`is`(definition) &&
                CallDefinitionClause.enclosingModularMacroCall(definition)?.let { Protocol.`is`(it) } == true
        }
    }

    /** A delegation's head declares the delegation, as a `def`'s head declares the `def`. */
    fun testCtrlClickOnImplementingDelegationShowsItsUsages() {
        myFixture.configureByFile(SIZE.file)
        moveCaretToImplementer(SIZE, "Atom")

        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testGotoImplementationRowsShowEachImplementationsHead() =
        assertEquals(mapOf("List" to "def size(list)", "Atom" to "defdelegate size(atom)"), gotoImplementationRows(SIZE))

    fun testProtocolGutterRowsShowEachImplementationsHead() =
        assertEquals(mapOf("List" to "def size(list)", "Atom" to "defdelegate size(atom)"), protocolGutterRows(SIZE))

    fun testGotoSuperFromEachImplementation() = assertEquals(PUBLIC, gotoSuper(SIZE))

    fun testImplementationGutterFromEachImplementation() = assertEquals(PUBLIC, implementationGutter(SIZE))

    fun testProtocolGutterTargetsPublicFunctionImplementations() = assertEquals(PUBLIC, protocolGutter(SIZE))

    fun testGotoImplementationFindsPublicFunctionImplementations() = assertEquals(PUBLIC, gotoImplementation(SIZE))

    fun testFindUsagesLinksNoMacroMember() = assertEquals(NONE, findUsages(MACRO))

    fun testRenameLinksNoMacroMember() = assertEquals(NONE, renamed(MACRO))

    fun testGotoDeclarationLinksNoMacroMember() = assertEquals(NONE, gotoDeclaration(MACRO))

    fun testGotoSuperLinksNoMacroMember() = assertEquals(NONE, gotoSuper(MACRO))

    fun testImplementationGutterLinksNoMacroMember() = assertEquals(NONE, implementationGutter(MACRO))

    fun testProtocolGutterLinksNoMacroMember() = assertEquals(NONE, protocolGutter(MACRO))

    fun testGotoImplementationLinksNoMacroMember() = assertEquals(NONE, gotoImplementation(MACRO))

    fun testFindUsagesListsADelegationWithoutAs() = assertEquals(setOf("Atom"), findUsages(DELEGATION))

    /** Renaming only its head would make it delegate to a function its target does not define. */
    fun testRenameLeavesADelegationWithoutAsAlone() = assertEquals(NONE, renamed(DELEGATION))

    fun testAPrivateImplementerKeepsItsOwnUsages() {
        myFixture.configureByFile(SIZE.file)
        moveCaretToImplementer(SIZE, "Map")

        val calls = myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }

        assertEquals(listOf("size"), calls.map { it.file.text.substring(it.range.startOffset, it.range.endOffset) })
    }

    private fun findUsages(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)
        moveCaretToMember(fixture)

        return myFixture.psiUsagesAtCaret(project)
            .mapNotNull { usage -> usage.file.findElementAt(usage.range.startOffset)?.let(::implementingModule) }
            .toSet()
    }

    private fun renamed(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)
        moveCaretToMember(fixture)
        myFixture.renameTargetAtCaret("fresh")

        val text = myFixture.file.text

        return fixture.implementers.keys.filterTo(mutableSetOf()) { module ->
            val header = text.indexOf("defimpl ${fixture.protocol}, for: $module do")

            text.substring(header, text.indexOf("\nend\n", header)).contains("fresh")
        }
    }

    private fun gotoDeclaration(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)

        return fixture.implementers.keys.filterTo(mutableSetOf()) { module ->
            moveCaretToImplementer(fixture, module)

            PsiSymbolReferenceService.getService()
                .getReferences(definitionAtCaret())
                .flatMap { it.resolveReference() }
                .any { it is ProtocolFunction && it.protocolName == fixture.protocol && it.name == fixture.name }
        }
    }

    private fun gotoSuper(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)
        val member = memberRange(fixture)

        return fixture.implementers.keys.filterTo(mutableSetOf()) { module ->
            moveCaretToImplementer(fixture, module)
            myFixture.performEditorAction(IdeActions.ACTION_GOTO_SUPER)

            myFixture.caretOffset in member.startOffset..member.endOffset
        }
    }

    /** The modules whose function-level gutter icon leads to a protocol function. */
    private fun implementationGutter(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)

        return leaves().mapNotNullTo(mutableSetOf()) { leaf ->
            val targets = markerTargets(ImplementationMarker().getLineMarkerInfo(leaf))

            implementingModule(leaf)?.takeIf { targets.any { it is Call && CallDefinitionClause.`is`(it) } }
        }
    }

    /** The modules of the implementations the protocol function's own gutter icon leads to. */
    private fun protocolGutter(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)
        val member = memberRange(fixture)

        return leaves()
            .filter { it.textOffset in member.startOffset..member.endOffset }
            .flatMap { markerTargets(ProtocolMarker().getLineMarkerInfo(it)) }
            .mapNotNullTo(mutableSetOf(), ::implementingModule)
    }

    private fun gotoImplementation(fixture: Fixture): Set<String> {
        myFixture.configureByFile(fixture.file)
        moveCaretToMember(fixture)

        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, ImplementationSearcher.getFlags(), myFixture.caretOffset)
            ?: return emptySet()

        return DefinitionsScopedSearch.search(source).findAll().mapNotNullTo(mutableSetOf(), ::implementingModule)
    }

    /** Each implementation Go To Implementation lists, by module, as its row in the chooser shows it. */
    private fun gotoImplementationRows(fixture: Fixture): Map<String, String?> {
        myFixture.configureByFile(fixture.file)
        moveCaretToMember(fixture)

        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, ImplementationSearcher.getFlags(), myFixture.caretOffset)!!

        return DefinitionsScopedSearch.search(source).findAll().associate { implementation ->
            implementingModule(implementation)!! to
                ElixirGotoTargetPresentationProvider().getTargetPresentation(implementation, false)?.presentableText
        }
    }

    /** Each implementation the protocol function's gutter icon leads to, by module, as its popup row shows it. */
    private fun protocolGutterRows(fixture: Fixture): Map<String, String> {
        myFixture.configureByFile(fixture.file)
        val member = memberRange(fixture)

        return leaves()
            .filter { it.textOffset in member.startOffset..member.endOffset }
            .flatMap { markerTargets(ProtocolMarker().getLineMarkerInfo(it)) }
            .associate { implementingModule(it)!! to ElixirImplementationCellRenderer().getElementText(it) }
    }

    private fun markerTargets(info: com.intellij.codeInsight.daemon.LineMarkerInfo<*>?): List<PsiElement> =
        (info as? RelatedItemLineMarkerInfo<*>)?.createGotoRelatedItems()?.mapNotNull { it.element }.orEmpty()

    private fun leaves(): List<PsiElement> = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }.toList()

    private fun definitionAtCaret(): Call =
        generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .filterIsInstance<Call>()
            .first { DefinitionDeclaration.`is`(it) }

    /** The `for:` of the `defimpl` that holds [element], or `null` when none does. */
    private fun implementingModule(element: PsiElement): String? =
        generateSequence(element) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { Implementation.`is`(it) }
            ?.let { FOR.find(it.text)?.groupValues?.get(1) }

    private fun moveCaretToMember(fixture: Fixture) {
        myFixture.editor.caretModel.moveToOffset(memberRange(fixture).startOffset + fixture.nameOffset(fixture.member))
    }

    private fun moveCaretToImplementer(fixture: Fixture, module: String) {
        val anchor = fixture.implementers.getValue(module)
        val header = myFixture.file.text.indexOf("defimpl ${fixture.protocol}, for: $module do")
        val start = myFixture.file.text.indexOf(anchor, header)

        assertTrue("`$anchor` not in $module's defimpl", start >= 0)
        myFixture.editor.caretModel.moveToOffset(start + fixture.nameOffset(anchor))
    }

    private fun memberRange(fixture: Fixture): TextRange {
        val start = myFixture.file.text.indexOf(fixture.member)

        return TextRange(start, start + fixture.member.length)
    }

    /** [member] and each module's [implementers] clause as written, so a caret can be put on its name. */
    private class Fixture(
        val file: String,
        val protocol: String,
        val name: String,
        val member: String,
        val implementers: Map<String, String>
    ) {
        /** The offset, one in from its start, of the name in [definition]. */
        fun nameOffset(definition: String): Int = definition.indexOf(name) + 1
    }

    private companion object {
        val FOR = Regex("""for:\s*([\w.]+)""")
        val NONE = emptySet<String>()
        val PUBLIC = setOf("List", "Atom")

        val SIZE = Fixture(
            "size.ex",
            "Size",
            "size",
            "def size(data)",
            linkedMapOf(
                "List" to "def size(list)",
                "Map" to "defp size(map)",
                "Tuple" to "defguard size(tuple)",
                "BitString" to "defmacro size(binary)",
                "Atom" to "defdelegate size(atom)"
            )
        )

        val DELEGATION = Fixture(
            "delegation.ex",
            "Size",
            "size",
            "def size(data)",
            linkedMapOf("Atom" to "defdelegate size(atom)")
        )

        val MACRO = Fixture(
            "macro_member.ex",
            "Shape",
            "describe",
            "defmacro describe(shape)",
            linkedMapOf("Map" to "defmacro describe(shape)", "List" to "def describe(shape)")
        )
    }
}
