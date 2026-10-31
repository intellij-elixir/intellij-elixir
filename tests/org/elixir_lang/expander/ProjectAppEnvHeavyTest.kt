package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressManager
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
import org.elixir_lang.psi.ElixirFile
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
                "Elixir.Configured compiled different/0\nElixir.Own compiled different/0 equal/0",
            files.joinToString("\n") { ended(it) },
        )

        val declared = files.first()
        rewrite(dep.findChild("dep.app")!!, app("dep", "{key, other}, {configured, other}"))

        assertEquals("the dependency's .app edited", "Elixir.Declared compiled different/0", ended(declared))

        edit(project, psi(root, "config/config.exs"), "import Config\n\nconfig :dep, :configured, :value\nconfig :dep, :key, :value\n")

        assertEquals("the config edited", "Elixir.Declared compiled equal/0", ended(declared))
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

    /** A setting a default build doesn't run, in another env's config file or under a condition, leaves the key unset. */
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
            "Elixir.Conditional compiled equal/0\nElixir.Prod compiled equal/0",
            listOf("conditional", "prod").joinToString("\n") { ended(psi(root, "lib/$it.ex")) },
        )
    }

    /** A setting whose key, options or app only the evaluation can read answers its value; another app's keys their `.app`. */
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
        val known = "Elixir.Dep compiled equal/0\nElixir.Other compiled equal/0"

        assertEquals("a key", known, files.joinToString("\n") { ended(it) })

        for ((what, setting) in listOf(
            "options" to "options = [key: :value]\nconfig :dep, options",
            "a keyword key" to "key = \"key\"\nconfig :dep, \"#{key}\": :value",
            "a tuple in the options" to "config :dep, [{:key, :value}]",
        )) {
            edit(project, config, "import Config\n\n$setting\n")

            assertEquals(what, known, files.joinToString("\n") { ended(it) })
        }

        edit(project, config, "import Config\n\napp = :dep\nconfig app, key: :value\n")

        assertEquals("an app", known, files.joinToString("\n") { ended(it) })
    }

    /**
     * Each other way a default build's config may set a key: what the read evaluates answers its value, a key of an app
     * it wrote is unknown, and where the read declined, or Elixir raises, the keys it may set are unknown.
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
        val different = "Elixir.Dep compiled different/0\nElixir.Other compiled equal/0"
        val depUnknown = "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0"
        val bothUnknown = "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0"

        assertEquals("nothing set", known, files.joinToString("\n") { ended(it) })

        val inputs = listOf(
            Triple("a returned keyword list", "[dep: [key: :other]]", different),
            Triple("a returned atom", "import Config\n\n:ok", known),
            Triple("a put_env", "import Config\n\nApplication.put_env(:dep, :key, :other)", depUnknown),
            Triple("a put_env of an app in a variable", "import Config\n\napp = :dep\nApplication.put_env(app, :key, :other)", depUnknown),
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
            Triple("an apply of config", "import Config\n\napply(Config, :config, [:dep, :key, :other])\n:ok", different),
            Triple(
                "an Erlang apply of put_env",
                "import Config\n\n:erlang.apply(Application, :put_env, [:dep, :key, :other])\n:ok",
                depUnknown,
            ),
            Triple("a captured config", "import Config\n\nset = &config/3\nset.(:dep, :key, :other)\n:ok", different),
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
            Triple("an import from elsewhere", "import Config\n\nimport_config \"envs/dev.exs\"", different),
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
                different,
            ),
            Triple("a returned variable named config", "config = [dep: [key: :other]]\nconfig", different),
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

    /** An imported file's stopped run leaves undone what the raise after it may rest on, so the raise is no more certain. */
    fun testARaiseAfterAReadDeclinedAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\nconfig :dep, :key, :other\nimport_config \"env.exs\"\nimport_config \"config.exs\"\n",
            "config/env.exs" to "import Config\n\nconfig :other, port: String.to_integer(System.get_env(\"PORT\") || \"4000\")\n",
        )

        assertEquals("Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /** A value from the system, which the read declines, so every answer is the settings index's. */
    fun testAReadThatDeclinedAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\nconfig :dep, :key, :other\nconfig :other, port: String.to_integer(System.get_env(\"PORT\") || \"4000\")\n",
        )

        assertEquals("Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /**
     * A macro's code the evaluator lost ran in the read, so the read's table isn't what Elixir's is. The settings index can't
     * read what `H.m()` sets, so `:other`'s key is unknown too, where the table leaves it to the `.app`.
     */
    fun testUnfollowedCodeInAReadAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\ndefmodule H do\n  defmacro m do\n    _ = System.get_env(\"X\")\n    :ok\n  end\nend\n\n" +
                "require H\nH.m()\nconfig :dep, :key, :other\n",
        )

        assertEquals(
            "Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0",
            "${ended(dep)}\n${ended(other)}",
        )
    }

    /** Elixir writes `:dep`'s key here: the code the arm's assumption passes over may write the read's config. */
    fun testADeclinePassedOverInAReadAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\ncase Code.require_file(\"extra.exs\", __DIR__) do\n  nil -> raise \"no\"\n  _ -> :ok\nend\n",
            "config/extra.exs" to "import Config\nconfig :dep, :key, :other\n",
        )

        assertEquals("Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0", "${ended(dep)}\n${ended(other)}")
    }

    fun testADeclinePassedOverInAGuardedArmInAReadAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\ncond do\n  Code.require_file(\"extra.exs\", __DIR__) == nil -> raise \"no\"\n  true -> :ok\nend\n",
            "config/extra.exs" to "import Config\nconfig :dep, :key, :other\n",
        )

        assertEquals("Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled different/0 equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /** A system read's decline writes nothing, but the arm that passes a decline over can't know which declines write. */
    fun testACaseOfASystemReadPassedOverInAReadAnswersAsMainDoes() {
        val (dep, other) = readers(
            "import Config\n\ncase System.get_env(\"X\") do\n  nil -> raise \"no\"\n  _ -> :ok\nend\n\nconfig :dep, :key, :other\n",
        )

        assertEquals("Elixir.Dep compiled different/0 equal/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /** A function the read made runs in the read's run, not its caller's, so the caller doesn't see what it writes. */
    fun testAFunctionInAConfigValueIsUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nconfig :dep, setter: fn -> Application.put_env(:dep, :key, :other) end\nconfig :dep, key: :value\n",
            "lib/dep.ex" to module("Dep", "(Application.get_env(:dep, :setter).(); Application.get_env(:dep, :key))", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals("Elixir.Dep compiled different/0 equal/0", ended(psi(root, "lib/dep.ex")))
    }

    fun testAFunctionNestedInAConfigValueIsUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nconfig :dep, setter: [run: {fn -> Application.put_env(:dep, :key, :other) end}]\nconfig :dep, key: :value\n",
            "lib/dep.ex" to module("Dep", "(elem(Application.get_env(:dep, :setter)[:run], 0).(); Application.get_env(:dep, :key))", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals("Elixir.Dep compiled different/0 equal/0", ended(psi(root, "lib/dep.ex")))
    }

    /** A remote capture names its function, so it runs in its caller's run. */
    fun testARemoteCaptureInAConfigValueAnswersFromTheTable() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nconfig :dep, key: &:erlang.is_atom/1\n",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key).(:a)", "true"),
        )
        library("dep", ebin(app("dep", "{key, value}")))

        assertEquals("Elixir.Dep compiled equal/0", ended(psi(root, "lib/dep.ex")))
    }

    /** Copying each of the value's parts where it appears would take more than the IDE's heap. */
    fun testASharedConfigValueIsReadInBoundedWork() {
        assertSharedValueIsReadInBoundedWork("config :dep, :key, t\n")
    }

    fun testASharedValueDeepMergedInAConfigIsReadInBoundedWork() {
        assertSharedValueIsReadInBoundedWork("config :dep, :key, [a: t]\nconfig :dep, :key, [b: t]\n")
    }

    fun testASharedValueInAKeywordListResultIsReadInBoundedWork() {
        assertSharedValueIsReadInBoundedWork("[dep: [key: t]]\n")
    }

    fun testASharedKeywordListMergedWithItselfInAConfigIsMergedInBoundedWork() {
        assertSharedValueIsReadInBoundedWork("config :dep, :key, t\nconfig :dep, :key, t\n", "t = [a: t, b: t]\n")
    }

    fun testTwoSharedKeywordListsMergedInAConfigAreMergedInBoundedWork() {
        assertSharedValueIsReadInBoundedWork(
            "u = []\n${"u = [a: u, b: u]\n".repeat(40)}config :dep, :key, t\nconfig :dep, :key, u\n",
            "t = [a: t, b: t]\n",
        )
    }

    /** A config file that doubles `t` forty times by [doubling], then ends in [last], answers a module reading `:dep`'s `:key` in bounded work. */
    private fun assertSharedValueIsReadInBoundedWork(last: String, doubling: String = "t = {t, t}\n") {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nt = []\n${doubling.repeat(40)}$last",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        val file = psi(root, "lib/dep.ex")
        var expansion: FileExpansion? = null

        whileCheckingIndicators {
            ProgressManager.getInstance().runProcess(
                { expansion = ReadAction.computeBlocking<FileExpansion, Throwable> { holder.expansion(file as ElixirFile, legInputs) } },
                CancelAt(2_000_000),
            )
        }

        assertEquals("Elixir.Dep compiled different/0", render(expansion!!))
    }

    /** The result statement runs a second time for its value, which declining or not, the first run already said. */
    fun testALegacyResultsLastStatementSetsNoDeclineFlag() {
        val (dep, other) = readers("[dep: [key: :other]]\n")

        assertEquals("Elixir.Dep compiled different/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    fun testAReadThatDeclinedNothingAnswersFromTheTable() {
        val (dep, other) = readers("import Config\n\nconfig :dep, :key, :other\nconfig :other, :unrelated, 1\n")

        assertEquals("Elixir.Dep compiled different/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /** Elixir reads `[]`, so the `.app`s answer, as they do where the read declined. */
    fun testAnUnknownLastValueWithNoConfigAnswersFromTheIndex() {
        val (dep, other) = readers("defmodule Helper do\nend\n")

        assertEquals("Elixir.Dep compiled equal/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
    }

    /** A reader rests on what its own read read: another env's file, or a file outside the config, leaves it held. */
    fun testAnEditOutsideAReadersReadLeavesItsExpansionHeld() {
        val root = envReader()
        val reader = psi(root, "lib/dep.ex")
        val held = expansion(holder, reader)

        edit(project, psi(root, "config/test.exs"), "import Config\n\nconfig :dep, :key, :value\n")
        assertSame("test.exs", held, expansion(holder, reader))

        edit(project, psi(root, "lib/unrelated.ex"), "defmodule Unrelated do\n  def f, do: 1\nend\n")
        assertSame("an unrelated file", held, expansion(holder, reader))
    }

    fun testAnEditToAFileAReadImportedMovesAReader() {
        val root = envReader()
        val reader = psi(root, "lib/dep.ex")

        assertEquals("Elixir.Dep compiled different/0", ended(reader))

        edit(project, psi(root, "config/dev.exs"), "import Config\n\nconfig :dep, :key, :value\n")

        assertEquals("Elixir.Dep compiled equal/0", ended(reader))
    }

    /** Which `_build` env's config a dependency built in two reads isn't known, so the settings index answers. */
    fun testADependencyBuiltInTwoEnvsAnswersAsMainDoes() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nconfig :dep, :key, :other\n",
            "lib/reader.ex" to "defmodule Reader do\nend\n",
            "deps/dep/mix.exs" to "defmodule Dep.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :dep, version: \"0.1.0\"]\nend\n",
            "deps/dep/lib/in_dep.ex" to module("InDep", "Application.get_env(:dep, :key)", ":value"),
            "_build/dev/lib/dep/ebin/dep.app" to app("dep", "{key, value}"),
            "_build/test/lib/dep/ebin/dep.app" to app("dep", "{key, value}"),
        )
        PsiTestUtil.addExcludedRoot(module, root.findChild("deps")!!)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("Elixir.InDep compiled different/0 equal/0", ended(psi(root, "deps/dep/lib/in_dep.ex")))
    }

    /** Elixir's answer for `apply/3`, a capture and an `fn`: each is `config`, which only the evaluation sees. */
    fun testAConfigThroughAnApplyACaptureOrAnFnAnswersItsValue() {
        val (dep, other) = readers("import Config\n")
        val config = psi(dep.virtualFile.parent.parent, "config/config.exs")

        for ((what, setting) in listOf(
            "an apply" to "apply(Config, :config, [:dep, :key, :other])\n:ok",
            "a capture" to "set = &config/2\nset.(:dep, key: :other)\n:ok",
            "an fn in a variable" to "set = fn app, key, value -> config(app, key, value) end\nset.(:dep, :key, :other)\n:ok",
        )) {
            edit(project, config, "import Config\n\n$setting\n")

            assertEquals(what, "Elixir.Dep compiled different/0\nElixir.Other compiled equal/0", "${ended(dep)}\n${ended(other)}")
        }
    }

    /**
     * Elixir's answer depends on what loading the app does to the write, which isn't read: `:value` for a key its `.app`
     * declares unless the write persists, `:other` otherwise. So any key of a written app is unknown.
     */
    fun testAWriteInAReadLeavesItsAppUnknown() {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
            "lib/undeclared.ex" to module("Undeclared", "Application.get_env(:dep, :other_key)", "nil"),
            "lib/other.ex" to module("Other", "Application.get_env(:other, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        library("other", ebin(app("other", "{key, value}"), "other"))
        val config = psi(root, "config/config.exs")
        val files = listOf("dep", "undeclared", "other").map { psi(root, "lib/$it.ex") }

        for ((what, write) in listOf(
            "an Erlang apply" to ":erlang.apply(Application, :put_env, [:dep, :key, :other])",
            "an Erlang apply persistent" to ":erlang.apply(Application, :put_env, [:dep, :key, :other, [persistent: true]])",
            "an undeclared key" to ":erlang.apply(Application, :put_env, [:dep, :other_key, :other])",
            "a direct put_env" to "Application.put_env(:dep, :key, :other)",
        )) {
            edit(project, config, "import Config\n\n$write\n:ok\n")

            assertEquals(
                what,
                "Elixir.Dep compiled different/0 equal/0\nElixir.Undeclared compiled different/0 equal/0\nElixir.Other compiled equal/0",
                files.joinToString("\n") { ended(it) },
            )
        }
    }

    fun testAConfigOfAWrittenKeyAnswersTheConfig() {
        val (dep, _) = readers("import Config\n\nApplication.put_env(:dep, :key, :x)\nconfig :dep, :key, :other\n")
        val config = psi(dep.virtualFile.parent.parent, "config/config.exs")

        assertEquals("the write first", "Elixir.Dep compiled different/0", ended(dep))

        edit(project, config, "import Config\n\nconfig :dep, :key, :other\nApplication.put_env(:dep, :key, :x)\n")

        assertEquals("the write after", "Elixir.Dep compiled different/0", ended(dep))
    }

    /** The issue's example: what `mix compile` and `mix test` print for it, on Elixir 1.20.4 and 1.11.4. */
    fun testAModuleBodyReadsItsBuildsConfig() {
        val body = "  @repo Application.compile_env(:my_app, MyApp.Repo)\n" +
            "  if @repo == [log: false, pool_size: 2], do: def(dev_repo, do: 1)\n" +
            "  if @repo == [pool_size: 10, log: false], do: def(test_repo, do: 1)\n" +
            "  if Application.compile_env(:my_app, :feature), do: def(feature, do: :on)\n"
        val root = project(
            "mix.exs" to "defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :my_app, version: \"0.1.0\", elixir: \"~> 1.11\"]\nend\n",
            "config/config.exs" to
                "import Config\n\nconfig :my_app, MyApp.Repo, pool_size: 10, log: false\nconfig :my_app, feature: true\n\nimport_config \"#{config_env()}.exs\"\n",
            "config/dev.exs" to "import Config\n\nconfig :my_app, MyApp.Repo, pool_size: 2\n",
            "config/test.exs" to "import Config\n\nconfig :my_app, feature: false\n",
            "lib/my_app.ex" to "defmodule MyApp do\n${body}end\n",
            "test/support/my_app_test_support.ex" to "defmodule MyAppTestSupport do\n${body}end\n",
        )
        PsiTestUtil.addSourceRoot(module, root.findChild("test")!!, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("Elixir.MyApp compiled dev_repo/0 feature/0", ended(psi(root, "lib/my_app.ex")))
        assertEquals("Elixir.MyAppTestSupport compiled test_repo/0", ended(psi(root, "test/support/my_app_test_support.ex")))
    }

    /** A module reading `:dep`'s `:key`, which `dev.exs` sets, beside `test.exs` and a file no read reaches. */
    private fun envReader(): VirtualFile {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            "config/dev.exs" to "import Config\n\nconfig :dep, :key, :other\n",
            "config/test.exs" to "import Config\n",
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
            "lib/unrelated.ex" to "defmodule Unrelated do\nend\n",
        )
        library("dep", ebin(app("dep", "{key, value}")))

        return root
    }

    /** The modules reading `:dep`'s and `:other`'s `:key`, each declared `:value`, under the config file [config]. */
    private fun readers(config: String, vararg imported: Pair<String, String>): Pair<PsiFile, PsiFile> {
        val root = project(
            "mix.exs" to MIX_EXS,
            "config/config.exs" to config,
            *imported,
            "lib/dep.ex" to module("Dep", "Application.get_env(:dep, :key)", ":value"),
            "lib/other.ex" to module("Other", "Application.get_env(:other, :key)", ":value"),
        )
        library("dep", ebin(app("dep", "{key, value}")))
        library("other", ebin(app("other", "{key, value}"), "other"))

        return psi(root, "lib/dep.ex") to psi(root, "lib/other.ex")
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
