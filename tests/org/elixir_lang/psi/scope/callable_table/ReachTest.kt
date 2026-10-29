package org.elixir_lang.psi.scope.callable_table

import com.intellij.util.IdempotenceChecker
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.scope.WalkTestSupport

/**
 * A use in a `~H` fragment of a nested module, with the `use` that injects its definition in the outer module after
 * that nested module, reaches the definition from the outer module, as walking the module does.
 */
class ReachTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    fun testUseAfterANestedModuleReachesItsSigilAsOuter() = WalkTestSupport.withHeexInjection(project, testRootDisposable) {
        val root = myFixture.copyDirectoryToProject("psi/scope/callable_table/reach", "")
        val dump = WalkDump(project, root)
        val file = root.findChild("using.ex")!!

        val (live, tabled) = listOf(false, true).map { table ->
            dump.lines(myFixture, listOf(file), table, injected = true).filter { "`injected(7)`" in it }
        }

        assertEquals(live.joinToString("\n"), tabled.joinToString("\n"))
        val found = tabled.filter { "  found  " in it }
        assertEquals("the candidates in both modes", 2, found.size)
        for (line in found) assertTrue(line, "using.ex:4:7 " in line && " OUTER " in line)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"
}
