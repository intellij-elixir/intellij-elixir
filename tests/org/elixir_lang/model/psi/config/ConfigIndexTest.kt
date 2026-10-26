package org.elixir_lang.model.psi.config

import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.ProjectKeywords

class ConfigIndexTest : PlatformTestCase() {
    // Settings

    fun testConfig2KeywordKeysAreSet() =
        assertConfig(
            "config :my_app, greeting: \"hello\", retries: 3",
            "App(my_app) Set Atom(greeting) `greeting`",
            "App(my_app) Set Atom(retries) `retries`"
        )

    fun testBracketedConfig2KeywordKeysAreSet() =
        assertConfig("config :my_app, [greeting: \"hello\"]", "App(my_app) Set Atom(greeting) `greeting`")

    fun testConfig3AtomKeyIsSet() = assertConfig("config :my_app, :retries, 5", "App(my_app) Set Atom(retries) `:retries`")

    fun testConfig3AliasKeyIsSetAsWritten() =
        assertConfig("config :my_app, MyApp.Repo, pool_size: 10", "App(my_app) Set Alias `MyApp.Repo`")

    fun testQuotedKeyIsSetUnderItsAtom() =
        assertConfig(
            "config :\"my_app\", \"my-key\": 1\nconfig :my_app, :\"esc\\x61ped\", 2",
            "App(my_app) Set Atom(my-key) `\"my-key\"`",
            "App(my_app) Set Atom(escaped) `:\"esc\\x61ped\"`"
        )

    fun testConfigInsideIfIsSet() =
        assertConfig(
            "if config_env() == :prod do\n  config :my_app, retries: 9\nend",
            "App(my_app) Set Atom(retries) `retries`"
        )

    fun testConfigInsideCaseIsSet() =
        assertConfig(
            "case config_env() do\n  :prod ->\n    config :my_app, :retries, 9\n\n  _ ->\n    :ok\nend",
            "App(my_app) Set Atom(retries) `:retries`"
        )

    fun testUseMixConfigFileSets() =
        assertEntries("use Mix.Config\n\nconfig :my_app, retries: 3\n", "App(my_app) Set Atom(retries) `retries`")

    fun testImportMixConfigFileSets() =
        assertEntries("import Mix.Config\n\nconfig :my_app, retries: 3\n", "App(my_app) Set Atom(retries) `retries`")

    fun testFileWithoutConfigImportSetsNothing() = assertEntries("config :my_app, retries: 3\n")

    fun testNonLiteralOptionsSetNothing() =
        assertConfig("config :my_app, options\nconfig :my_app, Keyword.merge([retries: 1], greeting: \"hi\")")

    fun testQualifiedConfigConfigIsSet() =
        assertConfig(
            "Config.config(:my_app, retries: 1)\nMix.Config.config(:my_app, :greeting, \"hi\")",
            "App(my_app) Set Atom(retries) `retries`",
            "App(my_app) Set Atom(greeting) `:greeting`"
        )

    fun testQualifiedConfigConfigSetsWithoutAnImport() =
        assertEntries(
            "Config.config(:my_app, retries: 1)\nMix.Config.config(:my_app, :greeting, \"hi\")\n",
            "App(my_app) Set Atom(retries) `retries`",
            "App(my_app) Set Atom(greeting) `:greeting`"
        )

    fun testDefinitionHeadIsNotASetting() =
        assertConfig("defmodule MyApp.Setter do\n  def config(:my_app, retries: 3), do: nil\nend")

    fun testParenthesizedConfigAppAndKeyAreLiterals() =
        assertConfig("config((:my_app), (:retries), 3)", "App(my_app) Set Atom(retries) `:retries`")

    fun testPipedConfigAppIsSet() =
        assertConfig(
            ":my_app |> config(:retries, 3)\n:my_app |> config(greeting: \"hi\")",
            "App(my_app) Set Atom(retries) `:retries`",
            "App(my_app) Set Atom(greeting) `greeting`"
        )

    fun testParenthesisedConfig2KeywordListIsSet() =
        assertConfig("config(:my_app, ([retries: 3]))", "App(my_app) Set Atom(retries) `retries`")

    // Reads, one per function and arity

    fun testGetEnv2IsARead() =
        assertRead("Application.get_env(:my_app, :greeting)", "App(my_app) Read(Application get_env/2) Atom(greeting) `:greeting`")

    fun testParenthesizedReadAppAndKeyAreLiterals() =
        assertRead(
            "Application.get_env((:my_app), (:greeting))\nApplication.compile_env(:my_app, [(:level), :sub])",
            "App(my_app) Read(Application get_env/2) Atom(greeting) `:greeting`",
            "App(my_app) Read(Application compile_env/2) Atom(level) `:level`"
        )

    fun testGetEnv3IsARead() =
        assertRead("Application.get_env(:my_app, :greeting, \"hi\")", "App(my_app) Read(Application get_env/3) Atom(greeting) `:greeting`")

