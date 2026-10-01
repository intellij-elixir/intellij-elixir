package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase

class UnresolvableModuleQualifierTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(UnresolvableModuleQualifier::class.java)
    }

    fun testUnresolvedQualifierFlagged() {
        myFixture.configureByFiles("unresolved_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testResolvedDirectModuleNotFlagged() {
        myFixture.configureByFiles("resolved_direct_module.ex", "referenced.ex")
        myFixture.checkHighlighting()
    }

    fun testResolvedAliasedModuleNotFlagged() {
        myFixture.configureByFiles("resolved_aliased_module.ex", "referenced.ex")
        myFixture.checkHighlighting()
    }

    fun testVariableQualifierSkipped() {
        myFixture.configureByFiles("variable_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testChainedCallQualifierSkipped() {
        myFixture.configureByFiles("chained_call_qualifier.ex", "referenced.ex")
        myFixture.checkHighlighting()
    }

    fun testModuleAttributeQualifierSkipped() {
        myFixture.configureByFiles("module_attribute_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testNoParenthesesCallUnresolved() {
        myFixture.configureByFiles("no_parens_unresolved.ex")
        myFixture.checkHighlighting()
    }

    fun testNoArgumentsCallUnresolved() {
        myFixture.configureByFiles("no_args_unresolved.ex")
        myFixture.checkHighlighting()
    }

    fun testNestedUnresolvedQualifierFlagged() {
        myFixture.configureByFiles("nested_unresolved_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testCurrentModuleQualifierNotFlagged() {
        myFixture.configureByFiles("current_module_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testQuotedUnquoteQualifierSkipped() {
        myFixture.configureByFiles("quoted_unquote_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testAtomQualifierSkipped() {
        myFixture.configureByFiles("atom_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testPipelineUnresolvedQualifierFlagged() {
        myFixture.configureByFiles("pipeline_unresolved_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersAliasSuppressedWhenRouterExists() {
        myFixture.configureByFiles("router_helpers_alias_suppressed.ex", "ic_web_router.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersDirectSuppressedWhenRouterExists() {
        myFixture.configureByFiles("router_helpers_direct_suppressed.ex", "ic_web_router.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersStillFlaggedWhenRouterMissing() {
        myFixture.configureByFiles("router_helpers_router_missing.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersAsSuppressedWhenRouterExists() {
        myFixture.configureByFiles("router_helpers_as_suppressed.ex", "ic_web_router.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersQuotedAsSuppressedWhenRouterExists() {
        myFixture.configureByFiles("router_helpers_quoted_as_suppressed.ex", "ic_web_router.ex")
        myFixture.checkHighlighting()
    }

    fun testRouterHelpersQuotedAliasSuppressedWhenRouterExists() {
        myFixture.configureByFiles("router_helpers_quoted_alias_suppressed.ex", "ic_web_router.ex")
        myFixture.checkHighlighting()
    }

    fun testUseInjectedAliasSuppressedWhenUseIsOpaque() {
        myFixture.configureByFiles("use_injected_alias.ex", "conn_case.ex")
        myFixture.checkHighlighting()
    }

    fun testDocCodeBlockQualifierNotFlagged() {
        myFixture.configureByFiles("doc_code_block_qualifier.ex")
        myFixture.checkHighlighting()
    }

    fun testElixirPrefixedModuleNotFlagged() {
        myFixture.configureByFiles("elixir_prefixed_module.ex", "referenced.ex")
        myFixture.checkHighlighting()
    }

    fun testElixirPrefixedUnresolvedFlagged() {
        myFixture.configureByFiles("elixir_prefixed_unresolved.ex")
        myFixture.checkHighlighting()
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/inspection/unresolvable_module_qualifier"
}
