package org.elixir_lang.heex.reference

import com.intellij.codeInspection.htmlInspections.XmlEntitiesInspection
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.heex.inspections.HTMLInspectionSuppressor
import org.elixir_lang.heex.xml.HeexComponentResolver
import org.elixir_lang.psi.CallDefinitionClause

/**
 * HEEx component tags resolve to the `def`/`defp` they name: Go To Declaration for `<.button>` and
 * `<Module.button>`, tag-name completion, and the [HTMLInspectionSuppressor] fallback. Each
 * resolution scenario runs against a `.heex` file and, derived from the same fixtures via
 * [heexSigilModuleText], a `~H` sigil - except the file-path conventions (`Sibling`,
 * `EmbedTemplatesConvention`, `TemplatesViewsConvention`), which an injected sigil never has.
 */
class HeexComponentReferenceTest : HeexHostTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/heex/reference/heex_component"

    /** `<.button>` resolves to the sibling view module's `def button/1` (the LiveView convention). */
    fun testLocalComponentResolvesToSiblingModule() {
        myFixture.configureByFiles("sibling/page_live.html.heex", "sibling/page_live.ex")
        assertButtonResolves()
    }

    /** `<.button>` resolves through an explicit `import` in the view module. */
    fun testLocalComponentResolvesThroughImport() {
        myFixture.configureByFiles(
            "import/page_live.html.heex",
            "import/page_live.ex",
            "import/core_components.ex"
        )
        assertButtonResolves()
    }

    /** Same, with the tag inside a `~H` sigil embedded in the importing module instead. */
    fun testLocalComponentResolvesThroughImportInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("import/page_live.ex"),
                heexBody = myFixture.fixtureText("import/page_live.html.heex"),
                myFixture.fixtureText("import/core_components.ex")
            )
        )
        assertButtonResolves()
    }

    /** `<.button>` resolves through `use MyAppWeb, :html` expanding to `import MyAppWeb.CoreComponents`. */
    fun testLocalComponentResolvesThroughUseHtml() {
        myFixture.configureByFiles(
            "use_html/page_live.html.heex",
            "use_html/page_live.ex",
            "use_html/web.ex",
            "use_html/core_components.ex"
        )
        assertButtonResolves()
    }

    /** Same, with the tag inside a `~H` sigil embedded in the `use`-ing module instead. */
    fun testLocalComponentResolvesThroughUseHtmlInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("use_html/page_live.ex"),
                heexBody = myFixture.fixtureText("use_html/page_live.html.heex"),
                myFixture.fixtureText("use_html/web.ex"),
                myFixture.fixtureText("use_html/core_components.ex")
            )
        )
        assertButtonResolves()
    }

    /**
     * `<.link>` resolves through `use MyAppWeb, :html` -> a `def html` quote containing a nested
     * `use MyComponentLibrary` -> a `__using__/1` whose body ends in the list literal
     * `[conditional, imports]`, the shape of `Phoenix.Component.__using__/1`.
     */
    fun testLocalComponentResolvesThroughNestedUseInsideUseHtml() {
        myFixture.configureByFiles(
            "use_phoenix_component/page_live_link.html.heex",
            "use_phoenix_component/page_live_link.ex",
            "use_phoenix_component/web.ex",
            "use_phoenix_component/component_library.ex"
        )
        myFixture.assertGotoDeclarationLandsIn("link", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** Same chain, with the tag inside a `~H` sigil embedded in the `use`-ing module instead. */
    fun testLocalComponentResolvesThroughNestedUseInsideUseHtmlInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("use_phoenix_component/page_live_link.ex"),
                heexBody = myFixture.fixtureText("use_phoenix_component/page_live_link.html.heex"),
                myFixture.fixtureText("use_phoenix_component/web.ex"),
                myFixture.fixtureText("use_phoenix_component/component_library.ex")
            )
        )
        myFixture.assertGotoDeclarationLandsIn("link", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** Same chain, for a second component defined in the same nested-`use`d library. */
    fun testLocalComponentResolvesThroughNestedUseInsideUseHtmlSecondComponent() {
        myFixture.configureByFiles(
            "use_phoenix_component/page_live_live_title.html.heex",
            "use_phoenix_component/page_live_live_title.ex",
            "use_phoenix_component/web.ex",
            "use_phoenix_component/component_library.ex"
        )
        myFixture.assertGotoDeclarationLandsIn("live_title", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** Same second component, with the tag inside a `~H` sigil. */
    fun testLocalComponentResolvesThroughNestedUseInsideUseHtmlSecondComponentInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("use_phoenix_component/page_live_live_title.ex"),
                heexBody = myFixture.fixtureText("use_phoenix_component/page_live_live_title.html.heex"),
                myFixture.fixtureText("use_phoenix_component/web.ex"),
                myFixture.fixtureText("use_phoenix_component/component_library.ex")
            )
        )
        myFixture.assertGotoDeclarationLandsIn("live_title", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** Control: the same `def html` quote's `unquote(html_helpers())` import still resolves. */
    fun testLocalComponentResolvesThroughUnquotedImportBesideNestedUse() {
        myFixture.configureByFiles(
            "use_phoenix_component/page_live_button.html.heex",
            "use_phoenix_component/page_live_button.ex",
            "use_phoenix_component/web.ex",
            "use_phoenix_component/core_components.ex",
            "use_phoenix_component/component_library.ex"
        )
        assertButtonResolves()
    }

    /** Same control, with the tag inside a `~H` sigil. */
    fun testLocalComponentResolvesThroughUnquotedImportBesideNestedUseInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("use_phoenix_component/page_live_button.ex"),
                heexBody = myFixture.fixtureText("use_phoenix_component/page_live_button.html.heex"),
                myFixture.fixtureText("use_phoenix_component/web.ex"),
                myFixture.fixtureText("use_phoenix_component/core_components.ex"),
                myFixture.fixtureText("use_phoenix_component/component_library.ex")
            )
        )
        assertButtonResolves()
    }

    /** A `__using__/1` whose trailing list holds `quote` blocks directly rather than bound variables. */
    fun testLocalComponentResolvesThroughUseEndingInListOfQuotes() {
        myFixture.configureByFiles(
            "use_list_of_quotes/page_live.html.heex",
            "use_list_of_quotes/page_live.ex",
            "use_list_of_quotes/component_library.ex"
        )
        myFixture.assertGotoDeclarationLandsIn("link", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** A `__using__/1` whose last statement is `imports = quote do ... end`. */
    fun testLocalComponentResolvesThroughUseEndingInMatch() {
        myFixture.configureByFiles(
            "use_match_last/page_live.html.heex",
            "use_match_last/page_live.ex",
            "use_match_last/component_library.ex"
        )
        myFixture.assertGotoDeclarationLandsIn("link", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** `<MyAppWeb.CoreComponents.button>` resolves to the fully-qualified module's `def button/1`. */
    fun testRemoteComponentResolvesFullyQualified() {
        myFixture.configureByFiles("remote_qualified/page_live.html.heex", "remote_qualified/core_components.ex")
        assertButtonResolves()
    }

    /** Fully-qualified resolution needs no particular entrance, so the entrance module is synthetic. */
    fun testRemoteComponentResolvesFullyQualifiedInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = "defmodule Test do\nend",
                heexBody = myFixture.fixtureText("remote_qualified/page_live.html.heex"),
                myFixture.fixtureText("remote_qualified/core_components.ex")
            )
        )
        assertButtonResolves()
    }

    /** `<Widgets.button>` resolves through `alias MyAppWeb.CoreComponents, as: Widgets`. */
    fun testRemoteComponentResolvesThroughAlias() {
        myFixture.configureByFiles(
            "remote_alias/page_live.html.heex",
            "remote_alias/page_live.ex",
            "remote_alias/core_components.ex"
        )
        assertButtonResolves()
    }

    /** Same, with the tag inside a `~H` sigil embedded in the aliasing module instead. */
    fun testRemoteComponentResolvesThroughAliasInsideHSigil() {
        myFixture.configureHeexSigilHost(
            heexSigilModuleText(
                entranceModuleText = myFixture.fixtureText("remote_alias/page_live.ex"),
                heexBody = myFixture.fixtureText("remote_alias/page_live.html.heex"),
                myFixture.fixtureText("remote_alias/core_components.ex")
            )
        )
        assertButtonResolves()
    }

    /** The Phoenix 1.7+ `embed_templates` convention: `controllers/page_html/index.html.heex` -> `controllers/page_html.ex`. */
    fun testLocalComponentResolvesThroughEmbedTemplatesConvention() {
        myFixture.configureByFiles(
            "embed_templates/controllers/page_html/index.html.heex",
            "embed_templates/controllers/page_html.ex"
        )
        assertButtonResolves()
    }

    /** The Phoenix 1.6 `templates/` -> `views/` convention: `templates/page/index.html.heex` -> `views/page_view.ex`. */
    fun testLocalComponentResolvesThroughTemplatesViewsConvention() {
        myFixture.configureByFiles(
            "templates_views/templates/page/index.html.heex",
            "templates_views/views/page_view.ex"
        )
        assertButtonResolves()
    }

    /** `<.button>` inside a `~H` sigil resolves to `def button/1` in the same module, through the injection host. */
    fun testLocalComponentResolvesInsideHSigil() {
        myFixture.configureHeexSigilHost(
            """
                defmodule Test do
                  def render(assigns) do
                    ~H'''
                    <.but<caret>ton>Click</.button>
                    '''
                  end

                  def button(assigns) do
                    ~H''
                  end
                end
            """.trimIndent()
        )
        assertButtonResolves()
    }

    /**
     * The candidate list [HeexComponentTagNameProvider] offers is the view module's arity-1 defs,
     * and only those - tested directly against [HeexComponentResolver], not through the completion
     * popup: tag-name completion does not fire inside a `.heex` file even for built-in HTML tags,
     * a separate gap in the multi-root HTML wiring.
     */
    fun testLocalComponentDefinitionsAreArity1Defs() {
        myFixture.configureByFiles("completion/page_live.html.heex", "completion/page_live.ex")
        val tag = PsiTreeUtil.findChildOfType(
            myFixture.file.viewProvider.getPsi(com.intellij.lang.html.HTMLLanguage.INSTANCE),
            XmlTag::class.java
        )!!

        val names = HeexComponentResolver.localComponents(tag).map { it.name }

        assertTrue("names=$names", "button" in names)
        assertTrue("icon" in names)
        assertFalse("wrong_arity" in names)
    }

    /**
     * [HTMLInspectionSuppressor] suppresses `HtmlUnknownTag` for a resolved and an unresolved but
     * syntactically valid component tag, and not for a dotted name that is not component syntax.
     */
    fun testInspectionSuppressorIsNarrowedToComponentSyntax() {
        myFixture.configureByText(
            "suppressor_check.html.heex",
            "<.button>Click</.button><.nonexistent>Click</.nonexistent><Foo.Bar>Click</Foo.Bar>"
        )
        val suppressor = HTMLInspectionSuppressor()

        assertTrue(
            "a resolved component should be suppressed",
            suppressor.isSuppressedFor(startTagNameElement(".button"), XmlEntitiesInspection.TAG_SHORT_NAME)
        )
        assertTrue(
            "an unresolved but syntactically valid component should still be suppressed",
            suppressor.isSuppressedFor(startTagNameElement(".nonexistent"), XmlEntitiesInspection.TAG_SHORT_NAME)
        )
        assertFalse(
            "a dotted name that is not valid HEEx component syntax must not be suppressed",
            suppressor.isSuppressedFor(startTagNameElement("Foo.Bar"), XmlEntitiesInspection.TAG_SHORT_NAME)
        )
    }

    /**
     * `<.name>` compiles to the local call `name(assigns)`, so every arity-1 runtime function the module defines is a
     * local component, whichever form defines it; a macro or guard is not.
     */
    fun testLocalComponentsAreTheModulesArityOneRuntimeFunctions() {
        myFixture.configureByFiles("components_by_form/page_live.html.heex", "components_by_form/page_live.ex", "components_by_form/eex.ex")

        val htmlRoot = myFixture.file.viewProvider.getPsi(com.intellij.lang.html.HTMLLanguage.INSTANCE)
        val tag = PsiTreeUtil.findChildrenOfType(htmlRoot, XmlTag::class.java).first { it.name == ".button" }
        val offered = mutableListOf<com.intellij.codeInsight.lookup.LookupElement>()
        org.elixir_lang.heex.xml.HeexComponentTagNameProvider().addTagNameVariants(offered, tag, "")

        assertEquals(
            listOf(".button", ".private_component", ".delegated_component", ".rendered_component"),
            offered.map { it.lookupString }
        )

        val symbols = listOf(".delegated_component", ".rendered_component").map { name ->
            val componentTag = PsiTreeUtil.findChildrenOfType(htmlRoot, XmlTag::class.java).first { it.name == name }

            "$name -> ${HeexComponentResolver.resolveFunctionSymbols(componentTag).map { "${it.name}/${it.arity}" }}"
        }

        assertEquals(listOf(".delegated_component -> [delegated_component/1]", ".rendered_component -> [rendered_component/1]"), symbols)
    }

    /** A component defined under a compile-time `if` belongs to its module, so it is a local component too. */
    fun testAComponentUnderAnIfIsALocalComponent() {
        myFixture.configureByFiles("conditional_component/page_live.html.heex", "conditional_component/page_live.ex")
        val tag = PsiTreeUtil.findChildOfType(
            myFixture.file.viewProvider.getPsi(com.intellij.lang.html.HTMLLanguage.INSTANCE),
            XmlTag::class.java
        )!!

        assertEquals(listOf("button", "gated"), HeexComponentResolver.localComponents(tag).map { it.name })
    }

    /** A tag's declaration is the component's name, whichever form declares it, as a call's Go To lands on. */
    fun testAComponentsDeclarationIsItsNameInEveryForm() {
        myFixture.configureByFiles("components_by_form/page_live.html.heex", "components_by_form/page_live.ex", "components_by_form/eex.ex")

        val htmlRoot = myFixture.file.viewProvider.getPsi(com.intellij.lang.html.HTMLLanguage.INSTANCE)
        val declared = listOf(".button", ".delegated_component", ".rendered_component").map { name ->
            val tag = PsiTreeUtil.findChildrenOfType(htmlRoot, XmlTag::class.java).first { it.name == name }

            "$name -> ${HeexComponentResolver.resolveDeclaration(tag)?.text}"
        }

        assertEquals(
            listOf(".button -> button", ".delegated_component -> delegated_component", ".rendered_component -> :rendered_component"),
            declared
        )
    }

    private fun assertButtonResolves() {
        myFixture.assertGotoDeclarationLandsIn("button", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    private fun startTagNameElement(tagName: String): com.intellij.psi.PsiElement {
        val htmlRoot = myFixture.file.viewProvider.getPsi(com.intellij.lang.html.HTMLLanguage.INSTANCE)
        val tag = PsiTreeUtil.findChildrenOfType(htmlRoot, XmlTag::class.java).first { it.name == tagName }
        return tag.node.findChildByType(com.intellij.psi.xml.XmlTokenType.XML_NAME)?.psi ?: tag
    }
}
