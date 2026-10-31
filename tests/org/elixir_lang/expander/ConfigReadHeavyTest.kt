package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.system.OS
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.model.psi.config.ConfigFiles
import java.util.concurrent.Callable

/**
 * A project's config read as `Config.Reader.read_imports!/2` reads it. Each expected table and file list is what that
 * gave for the fixture on Elixir 1.20.4 and 1.11.4, unless a row says otherwise.
 */
class ConfigReadHeavyTest : ConfigHeavyTestCase() {
    fun testImportConfigOfTheEnvFile() {
        val configEnv = project(
            CONFIG to "import Config\n\nconfig :app, k: 1, nested: [a: 1]\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n\nconfig :app, k: 2, nested: [b: 2]\n",
        )

        assertEquals("[{app,[{k,2},{nested,[{a,1},{b,2}]}]}] [config/dev.exs, config/config.exs]", read(configEnv))

        val mixEnv = project(
            CONFIG to "import Config\n\nconfig :app, k: 1\nimport_config \"#{Mix.env()}.exs\"\n",
            DEV to "import Config\n\nconfig :app, k: 2\n",
        )

        assertEquals("[{app,[{k,2}]}] [config/dev.exs, config/config.exs]", read(mixEnv))
    }

    fun testAFileExistsGuardedImportIsFollowed() {
        val present = project(CONFIG to GUARDED, DEV to "import Config\n\nconfig :app, k: 2\n")

        assertEquals("[{app,[{k,2}]}] [config/dev.exs, config/config.exs]", read(present))

        val absent = project(CONFIG to GUARDED)

        assertEquals("[{app,[{k,1}]}] [config/config.exs]", read(absent))
    }

    /** Past no guard, the missing file's `File.Error` ends the read, and so the build. */
    fun testAnUnguardedImportOfAMissingFileLeavesEveryKeyUnknown() {
        val root = project(CONFIG to "import Config\n\nconfig :app, k: 1\nimport_config \"#{Mix.env()}.exs\"\n")

        assertEquals("unknown", read(root))
    }

    fun testARelativeImportFromANestedApp() {
        val root = project(
            CONFIG to "import Config\n\nconfig :shared, k: 1\n",
            "apps/a/mix.exs" to MIX_EXS,
            "apps/a/lib/reader.ex" to READER,
            "apps/a/config/config.exs" to "import Config\n\nconfig :app, k: 2\nimport_config \"../../../config/config.exs\"\n",
        )

        assertEquals(
            "[{app,[{k,2}]},{shared,[{k,1}]}] [config/config.exs, apps/a/config/config.exs]",
            read(root, reader = "apps/a/lib/reader.ex"),
        )
    }

    /** Mix doesn't change directory for `import_config`: an imported file's relative paths are from the reading project. */
    fun testAnImportedFilesRelativePathsAreFromTheReadingProject() {
        val root = project(
            CONFIG to "import Config\nimport_config \"../shared/shared.exs\"\n",
            "config/enabled" to "",
            "shared/mix.exs" to MIX_EXS,
            "shared/shared.exs" to
                "import Config\nconfig :app, k: File.exists?(\"config/enabled\"), rel: Path.relative_to_cwd(__DIR__) == \"shared\"\n",
        )

        assertEquals(
            "[{app,[{k,true},{rel,true}]}] [shared/shared.exs, config/config.exs]",
            read(root),
        )
    }

    /** The same file read by two projects reads each one's directory, each with its own table. */
    fun testAConfigFileTwoProjectsReadIsReadFromEachProject() {
        val root = project(
            CONFIG to "import Config\nconfig :app, here: File.exists?(\"marker\")\n",
            "apps/a/mix.exs" to mixExs("config_path: \"../../config/config.exs\""),
            "apps/a/lib/reader.ex" to READER,
            "apps/a/marker" to "",
        )

        assertEquals("[{app,[{here,false}]}] [config/config.exs]", read(root))
        assertEquals("[{app,[{here,true}]}] [config/config.exs]", read(root, reader = "apps/a/lib/reader.ex"))
    }

