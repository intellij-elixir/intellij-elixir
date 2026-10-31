package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.Callable

/** The config files the `:dev`, `:test` and `:prod` reads of a config path import, `config.exs` included. */
class ConfigReachedHeavyTest : ConfigHeavyTestCase() {
    fun testASubfolderFileImportConfigReachesIsReached() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n",
            "config/test.exs" to "import Config\n",
            "config/prod.exs" to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n",
        )

        assertTrue("config/prod/database.exs" in reached(root))
    }

    fun testASubfolderFileNothingImportsIsNotReached() {
        val root = project(CONFIG to "import Config\n", "config/prod/database.exs" to "import Config\n")

        assertFalse("config/prod/database.exs" in reached(root))
    }

    /** The build fails at the missing import, so the file naming it was read. */
    fun testAFileAReadThatRaisesImportedIsReached() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n",
            "config/test.exs" to "import Config\n",
            "config/prod.exs" to "import Config\n\nimport_config \"prod.secret.exs\"\n",
        )

        assertEquals(listOf(CONFIG, DEV, "config/prod.exs", "config/test.exs"), reached(root))
    }

    fun testTheReachedSetUnionsDevTestAndProd() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n",
            "config/test.exs" to "import Config\n",
            "config/prod.exs" to "import Config\n",
            "config/staging.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG, DEV, "config/prod.exs", "config/test.exs"), reached(root))
    }

    private fun reached(root: VirtualFile): List<String> =
        ReadAction.nonBlocking(Callable { holder.reachedConfigFiles(legInputs) }).executeSynchronously()
            .mapNotNull { FileUtil.getRelativePath(root.path, it.path, '/') }
            .sorted()
}
