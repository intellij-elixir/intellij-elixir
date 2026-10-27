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
     * the config file runs, so it is configured, and the module expanded on its own answers from the dependency's
     * `.app` file.
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
        assertEquals("Elixir.App.Helpers compiled different/0", ended(psi(root, "lib/helpers.ex")))
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
    }
}
