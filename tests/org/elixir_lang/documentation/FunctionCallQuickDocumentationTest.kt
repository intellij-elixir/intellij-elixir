package org.elixir_lang.documentation

/**
 * Behavioural Quick Documentation (Ctrl+Q) tests for function calls.
 *
 * Drives the real documentation gesture at the caret via [QuickDocumentationTestCase] instead of
 * resolving the call reference in the test, so each case locks the user-visible behaviour ("Ctrl+Q
 * on a documented call shows its `@doc`") independent of how call-site resolution is implemented.
 */
class FunctionCallQuickDocumentationTest : QuickDocumentationTestCase() {
    /** A call that resolves to nothing documents nothing. */
    fun testQuickDocOnACallOfNoDefinitionShowsNothing() {
        myFixture.configureByText("function_call_quick_doc.ex", "defmodule Caller do\n  def call, do: Nowhere.miss<caret>ing(1)\nend\n")

        assertNull(quickDocumentationAtCaret())
    }

    fun testQuickDocOnACallOfNoDefinitionShowsNothingWithTheExpanderOn() = withTheExpanderOn(::testQuickDocOnACallOfNoDefinitionShowsNothing)

    fun testQuickDocOnLocalFunctionCallShowsAtDoc() {
        myFixture.configureByFiles("local_function.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented local function call", documentation)
        assertTrue(
            "Expected the defining module in the documentation, got: $documentation",
            documentation!!.contains("<b>Documented</b>")
        )
        assertTrue(
            "Expected the function head in the documentation, got: $documentation",
            documentation.contains("add")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Adds two numbers together")
        )
    }

    fun testQuickDocOnRemoteFunctionCallShowsAtDoc() {
        myFixture.configureByFiles("remote_function.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented remote function call", documentation)
        assertTrue(
            "Expected the defining module in the documentation, got: $documentation",
            documentation!!.contains("<b>Callee</b>")
        )
        assertTrue(
            "Expected the function head in the documentation, got: $documentation",
            documentation.contains("multiply")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Multiplies two numbers")
        )
    }

    /** A capture names the function without calling it, so Ctrl+Q on it must still show its `@doc`. */
    fun testQuickDocOnQualifiedCaptureShowsAtDoc() {
        myFixture.configureByFiles("capture_function.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented captured function", documentation)
        assertTrue(
            "Expected the defining module in the documentation, got: $documentation",
            documentation!!.contains("<b>Callee</b>")
        )
        assertTrue(
            "Expected the function head in the documentation, got: $documentation",
            documentation.contains("multiply")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Multiplies two numbers")
        )
    }

    /**
     * The qualifier is a short name bound by an `alias` directive, so showing the right `@doc`
     * requires following the alias to the declaring module.
     */
    fun testQuickDocOnAliasedRemoteFunctionCallShowsAtDoc() {
        myFixture.configureByFiles("aliased_remote_function.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a call qualified by an alias", documentation)
        assertTrue(
            "Expected the declaring module in the documentation, got: $documentation",
            documentation!!.contains("Callee")
        )
        assertTrue(
            "Expected the function head in the documentation, got: $documentation",
            documentation.contains("divide")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Divides two numbers")
        )
    }

    /**
     * `as:` renames the qualifier to something no module is called, so nothing but the alias can
     * lead back to the declaring module - the case a suffix match cannot accidentally satisfy.
     */
    fun testQuickDocOnRenamedAliasRemoteFunctionCallShowsAtDoc() {
        myFixture.configureByFiles("renamed_alias_remote_function.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a call qualified by a renamed alias", documentation)
        assertTrue(
            "Expected the declaring module in the documentation, got: $documentation",
            documentation!!.contains("Callee")
        )
        assertTrue(
            "Expected the function head in the documentation, got: $documentation",
            documentation.contains("subtract")
        )
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation.contains("Subtracts two numbers")
        )
    }

    fun testQuickDocOnModuleAliasShowsModuleDoc() {
        myFixture.configureByFiles("module_alias.ex")

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented module alias", documentation)
        assertTrue(
            "Expected the module definition in the documentation, got: $documentation",
            documentation!!.contains("<i>module</i> <b>Callee</b>")
        )
        assertTrue(
            "Expected the @moduledoc body in the documentation, got: $documentation",
            documentation.contains("Callee module documentation")
        )
    }

    /**
     * A `def`'s `@doc` documents the clause's own name identifier, not every identifier inside the
     * clause. Without that distinction anything in a documented function's body would inherit its
     * `@doc`.
     */
    fun testQuickDocOnVariableInsideDocumentedFunctionDoesNotShowItsAtDoc() {
        myFixture.configureByFiles("variable_in_documented_function_body.ex")

        val documentation = quickDocumentationAtCaret()

        assertFalse(
            "A variable in a documented function's body should not inherit its @doc, got: $documentation",
            documentation.orEmpty().contains("Adds two numbers together")
        )
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/documentation/local_function_quick_doc"
}
