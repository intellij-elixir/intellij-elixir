package org.elixir_lang.facet

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ExternalLibrariesNode
import com.intellij.ide.projectView.impl.nodes.NamedLibraryElementNode
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.libraryNames
import org.elixir_lang.facet.FacetModelFixtures.Companion.moduleLibrary
import org.elixir_lang.facet.FacetModelFixtures.Companion.nameFacet
import org.elixir_lang.facet.FacetModelFixtures.Companion.storedName
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures.renameInTable
import org.elixir_lang.sdk.SdkFixtures.waitUntil
import java.util.concurrent.Callable

/**
 * When a project opens, or an Elixir SDK is added or renamed, the module library an earlier version wrote in place of
 * an Elixir facet's SDK becomes the name the facet stores, and goes.
 */
class StandInMigrationTest : SmallIdeFacetTestCase() {
    private fun standIn(name: String): String = models.standIn(module, name, testRootDisposable).name

    private fun migrate() {
        runSuspendOnPooledThread { StandInMigration.migrate(project) }
        settle()
    }

    fun testAStandInBecomesTheName() {
        val name = standIn("Stand In Test A")

        migrate()

        assertEquals("the stand-in's name is not the facet's", name, storedName(module))
        assertFalse("the stand-in is still a library of the module", name in libraryNames(module))
    }

    fun testAStandInOverridesAStoredName() {
        val stored = models.register("Stand In Test Stored").name
        nameFacet(module, stored)
        val name = standIn("Stand In Test B")

        migrate()

        assertEquals("the stand-in's name did not replace the stored one", name, storedName(module))
    }

    fun testALibraryNamedLikeNoModelIsKept() {
        facet(module)
        moduleLibrary(module, "Stand In Test Not An SDK")

        migrate()

        assertTrue("a library named like no SDK was removed", "Stand In Test Not An SDK" in libraryNames(module))
        assertNull("a library named like no SDK became the facet's name", storedName(module))
    }

    fun testAStandInWhoseSdkIsAddedLaterGoes() {
        val name = "Stand In Test Later"
        facet(module)
        moduleLibrary(module, name)
        migrate()
        assertTrue("the stand-in went before its SDK was added", name in libraryNames(module))

        models.register(name)
        waitUntil("the stand-in is still a library of the module after its SDK was added") {
            name !in libraryNames(module)
        }

        assertEquals("the SDK added later is not the facet's name", name, storedName(module))
    }

    fun testAStandInWhoseSdkIsRenamedToItGoes() {
        val name = "Stand In Test Renamed"
        facet(module)
        moduleLibrary(module, name)
        models.register("Stand In Test Before Rename")
        migrate()
        assertTrue("the stand-in went before an SDK had its name", name in libraryNames(module))

        renameInTable("Stand In Test Before Rename", name)
        waitUntil("the stand-in is still a library of the module after an SDK was renamed to it") {
            name !in libraryNames(module)
        }

        assertEquals("the SDK renamed to the stand-in is not the facet's name", name, storedName(module))
    }

    fun testASecondRunChangesNothing() {
        standIn("Stand In Test C")
        migrate()
        val name = storedName(module)
        val names = libraryNames(module)
        val count = ProjectRootManager.getInstance(project).modificationCount

        migrate()

        assertEquals("a second run changed the facet's name", name, storedName(module))
        assertEquals("a second run changed the module's libraries", names, libraryNames(module))
        assertEquals("a second run changed the roots", count, ProjectRootManager.getInstance(project).modificationCount)
    }

    fun testOnIntelliJIdeaTheModelShowsOnce() {
        ProcessOutput.isSmallIdeOverride = false
        val name = standIn("Stand In Test IDEA")
        val sdk = Facet.sdkNamed(name)!!
        val previous = ModuleRootManager.getInstance(module).sdk
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        try {
            migrate()

            val entries = ReadAction.nonBlocking(Callable {
                ExternalLibrariesNode(project, ViewSettings.DEFAULT).children
                    .mapNotNull { (it as? NamedLibraryElementNode)?.value?.orderEntry }
                    .filter {
                        (it is JdkOrderEntry && it.jdk == sdk) || (it is LibraryOrderEntry && it.libraryName == name)
                    }
            }).executeSynchronously()
            assertEquals("the SDK does not show once under External Libraries: $entries", 1, entries.size)
            assertTrue("the SDK's node is not the module SDK's: ${entries.single()}", entries.single() is JdkOrderEntry)
        } finally {
            ModuleRootModificationUtil.setModuleSdk(module, previous)
        }
    }
}
