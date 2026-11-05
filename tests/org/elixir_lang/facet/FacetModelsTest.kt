package org.elixir_lang.facet

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import org.elixir_lang.facet.FacetModelFixtures.Companion.addClassesRoot
import org.elixir_lang.facet.FacetModelFixtures.Companion.facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.nameFacet
import org.elixir_lang.facet.FacetModelFixtures.Companion.storedName
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.stub.index.AllName
import org.elixir_lang.sdk.SdkFixtures.renameInTable
import java.util.concurrent.Callable

/**
 * On a small IDE, the SDK an Elixir facet names reaches the project's library roots live, with no library of its own.
 */
class FacetModelsTest : SmallIdeFacetTestCase() {
    private fun isInLibraryClasses(file: VirtualFile): Boolean = FacetModelFixtures.isInLibraryClasses(project, file)

    private fun isInLibrarySource(file: VirtualFile): Boolean = FacetModelFixtures.isInLibrarySource(project, file)

    private fun declarations(name: String): Collection<NamedElement> =
        ReadAction.nonBlocking(Callable {
            val scope = GlobalSearchScope.allScope(project)
            StubIndex.getElements(AllName.KEY, name, project, scope, NamedElement::class.java)
        }).executeSynchronously()

    fun testAFacetModelsRootsAreLibraryRoots() {
        val roots = models.roots("FacetModelsTestA", testRootDisposable)
        val sdk = models.register("Facet Models Test A", roots)

        nameFacet(module, sdk.name)
        settle()

        assertTrue("the named SDK's beam is not in the library classes", isInLibraryClasses(roots.beam))
        assertTrue("the named SDK's source is not in the library sources", isInLibrarySource(roots.source))
        assertEquals(
            "the named SDK's module is not indexed",
            listOf(roots.source),
            declarations(roots.moduleName).map { it.containingFile.virtualFile },
        )
    }

    fun testARootAddedToTheModelIsALibraryRoot() {
        val roots = models.roots("FacetModelsTestB", testRootDisposable)
        val added = models.roots("FacetModelsTestBAdded", testRootDisposable)
        val sdk = models.register("Facet Models Test B", roots)
        nameFacet(module, sdk.name)
        settle()

        addClassesRoot(sdk, added.ebin)
        settle()

        assertTrue("a root added to the named SDK is not in the library classes", isInLibraryClasses(added.beam))
    }

    fun testReassigningTheNameMovesTheRoots() {
        val mRoots = models.roots("FacetModelsTestM", testRootDisposable)
        val nRoots = models.roots("FacetModelsTestN", testRootDisposable)
        val m = models.register("Facet Models Test M", mRoots)
        val n = models.register("Facet Models Test N", nRoots)
        nameFacet(module, m.name)
        settle()

        nameFacet(module, n.name)
        settle()

        assertTrue("the newly named SDK's beam is not in the library classes", isInLibraryClasses(nRoots.beam))
        assertFalse("the SDK no longer named is still in the library classes", isInLibraryClasses(mRoots.beam))
    }

    fun testAReaddedModelsNewRootIsALibraryRoot() {
        val roots = models.roots("FacetModelsTestR", testRootDisposable)
        val added = models.roots("FacetModelsTestRAdded", testRootDisposable)
        val removed = models.register("Facet Models Test R", roots)
        nameFacet(module, removed.name)
        settle()
        models.remove(removed)
        settle()
        val readded = models.register(removed.name, roots)
        settle()

        addClassesRoot(readded, added.ebin)
        settle()

        assertTrue("a root added to the re-added SDK is not in the library classes", isInLibraryClasses(added.beam))
    }

    fun testRenamingTheModelRenamesTheFacetsName() {
        val roots = models.roots("FacetModelsTestRenamed", testRootDisposable)
        val sdk = models.register("Facet Models Test Before Rename", roots)
        nameFacet(module, sdk.name)
        settle()

        // As Settings renames an SDK: its editable copy, renamed, updates it in the table.
        renameInTable(sdk.name, "Facet Models Test After Rename")
        settle()

        assertEquals("the facet does not supply the renamed SDK", sdk, facet(module).sdk)
        assertEquals("the facet does not store the SDK's new name", sdk.name, storedName(module))
        assertTrue("the renamed SDK's beam is not in the library classes", isInLibraryClasses(roots.beam))
    }
}
