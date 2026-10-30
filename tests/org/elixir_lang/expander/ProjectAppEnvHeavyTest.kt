package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * A module body's `Application` env reads answer from what a default build of the project sees: the project's config,
 * else a dependency's `.app`. The project's own app is built from the sources being expanded, so its `.app` is not read.
 */
class ProjectAppEnvHeavyTest : HeavyTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

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

    fun testEachSourceAnswersAndAnEditMovesTheHeldExpansion() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nconfig :dep, :configured, :value\n",
            // The project's own build, in its content, as a library too.
            "_build/dev/lib/app/ebin/app.app" to app("app", "{key, value}"),
            "lib/declared.ex" to module("Declared", "Application.get_env(:dep, :key)", ":value"),
            "lib/unset.ex" to module("Unset", "Application.get_env(:dep, :missing, :default)", ":default"),
            "lib/configured.ex" to module("Configured", "Application.get_env(:dep, :configured)", ":other"),
            "lib/own.ex" to module("Own", "Application.get_env(:app, :key)", ":value"),
        )
        val dep = ebin(app("dep", "{key, value}, {configured, other}"))
        library("dep", dep)
        library("app", root.findFileByRelativePath("_build/dev/lib/app/ebin")!!)
        val files = listOf("declared", "unset", "configured", "own").map { psi(root, "lib/$it.ex") }

        // A known answer defines one of `equal` and `different`; one that isn't known leaves the walk defining both.
        assertEquals(
            "Elixir.Declared compiled equal/0\nElixir.Unset compiled equal/0\n" +
                "Elixir.Configured compiled different/0 equal/0\nElixir.Own compiled different/0 equal/0",
            files.joinToString("\n") { ended(it) },
        )

        val declared = files.first()
        rewrite(dep.findChild("dep.app")!!, app("dep", "{key, other}, {configured, other}"))

        assertEquals("the dependency's .app edited", "Elixir.Declared compiled different/0", ended(declared))

        edit(project, psi(root, "config/config.exs"), "import Config\n\nconfig :dep, :configured, :value\nconfig :dep, :key, :value\n")

        assertEquals("the config edited", "Elixir.Declared compiled different/0 equal/0", ended(declared))
    }

    /** A dependency built in several envs has a `.app` in each: copies that declare the same env answer as one. */
    fun testADependencyBuiltInSeveralEnvsAnswersWhereItsCopiesAgree() {
        val root = project("mix.exs" to MIX_EXS, "lib/declared.ex" to module("Declared", "Application.get_env(:dep, :key)", ":value"))
        val dev = ebin(app("dep", "{key, value}"))
        val test = ebin(app("dep", "{key, value}"))
        library("dep", dev, test)
        val declared = psi(root, "lib/declared.ex")

        assertEquals("Elixir.Declared compiled equal/0", ended(declared))

        rewrite(test.findChild("dep.app")!!, app("dep", "{key, other}"))

        assertEquals("the copies differ", "Elixir.Declared compiled different/0 equal/0", ended(declared))
    }

    /**
     * A config file that reads the env, beside a setting whose key its own expansion names, expands, and so does a file
     * that reads the env it sets. A read in a config file isn't known.
     */
    fun testAConfigFileThatReadsTheEnvBesideAnAliasKeyExpands() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to
                "import Config\n\n_ = Application.get_env(:dep, :key)\nconfig :dep, App.Repo, a: 1\nconfig :dep, :configured, Application.get_env(:dep, :key)\n",
            "lib/configured.ex" to module("Configured", "Application.get_env(:dep, :configured)", "nil"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals("", render(expansion(holder, psi(root, "config/config.exs"))))
        assertEquals("Elixir.Configured compiled different/0 equal/0", ended(psi(root, "lib/configured.ex")))
    }

    /**
     * A config file whose expansion compiles a module that reads the env, beside an alias key: the read is made while
     * the config file runs, so it is configured, and the module expanded on its own expands. The alias key, past an
     * `import` of a module only `Code.require_file` loads, can't be named, so it may be the key read.
     */
    fun testAConfigFileThatCompilesAModuleReadingTheEnvBesideAnAliasKeyExpands() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to
                "import Config\n\nCode.require_file(\"../lib/helpers.ex\", __DIR__)\nimport App.Helpers\nconfig :dep, App.Repo, a: 1\n",
            "lib/helpers.ex" to module("App.Helpers", "Application.get_env(:dep, :key)", "nil"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals("", render(expansion(holder, psi(root, "config/config.exs"))))
        assertEquals("Elixir.App.Helpers compiled different/0 equal/0", ended(psi(root, "lib/helpers.ex")))
    }

    /**
     * A setting a default build may not run, in another env's config file or under a condition, doesn't show its key
     * is set: where the `.app` declares none, Elixir finds no key.
     */
    fun testASettingADefaultBuildMayNotRunLeavesWhetherTheKeyIsSetUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nif false, do: config(:dep, :conditional, true)\n",
            "config/prod.exs" to "import Config\n\nconfig :dep, :prod, true\n",
            "lib/conditional.ex" to
                module("Conditional", "match?({:ok, _}, Application.fetch_env(:dep, :conditional))", "false"),
            "lib/prod.ex" to module("Prod", "match?({:ok, _}, Application.fetch_env(:dep, :prod))", "false"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals(
            "Elixir.Conditional compiled different/0 equal/0\nElixir.Prod compiled different/0 equal/0",
            listOf("conditional", "prod").joinToString("\n") { ended(psi(root, "lib/$it.ex")) },
        )
    }

    /**
     * A setting whose key or options the config index can't read may set any of its app's keys, and one whose app it
     * can't read any app's, so those keys aren't known. Another app's keys answer from its `.app`.
     */
    fun testASettingTheIndexCantReadLeavesTheKeysItMaySetUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nkey = :key\nconfig :dep, key, :value\n",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
            "lib/other.ex" to module("Other", "Application.get_env(:other, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        library("other", ebin(app("other", "{key, value}"), "other"))
        val config = psi(root, "config/config.exs")
        val files = listOf("dep", "other").map { psi(root, "lib/$it.ex") }
        val depUnknown = "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0"

        assertEquals("a key", depUnknown, files.joinToString("\n") { ended(it) })

        for ((what, setting) in listOf(
            "options" to "options = [key: :value]\nconfig :dep, options",
            "a keyword key" to "key = \"key\"\nconfig :dep, \"#{key}\": :value",
            "a tuple in the options" to "config :dep, [{:key, :value}]",
        )) {
            edit(project, config, "import Config\n\n$setting\n")

            assertEquals(what, depUnknown, files.joinToString("\n") { ended(it) })
        }

        edit(project, config, "import Config\n\napp = :dep\nconfig app, key: :value\n")

        assertEquals(
            "an app",
            "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0",
            files.joinToString("\n") { ended(it) },
        )
    }

    /**
     * Each other way a default build's config may set a key that the config index can't read leaves the keys it may set
     * unknown: its app's, or any app's where the app isn't known. A config file that returns something not a list, and
     * an import from the config directory, leave them known.
     */
    fun testAConfigInputTheIndexCantReadLeavesTheKeysItMaySetUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n",
            "config/dev.exs" to "import Config\n",
            "config/envs/dev.exs" to "import Config\n\nconfig :dep, :key, :other\n",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
            "lib/other.ex" to module("Other", "Application.get_env(:other, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        library("other", ebin(app("other", "{key, value}"), "other"))
        val config = psi(root, "config/config.exs")
        val files = listOf("dep", "other").map { psi(root, "lib/$it.ex") }
        val known = "Elixir.Dep compiled equal/0\nElixir.Other compiled equal/0"
        val depUnknown = "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0"
        val bothUnknown = "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0"

        assertEquals("nothing set", known, files.joinToString("\n") { ended(it) })

        val inputs = listOf(
            Triple("a returned keyword list", "[dep: [key: :other]]", bothUnknown),
            Triple("a returned atom", "import Config\n\n:ok", known),
            Triple("a put_env", "import Config\n\nApplication.put_env(:dep, :key, :other)", depUnknown),
            Triple("a put_env of an app not written", "import Config\n\napp = :dep\nApplication.put_env(app, :key, :other)", bothUnknown),
            Triple("a put_all_env", "import Config\n\nApplication.put_all_env(dep: [key: :other])", depUnknown),
            Triple("a persist", "Mix.Config.persist(dep: [key: :other])", depUnknown),
            Triple("a config through an alias of Config", "import Config\n\nalias Config, as: C\nC.config(:dep, :other_key, :x)", known),
            Triple(
                "a put_env through an alias of Application",
                "import Config\n\nalias Application, as: A\nA.put_env(:dep, :key, :other)",
                depUnknown,
            ),
            Triple("an imported put_env", "import Config\n\nimport Application\nput_env(:dep, :key, :other)", depUnknown),
            Triple(
                "a put_env of a module the expansion can't name",
                "import Config\n\nmodule = String.to_atom(System.fetch_env!(\"M\"))\nmodule.put_env(:dep, :key, :other)\n:ok",
                depUnknown,
            ),
            // A write through `apply`, a capture or evaluated code isn't seen until the config is evaluated (#4563).
            Triple("an apply of config", "import Config\n\napply(Config, :config, [:dep, :key, :other])\n:ok", known),
            Triple(
                "an Erlang apply of put_env",
                "import Config\n\n:erlang.apply(Application, :put_env, [:dep, :key, :other])\n:ok",
                known,
            ),
            Triple("a captured config", "import Config\n\nset = &config/3\nset.(:dep, :key, :other)\n:ok", known),
            Triple(
                "an evaluated put_env",
                "import Config\n\nCode.eval_string(\"Application.put_env(:dep, :key, :other)\")\n:ok",
                known,
            ),
            Triple("a config without import Config", "config :dep, :key, :other", bothUnknown),
            Triple(
                "an alias key past an import of a module only Code.require_file loads",
                "import Config\n\nCode.require_file(\"../lib/other.ex\", __DIR__)\nimport Other\nconfig :dep, App.Repo, a: 1",
                depUnknown,
            ),
            Triple("an import from the config directory", "import Config\n\nimport_config \"#{config_env()}.exs\"", known),
            Triple("an import from elsewhere", "import Config\n\nimport_config \"envs/dev.exs\"", bothUnknown),
            Triple("an import of Mix's env from the config directory", "import Config\n\nimport_config \"#{Mix.env()}.exs\"", known),
            Triple("an import of Mix's env with no parentheses", "import Config\n\nimport_config \"#{Mix.env}.exs\"", known),
            Triple(
                "an import of Config's env from the config directory",
                "import Config\n\nimport_config \"#{Config.config_env()}.exs\"",
                known,
            ),
            Triple(
                "an interpolated import from elsewhere",
                "import Config\n\nenv = \"envs/dev\"\nimport_config \"#{env}.exs\"",
                bothUnknown,
            ),
            Triple("a returned variable named config", "config = [dep: [key: :other]]\nconfig", bothUnknown),
        )

        assertEquals(
            inputs.joinToString("\n\n") { (what, _, expected) -> "$what:\n$expected" },
            inputs.joinToString("\n\n") { (what, text, _) ->
                edit(project, config, "$text\n")

                "$what:\n${files.joinToString("\n") { ended(it) }}"
            },
        )

        edit(project, config, "import Config\n")
        edit(project, psi(root, "mix.exs"), MIX_EXS.replace("version: \"0.1.0\"]", "version: \"0.1.0\"] ++ []"))

        assertEquals("a project the index can't read", bothUnknown, files.joinToString("\n") { ended(it) })
    }

    /**
     * Whether a config file's call is a setting can rest on a module its expansion reads: an edit to that module moves a
     * held expansion that read the key.
     */
    fun testAnEditToAModuleAConfigFilesExpansionReadsMovesTheHeldExpansion() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nuse App.Helper\nC.config(:dep, :key, :other)\n:ok\n",
            "lib/helper.ex" to helper("Other"),
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        val dep = psi(root, "lib/dep.ex")
        val helper = psi(root, "lib/helper.ex")

        assertEquals("Elixir.Dep compiled equal/0", ended(dep))

        edit(project, helper, helper("Config"))

        assertEquals("a setting", "Elixir.Dep compiled different/0 equal/0", ended(dep))

        edit(project, helper, helper("Other"))
        edit(project, psi(root, "config/config.exs"), "import Config\n\nuse App.Helper\napp = :dep\nC.put_env(app, :key, :other)\n:ok\n")

        assertEquals("Elixir.Dep compiled equal/0", ended(dep))

        edit(project, helper, helper("Application"))

        assertEquals("a write of an app not written", "Elixir.Dep compiled different/0 equal/0", ended(dep))
    }

    /**
     * A dependency's `.app` file that names another app, and copies whose envs differ only in a zero's sign, leave the
     * app's keys unknown: the VM keeps the sign even where `=:=` can't tell the two zeros apart.
     */
    fun testAnAppFileOfAnotherAppOrCopiesDifferingInAZerosSignLeaveTheKeysUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "lib/named.ex" to module("Named", "Application.get_env(:dep, :key)", ":value"),
            "lib/zero.ex" to module("Zero", "Application.get_env(:zero, :key) === 0.0", "true"),
        )
        library("dep", ebin(app("other", "{key, value}")))
        library("zero", ebin(app("zero", "{key, 0.0}"), "zero"), ebin(app("zero", "{key, -0.0}"), "zero"))

        assertEquals(
            "Elixir.Named compiled different/0 equal/0\nElixir.Zero compiled different/0 equal/0",
            listOf("named", "zero").joinToString("\n") { ended(psi(root, "lib/$it.ex")) },
        )
    }

    /** How [file]'s module ended, from the held expansion, which a fresh one equals, with what it defined if it compiled. */
    private fun ended(file: PsiFile): String {
        val held = render(expansion(holder, file))

        assertEquals("${file.name} held against fresh", render(freshExpansion(project, file)), held)

        return held
    }

    private fun render(expansion: FileExpansion): String =
        expansion.modules.joinToString("\n") { result ->
            when (result.ended) {
                ExpansionResult.Ended.Compiled ->
                    (listOf("${result.module} compiled") + result.table.entries.map { "${it.key.name}/${it.key.arity}" }.sorted())
                        .joinToString(" ")
                is ExpansionResult.Ended.Stopped -> "${result.module} stopped"
                else -> "${result.module} ${result.ended}"
            }
        }

    private fun project(vararg sources: Pair<String, String>): VirtualFile {
        val directory = createTempDir("app")
        sources.forEach { (path, text) -> FileUtil.writeToFile(File(directory, path), text) }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    /** The dependency [app]'s `ebin`, outside the project's content, holding only its `.app` file, [text]. */
    private fun ebin(text: String, app: String = "dep"): VirtualFile {
        val directory = createTempDir("$app-ebin")
        FileUtil.writeToFile(File(directory, "$app.app"), text)

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
    }

    private fun library(name: String, vararg ebins: VirtualFile) {
        ModuleRootModificationUtil.addModuleLibrary(module, name, ebins.map { it.url }, emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun rewrite(file: VirtualFile, text: String) =
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(file, text) }

    private fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!

    private companion object {
        const val MIX_EXS = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"

        fun app(name: String, env: String) = "{application, $name, [{vsn, \"0.1.0\"}, {modules, []}, {env, [$env]}]}.\n"

        /** A module defining `equal/0` where [read] gives [value] and `different/0` where it doesn't. */
        fun module(name: String, read: String, value: String) =
            "defmodule $name do\n  if $read == $value, do: def(equal, do: 1)\n  if $read != $value, do: def(different, do: 1)\nend\n"

        /** `App.Helper`, whose `__using__/1` aliases [module] as `C`. */
        fun helper(module: String) =
            "defmodule App.Helper do\n  defmacro __using__(_) do\n    quote do\n      alias $module, as: C\n    end\n  end\nend\n"
    }
}
