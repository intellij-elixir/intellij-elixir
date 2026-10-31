package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.inspection.UnreadConfigKey
import org.elixir_lang.model.psi.config.ConfigIndexing.Decided
import org.elixir_lang.psi.call.Call

/**
 * What the config readers answer with the Experimental setting off, where the file's expansion would name a module, and
 * with it on. Each test states both: the setting off is what users get until they turn it on, so the wrong answers are
 * pinned here as well as the missing ones.
 */
class ConfigFlagOffTest : PlatformTestCase() {
    private var savedProperty: String? = null
    private var configured = false

    override fun setUp() {
        super.setUp()
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
        myFixture.addFileToProject("mix.exs", MIX_EXS)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    // Settings

    fun testAnAliasKeySettingHasNoAtom() {
        config("config :my_app, MyApp.Repo, x: 1")

        assertOffOn(off = Pair(0, true), on = Pair(1, false)) {
            Pair(ConfigSettings.of(project, "my_app", "Elixir.MyApp.Repo").size, ConfigSettings.unread(project, "my_app", configScope()))
        }
    }

    fun testASettingThroughAnAttributeIsUnread() {
        config("defmodule M do\n  @mod Config\n\n  def f, do: @mod.config(:my_app, :k, 1)\nend")

        assertOffOn(off = Pair(0, 1), on = Pair(1, 0)) {
            Pair(written(Decided.SET).size, written(Decided.UNREAD).size)
        }
    }

    fun testAnAliasOfConfigIsNotASetting() {
        config("alias Config, as: C\nC.config(:my_app, :k, 1)\n:ok", import = false)

        assertOffOn(off = 0, on = 1) { ConfigSettings.of(project, "my_app", "k").size }
    }

    fun testAnAliasOfConfigAsTheLastStatementIsAReturnedCandidate() {
        config("alias Config, as: C\nC.config(:my_app, :k, 1)", import = false)

        assertOffOn(off = true, on = false) { ConfigSettings.unread(project, "other_app", configScope()) }
    }

    fun testANestedImportOfConfigIsNotASetting() {
        config("if true do\n  import Config\n  config :my_app, :k, 1\nend", import = false)

        assertOffOn(off = Pair(0, true), on = Pair(1, false)) {
            Pair(ConfigSettings.of(project, "my_app", "k").size, ConfigSettings.unread(project, "other_app", configScope()))
        }
    }

    fun testAnEnvWriteThroughAnAliasIsNotAWrite() {
        config("alias Application, as: A\nA.put_env(:my_app, :flag, true)")

        assertOffOn(off = 0, on = 1) { written(Decided.UNREAD).size }
    }

    fun testAnEnvWriteThroughAnAliasAsTheLastStatementIsAReturnedCandidate() {
        config("alias Application, as: A\nA.put_env(:my_app, :flag, true)")

        assertOffOn(off = true, on = false) { ConfigSettings.unread(project, "other_app", configScope()) }
    }

    fun testABuildNameThroughAnAliasIsNotABuildName() {
        config("alias Mix, as: M\nimport_config \"#{M.env()}.exs\"")

        assertOffOn(off = true, on = false) { ConfigFiles.unread(project, configScope()) }
    }

    fun testAnImportConfigThroughAnAliasOfConfigIsNotAnImport() {
        config("alias Config, as: C\nC.import_config(\"x.exs\")\n:ok", import = false)

        assertOffOn(off = 0, on = 1) { ConfigIndexing.written(project, ConfigIndexKey.AnyApp, configScope(), Decided.IMPORT).size }
    }

    fun testAnImportConfigThroughAnAliasAsTheLastStatementIsAReturnedCandidate() {
        config("alias Config, as: C\nC.import_config(\"x.exs\")", import = false)

        assertOffOn(off = 1, on = 0) { ConfigIndexing.written(project, ConfigIndexKey.AnyApp, configScope(), Decided.UNREAD).size }
    }

    // Reads

    fun testAnAliasKeyReadGoesToTheModuleOnly() {
        config("config :my_app, MyApp.Repo, x: 1")
        lib("repo.ex", "defmodule MyApp.Repo do\nend\n")
        lib("worker.ex", "defmodule MyApp.Worker do\n  def repo, do: Application.get_env(:my_app, MyApp.Repo)\nend\n")
        myFixture.caret("lib/my_app/worker.ex", "MyApp.Re<caret>po)")

        assertOffOn(off = listOf("lib/my_app/repo.ex:1"), on = listOf("config/config.exs:3", "lib/my_app/repo.ex:1")) { destinations() }
    }

    fun testAModuleKeyReadIsDropped() {
        config("config :my_app, MyApp.Repo, x: 1")
        lib("repo.ex", "defmodule MyApp.Repo do\n  def env, do: Application.get_env(:my_app, __MODULE__)\nend\n")

        assertOffOn(off = 0, on = 1) { reads("Elixir.MyApp.Repo") }
    }

    fun testATemplateAliasKeyReadIsDropped() {
        config("config :my_app, MyApp.Repo, x: 1")
        lib("page.ex", "defmodule MyApp.Page do\n  def render(assigns) do\n    ~H\"\"\"\n    <%= Application.get_env(:my_app, MyApp.Repo) %>\n    \"\"\"\n  end\nend\n")

        assertOffOn(off = 0, on = 1) { reads("Elixir.MyApp.Repo") }
    }

    fun testANestedImportOfApplicationIsNotARead() {
        lib("reader.ex", "defmodule MyApp.Reader do\n  import Application\n\n  def read, do: get_env(:my_app, :k)\nend\n")
        lib("other.ex", "defmodule MyApp.Other do\n  def read do\n    import Application\n    get_env(:my_app, :j)\n  end\nend\n")

        assertOffOn(off = listOf("j", "k"), on = emptyList()) { reported("config :my_app, k: 1, j: 2") }
    }

    fun testAnAliasOfApplicationIsNotARead() {
        lib("reader.ex", "defmodule MyApp.Reader do\n  alias Application, as: App\n\n  def read, do: App.get_env(:my_app, :k)\nend\n")

        assertOffOn(off = listOf("k"), on = emptyList()) { reported("config :my_app, k: 1") }
    }

    fun testApplicationAliasedAwayIsARead() {
        lib("fake.ex", "defmodule Foo.Application do\n  def get_env(_app, _key), do: nil\nend\n")
        lib("reader.ex", "defmodule MyApp.Reader do\n  alias Foo.Application\n\n  def read, do: Application.get_env(:my_app, :k)\nend\n")

        assertOffOn(off = emptyList(), on = listOf("k")) { reported("config :my_app, k: 1") }
    }

    fun testAnImportThatExcludesTheNameIsARead() {
        lib("reader.ex", "import Application, only: [fetch_env: 2]\n\ndefmodule MyApp.Reader do\n  def read, do: get_env(:my_app, :k)\nend\n")

        assertOffOn(off = emptyList(), on = listOf("k")) { reported("config :my_app, k: 1") }
    }

    fun testAReadThroughAnAttributeIsNotARead() {
        lib("reader.ex", "defmodule MyApp.Reader do\n  @mod Application\n\n  def read, do: @mod.get_env(:my_app, :k)\nend\n")

        assertOffOn(off = listOf("k"), on = emptyList()) { reported("config :my_app, k: 1") }
    }

    fun testApplicationAliasedAwayOffersKeys() {
        lib("fake.ex", "defmodule MyApp.Fake do\n  def get_env(_app, _key), do: nil\nend\n")
        val read = read("alias MyApp.Fake, as: Application\n  def f, do: Application.get_env(:my_app, :ret)")

        assertOffOn(off = true, on = false) { Read.slotAt(read.element) is Slot.Key }
    }

    // Names

    fun testEnvAtomIsNullForAnAlias() {
        val read = read("def f, do: Application.get_env(:my_app, MyApp.Repo)")

        assertOffOn(off = null, on = "Elixir.MyApp.Repo") { KeyAtom.of(read.element) }
    }

    // What the gate does not move

    fun testAnAtomKeyStillGoesToItsSetting() {
        config("config :my_app, :k, 1")
        lib("worker.ex", "defmodule MyApp.Worker do\n  def read, do: Application.get_env(:my_app, :k)\nend\n")
        myFixture.caret("lib/my_app/worker.ex", ":<caret>k)")

        assertOffOn(off = listOf("config/config.exs:3"), on = listOf("config/config.exs:3")) { destinations() }
    }

    fun testAQualifiedApplicationReadStillClearsItsKey() {
        lib("reader.ex", "defmodule MyApp.Reader do\n  def read, do: Application.get_env(:my_app, :k)\nend\n")

        assertOffOn(off = emptyList(), on = emptyList()) { reported("config :my_app, k: 1") }
    }

    fun testATopLevelImportConfigStillSets() {
        config("config :my_app, k: 1")

        assertOffOn(off = 1, on = 1) { ConfigSettings.of(project, "my_app", "k").size }
    }

    fun testAReadThroughAVariableIsNotARead() {
        lib("reader.ex", "defmodule MyApp.Reader do\n  def read do\n    mod = Application\n    mod.get_env(:my_app, :k)\n  end\nend\n")

        assertOffOn(off = listOf("k"), on = listOf("k")) { reported("config :my_app, k: 1") }
    }

    fun testATemplateReadThroughAnAttributeIsUndecided() {
        config("config :my_app, k: 1")
        lib("page.ex", "defmodule MyApp.Page do\n  @mod Application\n\n  def render(assigns) do\n    ~H\"\"\"\n    <%= @mod.get_env(:my_app, :k) %>\n    \"\"\"\n  end\nend\n")

        assertOffOn(off = listOf(false), on = listOf(false)) {
            ConfigReads.of(project, "my_app", "k", GlobalSearchScope.allScope(project)).map { it.decided }.toList()
        }
    }

    // Fixtures

    private fun config(text: String, import: Boolean = true): PsiFile =
        myFixture.addFileToProject("config/config.exs", if (import) "import Config\n\n$text\n" else "$text\n")

    private fun lib(name: String, text: String): PsiFile = myFixture.addFileToProject("lib/my_app/$name", text)

    private fun configScope(): GlobalSearchScope = ConfigFiles.scope(project)

    private fun written(kind: Decided): List<ConfigIndexing.Written> =
        ConfigIndexing.written(project, ConfigIndexKey.App("my_app"), configScope(), kind)

    private fun reads(key: String): Int = ConfigReads.of(project, "my_app", key, GlobalSearchScope.allScope(project)).count()

    /** The single read in a module with [body]. */
    private fun read(body: String): Read =
        PsiTreeUtil.findChildrenOfType(lib("typing.ex", "defmodule MyApp.Typing do\n  $body\nend\n"), Call::class.java)
            .mapNotNull(Read::of)
            .single()

    /** The keys the inspection reports in `config/config.exs`. */
    private fun reported(settings: String): List<String> {
        myFixture.enableInspections(UnreadConfigKey::class.java)
        if (!configured) config(settings)
        configured = true
        myFixture.configureFromTempProjectFile("config/config.exs")

        return myFixture.doHighlighting().filter { it.inspectionToolId == "UnreadConfigKey" }.map { it.text }.sorted()
    }

    private fun destinations(): List<String> =
        when (val navigation = myFixture.gtduNavigationAtCaret()) {
            is GtduNavigation.GotoDeclaration -> navigation.targets.map { target ->
                val destination = target.destination!!
                val file = destination.containingFile
                val line = StringUtil.offsetToLineNumber(file.text, destination.textRange.startOffset) + 1

                "${file.virtualFile.path.substringAfter("/src/")}:$line"
            }.sorted()
            GtduNavigation.None, is GtduNavigation.ShowUsages -> emptyList()
        }

    /** [observe]'s result with the setting off, then with it on. */
    private fun <T> assertOffOn(off: T, on: T, observe: () -> T) {
        assertEquals("the setting off", off, observe())

        val enabled = Disposer.newDisposable()
        enableExpander(enabled)
        try {
            assertEquals("the setting on", on, observe())
        } finally {
            Disposer.dispose(enabled)
        }
    }

    private companion object {
        val MIX_EXS =
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :my_app, version: "0.1.0"]
              end
            end
            """.trimIndent()
    }
}
