package org.elixir_lang.facet

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ExternalLibrariesNode
import com.intellij.openapi.application.ReadAction
import org.elixir_lang.facet.FacetModelFixtures.Companion.assign
import org.elixir_lang.facet.FacetModelFixtures.Companion.facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.libraryNames
import org.elixir_lang.facet.FacetModelFixtures.Companion.nameFacet
import org.elixir_lang.facet.FacetModelFixtures.Companion.storedName
import java.util.concurrent.Callable

/** On a small IDE the Elixir facet stores its SDK by name, and the name outlives the SDK it names. */
class FacetSdkRootsTest : SmallIdeFacetTestCase() {
    fun testAssignmentStoresTheName() {
        val sdk = models.register("Facet Name Test A")

        assign(module, sdk)

        assertEquals("the facet does not store the assigned SDK's name", sdk.name, storedName(module))
    }

    fun testRemovingTheModelLeavesTheName() {
        val sdk = models.register("Facet Name Test B")
        nameFacet(module, sdk.name)
        assertEquals("the facet does not supply the SDK it names", sdk, facet(module).sdk)

        models.remove(sdk)

        assertNull("the facet supplies an SDK that is no longer registered", facet(module).sdk)
        assertEquals("removing the SDK changed the name the facet stores", sdk.name, storedName(module))
    }

    fun testReaddingTheModelRelinksIt() {
        val removed = models.register("Facet Name Test C")
        nameFacet(module, removed.name)
        models.remove(removed)

        val readded = models.register(removed.name)

        assertEquals("the facet does not supply the SDK re-added under its stored name", readded, facet(module).sdk)
    }

    fun testAssignmentWritesNoLibrary() {
        val sdk = models.register("Facet Name Test D")

        assign(module, sdk)

        val libraryNames = libraryNames(module)
        assertFalse("assigning the SDK wrote a library named after it: $libraryNames", sdk.name in libraryNames)
    }

    fun testTheModelShowsOnceUnderExternalLibraries() {
        val sdk = models.register("Facet Name Test E", models.roots("FacetSdkRootsTestShown", testRootDisposable))

        assign(module, sdk)
        settle()

        val shown = ReadAction.nonBlocking(Callable {
            ExternalLibrariesNode(project, ViewSettings.DEFAULT).children.map { node ->
                node.update()
                node.presentation.presentableText
            }
        }).executeSynchronously()
        assertEquals("the SDK does not show once under External Libraries: $shown", 1, shown.count { it == sdk.name })
    }
}
