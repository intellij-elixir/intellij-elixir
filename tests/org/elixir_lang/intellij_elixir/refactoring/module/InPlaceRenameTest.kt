package org.elixir_lang.intellij_elixir.refactoring.module

import org.elixir_lang.intellij_elixir.refactoring.InPlaceSymbolRenameTestCase

/**
 * Exercises the real IDE Shift+F6 (in-place) rename gesture on a module alias: press Shift+F6, type
 * the new name, press Enter, then assert the whole document renamed correctly. This drives the inline
 * template and its asynchronous commit - the actual user path.
 */
class InPlaceRenameTest : InPlaceSymbolRenameTestCase() {
    override fun getTestDataPath(): String {
        return "testData/org/elixir_lang/refactoring/module/rename"
    }

    fun testDeclarationInPlaceRename() {
        myFixture.configureByFiles("declaration.ex")

        inPlaceRenameAtCaret("RenamedModule")

        myFixture.checkResultByFile("declaration.ex", "after.ex", false)
    }

    fun testNestedDeclarationInPlaceRename() {
        myFixture.configureByFiles("nested_declaration.ex")

        inPlaceRenameAtCaret("Renamed")

        myFixture.checkResultByFile("nested_declaration.ex", "nested_declaration_after.ex", false)
    }

    fun testUsageInPlaceRename() {
        myFixture.configureByFiles("usage.ex")

        inPlaceRenameAtCaret("RenamedModule")

        myFixture.checkResultByFile("usage.ex", "after.ex", false)
    }

    fun testAliasUsageInPlaceRename() {
        myFixture.configureByFiles("alias_usage.ex")

        inPlaceRenameAtCaret("RenamedModule")

        myFixture.checkResultByFile("alias_usage.ex", "alias_usage_after.ex", false)
    }

    fun testImportUsageInPlaceRename() {
        myFixture.configureByFiles("import_usage.ex")

        inPlaceRenameAtCaret("RenamedModule")

        myFixture.checkResultByFile("import_usage.ex", "import_usage_after.ex", false)
    }

    fun testUseUsageInPlaceRename() {
        myFixture.configureByFiles("use_usage.ex")

        inPlaceRenameAtCaret("RenamedModule")

        myFixture.checkResultByFile("use_usage.ex", "use_usage_after.ex", false)
    }
}
