package org.elixir_lang.expander

import com.intellij.openapi.util.io.FileUtil

/**
 * An `import_config` in an arm that may not run: each arm starts from the files read before it, so a file both arms
 * import is read, and a later import of a file only one arm read may raise, so the read declines.
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

    private companion object {
        const val IMPORTED_IN_ONE_ARM = "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\nconfig :app, k1: 1\n"
        val O_EXS = "config/o.exs" to "import Config\nconfig :app, o: 1\n"
    }
}
