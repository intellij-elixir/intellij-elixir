package org.elixir_lang.psi.scope.callable_table

import com.intellij.psi.PsiManager
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.PlatformTestCase

/**
 * The table looks a name up by the atom each definition declares. A head named `unquote(name)` declares no atom, so a
 * use the walk names by its text `unquote` no longer reaches it, as a prefix match, the way walking the module did.
 * A head named by a literal atom is still found by that atom.
 */
class AnswerChangeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    fun testCallbackHeadNamedByUnquote() = assertNoLongerReachesUnquoteName("unquotes.ex:16:13")
    fun testDelegationCallNamedByUnquote() = assertNoLongerReachesUnquoteName("unquotes.ex:29:28")
    fun testUseNamedByUnquote() = assertNoLongerReachesUnquoteName("unquotes.ex:28:31")

    fun testLiteralAtomHeadIsFoundByItsAtom() {
        val dump = dump(UNQUOTES)

        for (incompleteCode in listOf(false, true)) {
            assertEquals(
                listOf("unquotes.ex:7:3"),
                multiResolve(dump, "unquotes.ex:27:24", incompleteCode, table = true)
            )
        }
    }

    private fun assertNoLongerReachesUnquoteName(location: String) {
        val dump = dump(UNQUOTES)

        for (incompleteCode in listOf(false, true)) {
            assertNoLongerReaches(dump, location, UNQUOTE_NAME, incompleteCode)
        }
    }

    private fun assertNoLongerReaches(dump: WalkDump, location: String, result: String, incompleteCode: Boolean) {
        assertTrue(
            "walking the module reaches $result from $location",
            multiResolve(dump, location, incompleteCode, table = false).any { result in it }
        )
        assertFalse(
            "the table reaches $result from $location",
            multiResolve(dump, location, incompleteCode, table = true).any { result in it }
        )
    }

    private fun dump(directory: String): WalkDump =
        WalkDump(project, myFixture.copyDirectoryToProject("psi/scope/callable_table/$directory", ""))

    /** Every use starting at [location]: a call and the call applying it can share one. */
    private fun multiResolve(dump: WalkDump, location: String, incompleteCode: Boolean, table: Boolean): List<String> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(location.substringBefore(':')))!!
        val kind = "  incompleteCode=$incompleteCode  multiResolve  "

        return dump.lines(myFixture, listOf(file.virtualFile), table)
            .filter { it.startsWith("$location ") && kind in it }
            .map { it.substringAfter(kind) }
            .also { check(it.isNotEmpty()) { "no use at $location" } }
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    companion object {
        val CHANGED = setOf("unquotes.ex:16:13", "unquotes.ex:28:31", "unquotes.ex:29:28")

        private const val UNQUOTES = "unquotes"
        private const val UNQUOTE_NAME = "unquotes.ex:9:3 (invalid)"
    }
}
