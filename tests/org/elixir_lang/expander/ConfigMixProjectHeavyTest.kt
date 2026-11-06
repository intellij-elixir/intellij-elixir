package org.elixir_lang.expander

/**
 * `Mix.Project`'s paths and config in a config read are what the build machine and the project stack make them, so each
 * is unknown, and the keys beside it stay known. `mix phx.new` 1.8.13 writes the first of these for esbuild and tailwind.
 */
class ConfigMixProjectHeavyTest : ConfigHeavyTestCase() {
    fun testTheBuildPathInAGeneratedEsbuildConfig() =
        assertEquals(
            "esbuild:demo=Configured esbuild:version=\"0.17.11\" k1=1",
            keys(
                "config :esbuild, version: \"0.17.11\", demo: [args: ~w(a b), cd: Path.expand(\"../assets\", __DIR__), " +
                    "env: %{\"NODE_PATH\" => [Path.expand(\"../deps\", __DIR__), Mix.Project.build_path()]}]\nconfig :app, k1: 1\n",
                "esbuild:demo",
                "esbuild:version",
                "k1",
            ),
        )

    fun testTheProjectsVersion() =
        assertEquals("k=Configured k1=1", keys("lv_vsn = Mix.Project.config()[:version]\nconfig :app, k: lv_vsn, k1: 1\n", "k", "k1"))

    fun testTheProjectsVersionInAKeyword() =
        assertEquals(
            "k/version=Configured k/other=2 k1=1",
            keys("config :app, k: [version: Mix.Project.config()[:version], other: 2], k1: 1\n", "k/version", "k/other", "k1"),
        )

    fun testTheProjectsAppKey() =
        assertEquals("k=Configured", keys("config :app, k: Keyword.get(Mix.Project.config(), :app)\n", "k"))

    fun testTheBuildPath() = assertEquals("k=Configured", keys("config :app, k: Mix.Project.build_path()\n", "k"))

    fun testTheBuildPathOfAConfig() =
        assertEquals("k=Configured k1=1", keyBeside("Mix.Project.build_path(Mix.Project.config())"))

    fun testTheAppPath() = assertEquals("k=Configured", keys("config :app, k: Mix.Project.app_path()\n", "k"))

    fun testTheAppPathOfAConfig() =
        assertEquals("k=Configured k1=1", keyBeside("Mix.Project.app_path(Mix.Project.config())"))

    fun testAJoinOnTheBuildPath() =
        assertEquals("k=Configured k1=1", keyBeside("Path.join(Mix.Project.build_path(), \"x\")"))

    fun testTheAppPathRescued() =
        assertEquals(
            "k=Configured k1=1",
            keys("v = try do\n  Mix.Project.app_path()\nrescue\n  _ -> :none\nend\nconfig :app, k: v, k1: 1\n", "k", "k1"),
        )

    fun testAConfigInAnIfOnTheProjectsConfig() =
        assertEquals(
            "k=Configured k1=1",
            keys("if Mix.Project.config()[:app] == :p do\n  config :app, k: 1\nend\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** Given a config that is known, Elixir's own clauses run, and raise on `1`. */
    fun testTheAppPathOfANonConfig() =
        assertEquals("raised", keyBeside("Mix.Project.app_path(1)"))

    fun testTheDepsPath() = assertEquals("k=Configured k1=1", keyBeside("Mix.Project.deps_path()"))

    /** Elixir's own clauses run over the project's unknown config. */
    fun testTheCompilePath() =
        assertEquals("k=Configured k1=1", keyBeside("Mix.Project.compile_path()"))

    /** The project on the stack is the build's, which nothing here reads. */
    fun testTheProjectModuleDeclines() =
        assertEquals("declined", keyBeside("Mix.Project.get()"))
}
