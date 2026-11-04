package org.elixir_lang.facet

import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.facet.FacetModelFixtures.Companion.assign
import java.io.File

/** What a small IDE's Elixir facet writes to the module's `.iml`. */
class FacetSdkHeavyTest : SmallIdeFacetHeavyTestCase() {
    fun testAssignmentSavesTheName() {
        val sdk = models.register("Facet Heavy Test A")

        assign(module, sdk)

        val saved = savedModuleFile()
        assertTrue("the facet's configuration saved no sdkName:\n$saved", saved.contains("sdkName=\"${sdk.name}\""))
    }
}
