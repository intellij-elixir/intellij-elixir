package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.holdsUnknown
import org.elixir_lang.expander.interpreter.keyValue
import org.elixir_lang.expander.interpreter.properElements
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.lowering.inspect
import java.io.File
import java.util.concurrent.Callable
import org.elixir_lang.model.psi.config.disableMixDepsCheck

/** A Mix project on disk and the config tables its files read. */
abstract class ConfigHeavyTestCase : HeavyTestCase() {
    internal val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    /** The project [sources], with a `mix.exs` and a `lib/reader.ex` where they don't name one. */
    protected fun project(vararg sources: Pair<String, String>): VirtualFile {
        val directory = createTempDir("app")
        val defaults = listOf("mix.exs" to MIX_EXS, "lib/reader.ex" to READER)
        (defaults.filter { (path, _) -> sources.none { it.first == path } } + sources).forEach { (path, text) ->
            FileUtil.writeToFile(File(directory, path), text)
        }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        root.findChild("test")?.let { PsiTestUtil.addSourceRoot(module, it, true) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    protected fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!

    internal fun table(file: VirtualFile, env: String = "dev", inputs: ProjectModules.Inputs = legInputs): ConfigTable? =
        ReadAction.nonBlocking(Callable { holder.configTable(file, inputs, env) }).executeSynchronously()

    /** The config table `lib/reader.ex`'s build reads under [env]: its terms and the files read, newest first. */
    protected fun read(root: VirtualFile, env: String = "dev", reader: String = "lib/reader.ex"): String {
        val table = table(root.findFileByRelativePath(reader)!!, env) ?: return "no table"

        return when {
            table.unknown -> "unknown"
            table.declined -> "declined"
            else -> "${table.config} ${table.files.map { FileUtil.getRelativePath(root.path, it, '/') }}"
        }
    }

    /**
     * What `lib/reader.ex`'s `:dev` table answers for each of [keys] once `config/config.exs` is `import Config` then
     * [config], with the files [extra] beside it: `declined` where a unit of the read declined, so every key answers as
     * the settings index does, `raised` where the read raises, else each key as `key=answer`. A key is `:app`'s unless
     * written `app:key`; `key/nested` reads a keyword inside the key's value.
     */
    protected fun keys(config: String, vararg keys: String, extra: Array<Pair<String, String>> = emptyArray()): String {
        val root = project(CONFIG to "import Config\n\n$config", *extra)
        val table = table(root.findFileByRelativePath("lib/reader.ex")!!) ?: return "no table"

        return when {
            table.declined -> "declined"
            table.unknown -> "raised"
            else -> keys.joinToString(" ") { key -> "$key=${answer(table, key)}" }
        }
    }

    /** The config sets `k` alone to [value], which isn't known. */
    protected fun assertConfigured(value: String) = assertEquals("k=Configured", keyAlone(value))

    /** The config sets `k` to [value], which isn't known, beside `k1: 1`, which stays known. */
    protected fun assertConfiguredBeside(value: String) = assertEquals("k=Configured k1=1", keyBeside(value))

    /** What [keys] answers for `k` once the config sets it alone to [value]. */
    protected fun keyAlone(value: String): String = keys("config :app, k: $value\n", "k")

    /** What [keys] answers for `k` and `k1` once the config sets `k` to [value] beside `k1: 1`. */
    protected fun keyBeside(value: String): String = keys("config :app, k: $value, k1: 1\n", "k", "k1")

    /** What [keys] answers for `k` and `k1` once [statements] bind `k`, which the config sets beside `k1: 1`. */
    protected fun keyBound(statements: String): String = keys(statements + "config :app, k: k, k1: 1\n", "k", "k1")

    private fun answer(table: ConfigTable, key: String): String {
        val app = key.substringBefore(':', "app")
        val path = key.substringAfter(':').split('/')

        if (path.size > 1) {
            val term = (listOf(app) + path).fold<String, OtpErlangObject?>(table.config) { term, nested ->
                term?.let(::properElements)?.let { elements -> keyValue(elements) { it == atom(nested) } }
            }

            return when {
                term == null -> "absent"
                holdsUnknown(term) -> "Configured"
                else -> inspect(term)
            }
        }

        return when (val value = table.of(app, atom(path.single()))) {
            null -> "absent"
            is AppEnvValue.Declared -> inspect(value.term)
            else -> value.javaClass.simpleName
        }
    }

    /** [expected], from `Config.read_config/1`; before it, where its call raises, [before]. */
    protected fun assertRead(expected: String, actual: String, before: String = "raised") =
        assertEquals(if (CONFIG_READ_CONFIG.isSufficient(legLevel())) expected else before, actual)

    /** [before], then a `try` around a second import of `config/o.exs`, which may raise, so the read declines. */
    protected fun assertImportedAgainDeclines(before: String) =
        assertEquals("declined", keys(before + IMPORT_IN_A_TRY, "k", "k1", extra = arrayOf(EMPTY_O_EXS)))

    protected companion object {
        /** A `try` around an import of `config/o.exs`: `k` is 1 where it raised, else 0, beside `k1: 1`. */
        const val IMPORT_IN_A_TRY = "k = try do\n  import_config \"o.exs\"\n  0\nrescue\n  _ -> 1\nend\nconfig :app, k: k, k1: 1\n"

        /** A `config/o.exs` that sets nothing. */
        val EMPTY_O_EXS = "config/o.exs" to "import Config\n"

        const val MIX_EXS = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"
        const val READER = "defmodule Reader do\nend\n"
        const val CONFIG = "config/config.exs"
        const val DEV = "config/dev.exs"

        fun mixExs(options: String) =
            "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\", $options]\nend\n"
    }
}
