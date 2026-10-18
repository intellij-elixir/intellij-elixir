package org.elixir_lang.intellij_elixir.refactoring.function

import org.elixir_lang.intellij_elixir.refactoring.InPlaceSymbolRenameTestCase

/**
 * Exercises the real IDE Shift+F6 (in-place) rename gesture on a function: press Shift+F6, type the
 * new name, press Enter, then assert the whole document renamed correctly. This drives the inline
 * template and its asynchronous commit - the actual user path.
 */
class InPlaceRenameTest : InPlaceSymbolRenameTestCase() {
    override fun getTestDataPath(): String {
        return "testData/org/elixir_lang/refactoring/function/rename"
    }

    fun testUsageInPlaceRename() {
        myFixture.configureByFiles("usage_underscore.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("usage_underscore.ex", "usage_underscore_after.ex", false)
    }

    fun testDeclarationInPlaceRename() {
        myFixture.configureByFiles("declaration_underscore.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("declaration_underscore.ex", "declaration_underscore_after.ex", false)
    }

    fun testInPlaceRenameLeavesSameNamedFunctionInOtherModuleUntouched() {
        myFixture.configureByFiles("out_of_scope.ex")

        inPlaceRenameAtCaret("renamed_fun")

        myFixture.checkResultByFile("out_of_scope.ex", "out_of_scope_after.ex", false)
    }

    fun testMultiClauseInPlaceRename() {
        myFixture.configureByFiles("multi_clause.ex")

        inPlaceRenameAtCaret("process")

        myFixture.checkResultByFile("multi_clause.ex", "multi_clause_after.ex", false)
    }

    /**
     * Two **explicit heads with different arities** (`foo/1` + `foo/2`) are distinct functions in
     * Elixir. Renaming from the `foo/1` head must rename only `foo/1`'s head and its `foo(1)` call
     * site, leaving the separate `foo/2` head and its `foo(1, 2)` call site untouched.
     */
    fun testDifferentAritySeparateHeadsRenameOnlyCaretArity() {
        myFixture.configureByFiles("multi_arity_heads.ex")

        inPlaceRenameAtCaret("renamed_foo")

        myFixture.checkResultByFile("multi_arity_heads.ex", "multi_arity_heads_after.ex", false)
    }

    /** An `unquote`-named clause's atom and its `@spec`'s are rewritten, never the word `unquote`. */
    fun testUnquotedNameInPlaceRename() {
        myFixture.configureByText(
            "unquoted.ex",
            "defmodule M do\n  @spec unquote(:f)(term) :: term\n  def unquote(:<caret>f)(x), do: x\n\n  def run, do: f(1)\nend\n"
        )

        inPlaceRenameAtCaret("g")

        assertEquals(
            "defmodule M do\n  @spec unquote(:g)(term) :: term\n  def unquote(:g)(x), do: x\n\n  def run, do: g(1)\nend\n",
            myFixture.editor.document.text
        )
    }

    fun testDefaultArgumentsInPlaceRename() {
        myFixture.configureByFiles("default_arguments.ex")

        inPlaceRenameAtCaret("salute")

        myFixture.checkResultByFile("default_arguments.ex", "default_arguments_after.ex", false)
    }

    fun testQualifiedCallSiteInPlaceRename() {
        myFixture.configureByFiles("qualified_call.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("qualified_call.ex", "qualified_call_after.ex", false)
    }

    fun testPrivateFunctionInPlaceRename() {
        myFixture.configureByFiles("private_function.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("private_function.ex", "private_function_after.ex", false)
    }

    fun testMacroInPlaceRename() {
        myFixture.configureByFiles("macro.ex")

        inPlaceRenameAtCaret("renamed_it")

        myFixture.checkResultByFile("macro.ex", "macro_after.ex", false)
    }

    /**
     * Renaming from the function **declaration** must also update a `&map_it/0` capture usage, so the
     * capture site becomes `&map_rename_it/0` (and not, e.g., a doubled or empty name).
     */
    fun testCaptureUsageRenamedFromDeclaration() {
        myFixture.configureByFiles("capture.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("capture.ex", "capture_after.ex", false)
    }

    /**
     * Renaming from the `&map_it/0` **capture site** must update both the capture and the function
     * declaration, replacing only the name (not the whole `&name/arity` capture).
     */
    fun testCaptureUsageRenamedFromCaptureSite() {
        myFixture.configureByFiles("capture_usage.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("capture_usage.ex", "capture_usage_after.ex", false)
    }

    fun testGuardInPlaceRename() {
        myFixture.configureByFiles("guard.ex")

        inPlaceRenameAtCaret("is_pair")

        myFixture.checkResultByFile("guard.ex", "guard_after.ex", false)
    }

    fun testSpecInPlaceRename() {
        myFixture.configureByFiles("spec.ex")

        inPlaceRenameAtCaret("map_rename_it")

        myFixture.checkResultByFile("spec.ex", "spec_after.ex", false)
    }
}
