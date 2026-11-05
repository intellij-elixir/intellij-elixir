package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.mix.sync.contentRootToken
import org.elixir_lang.mix.sync.scopedDepLibraryName
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import java.io.File

/**
 * What the expander answers a use of a module that a library or the SDK holds as a `.beam` with: the definitions of the
 * source the same library or SDK attaches, where it attaches one, as the walk finds them; the `.beam`'s otherwise.
 */
class SourceOfCompiledHeavyTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        // The sources `ElixirSdkPathConfigurator` attaches, and Erlang's `stdlib`, as `ElixirErlangClasspath` adds the Erlang
        // SDK's code paths.
        val stdlib = appEbins("ERLANG_SDK_HOME").single { it.parentFile.name.startsWith("stdlib-") }
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                ElixirSdkPathConfigurator.addSourcePaths(this)
                addRoot(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(stdlib)!!, OrderRootType.CLASSES)
                commitChanges()
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // The cost of reading the heads.

    /** A module's heads, the nine its clauses write with an atom name, are read from its source once; a second lookup reads none. */
    fun testASecondLookupInAModuleIndexesNothing() {
        dependency()
        val source = read { PsiManager.getInstance(project).findFile(dir("deps/d/lib/phoenix_component.ex")) as ElixirFile }

        val indexed = read {
            (1..2).map {
                ExpanderSource.Probe.reset()
                ExpanderSource.Probe.counting { NameIndex.of(source, "Phoenix.Component") }
                ExpanderSource.Probe.snapshot().getValue(ExpanderSource.Probe.Counter.INDEXED)
            }
        }

        assertEquals(listOf(9L, 0L), indexed)
    }

    /** The fixture's sources in `deps/d/lib` and their `.beam`s in `_build/dev/lib/d/ebin`, in the library Mix sync builds for `d`. */
    private fun dependency() {
        write("deps/d/lib" to sources(), "_build/dev/lib/d/ebin" to beams())
        exclude("deps", "_build")
        ModuleRootModificationUtil.addDependency(module, synced("d"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** The library Mix sync built from the project's `deps` and `_build` for [name], which the module is then given. */
    private fun synced(name: String): Library {
        val scoped = scopedDepLibraryName(contentRootToken(project, root().url), name)
        val library = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(scoped)
        assertNotNull("Mix sync's `$scoped` library", library)

        return library!!
    }

    /** Copies each of the fixture's files named in each pair into the directory it names, under the project. */
    private fun write(vararg into: Pair<String, List<String>>) {
        for ((directory, names) in into) {
            val target = File(myFixture.tempDirPath, directory).apply { mkdirs() }
            names.forEach { File(FIXTURES, it).copyTo(File(target, it)) }
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, root())
        // The `deps` and `_build` files queue a Mix deps sync, whose libraries are the ones attached.
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
    }

    private fun exclude(vararg directories: String) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.single()
            directories.forEach { entry.addExcludeFolder("${root().url}/$it") }
        }
    }

    private fun sources(): List<String> = fixtures(".ex")

    private fun beams(): List<String> = fixtures(".beam")

    private fun fixtures(extension: String): List<String> = FIXTURES.list()!!.filter { it.endsWith(extension) }.sorted()

    private fun root(): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(myFixture.tempDirPath)!!

    private fun dir(path: String): VirtualFile = myFixture.findFileInTempDir(path)

    private companion object {
        val FIXTURES: File = File("testData/org/elixir_lang/expander/source_of_compiled").absoluteFile
    }
}
