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

    /** The arm may import a file that redefines a module the config reads past the arm. */
    fun testAModuleAFileImportedInAnArmRedefinesDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def v, do: 0\nend\n\nif System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\n\nconfig :app, k: H.v(), k1: 1\n",
                "k",
                "k1",
                extra = arrayOf("config/o.exs" to "import Config\n\ndefmodule H do\n  def v, do: 1\nend\n"),
            ),
        )

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

    /** Where the arms set an app's settings in another order, which comes first isn't known. */
    fun testArmsSettingAnAppsSettingsInAnotherOrder() =
        assertRead(
            "other:h=Configured other:k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, a: 1, b: 1\nelse\n  config :app, b: 2, a: 1\nend\n\n" +
                    "config :other, h: elem(hd(Config.read_config(:app)), 0), k1: 1\n",
                "other:h",
                "other:k1",
            ),
        )

    /** Where one arm adds a setting, how many the app has isn't known. */
    fun testAnArmAddingASettingToAnApp() =
        assertRead(
            "other:n=Configured other:k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, a: 1, b: 1\nelse\n  config :app, a: 1\nend\n\n" +
                    "config :other, n: length(Config.read_config(:app)), k1: 1\n",
                "other:n",
                "other:k1",
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

    /** Where only one arm sets an app, whether it has settings isn't known. */
    fun testAnArmSettingAnAppTheOtherDoesnt() =
        assertRead(
            "other:n=Configured other:k1=1",
            keys(
                "if System.get_env(\"X\"), do: config(:app, k: 1)\nconfig :other, n: is_list(Config.read_config(:app)), k1: 1\n",
                "other:n",
                "other:k1",
            ),
        )

    /** `config/2` gives back the config before it, which one arm may have left empty. */
    fun testTheConfigAConfigCallGivesBackWhereAnArmMayHaveLeftItEmpty() =
        assertEquals(
            "other:len=Configured other:k1=1",
            keys(
                "if System.get_env(\"X\"), do: config(:app, k: 1)\nbefore = config(:other, n: 1)\n" +
                    "config :other, len: length(before), k1: 1\n",
                "other:len",
                "other:k1",
            ),
        )

    /** `config/2` gives back the config before it, in which how many settings an app the arms set apart has isn't known. */
    fun testTheConfigAConfigCallGivesBackWithAnAppTheArmsSetApart() =
        assertEquals(
            "other:len=Configured other:k1=1",
            keys(
                CONFIG_BEFORE.format("config(:app, b: 1)", "config(:app, a: 2)"),
                "other:len",
                "other:k1",
            ),
        )

    /** Where the arms leave an app's settings alike in their keys, the config `config/2` gives back keeps how many. */
    fun testTheConfigAConfigCallGivesBackWithAnAppTheArmsSetAlike() =
        assertEquals(
            "other:len=1 other:k1=1",
            keys(
                CONFIG_BEFORE.format("config(:app, a: 2)", "config(:app, a: 3)"),
                "other:len",
                "other:k1",
            ),
        )

    /** An app one arm sets to no settings may not be in the config `config/2` gives back. */
    fun testTheConfigAConfigCallGivesBackWithAnAppOneArmSetsToNoSettings() =
        assertEquals(
            "other:n=Configured other:k1=1",
            keys(
                "config :other, x: 1\nif System.get_env(\"X\"), do: config(:app, [])\n" +
                    "config :other, n: is_list(Keyword.get(config(:z, k: 1), :app)), k1: 1\n",
                "other:n",
                "other:k1",
            ),
        )

    /** Where one arm adds an app, how many apps the config `config/2` gives back holds isn't known. */
    fun testTheConfigAConfigCallGivesBackWithAnAppOneArmAdds() =
        assertEquals(
            "other:len=Configured other:k1=1",
            keys(
                "config :other, x: 1\nif System.get_env(\"X\"), do: config(:app, k: 1)\nbefore = config(:y, z: 1)\n" +
                    "config :other, len: length(before), k1: 1\n",
                "other:len",
                "other:k1",
            ),
        )

    /** Where the arms set apps in another order, which comes first in the config `config/2` gives back isn't known. */
    fun testTheConfigAConfigCallGivesBackWithAppsTheArmsSetInAnotherOrder() =
        assertEquals(
            "other:h=Configured other:k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :a, k: 1\n  config :b, k: 1\nelse\n  config :b, k: 1\n  config :a, k: 1\nend\n\n" +
                    "before = config(:y, z: 1)\nconfig :other, h: elem(hd(before), 0), k1: 1\n",
                "other:h",
                "other:k1",
            ),
        )

    /** Where the arms set the same apps in the same order, how many apps the config `config/2` gives back holds is known. */
    fun testTheConfigAConfigCallGivesBackWithAppsTheArmsSetAlike() =
        assertEquals(
            "other:len=2 other:k1=1",
            keys(
                "config :other, x: 1\nif System.get_env(\"X\"), do: config(:a, k: 1), else: config(:a, k: 2)\n" +
                    "before = config(:y, z: 1)\nconfig :other, len: length(before), k1: 1\n",
                "other:len",
                "other:k1",
            ),
        )

    /** A file's keyword result is its config only where it configured nothing, which one arm may have. */
    fun testAnArmLeavingTheConfigEmptyBeforeAKeywordResultDeclines() =
        assertEquals("declined", keys("if System.get_env(\"X\"), do: config(:app, k: 1)\n[app: [j: 1]]\n", "k", "j"))

    /** A loop's iterations, whose arms set apps apart, join to the same config again, so the loop ends. */
    fun testALoopWhoseArmsSetAppsApart() =
        assertEquals(
            "other:enabled=Configured step=Configured k1=1",
            keys(
                "config :app, step: 0\n\nfor <<_ <- System.get_env(\"X\", \"ab\")>> do\n" +
                    "  if System.get_env(\"Y\"), do: config(:app, step: 1), else: config(:other, enabled: true)\nend\n\n" +
                    "config :app, k1: 1\n",
                "other:enabled",
                "step",
                "k1",
            ),
        )

    /** A loop's iterations whose arms set a setting to known values apart join to the same config again, so the loop ends. */
    fun testALoopWhoseArmsSetASettingToKnownValuesApart() =
        assertEquals(
            "level=Configured k1=1",
            keys(
                "config :app, level: :info\n\nfor <<_ <- System.get_env(\"XS\", \"ab\")>> do\n" +
                    "  if System.get_env(\"X\"), do: config(:app, level: :debug), else: config(:app, level: :warn)\nend\n\n" +
                    "config :app, k1: 1\n",
                "level",
                "k1",
            ),
        )

    /** A loop's iterations that each set a setting to a new value holding a value not known join to the same config again. */
    fun testALoopSettingANewValueHoldingAValueNotKnown() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: []\nfor <<_ <- System.get_env(\"XS\", \"ab\")>>, do: config(:app, k: {System.get_env(\"X\")})\n" +
                    "config :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A loop's iterations that each set a setting to the element join to the same config again. */
    fun testALoopSettingASettingToTheElement() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: :x\nfor y <- System.get_env(), do: config(:app, k: y)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    private companion object {
        /** `:app` set, then set again in each of two arms, before `config/2` gives back the config. */
        const val CONFIG_BEFORE =
            "config :app, a: 1\nif System.get_env(\"X\"), do: %s, else: %s\nbefore = config(:other, n: 1)\n" +
                "config :other, len: length(Keyword.get(before, :app)), k1: 1\n"
        const val IMPORTED_IN_ONE_ARM = "if System.get_env(\"X\") do\n  import_config \"o.exs\"\nend\nconfig :app, k1: 1\n"
        val O_EXS = "config/o.exs" to "import Config\nconfig :app, o: 1\n"
    }
}
