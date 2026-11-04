package org.elixir_lang.facet

import org.elixir_lang.facet.FacetModelFixtures.Companion.assign
import org.elixir_lang.facet.FacetModelFixtures.Companion.facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.nameFacet
import org.elixir_lang.facet.FacetModelFixtures.Companion.storedName

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
}
