package org.elixir_lang.facet.configurable

import com.intellij.facet.FacetManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.SmallIdeFacetHeavyTestCase
import org.elixir_lang.facet.sdk.ComboBox
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/** The small IDEs' Settings > Elixir page, applied with its one module's SDK combo set to a pick. */
class ProjectTest : SmallIdeFacetHeavyTestCase() {
    private fun applyPick(pick: Sdk?) {
        val page = Project(project)
        Disposer.register(testRootDisposable) { page.disposeUIResources() }
        val combo = UIUtil.findComponentsOfType(page.createComponent(), ComboBox::class.java).single()

        combo.selectedItem = pick?.let { SdksService.getInstance()!!.getModel().findSdk(it.name) }
        page.apply()
    }

    private val facet: Facet? get() = FacetManager.getInstance(module).getFacetByType(Facet.ID)

    fun testAPickAddsTheFacetAndNamesIt() {
        val pick = models.register("pick")

        applyPick(pick)

        assertEquals("Elixir facet", facet?.name)
        assertEquals("pick", FacetModelFixtures.storedName(module))
    }

    fun testAPickNotYetInTheTableIsNamed() {
        val pick = ProjectJdkImpl("not yet applied", ElixirSdkType.instance)
        SdksService.getInstance()!!.getModel().addSdk(pick)

        applyPick(pick)

        assertEquals("not yet applied", FacetModelFixtures.storedName(module))
    }

    fun testNoSdkClearsTheName() {
        FacetModelFixtures.assign(module, models.register("before"))

        applyPick(null)

        assertNotNull(facet)
        assertNull(FacetModelFixtures.storedName(module))
    }

    fun testNoSdkAddsNoFacet() {
        applyPick(null)

        assertNull(facet)
    }
}