    /** The file's statements run once, so a `config` that reads what it writes sees only what came before. */
    fun testAConfigReadingItsOwnKeyRunsOnce() {
        val root = project(CONFIG to "import Config\nconfig :app, k: [2 | (Config.read_config(:app)[:k] || [])]\n")

        assertEquals(if (CONFIG_READ_CONFIG.isSufficient(legLevel())) "[{app,[{k,[2]}]}] [config/config.exs]" else "unknown", read(root))
    }

    /** A result after an import takes its value from the last statement, without importing again. */
    fun testAKeywordListResultAfterAnImportIsTheConfig() {
        val root = project(CONFIG to "import Config\nimport_config \"dev.exs\"\n[app: [k: 1]]\n", DEV to "import Config\n")

        assertEquals("[{app,[{k,1}]}] [config/dev.exs, config/config.exs]", read(root))
    }

    /** Only the type of an imported file's raise is known, so a `rescue` above it can't read the rest. */
    fun testARescuedErrorFromAnImportedFileIsDeclined() {
        val root = project(CONFIG to rescuing("dev.exs"), DEV to "import Config\nimport_config \"missing.exs\"\n")

        assertEquals("declined", read(root))
    }

    fun testARescuedMissingImportReadsItsFields() {
        assertEquals("[{app,[{k,true}]}] [config/config.exs]", read(project(CONFIG to rescuing("missing.exs"))))
    }

    fun testAMissingImportInAnImportedFileLeavesEveryKeyUnknown() {
        val root = project(CONFIG to "import Config\nimport_config \"dev.exs\"\n", DEV to "import Config\nimport_config \"missing.exs\"\n")

        assertEquals("unknown", read(root))
    }

    private fun rescuing(file: String) =
        "import Config\ntry do\n  import_config \"$file\"\nrescue\n  e in File.Error -> config :app, k: match?(%{reason: :enoent}, e)\nend\n"

    /** Elixir compiles this module as the read reaches it, under the read's env; the plugin's copy wouldn't be, so it declines. */
    fun testAModuleInAFileTheReadImportsIsDeclined() {
        val root = project(
            CONFIG to "import Config\nimport_config \"../lib/h.ex\"\nconfig :app, k: H.value()\n",
            "lib/h.ex" to HELPER_BY_ENV,
        )

        assertEquals("declined", read(root, env = "test"))
    }

    /** Elixir raises, as Mix reads config before it loads the project's code. */
    fun testAProjectModuleTheReadDoesNotImportIsDeclined() {
        val root = project(CONFIG to "import Config\nconfig :app, k: H.value()\n", "lib/h.ex" to HELPER_BY_ENV)

        assertEquals("declined", read(root, env = "test"))
    }

    fun testAModuleInAnImportedExsFileIsDeclined() {
        val root = project(
            CONFIG to "import Config\nimport_config \"helper.exs\"\nconfig :app, k: H.value()\n",
            "config/helper.exs" to HELPER_BY_ENV,
        )

        assertEquals("declined", read(root, env = "test"))
    }

    fun testAModuleTheReadsOwnFileDefinesIsRead() {
        assertEquals(
            "[{app,[{k,t}]}] [config/config.exs]",
            read(project(CONFIG to HELPER_BY_ENV + "config :app, k: H.value()\n"), env = "test"),
        )
    }

    fun testASubfolderImportUnderProd() {
        val root = project(
            CONFIG to "import Config\n\nconfig :app, k: 1\nimport_config \"#{config_env()}.exs\"\n",
            "config/prod.exs" to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n\nconfig :app, Repo, pool_size: 10\n",
        )

        assertEquals(
            "[{app,[{k,1},{'Elixir.Repo',[{pool_size,10}]}]}] [config/prod/database.exs, config/prod.exs, config/config.exs]",
            read(root, "prod"),
        )
    }

    /** Each raises `ArgumentError` in Elixir: a file already read is recursion, whether or not it is still running. */
    fun testARecursiveImportLeavesEveryKeyUnknown() {
        val self = project(CONFIG to "import Config\n\nconfig :app, k: 1\nimport_config \"config.exs\"\n")
        val mutual = project(CONFIG to "import Config\n\nimport_config \"dev.exs\"\n", DEV to "import Config\n\nimport_config \"config.exs\"\n")
        val sibling = project(
            CONFIG to "import Config\n\nimport_config \"dev.exs\"\nimport_config \"dev.exs\"\n",
            DEV to "import Config\n\nconfig :app, k: 2\n",
        )

        assertEquals("self", "unknown", read(self))
        assertEquals("mutual", "unknown", read(mutual))
        assertEquals("a sibling twice", "unknown", read(sibling))
    }

