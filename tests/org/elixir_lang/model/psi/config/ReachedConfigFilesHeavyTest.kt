package org.elixir_lang.model.psi.config

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ConfigHeavyTestCase
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.expander.edit
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.util.concurrent.Callable

/**
 * [ConfigFiles.counted]: the config folder's `.exs` files, and the files `import_config` reaches from them. On disk,
 * because an import under a light fixture's `temp://` files declines.
 */
class ReachedConfigFilesHeavyTest : ConfigHeavyTestCase() {
    private var savedProperty: String? = null

    override fun setUp() {
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        super.setUp()
        enableExpander(testRootDisposable)
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
        }
    }

    fun testAFileOnlyProdImportsCounts() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            DEV to "import Config\n",
            "config/test.exs" to "import Config\n",
            "config/prod.exs" to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG, DEV, "config/prod.exs", "config/prod/database.exs", "config/test.exs"), counted(root))
    }

    fun testASubfolderFileNothingImportsDoesNotCount() {
        val root = project(CONFIG to "import Config\n", "config/prod/database.exs" to "import Config\n")

        assertEquals(listOf(CONFIG), counted(root))
    }

    fun testASiblingNothingImportsStillCounts() {
        val root = project(CONFIG to "import Config\n", "config/staging.exs" to "import Config\n")

        assertEquals(listOf(CONFIG, "config/staging.exs"), counted(root))
    }

    fun testAnInterpolatedSubfolderImportCounts() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"#{config_env()}/database.exs\"\n",
            "config/dev/database.exs" to "import Config\n",
            "config/prod/database.exs" to "import Config\n",
            "config/staging/database.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG, "config/dev/database.exs", "config/prod/database.exs"), counted(root))
    }

    fun testAMixConfigWildcardImportCounts() {
        val root = project(
            CONFIG to "use Mix.Config\n\nimport_config \"extra/*.exs\"\n",
            "config/extra/a.exs" to "use Mix.Config\n",
            "config/extra/b.exs" to "use Mix.Config\n",
            "config/other/c.exs" to "use Mix.Config\n",
        )

        assertEquals(listOf(CONFIG, "config/extra/a.exs", "config/extra/b.exs"), counted(root))
    }

    fun testAnImportOutsideTheConfigFolderCounts() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"../shared/config.exs\"\n",
            "shared/config.exs" to "import Config\n",
            "shared/unused.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG, "shared/config.exs"), counted(root))
    }

    /** What a declined statement might have imported isn't known to be reached. */
    fun testAnImportInsideADeclinedUnitDoesNotCount() {
        val root = project(
            CONFIG to "import Config\n\nif System.get_env(\"HOME\") do\n  import_config \"prod/database.exs\"\nend\n",
            "config/prod/database.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG), counted(root))
    }

    fun testNoSdkInputsCountsOnlyTheConfigFolder() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n",
            "config/staging.exs" to "import Config\n",
        )
        ModuleRootModificationUtil.setModuleSdk(module, null)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals(listOf(CONFIG, "config/staging.exs"), counted(root))
    }

    fun testRemovingTheImportStopsItCounting() {
        val root = project(
            CONFIG to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n",
        )

        assertEquals("before", listOf(CONFIG, "config/prod/database.exs"), counted(root))

        edit(project, psi(root, CONFIG), "import Config\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("after", listOf(CONFIG), counted(root))
    }

    fun testFlagOffCountsOnlyTheConfigFolder() {
        ElixirExperimentalSettings.instance.state.enableExpander = false
        val root = project(
            CONFIG to "import Config\n\nimport_config \"prod/database.exs\"\n",
            "config/prod/database.exs" to "import Config\n",
            "config/staging.exs" to "import Config\n",
        )

        assertEquals(listOf(CONFIG, "config/staging.exs"), counted(root))
        assertNull(project.getServiceIfCreated(ProjectModules::class.java))
        assertNull(project.getServiceIfCreated(SdkInputs::class.java))
    }

    /** With no `import_config`, [ConfigFiles.scope] holds every file a build reads, so no expander service starts. */
    fun testNoImportStartsNoExpanderService() {
        val root = project(CONFIG to "import Config\n", "config/staging.exs" to "import Config\n")

        assertEquals(listOf(CONFIG, "config/staging.exs"), counted(root))
        assertNull(project.getServiceIfCreated(ProjectModules::class.java))
    }

    /** Each `.exs` file under [root], as a path from it, that [ConfigFiles.counted] holds. */
    private fun counted(root: VirtualFile): List<String> {
        val files = mutableListOf<VirtualFile>()

        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file.name != ".idea"
                if (file.extension == "exs") files += file

                return true
            }
        })

        val held = ReadAction.nonBlocking(Callable {
            val scope = ConfigFiles.counted(project)

            files.filter { scope.contains(it) }
        }).executeSynchronously()

        return held
            .mapNotNull { FileUtil.getRelativePath(root.path, it.path, '/') }
            .sorted()
    }
}
