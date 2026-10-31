package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.IndexingTestUtil

/** An edit to what a held config table read gives a new table; any other edit leaves the held one. */
class ConfigTableEditsHeavyTest : ConfigHeavyTestCase() {
    fun testAConfigValueEdited() {
        val root = project(CONFIG to "import Config\n\nconfig :dep, k: 1\n")

        assertEquals("[{dep,[{k,1}]}] [config/config.exs]", read(root))

        edit(project, psi(root, CONFIG), "import Config\n\nconfig :dep, k: 2\n")

        assertEquals("[{dep,[{k,2}]}] [config/config.exs]", read(root))
    }

    fun testAnImportedValueEdited() {
        val root = project(
            CONFIG to "import Config\n\nconfig :dep, k: 1\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n\nconfig :dep, k: 2\n",
        )

        assertEquals("[{dep,[{k,2}]}] [config/dev.exs, config/config.exs]", read(root))

        edit(project, psi(root, DEV), "import Config\n\nconfig :dep, k: 1\n")

        assertEquals("[{dep,[{k,1}]}] [config/dev.exs, config/config.exs]", read(root))
    }

    fun testAGuardedFileCreated() {
        val root = project(CONFIG to GUARDED)

        assertEquals("[{dep,[{k,1}]}] [config/config.exs]", read(root))

        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(root.findChild("config")!!.createChildData(this, "dev.exs"), "import Config\n\nconfig :dep, k: 2\n")
        }

        assertEquals("[{dep,[{k,2}]}] [config/dev.exs, config/config.exs]", read(root))
    }

    fun testAGuardedFileDeleted() {
        val root = project(CONFIG to GUARDED, DEV to "import Config\n\nconfig :dep, k: 2\n")

        assertEquals("[{dep,[{k,2}]}] [config/dev.exs, config/config.exs]", read(root))

        WriteAction.runAndWait<Throwable> { root.findFileByRelativePath(DEV)!!.delete(this) }

        assertEquals("[{dep,[{k,1}]}] [config/config.exs]", read(root))
    }

    /** Mix compares `config_path:` as written: a missing default is no config, and the same file spelled otherwise raises. */
    fun testAMissingConfigPathRespelledInMixExs() {
        val root = project()

        assertEquals("[] []", read(root))

        edit(project, psi(root, "mix.exs"), mixExs("config_path: \"./config/config.exs\""))

        assertEquals("unknown", read(root))

        edit(project, psi(root, "mix.exs"), mixExs("config_path: \"config/config.exs\""))

        assertEquals("[] []", read(root))
    }

    fun testASubfolderFileEdited() {
        val root = project(CONFIG to "import Config\n\nimport_config \"dev/k.exs\"\n", "config/dev/k.exs" to "import Config\n\nconfig :dep, k: 1\n")

        assertEquals("[{dep,[{k,1}]}] [config/dev/k.exs, config/config.exs]", read(root))

        edit(project, psi(root, "config/dev/k.exs"), "import Config\n\nconfig :dep, k: 2\n")

        assertEquals("[{dep,[{k,2}]}] [config/dev/k.exs, config/config.exs]", read(root))
    }

    fun testAConfigPathEdited() {
        val root = project(CONFIG to "import Config\n\nconfig :dep, k: 1\n", "other/config.exs" to "import Config\n\nconfig :dep, k: 2\n")

        assertEquals("[{dep,[{k,1}]}] [config/config.exs]", read(root))

        edit(project, psi(root, "mix.exs"), mixExs("config_path: \"other/config.exs\""))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("[{dep,[{k,2}]}] [other/config.exs]", read(root))
    }

    fun testTestExsEditedForAFileUnderTest() {
        val root = envFiles("test/support/reader.ex" to READER)

        assertEquals("[{dep,[{k,2}]}] [config/test.exs, config/config.exs]", read(root, "test", "test/support/reader.ex"))

        edit(project, psi(root, "config/test.exs"), "import Config\n\nconfig :dep, k: 3\n")

        assertEquals("[{dep,[{k,3}]}] [config/test.exs, config/config.exs]", read(root, "test", "test/support/reader.ex"))
    }

    /** The `dev` table never read `test.exs`. */
    fun testTestExsEditedForAFileUnderLib() {
        val root = envFiles()
        val reader = root.findFileByRelativePath("lib/reader.ex")!!
        val held = table(reader)!!

        edit(project, psi(root, "config/test.exs"), "import Config\n\nconfig :dep, k: 3\n")

        assertSame(held, table(reader))
    }

    fun testAnUnrelatedLibFileEdited() {
        val root = project(CONFIG to "import Config\n\nconfig :dep, k: 1\n", "lib/other.ex" to "defmodule Other do\nend\n")
        val reader = root.findFileByRelativePath("lib/reader.ex")!!
        val held = table(reader)!!

        edit(project, psi(root, "lib/other.ex"), "defmodule Other do\n  def f, do: 1\nend\n")

        assertSame(held, table(reader))
    }

    /** A second read of an unchanged table doesn't evaluate it again, and whitespace gives what it gave. */
    fun testWhitespaceEdited() {
        val root = project(CONFIG to "import Config\n\nconfig :dep, k: 1\n")
        val reader = root.findFileByRelativePath("lib/reader.ex")!!

        assertSame(table(reader), table(reader))

        edit(project, psi(root, CONFIG), "import Config\n\n\nconfig :dep,  k: 1\n")

        assertEquals("[{dep,[{k,1}]}] [config/config.exs]", read(root))
    }

    private fun envFiles(vararg sources: Pair<String, String>) =
        project(
            CONFIG to "import Config\n\nconfig :dep, k: 1\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n",
            "config/test.exs" to "import Config\n\nconfig :dep, k: 2\n",
            *sources,
        )

    private companion object {
        const val GUARDED =
            "import Config\n\nconfig :dep, k: 1\n\nif File.exists?(\"config/#{config_env()}.exs\") do\n  import_config \"#{config_env()}.exs\"\nend\n"
    }
}
