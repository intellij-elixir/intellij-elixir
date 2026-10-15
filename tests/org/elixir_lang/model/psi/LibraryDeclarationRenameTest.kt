package org.elixir_lang.model.psi

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.Type
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import java.io.File

/**
 * Rename refuses a declaration that lives in a library or an SDK, because the project does not own those files and
 * an edit to them is lost the next time the dependency is fetched. Anything the project does own still renames:
 * a path dependency under a content root, a scratch file, a file opened from outside the project.
 */
class LibraryDeclarationRenameTest : PlatformTestCase() {
    private val cleanups = mutableListOf<() -> Unit>()

    override fun tearDown() {
        runAll(
            *cleanups.asReversed().map { cleanup -> { cleanup() } }.toTypedArray(),
            { super.tearDown() },
        )
    }

    fun testSdkSourceIsRefused() {
        val declaration = outsideProject("sdk/lib/elixir/lib", "sdk_lib.ex", LIBRARY_SOURCE)
        val sdk = registerSdk("Library Rename Test Module SDK", declaration.parent)

        WriteAction.runAndWait<Throwable> { ModuleRootModificationUtil.setModuleSdk(module, sdk) }
        cleanups += { WriteAction.runAndWait<Throwable> { ModuleRootModificationUtil.setModuleSdk(module, null) } }

        assertRenameRefused(declaration)
    }

    fun testFacetSdkSourceIsRefused() {
        val declaration = outsideProject("facet_sdk/lib/elixir/lib", "sdk_lib.ex", LIBRARY_SOURCE)
        val sdk = registerSdk("Library Rename Test Facet SDK", declaration.parent)

        if (FacetManager.getInstance(module).getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
        SdksService.getInstance()!!.resetForTests()
        WriteAction.runAndWait<Throwable> { FacetManager.getInstance(module).getFacetByType(Facet.ID)!!.sdk = sdk }
        cleanups += {
            WriteAction.runAndWait<Throwable> { FacetManager.getInstance(module).getFacetByType(Facet.ID)?.sdk = null }
            SdksService.getInstance()!!.resetForTests()
        }

        assertRenameRefused(declaration)
    }

    fun testExcludedDepsLibrarySourceIsRefused() {
        val depsDirectory = myFixture.tempDirFixture.findOrCreateDir("deps")
        val declaration = myFixture.tempDirFixture.createFile("deps/lib/lib/lib.ex", LIBRARY_SOURCE)

        PsiTestUtil.addExcludedRoot(module, depsDirectory)
        cleanups += { PsiTestUtil.removeExcludedRoot(module, depsDirectory) }
        addSourcesLibrary(declaration.parent)

        val (inLibrary, inContent) = ReadAction.computeBlocking<Pair<Boolean, Boolean>, Throwable> {
            val index = ProjectFileIndex.getInstance(project)
            Pair(index.isInLibrary(declaration), index.isInContent(declaration))
        }
        assertTrue("the excluded dependency's source should be in a library", inLibrary)
        assertFalse("the excluded dependency's source should be outside content", inContent)

        assertRenameRefused(declaration)
    }

    fun testPathDependencyInContentRenames() {
        val declaration = myFixture.tempDirFixture.createFile("vendor/lib/lib/lib.ex", LIBRARY_SOURCE)

        addSourcesLibrary(declaration.parent)

        assertRenamed(declaration)
    }

    fun testExternalPathDependencyIsRefused() {
        val declaration = outsideProject("path_dependency/lib", "lib.ex", LIBRARY_SOURCE)

        addSourcesLibrary(declaration.parent)

        assertRenameRefused(declaration)
    }

    /**
     * A function that implements a callback declared in a library is one symbol with it, so renaming it from a call
     * would rewrite the library's `@callback`. Rename refuses, as it does from the implementing head.
     */
    fun testImplementedCallbackInALibraryIsRefusedFromACall() {
        val declaration = outsideProject("path_dependency_callback/lib", "lib.ex", CALLBACK_SOURCE)

        addSourcesLibrary(declaration.parent)

        assertRenameRefused(declaration, IMPLEMENTER, CALLBACK_SOURCE)
    }

    /** The same from the head of a `defdelegate` with no `as:` to the implementing function, which names it too. */
    fun testImplementedCallbackInALibraryIsRefusedFromADelegationHead() {
        val declaration = outsideProject("path_dependency_callback_delegation/lib", "lib.ex", CALLBACK_SOURCE)

        addSourcesLibrary(declaration.parent)

        assertRenameRefused(declaration, DELEGATION_TO_IMPLEMENTER, CALLBACK_SOURCE)
    }

    /** The same from an atom naming the implementing function. */
    fun testImplementedCallbackInALibraryIsRefusedFromAnAtom() {
        val declaration = outsideProject("path_dependency_callback_atom/lib", "lib.ex", CALLBACK_SOURCE)

        addSourcesLibrary(declaration.parent)

        assertRenameRefused(declaration, IMPLEMENTER_ATOM, CALLBACK_SOURCE)
    }

    fun testScratchFileRenames() {
        val scratch = ScratchRootType.getInstance().createScratchFile(
            project,
            "scratch.ex",
            ElixirLanguage,
            "defmodule Scratch do\n  def fun, do: :ok\nend\n",
            ScratchFileService.Option.create_if_missing
        )!!
        cleanups += { WriteAction.runAndWait<Throwable> { scratch.delete(this) } }

        myFixture.configureFromExistingVirtualFile(scratch)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("fun") + 1)
        myFixture.renameTargetAtCaret("fresh")

        assertEquals("defmodule Scratch do\n  def fresh, do: :ok\nend\n", myFixture.file.text)
    }

