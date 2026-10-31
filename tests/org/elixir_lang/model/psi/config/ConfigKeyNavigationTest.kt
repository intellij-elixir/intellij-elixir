package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.DumbModeTestUtils
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.code_insight.gtduNavigationInInjectionAtCaret
import org.elixir_lang.code_insight.searchTargetsAtCaret
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk

/** Ctrl-Click between an application env key's reads and the config lines that set it. */
class ConfigKeyNavigationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/config"

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
        enableExpander(testRootDisposable)
        myFixture.copyDirectoryToProject("navigation", "")
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    // read to settings

    fun testReadKeyGoesToEverySettingLine() {
        myFixture.caret(WORKER, "fetch_env!(:my_app, :ret<caret>ries)")

        assertEquals(listOf("config/config.exs:3", "config/dev.exs:3"), destinations())
    }

    fun testCompileEnvKeyPathHeadGoesToSetting() {
        myFixture.caret(WORKER, "[:gree<caret>ting, :nested]")

        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testEscapedTerminatorKeyInAnInjectedTemplateGoesToSetting() {
        myFixture.addFileToProject(
            TEMPLATES,
            """
            defmodule MyAppWeb.Templates do
              def greeting(assigns), do: ~E"<%= Application.get_env(:my_app, :\"greeting\") %>"
            end
            """.trimIndent(),
        )

        val destinations = myFixture.withSigilInjection {
            myFixture.caret(TEMPLATES, ":\\\"greet<caret>ing")
            destinations(myFixture.gtduNavigationInInjectionAtCaret())
        }

        assertEquals(listOf("config/config.exs:3"), destinations)
    }

    fun testReadThroughAnAliasOfApplicationInAnInjectedTemplateGoesToSetting() {
        assertEquals(
            listOf("config/config.exs:3"),
            injectedDestinations("alias Application, as: App", "<%= App.get_env(:my_app, :greeting) %>", ":gree<caret>ting"),
        )
    }

    fun testReadOfApplicationAliasedAwayInAnInjectedTemplateGoesNowhere() {
        assertEquals(
            emptyList<String>(),
            injectedDestinations("alias MyApp.Fake, as: Application", "<%= Application.get_env(:my_app, :greeting) %>", ":gree<caret>ting"),
        )
    }

    fun testAliasKeyInAnInjectedTemplateGoesToSetting() {
        assertEquals(
            listOf("config/config.exs:4"),
            injectedDestinations("alias MyApp.Repo", "<%= Application.get_env(:my_app, Repo) %>", "(:my_app, Re<caret>po)")
                .filter { it.startsWith("config/") },
        )
    }

    fun testAliasKeyAfterAnAliasInAnInjectedTemplateGoesToNoSetting() {
        assertEquals(
            emptyList<String>(),
            injectedDestinations("alias MyApp.Repo", "<% alias Other.Repo %><%= Application.get_env(:my_app, Repo) %>", "(:my_app, Re<caret>po)")
                .filter { it.startsWith("config/") },
        )
    }

    fun testReadAfterTheTemplateAliasesApplicationAwayInAnInjectedTemplateGoesNowhere() {
        assertEquals(
            emptyList<String>(),
            injectedDestinations("", "<% alias MyApp.Fake, as: Application %><%= Application.get_env(:my_app, :greeting) %>", ":gree<caret>ting"),
        )
    }

    fun testReadThroughTheTemplatesAliasOfApplicationInAnInjectedTemplateGoesNowhere() {
        assertEquals(
            emptyList<String>(),
            injectedDestinations("", "<% alias Application, as: App %><%= App.get_env(:my_app, :greeting) %>", ":gree<caret>ting"),
        )
    }

    fun testReadThroughTheTemplatesImportOfApplicationInAnInjectedTemplateGoesNowhere() {
        assertEquals(
            emptyList<String>(),
            injectedDestinations("", "<% import Application %><%= get_env(:my_app, :greeting) %>", ":gree<caret>ting"),
        )
    }

    fun testReadKeyGoesToASettingThroughAnAliasOfConfig() {
        aliasedConfig()
        myFixture.caret(ALIASED_CONFIG_READER, ":alia<caret>sed")

        assertEquals(listOf("config/runtime.exs:4"), destinations())
    }

    fun testPipedReadKeyGoesToSetting() {
        myFixture.caret(WORKER, "Application.get_env(:gree<caret>ting)")

        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testAnyAppReadGoesToTheSettingsOfEveryApp() {
        myFixture.caret(WORKER, "get_env(@app, :lev<caret>el)")

        assertEquals(listOf("config/config.exs:6", "config/dev.exs:4"), destinations())
    }

    fun testLibraryAppKeyGoesToSetting() {
        myFixture.caret(WORKER, ":time<caret>out)")

        assertEquals(listOf("config/config.exs:7"), destinations())
    }

    fun testSettingInUncountedFileIsNotATarget() {
        myFixture.caret(WORKER, "get_env(:my_app, :gree<caret>ting)")

        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testUnsetKeyGoesNowhere() {
        myFixture.caret(WORKER, ":gre<caret>ting)")

        assertEquals(GtduNavigation.None, myFixture.gtduNavigationAtCaret())
    }

    fun testReadKeyInDumbModeGoesNowhereThenToSetting() {
        myFixture.caret(WORKER, "get_env(:my_app, :gree<caret>ting)")

        assertEquals(
            GtduNavigation.None,
            DumbModeTestUtils.computeInDumbModeSynchronously(project) { myFixture.gtduNavigationAtCaret() },
        )
        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testReadThroughAnAliasOfApplicationGoesToSetting() {
        myFixture.caret(ALIASED, "App.get_env(:my_app, :gree<caret>ting)")

        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testPipedKeyReadGoesToSetting() {
        myFixture.caret(ALIASED, ":gree<caret>ting |> :application.get_env()")

        assertEquals(listOf("config/config.exs:3"), destinations())
    }

    fun testAHeadDefiningAnImportedReadsNameGoesNowhere() {
        myFixture.caret("lib/my_app/imported.ex", "def get_env(:my_app, :gree<caret>ting)")

        assertEquals(GtduNavigation.None, myFixture.gtduNavigationAtCaret())
    }

    fun testAnotherModulesGetEnvKeyGoesNowhere() {
        myFixture.caret(ALIASED, "Fake.get_env(:my_app, :gree<caret>ting)")

        assertEquals(GtduNavigation.None, myFixture.gtduNavigationAtCaret())
    }

    // setting to reads

    fun testConfig3KeyThroughAnAliasOfConfigShowsReads() {
        aliasedConfig()
        myFixture.caret(RUNTIME, ":alia<caret>sed")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "aliased"))
    }

    /** A setting through an alias of `Config`, in `config/runtime.exs`, and a read of it. */
    private fun aliasedConfig() {
        myFixture.addFileToProject(RUNTIME, "import Config\n\nalias Config, as: C\nC.config(:my_app, :aliased, 1)\n")
        myFixture.addFileToProject(
            ALIASED_CONFIG_READER,
            "defmodule MyApp.AliasedConfigReader do\n  def read, do: Application.get_env(:my_app, :aliased)\nend\n",
        )
    }

    fun testConfig2KeyShowsReads() {
        myFixture.caret(CONFIG, "gree<caret>ting: \"hello\"")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "greeting"))
    }

    fun testConfig3AtomKeyShowsReads() {
        myFixture.caret(DEV, ":ret<caret>ries, 5")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "retries"))
    }

    fun testConfig3AtomKeyNamingAModuleShowsReads() {
        myFixture.caret(CONFIG, ":\"Elixir.MyApp.Wo<caret>rker\"")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "Elixir.MyApp.Worker"))
    }

    fun testPipedConfigKeyShowsReads() {
        myFixture.caret(CONFIG, ":pip<caret>ed, 1")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "piped"))
    }

    fun testParenthesisedConfig2KeyShowsReads() {
        myFixture.caret(CONFIG, "parenthe<caret>sised: 2")

        assertShowsReadsOf(ConfigKeySymbol("my_app", "parenthesised"))
    }

    // alias and __MODULE__

    fun testAliasKeyReadOffersModuleAndSettings() {
        myFixture.caret(WORKER, "MyApp.Re<caret>po)")

        assertEquals(listOf("config/config.exs:4", "lib/my_app/repo.ex:1"), destinations())
    }

    fun testAliasKeySettingOffersModuleAndConfigKey() {
        myFixture.caret(CONFIG, "MyApp.Re<caret>po, pool_size")

        assertEquals(listOf("config/config.exs:4", "lib/my_app/repo.ex:1"), destinations())
    }

    fun testModuleKeyReadOffersNoSetting() {
        myFixture.caret(REPO, "__MOD<caret>ULE__")

        assertEquals(emptyList<String>(), destinations().filter { it.startsWith("config/") })
    }

    fun testAliasKeyInDumbModeOffersNoSettingThenBoth() {
        myFixture.caret(WORKER, "MyApp.Re<caret>po)")

        assertEquals(
            emptyList<String>(),
            DumbModeTestUtils.computeInDumbModeSynchronously(project) { destinations() }.filter { it.startsWith("config/") },
        )
        assertEquals(listOf("config/config.exs:4", "lib/my_app/repo.ex:1"), destinations())
    }

    private fun assertShowsReadsOf(symbol: ConfigKeySymbol) {
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(listOf(symbol), myFixture.searchTargetsAtCaret(project))
    }

    /** Where Ctrl-Click goes, as `path:line`, sorted; empty where it goes nowhere or shows usages. */
    private fun destinations(navigation: GtduNavigation = myFixture.gtduNavigationAtCaret()): List<String> =
        when (navigation) {
            is GtduNavigation.GotoDeclaration -> navigation.targets.map { target ->
                val destination = target.destination!!
                val file = destination.containingFile
                val line = StringUtil.offsetToLineNumber(file.text, destination.textRange.startOffset) + 1

                "${file.virtualFile.path.substringAfter("/src/")}:$line"
            }.sorted()
            GtduNavigation.None, is GtduNavigation.ShowUsages -> emptyList()
        }

    /**
     * [destinations] from [caret] in `~E"[template]"`, injected, in a function whose body runs [setup] before the
     * sigil.
     */
    private fun injectedDestinations(setup: String, template: String, caret: String): List<String> {
        myFixture.addFileToProject(
            TEMPLATES,
            "defmodule MyAppWeb.Templates do\n  def render(assigns) do\n    $setup\n\n    ~E\"$template\"\n  end\nend\n",
        )

        return myFixture.withSigilInjection {
            myFixture.caret(TEMPLATES, caret)
            destinations(myFixture.gtduNavigationInInjectionAtCaret())
        }
    }

    private companion object {
        const val ALIASED = "lib/my_app/aliased.ex"
        const val ALIASED_CONFIG_READER = "lib/my_app/aliased_config_reader.ex"
        const val CONFIG = "config/config.exs"
        const val DEV = "config/dev.exs"
        const val REPO = "lib/my_app/repo.ex"
        const val RUNTIME = "config/runtime.exs"
        const val TEMPLATES = "lib/my_app_web/templates.ex"
        const val WORKER = "lib/my_app/worker.ex"
    }
}
