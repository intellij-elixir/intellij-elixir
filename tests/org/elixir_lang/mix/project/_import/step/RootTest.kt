package org.elixir_lang.mix.project._import.step

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.project._import.Builder
import org.elixir_lang.mix.project._import.mixExs
import java.nio.file.Path

/** The Mix project root the import's Root page imports from. */
class RootTest : HeavyTestCase() {
    private val builder = Builder()

    override fun tearDown() {
        try {
            builder.cleanup()
        } finally {
            super.tearDown()
        }
    }

    /**
     * Project Structure's Import Module gives the wizard the open project's folder as its project file directory and
     * hands the folder chosen to the builder alone.
     */
    fun testImportModuleImportsTheFolderChosen() {
        val open = mixProject("open")
        val chosen = mixProject("chosen")
        val context = WizardContext(project, Disposer.newDisposable(testRootDisposable))
        context.setProjectFileDirectory(Path.of(open.path), true)
        context.projectBuilder = builder
        builder.fileToImport = chosen.path
        val root = Root(context)

        root.updateStep()
        assertTrue(root.validate())

        assertEquals(listOf("chosen"), builder.list.map { it.name })
    }

    private fun mixProject(name: String): VirtualFile {
        val root = tempDir.createVirtualDir(name)
        mixExs(root, name)
        return root
    }
}