    fun testFetchEnvIsARead() =
        assertRead("Application.fetch_env(:my_app, :greeting)", "App(my_app) Read(Application fetch_env/2) Atom(greeting) `:greeting`")

    fun testFetchEnvBangIsARead() =
        assertRead("Application.fetch_env!(:my_app, :greeting)", "App(my_app) Read(Application fetch_env!/2) Atom(greeting) `:greeting`")

    fun testCompileEnv2IsARead() =
        assertRead("Application.compile_env(:my_app, :greeting)", "App(my_app) Read(Application compile_env/2) Atom(greeting) `:greeting`")

    fun testCompileEnv3IsARead() =
        assertRead("Application.compile_env(:my_app, :greeting, \"hi\")", "App(my_app) Read(Application compile_env/3) Atom(greeting) `:greeting`")

    fun testCompileEnvBang2IsARead() =
        assertRead("Application.compile_env!(:my_app, :greeting)", "App(my_app) Read(Application compile_env!/2) Atom(greeting) `:greeting`")

    fun testCompileEnv4IsARead() =
        assertRead(
            "Application.compile_env(__ENV__, :my_app, :greeting, \"hi\")",
            "App(my_app) Read(Application compile_env/4) Atom(greeting) `:greeting`"
        )

    fun testCompileEnvBang3IsARead() =
        assertRead("Application.compile_env!(__ENV__, :my_app, :greeting)", "App(my_app) Read(Application compile_env!/3) Atom(greeting) `:greeting`")

    fun testGetAllEnvIsAnAllRead() =
        assertRead("Application.get_all_env(:my_app)", "App(my_app) Read(Application get_all_env/1) All `Application.get_all_env(:my_app)`")

    fun testErlangGetEnv1IsAnAnyAppRead() = assertRead(":application.get_env(:greeting)", "AnyApp Read(:application get_env/1) Atom(greeting) `:greeting`")

    fun testErlangGetEnv2IsARead() =
        assertRead(":application.get_env(:my_app, :greeting)", "App(my_app) Read(:application get_env/2) Atom(greeting) `:greeting`")

    fun testErlangGetEnv3IsARead() =
        assertRead(":application.get_env(:my_app, :greeting, \"hi\")", "App(my_app) Read(:application get_env/3) Atom(greeting) `:greeting`")

    fun testErlangGetAllEnvIsAnAllRead() =
        assertRead(":application.get_all_env(:my_app)", "App(my_app) Read(:application get_all_env/1) All `:application.get_all_env(:my_app)`")

    fun testErlangGetAllEnv0IsNotARead() = assertRead(":application.get_all_env()")

    // Read shapes

    fun testCompileEnvKeyPathHeadIsTheKey() =
        assertRead(
            "Application.compile_env(:my_app, [MyApp.Repo, :pool_size])\nApplication.compile_env!(:my_app, [:greeting, :to])",
            "App(my_app) Read(Application compile_env/2) Alias `MyApp.Repo`",
            "App(my_app) Read(Application compile_env!/2) Atom(greeting) `:greeting`"
        )

    fun testPipedReadTakesThePipedApp() =
        assertRead(":my_app |> Application.get_env(:greeting)", "App(my_app) Read(Application get_env/2) Atom(greeting) `:greeting`")

    fun testAttributeAppIsAnyApp() =
        assertRead("Application.get_env(@app, :greeting)", "AnyApp Read(Application get_env/2) Atom(greeting) `:greeting`")

    fun testVariableKeyIsUnknown() = assertRead("Application.get_env(:my_app, key)", "App(my_app) Read(Application get_env/2) Unknown `key`")

    fun testAliasKeyIsReadAsWritten() =
        assertRead(
            "Application.get_env(:my_app, Repo)\nApplication.compile_env(:my_app, MyApp.Repo)",
            "App(my_app) Read(Application get_env/2) Alias `Repo`",
            "App(my_app) Read(Application compile_env/2) Alias `MyApp.Repo`"
        )

    fun testModuleKeyIsReadAsWritten() =
        assertRead("Application.get_env(:my_app, __MODULE__)", "App(my_app) Read(Application get_env/2) Module `__MODULE__`")

    fun testOtherQualifiedShapeIsACandidate() =
        assertRead(
            "App.get_env(:my_app, :greeting)\n@m.fetch_env!(:my_app, :retries)\nElixir.App.get_all_env(:my_app)",
            "App(my_app) Read(Other get_env/2) Atom(greeting) `:greeting`",
            "App(my_app) Read(Other fetch_env!/2) Atom(retries) `:retries`",
            "App(my_app) Read(Other get_all_env/1) All `Elixir.App.get_all_env(:my_app)`"
        )

