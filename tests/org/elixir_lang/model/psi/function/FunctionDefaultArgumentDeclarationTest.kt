package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * Regression for the default-argument declaration collapse: a `def foo(a, b \\ 1)` clause yields one
 * [FunctionSymbol] per arity (foo/1 + foo/2) sharing the same name range, but
 * [FunctionSymbolDeclarationProvider] must expose exactly ONE declaration so Go-To-Declaration and
 * Shift+F6 do not pop a spurious arity chooser.
 */
class FunctionDefaultArgumentDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/function"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    @RequiresReadLock
    fun testDefaultArgumentClauseExposesSingleDeclaration() {
        myFixture.configureByFiles("default_argument_declaration.ex")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        assertNotNull("Element at caret should exist", element)

        val clause = generateSequence(element!!) { it.parent }
            .filterIsInstance<Call>()
            .first { CallDefinitionClause.`is`(it) }

        // Guard the test itself: the clause must really expand to multiple arities, otherwise
        // collapsing to a single declaration would prove nothing.
        assertTrue(
            "Default-argument def should expand to multiple arities",
            FunctionSymbol.fromDeclaration(clause).size >= 2
        )

        val declarations = FunctionSymbolDeclarationProvider().getDeclarations(clause, 0)
        assertEquals(
            "A default-argument def must expose exactly one declaration (no arity chooser)",
            1,
            declarations.size
        )
    }
}