    /** Before 1.18 the imported call is a `CompileError`, which ends the read. */
    fun testReadConfigReadsAnEarlierWrite() {
        val root = project(
            CONFIG to "import Config\n\nconfig :app, k: 1\nimport_config \"dev.exs\"\n",
            DEV to "import Config\n\nconfig :app, k2: read_config(:app)[:k]\n",
        )

        assertEquals(
            if (CONFIG_READ_CONFIG.isSufficient(legLevel())) "[{app,[{k,1},{k2,1}]}] [config/dev.exs, config/config.exs]" else "unknown",
            read(root),
        )
    }

    fun testUseMixConfigWithImportAndWildcard() {
        val plain = project(
            CONFIG to "use Mix.Config\n\nconfig :app, k: 1\nimport_config \"#{Mix.env()}.exs\"\n",
            DEV to "use Mix.Config\n\nconfig :app, k: 2\n",
        )

        assertEquals("[{app,[{k,2}]}] [config/dev.exs, config/config.exs]", read(plain))

        val wildcard = project(
            CONFIG to "use Mix.Config\n\nconfig :app, k: 1\nimport_config \"extra/*.exs\"\n",
            "config/extra/a.exs" to "use Mix.Config\n\nconfig :app, a: 1\n",
            "config/extra/b.exs" to "use Mix.Config\n\nconfig :app, b: 2, k: 3\n",
        )

        assertEquals("[{app,[{a,1},{b,2},{k,3}]}] [config/extra/b.exs, config/extra/a.exs, config/config.exs]", read(wildcard))

        val noMatch = project(CONFIG to "use Mix.Config\n\nconfig :app, k: 1\nimport_config \"extra/*.exs\"\n")

        assertEquals("[{app,[{k,1}]}] [config/config.exs]", read(noMatch))

        val dotFile = project(
            CONFIG to "use Mix.Config\n\nconfig :app, k: 1\nimport_config \".*.exs\"\n",
            "config/.a.exs" to "use Mix.Config\n\nconfig :app, a: 1\n",
        )

        assertEquals("Path.wildcard/1 skips a name starting with a dot", "[{app,[{k,1}]}] [config/config.exs]", read(dotFile))
    }

    /** `{a,{b,c}}` raises in `filelib`, and `[` matches nothing there: neither is a pattern the run reads. */
    fun testAWildcardThatCantBeCompiledDeclines() {
        for (pattern in listOf("{a,{b,c}}.exs", "[.exs")) {
            assertEquals(pattern, "declined", read(project(CONFIG to "use Mix.Config\n\nimport_config \"$pattern\"\n")))
        }
    }

    /** A path the SDK's machine resolves can't be looked up from the IDE, so what is there isn't known. */
    fun testAnAbsolutePathUnderAnSdkOnAnotherOsDeclines() {
        val root = project(CONFIG to "import Config\n", "config/elsewhere.exs" to "import Config\n\nconfig :app, k: 2\n")
        val reader = root.findFileByRelativePath("lib/reader.ex")!!
        val elsewhere = "${root.path}/config/elsewhere.exs"
        val other = legInputs.copy(os = if (OS.CURRENT == OS.Windows) OS.Linux else OS.Windows)

        for ((what, config) in listOf(
            "File.exists?/1" to "import Config\n\nconfig :app, k: File.exists?(\"$elsewhere\")\n",
            "import_config" to "import Config\n\nimport_config \"$elsewhere\"\n",
            "a wildcard" to "use Mix.Config\n\nimport_config \"${root.path}/config/else*.exs\"\n",
        )) {
            edit(project, psi(root, CONFIG), config)

            assertTrue(what, table(reader, inputs = other)!!.declined)
        }
    }

    fun testAKeywordListResultIsTheConfig() {
        assertEquals("[{app,[{k,1}]}] [config/config.exs]", read(project(CONFIG to "[app: [k: 1]]\n")))
        assertEquals("a result not a list", "[] [config/config.exs]", read(project(CONFIG to ":ok\n")))
    }

