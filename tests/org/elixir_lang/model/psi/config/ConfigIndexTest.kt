package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiFile
import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.mix.ProjectKeywords

/**
 * Each file's config index entries: a setting, an unread or an import as [ConfigIndexing.decided] decides it from the
 * module its call reaches, in the file's expansion on the leg's SDK; a read as its candidate.
 */
class ConfigIndexTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

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

    fun testConfigWithoutConfigImportSetsNoKeyAndIsUnread() =
        assertEntries("config :my_app, retries: 3\nconfig app, :retries, 3\n", "AnyApp Unread Unknown `config app, :retries, 3`")

    fun testConfigWithoutConfigImportEndingInAnAtomSetsNoKey() = assertEntries("config :my_app, retries: 3\n:ok\n")

    fun testAReturnedKeywordListIsUnreadForAnyApp() =
        assertEntries("x = 1\n[my_app: [retries: x]]\n", "AnyApp Unread Unknown `[my_app: [retries: x]]`")

    fun testAReturnedValueThatMayBeAListIsUnreadForAnyApp() =
        assertEntries("Keyword.new(my_app: [retries: 3])\n", "AnyApp Unread Unknown `Keyword.new(my_app: [retries: 3])`")

    fun testAReturnedCallGivenADoKeywordIsUnreadForAnyApp() =
        assertEntries(
            "Keyword.get([settings: [dep: [key: :other]]], :settings, do: :ignored)\n",
            "AnyApp Unread Unknown `Keyword.get([settings: [dep: [key: :other]]], :settings, do: :ignored)`"
        )

    fun testAReturnedForIsUnreadForAnyApp() =
        assertEntries(
            "for {app, env} <- [dep: [key: :other]], do: {app, env}\n",
            "AnyApp Unread Unknown `for {app, env} <- [dep: [key: :other]], do: {app, env}`"
        )

    fun testAReturnedWithWhoseClauseMayNotMatchIsUnreadForAnyApp() =
        assertEntries(
            "with :ok <- [dep: [key: :other]], do: :ok\n",
            "AnyApp Unread Unknown `with :ok <- [dep: [key: :other]], do: :ok`"
        )

    /** A `use` gives what its module's `__using__/1` expands to, which may be a keyword list. */
    fun testAReturnedUseIsUnreadForAnyApp() =
        assertEntries("import Config\nuse MyApp.Settings\n", "AnyApp Unread Unknown `use MyApp.Settings`")

    fun testAReturnedValueNotAListIsRead() {
        assertEntries("import Config\n")
        assertEntries("import Config\n\n:ok\n")
        assertEntries("defmodule M do\nend\n")
        assertEntries("")
    }

    fun testAReturnedListBesideAConfigCallIsUnreadForAnyApp() =
        assertConfig(
            "if false, do: config(:my_app, retries: 3)\n[other: [a: 1]]",
            "App(my_app) Set Atom(retries) `retries`",
            "AnyApp Unread Unknown `[other: [a: 1]]`"
        )

    fun testAReturnedValueBesideAConfigCallThatMayNotRunIsUnreadForAnyApp() =
        assertConfig(
            "if false, do: config(:dep, :unused, true)\nKeyword.new(dep: [key: :other])",
            "App(dep) Set Atom(unused) `:unused`",
            "AnyApp Unread Unknown `Keyword.new(dep: [key: :other])`"
        )

    fun testAReturnedValueAfterATopLevelConfigCallIsRead() =
        assertConfig("config :my_app, retries: 3\nKeyword.new(other: [a: 1])", "App(my_app) Set Atom(retries) `retries`")

    fun testAReturnedListAfterATopLevelConfigCallIsRead() =
        assertConfig("config :my_app, retries: 3\n[other: [a: 1]]", "App(my_app) Set Atom(retries) `retries`")

    fun testAReturnedListAfterAPipedConfigCallIsRead() =
        assertConfig(":my_app |> config(retries: 3)\n[other: [a: 1]]", "App(my_app) Set Atom(retries) `retries`")

    fun testAReturnedConditionalWithAConfigCallInOneBranchAndAListInTheOtherIsUnreadForAnyApp() {
        assertConfig(
            "c = System.get_env(\"C\") == \"1\"\nif c, do: config(:my_app, retries: 3), else: [other: [a: 1]]",
            "App(my_app) Set Atom(retries) `retries`",
            "AnyApp Unread Unknown `if c, do: config(:my_app, retries: 3), else: [other: [a: 1]]`"
        )
        assertConfig(
            "c = System.get_env(\"C\") == \"1\"\nif c do\n  config :my_app, retries: 3\nelse\n  [other: [a: 1]]\nend",
            "App(my_app) Set Atom(retries) `retries`",
            "AnyApp Unread Unknown `if c do\n  config :my_app, retries: 3\nelse\n  [other: [a: 1]]\nend`"
        )
        assertConfig(
            "case System.get_env(\"C\") do\n  nil -> config :my_app, retries: 3\n  _ -> Keyword.new(other: [a: 1])\nend",
            "App(my_app) Set Atom(retries) `retries`",
            "AnyApp Unread Unknown `case System.get_env(\"C\") do\n  nil -> config :my_app, retries: 3\n  _ -> Keyword.new(other: [a: 1])\nend`"
        )
    }

    fun testAReturnedConditionalWhoseBranchesEachRunAConfigCallIsRead() =
        assertConfig(
            "if config_env() == :prod do\n  config :my_app, retries: 9\n  [other: [a: 1]]\nelse\n  config :my_app, retries: 1\nend",
            "App(my_app) Set Atom(retries) `retries`",
            "App(my_app) Set Atom(retries) `retries`"
        )

    fun testAReturnedVariableNamedConfigIsUnreadForAnyApp() =
        assertEntries("config = [my_app: [retries: 3]]\nconfig\n", "AnyApp Unread Unknown `config`")

    fun testConfigUnderANestedConfigImportIsSet() =
        assertEntries("if true do\n  import Config\n  config :my_app, retries: 3\nend\n", "App(my_app) Set Atom(retries) `retries`")

    fun testAReturnedConditionalBesideAConfigCallIsRead() =
        assertConfig("if config_env() == :prod do\n  config :my_app, retries: 9\nend", "App(my_app) Set Atom(retries) `retries`")

    fun testAnEnvWriteIsUnreadForItsApp() =
        assertConfig(
            "Application.put_env(:my_app, :greeting, \"hi\")\n" +
                "Application.put_env(app, :greeting, \"hi\", persistent: true)\n" +
                "Application.delete_env(:my_app, :greeting)\n" +
                "Application.put_all_env(my_app: [a: 1], other: [b: 2])\n" +
                "Application.put_all_env(config)\n" +
                "Mix.Config.persist(my_app: [a: 1])\n" +
                ":application.set_env(:my_app, :k, 1)\n" +
                ":application.unset_env(:my_app, :k)\n" +
                ":application.set_env([{:my_app, [k: 1]}])",
            "App(my_app) Unread Unknown `Application.put_env(:my_app, :greeting, \"hi\")`",
            "AnyApp Unread Unknown `Application.put_env(app, :greeting, \"hi\", persistent: true)`",
            "App(my_app) Unread Unknown `Application.delete_env(:my_app, :greeting)`",
            "App(my_app) Unread Unknown `my_app`",
            "App(other) Unread Unknown `other`",
            "AnyApp Unread Unknown `config`",
            "App(my_app) Unread Unknown `my_app`",
            "App(my_app) Unread Unknown `:application.set_env(:my_app, :k, 1)`",
            "App(my_app) Unread Unknown `:application.unset_env(:my_app, :k)`",
            "AnyApp Unread Unknown `[{:my_app, [k: 1]}]`"
        )

    fun testAnEnvWriteThroughAnAliasOrAnImportIsUnreadForItsApp() =
        assertConfig(
            "alias Application, as: A\nA.put_env(:my_app, :flag, true, persistent: true)\n" +
                "alias :application, as: E\nE.unset_env(:my_app, :k)\nSystem.put_env(\"A\", \"b\")\n" +
                "import Application\nput_env(:my_app, :k, 1)",
            "App(my_app) Unread Unknown `A.put_env(:my_app, :flag, true, persistent: true)`",
            "App(my_app) Unread Unknown `E.unset_env(:my_app, :k)`",
            "App(my_app) Unread Unknown `put_env(:my_app, :k, 1)`"
        )

    fun testAConfigThroughAnAliasOfConfigIsSet() =
        assertEntries("alias Config, as: C\nC.config(:my_app, retries: 3)\n", "App(my_app) Set Atom(retries) `retries`")

    fun testACallOfAModuleTheExpansionCantNameIsUnreadForItsApp() =
        assertConfig(
            "module = String.to_atom(System.fetch_env!(\"M\"))\nmodule.put_env(:my_app, :k, 1)\nmodule.config(:other, k: 1)\n:ok",
            "App(my_app) Unread Unknown `module.put_env(:my_app, :k, 1)`",
            "App(other) Unread Atom(k) `k`"
        )

    fun testImportConfigIsAnImport() =
        assertConfig(
            "import_config \"#{config_env()}.exs\"\nimport_config \"envs/dev.exs\"\nimport_config path",
            "AnyApp Import Unknown `\"#{config_env()}.exs\"`",
            "AnyApp Import Unknown `\"envs/dev.exs\"`",
            "AnyApp Import Unknown `path`"
        )

    fun testNonLiteralOptionsSetNoKeyAndAreUnread() =
        assertConfig(
            "config :my_app, options\nconfig :my_app, Keyword.merge([retries: 1], greeting: \"hi\")",
            "App(my_app) Unread Unknown `options`",
            "App(my_app) Unread Unknown `Keyword.merge([retries: 1], greeting: \"hi\")`"
        )

    fun testConfig3KeyNotWrittenAsAnAtomOrAliasIsUnread() =
        assertConfig(
            "key = :retries\nconfig :my_app, key, 5\nconfig :my_app, __MODULE__, 1",
            "App(my_app) Unread Unknown `key`",
            "App(my_app) Unread Unknown `__MODULE__`"
        )

    fun testConfig2OptionsWithAnEntryNotAnAtomKeyAreUnreadBesideTheKeysTheySet() =
        assertConfig(
            "key = \"greeting\"\nconfig :my_app, [{:retries, 1}, timeout: 2]\nconfig :my_app, [1, size: 5]\n" +
                "config :my_app, \"#{key}\": 3, other: 4",
            "App(my_app) Set Atom(timeout) `timeout`",
            "App(my_app) Unread Unknown `[{:retries, 1}, timeout: 2]`",
            "App(my_app) Set Atom(size) `size`",
            "App(my_app) Unread Unknown `[1, size: 5]`",
            "App(my_app) Set Atom(other) `other`",
            "App(my_app) Unread Unknown `\"#{key}\": 3, other: 4`"
        )

    fun testAppNotWrittenAsALiteralIsUnreadForAnyApp() =
        assertConfig(
            "app = :my_app\nconfig app, retries: 1\nconfig app, :greeting, \"hi\"",
            "AnyApp Unread Unknown `app`",
            "AnyApp Unread Unknown `app`"
        )

    fun testEmptyOptionsAreRead() = assertConfig("config :my_app, []")

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
        val actual = data.flatMap { (key, entries) -> entries.mapNotNull { render(file, key, it, text) } }.sorted()

        assertEquals(expected.sorted(), actual)
    }

    private fun render(file: PsiFile, key: ConfigIndexKey, entry: ConfigEntry, text: String): String? =
        when (entry) {
            is ConfigEntry.KeyEntry ->
                render(file, entry)?.let { kind -> "${render(key)} $kind ${render(entry.key)} `${entry.range.substring(text)}`" }
            is ConfigEntry.ProjectEntry -> "${render(key)} app=${entry.app} configPath=${render(entry.configPath)}"
        }

    /** [entry]'s kind: a read's as indexed, any other's as decided; `null` where it is none. */
    private fun render(file: PsiFile, entry: ConfigEntry.KeyEntry): String? =
        when (val kind = entry.kind) {
            is ConfigEntry.Kind.Read -> "Read(${render(kind.module.qualifier)} ${kind.module.name}/${kind.module.arity})"
            else ->
                when (ConfigIndexing.element(file, entry.range)?.let { ConfigIndexing.decided(kind, it) }) {
                    ConfigIndexing.Decided.SET -> "Set"
                    ConfigIndexing.Decided.UNREAD -> "Unread"
                    ConfigIndexing.Decided.IMPORT -> "Import"
                    null -> null
                }
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

    private fun render(qualifier: CallModule.Qualifier): String =
        when (qualifier) {
            CallModule.Qualifier.Written(ELIXIR_APPLICATION) -> "Application"
            CallModule.Qualifier.Written(ERLANG_APPLICATION) -> ":application"
            is CallModule.Qualifier.Written, CallModule.Qualifier.Other -> "Other"
            CallModule.Qualifier.Unqualified -> "Unqualified"
        }

    private companion object {
        const val ELIXIR_APPLICATION = "Elixir.Application"
        const val ERLANG_APPLICATION = "application"
    }
}
