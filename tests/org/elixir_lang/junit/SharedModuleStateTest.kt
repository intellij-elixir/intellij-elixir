package org.elixir_lang.junit

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.facet.Type
import org.elixir_lang.sdk.SdkFixtures
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService

class SharedModuleStateTest : PlatformTestCase() {
    /** Everything a light test can leave on the shared module and project is taken off again. */
    fun testRestoreRemovesWhatATestAdded() {
        val before = SharedModuleState.of(module)
        val sourceRootUrl = myFixture.tempDirFixture.findOrCreateDir("").url
        val addedRoot = myFixture.tempDirFixture.findOrCreateDir("restored_app")
        WriteAction.runAndWait<Throwable> { FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java)) }
        val library = WriteAction.computeAndWait<com.intellij.openapi.roots.libraries.Library, Throwable> {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary("restored_library")
        }
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.addContentEntry(addedRoot)
            model.contentEntries.single { it.url == sourceRootUrl }.addExcludeFolder("$sourceRootUrl/excluded")
            model.addLibraryEntry(library)
            model.addInvalidModuleEntry("restored_module")
        }
        assertFalse("the fixture must change the state", before.differences(SharedModuleState.of(module)).isEmpty())

        SharedModuleState.restore(module, before)

        assertEquals(emptyList<String>(), before.differences(SharedModuleState.of(module)))
    }

    /** A module SDK and a source folder on the fixture's own root are taken off too. */
    @RequiresEdt
    fun testRestoreRemovesAnSdkAndASourceFolder() {
        val before = SharedModuleState.of(module)
        val sourceRootUrl = myFixture.tempDirFixture.findOrCreateDir("").url
        val home = myFixture.tempDirFixture.findOrCreateDir("restored_sdk_home").path
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("Restored SDK", home), testRootDisposable)
        WriteAction.runAndWait<Throwable> {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
            FacetManager.getInstance(module).getFacetByType(Facet.ID)!!.sdk = sdk
        }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.single { it.url == sourceRootUrl }
                .addSourceFolder("$sourceRootUrl/restored_sources", false)
        }
        assertFalse("the fixture must change the state", before.differences(SharedModuleState.of(module)).isEmpty())

        SharedModuleState.restore(module, before)

        assertEquals(emptyList<String>(), before.differences(SharedModuleState.of(module)))
    }

    /** A name a test leaves stored on a kept facet is reported. */
    fun testDifferencesReportAFacetsStoredName() {
        FacetModelFixtures.facet(module)
        val before = SharedModuleState.of(module)

        FacetModelFixtures.nameFacet(module, "Shared Module State SDK")

        assertFalse(
            "a facet's stored name is not part of the state",
            before.differences(SharedModuleState.of(module)).isEmpty(),
        )
    }

    /** A source folder re-marked as test sources keeps its URL, and is put back as it was, properties included. */
    fun testRestorePutsBackASourceFolderWhoseTypeChanged() {
        val sourceRootUrl = myFixture.tempDirFixture.findOrCreateDir("").url
        val folderUrl = "$sourceRootUrl/retyped_sources"
        ModuleRootModificationUtil.updateModel(module) { model ->
            val properties = JpsJavaExtensionService.getInstance().createSourceRootProperties("retyped.prefix", true)
            model.contentEntries.single { it.url == sourceRootUrl }
                .addSourceFolder(folderUrl, JavaSourceRootType.SOURCE, properties)
        }
        val before = SharedModuleState.of(module)
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.single { it.url == sourceRootUrl }
            entry.sourceFolders.filter { it.url == folderUrl }.forEach(entry::removeSourceFolder)
            entry.addSourceFolder(folderUrl, true)
        }

        assertEquals(emptyList<String>(), before.differences(SharedModuleState.restore(module, before)))
        val restored = ReadAction.computeBlocking<SharedModuleState.SourceFolderState, Throwable> {
            SharedModuleState.SourceFolderState(
                ModuleRootManager.getInstance(module).contentEntries.single { it.url == sourceRootUrl }
                    .sourceFolders.single { it.url == folderUrl },
            )
        }
        assertEquals(
            SharedModuleState.SourceFolderState(folderUrl, JavaSourceRootType.SOURCE, "retyped.prefix", "", true),
            restored,
        )
    }

    /** A resource folder re-marked as test resources is put back with its output path and generated flag. */
    fun testRestorePutsBackAResourceFolderWhoseTypeChanged() {
        val sourceRootUrl = myFixture.tempDirFixture.findOrCreateDir("").url
        val folderUrl = "$sourceRootUrl/retyped_resources"
        ModuleRootModificationUtil.updateModel(module) { model ->
            val properties = JpsJavaExtensionService.getInstance().createResourceRootProperties("assets/out", true)
            model.contentEntries.single { it.url == sourceRootUrl }
                .addSourceFolder(folderUrl, JavaResourceRootType.RESOURCE, properties)
        }
        val before = SharedModuleState.of(module)
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.single { it.url == sourceRootUrl }
            entry.sourceFolders.filter { it.url == folderUrl }.forEach(entry::removeSourceFolder)
            entry.addSourceFolder(folderUrl, JavaResourceRootType.TEST_RESOURCE)
        }

        assertEquals(emptyList<String>(), before.differences(SharedModuleState.restore(module, before)))
        val restored = ReadAction.computeBlocking<SharedModuleState.SourceFolderState, Throwable> {
            SharedModuleState.SourceFolderState(
                ModuleRootManager.getInstance(module).contentEntries.single { it.url == sourceRootUrl }
                    .sourceFolders.single { it.url == folderUrl },
            )
        }
        assertEquals(
            SharedModuleState.SourceFolderState(folderUrl, JavaResourceRootType.RESOURCE, "", "assets/out", true),
            restored,
        )
    }

    /** What the restore does not put back, such as a removed entry, is reported rather than hidden. */
    fun testDifferencesReportWhatTheRestoreCannotUndo() {
        val now = SharedModuleState.of(module)
        val before = now.copy(
            contentEntries = now.contentEntries +
                ("temp:///src/removed_app" to SharedModuleState.ContentEntryState(emptySet(), emptySet())),
        )

        SharedModuleState.restore(module, before)

        assertEquals(
            listOf("content entry removed: temp:///src/removed_app"),
            before.differences(SharedModuleState.of(module)),
        )
    }
}
