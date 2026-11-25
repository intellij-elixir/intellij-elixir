package org.elixir_lang.expander

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.beam.BeamLibraryFixture
import java.io.File

/** A Mix project on disk under the leg's SDK, with a dependency's `.beam` attached as Mix sync attaches it. */
abstract class MixProjectHeavyTestCase : LegSdkHeavyTestCase() {
    protected fun defines(root: VirtualFile, path: String): String = defines(holder, psi(root, path))

    /** [bytes] as the `.beam` [name] of dependency `d` in `_build/dev` and `_build/test`, attached as Mix sync attaches it. */
    protected fun dependencyBuilt(root: VirtualFile, name: String = DEP_BEAM, bytes: ByteArray = File(FIXTURES, name).readBytes()) {
        val ebins = listOf("dev", "test").map { env ->
            val beam = File(root.path, "_build/$env/lib/d/ebin/$name")
            FileUtil.writeToFile(beam, bytes)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam.parentFile)!!
        }
        PsiTestUtil.addExcludedRoot(module, root.findChild("_build")!!)
        BeamLibraryFixture.addLibrary(project, module, "d", ebins)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** A content root holding [mixExs], where not `null`, and [sources], with `lib` and `test` as source roots and `deps` excluded. */
    protected fun project(vararg sources: Pair<String, String>, mixExs: String? = MIX_EXS): VirtualFile {
        val directory = createTempDir("app")
        (listOfNotNull(mixExs?.let { "mix.exs" to it }) + sources).forEach { (path, text) ->
            FileUtil.writeToFile(File(directory, path), text)
        }
        val root = rooted(directory)
        root.findChild("deps")?.let { PsiTestUtil.addExcludedRoot(module, it) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    protected companion object {
        val FIXTURES = File("testData/org/elixir_lang/expander/loaded_copy")
        const val DEP = "LoadedCopy.Dep"
        const val DEP_BEAM = "Elixir.LoadedCopy.Dep.beam"
    }
}
