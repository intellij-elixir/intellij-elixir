package org.elixir_lang.intellij_elixir.refactoring.protocol

import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.intellij_elixir.refactoring.InPlaceSymbolRenameTestCase

/**
 * Exercises the real IDE Shift+F6 (in-place) rename gesture on a protocol function: press Shift+F6,
 * type the new name, press Enter, then assert the whole document renamed correctly. This drives the
 * inline template and its asynchronous commit - the actual user path.
 */
class InPlaceRenameTest : InPlaceSymbolRenameTestCase() {
    /** The walk's answers, whatever the run's default for Run the expander. */
    override fun setUp() {
        super.setUp()
        disableExpander(testRootDisposable)
    }

    override fun getTestDataPath(): String {
        return "testData/org/elixir_lang/refactoring/protocol/rename"
    }

    fun testDeclarationInPlaceRename() {
        myFixture.configureByFiles("declaration.ex")

        inPlaceRenameAtCaret("transform")

        myFixture.checkResultByFile("declaration.ex", "declaration_after.ex", false)
    }

    fun testImplementationInPlaceRename() {
        myFixture.configureByFiles("implementation.ex")

        inPlaceRenameAtCaret("transform")

        myFixture.checkResultByFile("implementation.ex", "implementation_after.ex", false)
    }

    fun testUsageInPlaceRename() {
        myFixture.configureByFiles("usage.ex")

        inPlaceRenameAtCaret("transform")

        myFixture.checkResultByFile("usage.ex", "usage_after.ex", false)
    }
}