    fun testAbsoluteApplicationShapeIsWrittenApplication() =
        assertRead(
            "Elixir.Application.get_all_env(:my_app)\n:\"Elixir.Application\".get_env(:my_app, :greeting)",
            "App(my_app) Read(Application get_all_env/1) All `Elixir.Application.get_all_env(:my_app)`",
            "App(my_app) Read(Application get_env/2) Atom(greeting) `:greeting`"
        )

    fun testUnqualifiedShapeIsACandidate() =
        assertRead(
            "get_env(:my_app, :greeting)\n:my_app |> compile_env(:level)",
            "App(my_app) Read(Unqualified get_env/2) Atom(greeting) `:greeting`",
            "App(my_app) Read(Unqualified compile_env/2) Atom(level) `:level`"
        )

    fun testDefinitionHeadIsNotARead() =
        assertEntries(
            """
            defmodule MyApp.Reader do
              def get_env(:my_app, :greeting), do: Application.get_env(:my_app, :greeting)
              defp fetch_env(:my_app, :retries) when true, do: nil
              defmacro compile_env(:my_app, :level), do: nil
              defmacrop get_all_env(:my_app), do: nil
              def with_default(x \\ Application.get_env(:my_app, :default_arg)), do: x
            end
            """.trimIndent(),
            "App(my_app) Read(Application get_env/2) Atom(default_arg) `:default_arg`",
            "App(my_app) Read(Application get_env/2) Atom(greeting) `:greeting`",
            fileName = "reader.ex"
        )

    fun testBothNonLiteralIsNotARead() =
        assertRead("Application.get_env(@app, key)\nApplication.get_all_env(@app)")

    fun testPutEnvIsNotARead() = assertRead("Application.put_env(:my_app, :greeting, \"hi\")")

    fun testDeleteEnvIsNotARead() = assertRead("Application.delete_env(:my_app, :greeting)")

    fun testPutAllEnvIsNotARead() = assertRead("Application.put_all_env(my_app: [greeting: \"hi\"])")

    // Project entries

    fun testMixExsRecordsAppAndConfigPath() =
        assertEntries(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :my_app, config_path: "../../config/config.exs"]
              end
            end
            """.trimIndent(),
            "Project app=my_app configPath=Written(../../config/config.exs)",
            fileName = "mix.exs"
        )

    private fun assertConfig(body: String, vararg expected: String) =
        assertEntries("import Config\n\n$body\n", *expected)

    private fun assertRead(body: String, vararg expected: String) =
        assertEntries("defmodule MyApp.Reader do\n  @app :my_app\n\n  def read(key) do\n$body\n  end\nend\n", *expected, fileName = "reader.ex")

    private fun assertEntries(text: String, vararg expected: String, fileName: String = "config.exs") {
        val file = myFixture.configureByText(fileName, text)
        val data = FileBasedIndex.getInstance().getFileData(ConfigIndexing.NAME, file.virtualFile, project)
        val actual = data.flatMap { (key, entries) -> entries.map { render(key, it, text) } }.sorted()

        assertEquals(expected.sorted(), actual)
    }

    private fun render(key: ConfigIndexKey, entry: ConfigEntry, text: String): String =
        when (entry) {
            is ConfigEntry.KeyEntry -> "${render(key)} ${render(entry.kind)} ${render(entry.key)} `${entry.range.substring(text)}`"
            is ConfigEntry.ProjectEntry -> "${render(key)} app=${entry.app} configPath=${render(entry.configPath)}"
        }

    private fun render(key: ConfigIndexKey): String =
        when (key) {
            is ConfigIndexKey.App -> "App(${key.atom})"
            ConfigIndexKey.AnyApp -> "AnyApp"
            ConfigIndexKey.Project -> "Project"
        }

    private fun render(key: WrittenKey): String =
        when (key) {
            is WrittenKey.Atom -> "Atom(${key.name})"
            WrittenKey.Alias -> "Alias"
            WrittenKey.Module -> "Module"
            WrittenKey.Unknown -> "Unknown"
            WrittenKey.All -> "All"
        }

    private fun render(configPath: ProjectKeywords.ConfigPath): String =
        when (configPath) {
            is ProjectKeywords.ConfigPath.Written -> "Written(${configPath.path})"
            ProjectKeywords.ConfigPath.Unknown -> "Unknown"
        }

    private fun render(kind: ConfigEntry.Kind): String =
        when (kind) {
            ConfigEntry.Kind.Set -> "Set"
            is ConfigEntry.Kind.Read -> "Read(${render(kind.module.qualifier)} ${kind.module.name}/${kind.module.arity})"
        }

    private fun render(qualifier: ReadModule.Qualifier): String =
        when (qualifier) {
            ReadModule.Qualifier.APPLICATION -> "Application"
            ReadModule.Qualifier.ERLANG_APPLICATION -> ":application"
            ReadModule.Qualifier.OTHER -> "Other"
            ReadModule.Qualifier.UNQUALIFIED -> "Unqualified"
        }
}