    /** Elixir has the value here: the plugin doesn't read it, so nothing in the result is known. */
    fun testAKeywordListResultWithAnUnknownValueIsUnknown() {
        assertEquals("unknown", read(project(CONFIG to "[app: [k: Application.get_env(:app, :k)]]\n")))
        assertEquals("a read that declined", "declined", read(project(CONFIG to "[app: [k: System.get_env(\"HOME\")]]\n")))
    }

    /** Elixir reads `[]`, as a `defmodule`'s value is no list; the plugin doesn't know that value, so the read is declined. */
    fun testAnUnknownLastValueWithNoConfigIsDeclined() {
        assertEquals("declined", read(project(CONFIG to "defmodule Helper do\nend\n")))
    }

    fun testAnImportedFileStartsWithAnEmptyBinding() {
        val root = project(
            CONFIG to "import Config\n\nx = 1\nconfig :app, k: x\nimport_config \"dev.exs\"\n",
            DEV to "import Config\n\nconfig :app, seen: Keyword.has_key?(binding(), :x)\n",
        )

        assertEquals("[{app,[{k,1},{seen,false}]}] [config/dev.exs, config/config.exs]", read(root))
    }

    fun testAKeywordValueDeepMerges() {
        val root = project(
            CONFIG to
                "import Config\n\nconfig :app, Repo, pool_size: 10, log: false\n" +
                "config :app, nested: [a: [x: 1, y: 2], b: 1], list: [1, 2], scalar: [a: 1]\n" +
                "config :app, Repo, pool_size: 2\nconfig :app, nested: [a: [y: 3]], list: [3], scalar: 4\n",
        )

        assertEquals(
            "[{app,[{'Elixir.Repo',[{log,false},{pool_size,2}]},{nested,[{b,1},{a,[{x,1},{y,3}]}]},{list,[3]},{scalar,4}]}] [config/config.exs]",
            read(root),
        )
    }

    /** Mix loads `runtime.exs` after compiling. */
    fun testRuntimeExsIsNotRead() {
        val root = project(CONFIG to "import Config\n\nconfig :app, k: 1\n", "config/runtime.exs" to "import Config\n\nconfig :app, k: 2\n")

        assertEquals("[{app,[{k,1}]}] [config/config.exs]", read(root))
    }

    /** Mix reads nothing where the default path is missing. */
    fun testNoConfigFileIsAnEmptyTable() {
        assertEquals("[] []", read(project()))
    }

    /** Mix raises `File.Error` for any other missing path, so the build fails. */
    fun testAMissingWrittenConfigPathLeavesEveryKeyUnknown() {
        assertEquals("unknown", read(project("mix.exs" to mixExs("config_path: \"other/config.exs\""))))
    }

    /** Mix expands `config_path:`, so an absolute one is read, and its directory's files count as config. */
    fun testAnAbsoluteConfigPath() {
        val root = project("conf/config.exs" to "import Config\n\nconfig :app, k: 1\n")

        edit(project, psi(root, "mix.exs"), mixExs("config_path: \"${root.path}/conf/config.exs\""))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("[{app,[{k,1}]}] [conf/config.exs]", read(root))
        assertTrue(
            ReadAction.nonBlocking(Callable { ConfigFiles.scope(project).contains(root.findFileByRelativePath("conf/config.exs")!!) }).executeSynchronously(),
        )
    }

    /** A read of the env inside a config file's own read isn't known (Elixir has `nil` here, as the app isn't loaded). */
    fun testCompileEnvAtAConfigFilesTopIsConfigured() {
        for (read in listOf("Application.get_env(:app, :k)", "Application.compile_env(:app, :k)")) {
            val root = project(CONFIG to "import Config\n\nconfig :app, k: 1\nconfig :app, read: $read\n")

            assertEquals(read, "[{app,[{k,1},{read,#Unknown}]}] [config/config.exs]", read(root))
        }
    }

    private companion object {
        const val GUARDED =
            "import Config\n\nconfig :app, k: 1\n\nif File.exists?(\"config/#{Mix.env()}.exs\") do\n  import_config \"#{Mix.env()}.exs\"\nend\n"
        const val HELPER_BY_ENV =
            "import Config\ndefmodule H do\n  if Mix.env() == :test do\n    def value, do: :t\n  else\n    def value, do: :d\n  end\nend\n"
    }
}
