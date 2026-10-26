package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.expander.edit
import org.elixir_lang.model.psi.config.disableMixDepsCheck
import java.io.File

/** A key a config file sets for the project's own app, and that nothing reads. */
class UnreadConfigKeyTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
        myFixture.enableInspections(UnreadConfigKey::class.java)
        myFixture.addFileToProject("mix.exs", MIX_EXS)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testKeySetAndNeverReadIsReported() {
        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testLiteralReadClears() {
        reads("def read, do: Application.get_env(:my_app, :retries)")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testAnyAppReadClears() {
        reads("def read(app), do: Application.get_env(app, :retries)")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testGetAllEnvClearsEveryKeyOfTheApp() {
        reads("def read, do: Application.get_all_env(:my_app)")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3, timeout: 5"))
    }

    fun testVariableKeyReadClearsEveryKeyOfTheApp() {
        reads("def read(key), do: Application.get_env(:my_app, key)")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3, timeout: 5"))
    }

    fun testCompileEnvKeyPathHeadClears() {
        reads("@count Application.compile_env(:my_app, [:retries, :count])")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: [count: 3]"))
    }

    fun testLibraryReadClears() {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(LIBRARY).absoluteFile)!!
        val library = WriteAction.computeAndWait<Library, Throwable> {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary("library_app").also { library ->
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.SOURCES)
                    commit()
                }
            }
        }
        ModuleRootModificationUtil.addDependency(myFixture.module, library)

        try {
            assertEquals(emptyList<String>(), reported("config :my_app, generators: [binary_id: true]"))
        } finally {
            ModuleRootModificationUtil.removeDependency(myFixture.module, library)
            WriteAction.runAndWait<Throwable> { LibraryTablesRegistrar.getInstance().getLibraryTable(project).removeLibrary(library) }
        }
    }

    fun testReadThroughAnAliasOfAnErlangModuleClears() {
        reads(
            """
            alias :crypto, as: Crypto

            def read, do: Application.get_env(:my_app, Crypto)
            """
        )

        assertEquals(emptyList<String>(), reported("config :my_app, crypto: true"))
    }

    fun testReadThroughAnAliasOfApplicationClears() {
        reads(
            """
            alias Application, as: App

            def read, do: App.get_env(:my_app, :retries)
            """
        )

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testReadOfApplicationAliasedAwayDoesNotClear() {
        myFixture.addFileToProject("lib/my_app/fake.ex", "defmodule MyApp.Fake do\n  def get_env(_app, _key), do: nil\nend\n")
        reads(
            """
            alias MyApp.Fake, as: Application

            def read, do: Application.get_env(:my_app, :retries)
            """
        )

        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testReadStopsClearingOnceAnotherFilesUseMacroAliasesApplicationAway() {
        myFixture.addFileToProject("lib/my_app/fake.ex", "defmodule MyApp.Fake do\n  def get_env(_app, _key), do: nil\nend\n")
        val using = myFixture.addFileToProject("lib/my_app/using.ex", "defmodule MyApp.Using do\n  defmacro __using__(_), do: :ok\nend\n")
        val reader = myFixture.addFileToProject(
            "lib/my_app/reader.ex",
            "defmodule MyApp.Reader do\n  use MyApp.Using\n\n  def read, do: Application.get_env(:my_app, :retries)\nend\n",
        )
        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))

        edit(project, using, "defmodule MyApp.Using do\n  defmacro __using__(_), do: quote(do: alias(MyApp.Fake, as: Application))\nend\n")

        assertEquals(listOf("retries"), reported())
        // Held to here, so its PSI and the reads classified on it outlive the first pass.
        assertTrue(reader.isValid)
    }

    fun testBothNonLiteralClearsNothing() {
        reads("def read(app, key), do: Application.get_env(app, key)")

        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testPutEnvDoesNotClear() {
        reads("def write, do: Application.put_env(:my_app, :retries, 5)")

        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testLibraryAppKeyIsNotJudged() {
        assertEquals(emptyList<String>(), reported("config :library_app, timeout: 5"))
    }

    fun testAliasWrittenKeyIsNotJudged() {
        assertEquals(emptyList<String>(), reported("config :my_app, MyApp.Repo, pool_size: 10"))
    }

    fun testAtomModuleNameKeyIsNotJudged() {
        assertEquals(emptyList<String>(), reported("config :my_app, :\"Elixir.MyApp.Repo\", pool_size: 10"))
    }

    fun testSubKeyIsNotJudged() {
        reads("def read, do: Application.get_env(:my_app, :http)")

        assertEquals(emptyList<String>(), reported("config :my_app, :http, port: 4000"))
    }

    fun testSettingInAnUncountedFileIsNotJudged() {
        myFixture.addFileToProject("priv/settings.exs", "import Config\n\nconfig :my_app, retries: 3\n")
        myFixture.configureFromTempProjectFile("priv/settings.exs")

        assertEquals(emptyList<String>(), reported())
    }

    fun testReadOnlyInAHeexFileClears() {
        myFixture.addFileToProject("lib/my_app_web/page.html.heex", "<p><%= Application.get_env(:my_app, :retries) %></p>\n")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testReadOnlyInAHeexFileBraceClears() {
        myFixture.addFileToProject("lib/my_app_web/page.html.heex", "<p>{Application.get_env(:my_app, :retries)}</p>\n")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testReadOnlyInAnEexFileClears() {
        myFixture.addFileToProject("lib/my_app_web/page.html.eex", "<p><%= Application.get_env(:my_app, :retries) %></p>\n")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testReadOnlyInALeexFileClears() {
        myFixture.addFileToProject("lib/my_app_web/page.html.leex", "<p><%= Application.get_env(:my_app, :retries) %></p>\n")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testOwnAppWithErlangSourceIsNotJudged() {
        myFixture.addFileToProject("src/my_app_config.erl", "-module(my_app_config).\n")

        assertEquals(emptyList<String>(), reported("config :my_app, retries: 3"))
    }

    fun testSameAppWithoutErlangSourceIsReported() {
        myFixture.addFileToProject("src/my_app_config.ex", "defmodule MyApp.Config do\nend\n")

        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testErlangSourceUnderDepsOrBuildIsNotTheApps() {
        myFixture.addFileToProject("deps/library_app/src/library_app.erl", "-module(library_app).\n")
        myFixture.addFileToProject("_build/dev/lib/library_app/src/library_app.erl", "-module(library_app).\n")

        assertEquals(listOf("retries"), reported("config :my_app, retries: 3"))
    }

    fun testUmbrellaJudgesOnlyTheAppWithoutErlangSource() {
        umbrellaApp("erlang_app")
        myFixture.addFileToProject("apps/erlang_app/src/erlang_app_config.erl", "-module(erlang_app_config).\n")
        umbrellaApp("elixir_app")

        assertEquals(listOf("retries"), reported("config :erlang_app, timeout: 5\nconfig :elixir_app, retries: 3"))
    }

    fun testInspectionIsOnByDefaultAsAWeakWarning() {
        val registration = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass == UnreadConfigKey::class.java.name }

        assertTrue(registration.enabledByDefault)
        assertEquals("WEAK WARNING", registration.level)
    }

    /** An umbrella app at `apps/<app>` whose config is the root's. */
    private fun umbrellaApp(app: String) {
        val module = app.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        myFixture.addFileToProject(
            "apps/$app/mix.exs",
            """
            defmodule $module.MixProject do
              use Mix.Project

              def project do
                [app: :$app, version: "0.1.0", config_path: "../../config/config.exs"]
              end
            end
            """.trimIndent()
        )
    }

    /** Writes a module in `lib/` whose body is [body]. */
    private fun reads(body: String) {
        myFixture.addFileToProject("lib/my_app/reader.ex", "defmodule MyApp.Reader do\n${body.trimIndent().prependIndent("  ")}\nend\n")
    }

    /** The keys reported in `config/config.exs` holding [settings]. */
    private fun reported(settings: String): List<String> {
        myFixture.addFileToProject("config/config.exs", "import Config\n\n$settings\n")
        myFixture.configureFromTempProjectFile("config/config.exs")

        return reported()
    }

    /** The keys this inspection reports in the open file. */
    private fun reported(): List<String> =
        myFixture.doHighlighting()
            .filter { it.inspectionToolId == SHORT_NAME }
            .map { it.text }

    private companion object {
        const val SHORT_NAME = "UnreadConfigKey"
        const val LIBRARY = "testData/org/elixir_lang/model/psi/config/unread_library"
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
