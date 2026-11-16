package org.elixir_lang.psi.scope.callable_table

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.documentation.ElixirDocumentationProvider
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.psi.scope.CallableTable
import org.elixir_lang.psi.scope.WalkTestSupport.location

/**
 * A name spelled with a combining mark, `c` and U+0301, quotes to the same atom as its precomposed spelling, so a
 * definition and a call meet whichever spelling each uses.
 */
class CombiningMarkTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        myFixture.copyDirectoryToProject("psi/scope/callable_table/combining", "")
    }

    override fun tearDown() {
        try {
            CallableTable.enabled = true
        } finally {
            super.tearDown()
        }
    }

    fun testQualifiedCallFromAnotherFile() =
        assertResolves("caller.ex", "Src.Def.W4.src_def_w4_snoć(", "def.ex:10:3")

    /** MICRO SIGN quotes to GREEK SMALL LETTER MU, so the table keys `src_μ` while both spellings are `src_µ`. */
    fun testMicroSignCalledLocally() = assertResolves("micro.ex", "src_\u00b5(a)", "micro.ex:4:3")

    fun testMicroSignCalledFromAnotherFile() =
        assertResolves("micro_caller.ex", "Src.Micro.src_\u00b5(", "micro.ex:4:3")

    fun testAppliedAtom() = assertResolves("apply_caller.ex", ":src_\u00b5", "micro.ex:4:3")

    fun testDocumentationLink() {
        disableExpander(testRootDisposable)
        val file = myFixture.configureFromTempProjectFile("caller.ex")
        val element = ElixirDocumentationProvider()
            .getDocumentationElementForLink(psiManager, "Src.Def.W4.src_def_w4_snoc\u0301/2", file)

        assertEquals("def.ex:10:3", location(element))
    }

    fun testDocumentationAtAQualifiedCall() =
        assertDocumentation("caller.ex", "Src.Def.W4.src_def_w4_snoć(")

    fun testDocumentationAtADelegation() =
        assertDocumentation("defdelegate.ex", "defdelegate src_defdelegate_w4_snoć(")

    fun testDocumentationAtADelegationCalledPrecomposed() =
        assertDocumentation("defdelegate_nfc_call.ex", "defdelegate src_defdelegate_x_nfc_snoć(")

    private fun assertResolves(file: String, use: String, expected: String) {
        val psiFile = myFixture.configureFromTempProjectFile(file)
        val offset = offsetOfName(psiFile.text, use)
        val reference = psiFile.viewProvider.findReferenceAt(offset, org.elixir_lang.ElixirLanguage) as PsiPolyVariantReference

        assertEquals(listOf(expected), reference.multiResolve(false).filter { it.isValidResult }.map { location(it.element) })
    }

    /** Quick Documentation shows something at [use]'s name, and the same without the table. */
    private fun assertDocumentation(file: String, use: String) {
        val psiFile = myFixture.configureFromTempProjectFile(file)
        myFixture.editor.caretModel.moveToOffset(offsetOfName(psiFile.text, use))

        val tabled = myFixture.quickDocumentationAtCaret(project)
        CallableTable.enabled = false
        // A file added is a PSI change, so every cached walk is recomputed without the table.
        myFixture.addFileToProject("without_table.ex", "")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val live = myFixture.quickDocumentationAtCaret(project)

        assertFalse("Quick Documentation without the table showed nothing", live.isNullOrBlank())
        assertEquals(live, tabled)
    }

    /** The offset of the name in [use], which starts after its last space, dot or colon. */
    private fun offsetOfName(text: String, use: String): Int {
        val start = text.indexOf(use).also { check(it >= 0) { "no `$use`" } }

        return start + maxOf(use.lastIndexOf(' '), use.lastIndexOf('.'), use.lastIndexOf(':')) + 1
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"
}
