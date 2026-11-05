package org.elixir_lang.expander

import com.intellij.openapi.util.io.FileUtil

/**
 * An `import_config` in an arm that may not run: each arm starts from the files read before it, so a file both arms
 * import is read, and a later import of a file only one arm read may raise, so the read declines. The config the arms
 * leave joins too: an app's settings are known as far as the arms set them alike.
 */
class ConfigImportsInArmsHeavyTest : ConfigHeavyTestCase() {
    fun testAFileImportedInEachArm() =
        assertEquals(
            "k1=1 o=1",
            keys(
                "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nelse\n  import_config \"o.exs\"\nend\nconfig :app, k1: 1\n",
                "k1",
                "o",
                extra = arrayOf(O_EXS),
            ),
        )

    /** Both arms read it, so a later import of it raises. */
    fun testAFileImportedInEachArmThenInATryRaises() =
        assertEquals(
            "k=1 k1=1",
            keys(
                "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nelse\n  import_config \"o.exs\"\nend\n$IMPORT_IN_A_TRY",
                "k",
                "k1",
                extra = arrayOf(EMPTY_O_EXS),
            ),
        )

    fun testAFileImportedThenInATryRaises() =
        assertEquals("k=1 k1=1", keys("import_config \"o.exs\"\n$IMPORT_IN_A_TRY", "k", "k1", extra = arrayOf(EMPTY_O_EXS)))

    fun testAFileImportedInOneArm() =
        assertEquals("k1=1 o=Configured", keys(IMPORTED_IN_ONE_ARM, "k1", "o", extra = arrayOf(O_EXS)))

    /** A file only an arm read is one of the read's, so an edit to it moves what reads the table. */
    fun testAFileImportedInOneArmIsAmongTheFilesRead() {
        val root = project(CONFIG to "import Config\n\n$IMPORTED_IN_ONE_ARM", O_EXS)
        val table = table(root.findFileByRelativePath("lib/reader.ex")!!)!!

        assertEquals(listOf("config/config.exs", "config/o.exs"), table.files.mapNotNull { FileUtil.getRelativePath(root.path, it, '/') }.sorted())
    }

    fun testAFileImportedInAnArmThenAgainDeclines() =
        assertEquals(
            "declined",
            keys(
                "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\nimport_config \"o.exs\"\nconfig :app, k1: 1\n",
                "k1",
                extra = arrayOf(EMPTY_O_EXS),
            ),
        )

    fun testAFileImportedInAnArmThenInATryDeclines() =
        assertImportedAgainDeclines("if System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\n")

    /** The second test runs only where the first is false, so its import may not have been made. */
    fun testAFileImportedInALaterCondTestDeclines() =
        assertImportedAgainDeclines(
            "cond do\n  System.get_env(\"X\") -> :ok\n  (import_config \"o.exs\"; false) -> :ok\n  true -> :ok\nend\n",
        )

    /** The arms of the files a read imports count toward the read's bound, not each file's own. */
    fun testArmsAcrossTheFilesImportedInArmsDecline() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def f(_, 0), do: import_config(\"o.exs\")\n\n" +
                    "  def f(v, n) do\n    case v do\n      nil -> f(v, n - 1)\n      _ -> f(v, n - 1)\n    end\n  end\nend\n\n" +
                    "H.f(System.get_env(\"X\"), 10)\nconfig :app, k1: 1\n",
                "k1",
                extra = arrayOf(
                    "config/o.exs" to "import Config\n\n" + (0 until 4).joinToString("") { "config :app, o$it: (if System.get_env(\"Y$it\"), do: 1)\n" },
                ),
            ),
        )

    /** `Config.read_config/1` reads the settings both arms set alike, duplicates included. */
    fun testEachArmSettingTheSameDuplicateSettingsOfAnApp() =
        assertRead(
            "n=2 k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, a: 1, a: 2\nelse\n  config :app, a: 1, a: 2\nend\n\n" +
                    "config :app, n: length(Config.read_config(:app)), k1: 1\n",
                "n",
                "k1",
            ),
        )

    /** Where the arms set the same settings in the same order, values apart, how many there are is known. */
    fun testArmsSettingAnAppsSettingsApart() =
        assertRead(
            "other:n=2 other:k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, a: 1, b: 1\nelse\n  config :app, a: 1, b: 2\nend\n\n" +
                    "config :other, n: length(Config.read_config(:app)), k1: 1\n",
                "other:n",
                "other:k1",
            ),
        )

    /** A file's keyword result is its config only where it configured nothing, which one arm may have. */
    fun testAnArmLeavingTheConfigEmptyBeforeAKeywordResultDeclines() =
        assertEquals("declined", keys("if System.get_env(\"X\"), do: config(:app, k: 1)\n[app: [j: 1]]\n", "k", "j"))

    private companion object {
        const val IMPORTED_IN_ONE_ARM = "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\nconfig :app, k1: 1\n"
        val O_EXS = "config/o.exs" to "import Config\nconfig :app, o: 1\n"
    }
}