    fun testLooseFileOutsideProjectRenames() {
        val loose = outsideProject("loose", "loose.ex", "defmodule Loose do\n  def fun, do: :ok\nend\n")

        myFixture.configureFromExistingVirtualFile(loose)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("fun") + 1)
        myFixture.renameTargetAtCaret("fresh")

        assertEquals("defmodule Loose do\n  def fresh, do: :ok\nend\n", myFixture.file.text)
    }

    /** A file outside every content root, on disk, as an SDK's or an external dependency's sources are. */
    private fun outsideProject(directory: String, name: String, text: String): VirtualFile {
        val root = FileUtil.createTempDirectory("library_declaration_rename", null, true)
        val file = File(root, "$directory/$name").also {
            it.parentFile.mkdirs()
            it.writeText(text)
        }

        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, root.path)

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    }

    private fun registerSdk(name: String, sourcesRoot: VirtualFile): Sdk {
        val sdk = ProjectJdkImpl(name, ElixirSdkType.instance)

        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(sourcesRoot, OrderRootType.SOURCES)
                commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk)
        }
        cleanups += {
            WriteAction.runAndWait<Throwable> {
                ProjectJdkTable.getInstance().takeIf { sdk in it.allJdks }?.removeJdk(sdk)
            }
        }
        SdksService.getInstance()!!.resetForTests()

        return sdk
    }

    private fun addSourcesLibrary(sourcesRoot: VirtualFile) {
        BeamLibraryFixture.addLibrary(project, module, LIBRARY_NAME, emptyList(), listOf(sourcesRoot))
        cleanups += { BeamLibraryFixture.removeLibrary(project, module, LIBRARY_NAME) }
    }

    private fun configureCaller(caller: String) {
        myFixture.configureByText("caller.ex", caller)
    }

    @Suppress("UnstableApiUsage")
    private fun assertRenameRefused(
        declaration: VirtualFile,
        caller: String = CALLER,
        librarySource: String = LIBRARY_SOURCE
    ) {
        configureCaller(caller)
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "fresh") }.exceptionOrNull()

            assertTrue(
                "Renaming $target, declared in a library or SDK, should be refused, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(REFUSAL) }
            )
        }

        assertEquals(librarySource, textOf(declaration))
    }

    private fun assertRenamed(declaration: VirtualFile) {
        configureCaller(CALLER)
        myFixture.renameTargetAtCaret("fresh")

        assertEquals(LIBRARY_SOURCE.replace("fun", "fresh"), textOf(declaration))
        assertEquals(CALLER.replace("<caret>", "").replace("fun", "fresh"), myFixture.file.text)
    }

    private fun textOf(file: VirtualFile): String =
        ReadAction.computeBlocking<String, Throwable> { FileDocumentManager.getInstance().getDocument(file)!!.text }

    private companion object {
        const val LIBRARY_NAME = "library_declaration_rename_test_lib"
        const val REFUSAL = "declared outside the project, in a library or SDK"
        const val LIBRARY_SOURCE = "defmodule Lib do\n  def fun, do: :ok\nend\n"
        const val CALLBACK_SOURCE = "defmodule Lib do\n  @callback fun() :: :ok\nend\n"
        const val IMPLEMENTER =
            "defmodule Caller do\n  @behaviour Lib\n\n  @impl true\n  def fun, do: :ok\n\n  def run, do: fu<caret>n()\nend\n"
        const val IMPLEMENTER_ATOM =
            "defmodule Caller do\n  @behaviour Lib\n\n  @impl true\n  def fun, do: :ok\n\n  def run, do: apply(Caller, :fu<caret>n, [])\nend\n"
        const val CALLER = "defmodule Caller do\n  def run, do: Lib.fu<caret>n()\nend\n"
        const val DELEGATION_TO_IMPLEMENTER =
            "defmodule Caller do\n  @behaviour Lib\n\n  @impl true\n  def fun, do: :ok\nend\n\n" +
                "defmodule Delegating do\n  defdelegate fu<caret>n(), to: Caller\nend\n"
    }
}
