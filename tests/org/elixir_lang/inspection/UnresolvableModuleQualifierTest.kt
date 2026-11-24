package org.elixir_lang.inspection

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.declaration.QualifiedModule
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

class UnresolvableModuleQualifierTest : ExpanderSettingTestCase() {
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

    /** Elixir quotes `(Foo.Bar)` as `Foo.Bar`, so the alias is in scope. */
    fun testResolvedParenthesizedAliasedModuleNotFlagged() {
        myFixture.configureByFiles("resolved_parenthesized_alias.ex", "referenced.ex")
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

    /** `__MODULE__.Sub` in a `quote` names `Sub` in the module the `quote` is injected into, here `U.Sub`. */
    fun testModuleAliasQualifierInAQuoteNotFlagged() {
        myFixture.configureByFiles("module_alias_qualifier_in_quote.ex")
        myFixture.checkHighlighting()
    }

    /** A module a `quote` writes is nested in the module the `quote` is injected into, here `U.Inner`. */
    fun testModuleAliasQualifierInAModuleAQuoteWritesNotFlagged() {
        myFixture.configureByFiles("module_alias_qualifier_in_quoted_module.ex")
        myFixture.checkHighlighting()
    }

    /** The module a `quote` writes is named by the caller's argument, here `N`. */
    fun testModuleAliasQualifierInAModuleAQuoteWritesUnquotedNotFlagged() {
        myFixture.configureByFiles("module_alias_qualifier_in_unquote_named_module.ex")
        myFixture.checkHighlighting()
    }

    fun testUndefinedModuleAliasQualifierFlagged() {
        myFixture.configureByFiles("undefined_module_alias_qualifier.ex")
        myFixture.checkHighlighting()
    }

    /** Outside any module `__MODULE__` is `nil`, so `__MODULE__.Nope` is `Nope`, which isn't defined. */
    fun testTopLevelModuleAliasQualifierFlagged() {
        myFixture.configureByFiles("top_level_module_alias_qualifier.ex")
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

    /** A variable, a call's result or an attribute names a module only at runtime, so the walk can't say it's absent. */
    fun testTheWalkCantSayWhetherAQualifierThatIsNotAnAliasNamesAHeldModule() {
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  @attr Mod\n\n  def run(x) do\n    x.f()\n    foo().bar()\n    @attr.f()\n  end\nend\n",
        )

        val answers = PsiTreeUtil.findChildrenOfType(myFixture.file, Qualified::class.java)
            .associate { it.text to LegacyWalkSource.qualifiedModule(it) }

        assertEquals(
            mapOf("x.f()" to QualifiedModule.Unknown, "foo().bar()" to QualifiedModule.Unknown, "@attr.f()" to QualifiedModule.Unknown),
            answers,
        )
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/inspection/unresolvable_module_qualifier"
}
