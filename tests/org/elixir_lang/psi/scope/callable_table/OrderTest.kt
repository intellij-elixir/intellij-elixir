package org.elixir_lang.psi.scope.callable_table

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * Uses whose unsorted answers depend on the order the module's declarations are replayed in: by name, grouped by
 * kind, or with an `import`'s or a delegation's stop applied to the whole module would each change one of them.
 */
class OrderTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    fun testImportStopStopsInsideTheImportedModule() =
        assertOrder("order", "order.ex:36:20", "multiResolve", listOf("order.ex:37:3"))

    fun testImportBeforeLocalKeepsTheImportFirst() =
        assertOrder("order", "order.ex:2:23", "multiResolve", listOf("imported.ex:2:3", "order.ex:4:3"))

    fun testDelegationStopStaysInsideTheDelegation() =
        assertOrder(
            "delegation_stop",
            "delegation_stop.ex:13:16",
            "found",
            listOf(
                "delegation_stop.ex:9:3 size/Exact(arity=1) OWN VALID via=[]",
                "delegation_stop.ex:2:3 bar/Exact(arity=1) DELEGATION_TARGET VALID via=[delegation_stop.ex:9:3]",
                "delegation_stop.ex:4:3 bar/Exact(arity=2) DELEGATION_TARGET WRONG_ARITY via=[delegation_stop.ex:9:3]",
                "delegation_stop.ex:5:3 bar/Exact(arity=1) DELEGATION_TARGET VALID via=[delegation_stop.ex:9:3]"
            )
        )

    /** [use]'s answers equal walking the module's, unsorted, and its [kind] answer is [expected] in that order. */
    private fun assertOrder(directory: String, use: String, kind: String, expected: List<String>) {
        val root = myFixture.copyDirectoryToProject("psi/scope/callable_table/$directory", "")
        val dump = WalkDump(project, root)
        val file = PsiManager.getInstance(project).findFile(root.findChild(use.substringBefore(':'))!!)!!
        check(PsiTreeUtil.findChildrenOfType(file, Call::class.java).any { dump.location(it) == use }) { "no call at $use" }

        val live = dump.lines(myFixture, listOf(file.virtualFile), table = false).filter { it.startsWith("$use ") }
        val tabled = dump.lines(myFixture, listOf(file.virtualFile), table = true).filter { it.startsWith("$use ") }

        assertEquals(use, live.joinToString("\n"), tabled.joinToString("\n"))

        val answer = live.single { "incompleteCode=false  $kind  " in it }.substringAfter("  $kind  ")
        assertEquals("$use's $kind answer, unsorted", expected, answer.split(" | "))
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"
}
