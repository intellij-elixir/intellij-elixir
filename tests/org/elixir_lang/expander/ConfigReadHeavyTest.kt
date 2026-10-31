package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.IndexingTestUtil
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

    fun testARescuedMissingImportReadsItsFields() {
        assertEquals("[{app,[{k,true}]}] [config/config.exs]", read(project(CONFIG to rescuing("missing.exs"))))
    }

    fun testAMissingImportInAnImportedFileLeavesEveryKeyUnknown() {
        val root = project(CONFIG to "import Config\nimport_config \"dev.exs\"\n", DEV to "import Config\nimport_config \"missing.exs\"\n")

        assertEquals("unknown", read(root))
    }

    private fun rescuing(file: String) =
        "import Config\ntry do\n  import_config \"$file\"\nrescue\n  e in File.Error -> config :app, k: match?(%{reason: :enoent}, e)\nend\n"

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

    fun testAKeywordListResultIsTheConfig() {
        assertEquals("[{app,[{k,1}]}] [config/config.exs]", read(project(CONFIG to "[app: [k: 1]]\n")))
        assertEquals("a result not a list", "[] [config/config.exs]", read(project(CONFIG to ":ok\n")))
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

    private companion object {
        const val GUARDED =
            "import Config\n\nconfig :app, k: 1\n\nif File.exists?(\"config/#{Mix.env()}.exs\") do\n  import_config \"#{Mix.env()}.exs\"\nend\n"
    }
}
