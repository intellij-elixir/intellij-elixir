package org.elixir_lang.psi.scope.callable_table

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.scope.WalkProbe
import org.elixir_lang.psi.scope.WalkProbe.Counter
import org.elixir_lang.psi.scope.WalkTestSupport

/**
 * The table answers every use exactly as walking the module does: `multiResolve` in both modes, the
 * candidates, and completion, unsorted. The only uses that differ are [AnswerChangeTest]'s.
 */
class ParityTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            WalkProbe.reset()
        } finally {
            super.tearDown()
        }
    }

    fun testInputs() = assertParity("snapshot/inputs")
    fun testCallableDeclaration() = assertParity("psi/callable_declaration")
    fun testForms() = assertParity("psi/scope/callable_table/forms")
    fun testUnquotes() = assertParity("psi/scope/callable_table/unquotes", AnswerChangeTest.CHANGED)
    fun testOrder() = assertParity("psi/scope/callable_table/order")
    fun testPlacement() = assertParity("psi/scope/callable_table/placement")
    fun testImplicitImportIndex() = assertParity("psi/scope/implicit_import_index")
    fun testReach() = assertParity("psi/scope/callable_table/reach")
    fun testReentry() = assertParity("psi/scope/callable_table/reentry")
    fun testReentryEEx() = assertParity("psi/scope/callable_table/reentry_eex")
    fun testReentryUnimported() = assertParity("psi/scope/callable_table/reentry_unimported")
    fun testReentryImported() = assertParity("psi/scope/callable_table/reentry_imported")
    fun testSites() = assertParity("psi/scope/callable_table/sites")
    fun testImportOptions() = assertParity("psi/scope/callable_table/import_options")
    fun testCombining() = assertParity("psi/scope/callable_table/combining")
    fun testForeign() = withHeexInjection { assertParity("psi/scope/callable_table/foreign", injected = true) }
    fun testReachInjected() = withHeexInjection { assertParity("psi/scope/callable_table/reach", injected = true) }

    fun testInputsWithKernel() = withKernel { assertParity("snapshot/inputs") }
    fun testCallableDeclarationWithKernel() = withKernel { assertParity("psi/callable_declaration") }
    fun testOrderWithKernel() = withKernel { assertParity("psi/scope/callable_table/order") }
    fun testPlacementWithKernel() = withKernel { assertParity("psi/scope/callable_table/placement") }
    fun testReachWithKernel() = withKernel { assertParity("psi/scope/callable_table/reach") }
    fun testReentryWithKernel() = withKernel { assertParity("psi/scope/callable_table/reentry") }
    fun testForeignWithKernel() =
        withKernel { withHeexInjection { assertParity("psi/scope/callable_table/foreign", injected = true) } }

    private fun withHeexInjection(block: () -> Unit) = WalkTestSupport.withHeexInjection(project, testRootDisposable, block)

    private fun withKernel(block: () -> Unit) =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, LIBRARY, WalkTestSupport.DOCS_KERNEL) { block() }

    /**
     * [changed]: the locations of uses whose resolution the table changes on purpose. [injected]: also the uses in what
     * the files inject.
     */
    private fun assertParity(directory: String, changed: Set<String> = emptySet(), injected: Boolean = false) {
        val root = myFixture.copyDirectoryToProject(directory, "")
        val files = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { if (!it.isDirectory) files += it; true }
        val dump = WalkDump(project, root)

        val live = dump.lines(myFixture, files, table = false, injected)
        WalkProbe.reset()
        val tabled = WalkProbe.counting { dump.lines(myFixture, files, table = true, injected) }
        val counts = WalkProbe.snapshot()

        assertTrue("no table was built, so the table run walked live", counts.getValue(Counter.TABLE_BUILD) > 0)
        assertEquals(
            directory,
            live.filterNot { isChanged(it, changed) }.joinToString("\n"),
            tabled.filterNot { isChanged(it, changed) }.joinToString("\n")
        )
    }

    private fun isChanged(line: String, changed: Set<String>): Boolean =
        changed.any { line.startsWith("$it ") } && "  multiResolve  " in line

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    private companion object {
        const val LIBRARY = "parity_kernel"
    }
}
