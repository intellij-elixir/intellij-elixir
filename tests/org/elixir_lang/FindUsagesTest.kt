package org.elixir_lang

import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase

@Suppress("UnstableApiUsage")
class FindUsagesTest : ExpanderSettingTestCase() {
    override fun getTestDataPath(): String {
        return "testData/org/elixir_lang/find_usages"
    }

    fun testFunctionRecursiveDeclaration() {
        val offsets = functionSymbolUsageOffsetsAtCaret("function_recursive_declaration.ex", "kernel.ex")
        assertEquals(3, offsets.size)
        assertContainsElements(offsets, listOf(63, 93))
    }

    fun testFunctionRecursiveUsage() {
        val offsets = functionSymbolUsageOffsetsAtCaret("function_recursive_usage.ex", "kernel.ex")
        assertEquals(3, offsets.size)
        assertContainsElements(offsets, listOf(63, 93))
    }

    fun testFunctionSingleClauseUnused() {
        val offsets = functionSymbolUsageOffsetsAtCaret("function_single_clause_unused.ex", "kernel.ex")
        assertEquals(1, offsets.size)
        assertContainsElements(offsets, listOf(26))
    }

    fun testFunctionMultipleClausesUnused() {
        val offsets = functionSymbolUsageOffsetsAtCaret("function_multiple_clauses_unused.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(26))
    }

    fun testFunctionMultipleModulesDeclaration() {
        val offsets = usageOffsetsAtCaret(
            "function_multiple_modules_declaration_target.ex",
            "function_multiple_modules_declaration_usage.ex",
            "kernel.ex"
        )
        // Declaration-side SearchTarget coverage for cross-module qualified dispatch is asserted in
        // testFunctionMultipleModulesUsage; here we keep declaration anchor behavior.
        assertTrue("Expected declaration usage at offset 31", offsets.contains(31))
    }

    fun testFunctionMultipleModulesUsage() {
        val offsets = usageOffsetsAtCaret(
            "function_multiple_modules_usage_target.ex",
            "function_multiple_modules_usage_declaration.ex",
            "kernel.ex"
        )
        assertTrue(
            "Expected cross-module qualified call-site usage at offset 50",
            offsets.contains(50)
        )
    }

    fun testFunctionImportDeclaration() {
        val offsets = usageOffsetsAtCaret(
            "function_import_declaration_target.ex",
            "function_import_declaration_usage.ex",
            "kernel.ex"
        )
        // Imported call-site coverage is asserted in testFunctionImportUsage; keep declaration anchor behavior here.
        assertTrue("Expected declaration usage at offset 31", offsets.contains(31))
    }

    fun testFunctionImportUsage() {
        val offsets = usageOffsetsAtCaret(
            "function_import_usage_target.ex",
            "function_import_usage_declaration.ex",
            "kernel.ex"
        )
        assertTrue(
            "Expected imported unqualified call-site usage at offset 60",
            offsets.contains(60)
        )
    }

    fun testParameterDeclaration() {
        val offsets = usageOffsetsAtCaret("parameter_declaration.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(39, 70))
    }

    fun testParameterUnused() {
        val offsets = usageOffsetsAtCaret("parameter_unused.ex", "kernel.ex")
        assertEquals(1, offsets.size)
        assertContainsElements(offsets, listOf(39))
    }

    fun testParameterUsage() {
        val offsets = usageOffsetsAtCaret("parameter_usage.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(39))
    }

    fun testVariableDeclaration() {
        val offsets = usageOffsetsAtCaret("variable_declaration.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(46, 99))
    }

    fun testVariableUnused() {
        val offsets = usageOffsetsAtCaret("variable_unused.ex", "kernel.ex")
        assertEquals(1, offsets.size)
        assertContainsElements(offsets, listOf(46))
    }

    fun testVariableUsage() {
        val offsets = usageOffsetsAtCaret("variable_usage.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(46))
    }

    fun testModuleAttributeDeclaration() {
        val offsets = usageOffsetsAtCaret("module_attribute_declaration.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(32, 70))
    }

    fun testModuleAttributeUsage() {
        val offsets = usageOffsetsAtCaret("module_attribute_usage.ex", "kernel.ex")
        assertEquals(2, offsets.size)
        assertContainsElements(offsets, listOf(32, 70))
    }

    fun testIssue2374() {
        val offsets = functionSymbolUsageOffsetsAtCaret("issue_2374.ex", "kernel.ex")
        assertEquals(3, offsets.size)
        assertContainsElements(offsets, listOf(23, 53, 53))
    }

    private fun functionSymbolUsageOffsetsAtCaret(vararg fileNames: String): kotlin.collections.List<Int> {
        return usageOffsetsAtCaret(*fileNames)
    }

    private fun usageOffsetsAtCaret(vararg fileNames: String): kotlin.collections.List<Int> {
        myFixture.configureByFiles(*fileNames)
        return myFixture.psiUsagesAtCaret(project).map { it.range.startOffset }
    }
}
