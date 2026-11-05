package org.elixir_lang.expander

/**
 * An `import_config` in an arm that may not run: each arm starts from the files read before it, so a file both arms
 * import is read, and a later import of a file only one arm read may raise, so the read declines.
 */
class ConfigImportsInArmsHeavyTest : ConfigHeavyTestCase() {
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
}
