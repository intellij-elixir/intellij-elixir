package org.elixir_lang.facet

import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.facet.FacetModelFixtures.Companion.assign
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** What a small IDE's Elixir facet writes to the module's `.iml`. */
class FacetSdkHeavyTest : SmallIdeFacetHeavyTestCase() {
    fun testAssignmentSavesTheName() {
        val sdk = models.register("Facet Heavy Test A")

        assign(module, sdk)

        val saved = savedModuleFile()
        assertTrue("the facet's configuration saved no sdkName:\n$saved", saved.contains("sdkName=\"${sdk.name}\""))
    }

    fun testAProjectOpenedWithANameHasItsModelsRoots() {
        val roots = models.roots("FacetSdkHeavyTestB", testRootDisposable)
        val sdk = models.register("Facet Heavy Test B", roots)
        val projectDir = createTempDir("facet_named_project").toPath()
        projectDir.resolve(".idea").createDirectories().resolve("modules.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectModuleManager">
                <modules>
                  <module fileurl="file://${'$'}PROJECT_DIR${'$'}/named.iml"
                          filepath="${'$'}PROJECT_DIR${'$'}/named.iml" />
                </modules>
              </component>
            </project>
            """.trimIndent()
        )
        projectDir.resolve("named.iml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <module type="EMPTY_MODULE" version="4">
              <component name="FacetManager">
                <facet type="Elixir" name="Elixir">
                  <configuration sdkName="${sdk.name}" />
                </facet>
              </component>
              <component name="NewModuleRootManager">
                <content url="file://${'$'}MODULE_DIR${'$'}" />
                <orderEntry type="sourceFolder" forTests="false" />
              </component>
            </module>
            """.trimIndent()
        )

        val opened = PlatformTestUtil.loadAndOpenProject(projectDir, testRootDisposable)
        FacetModelFixtures.settle(opened)

        assertTrue(
            "the opened project's facet SDK is not in its library classes",
            FacetModelFixtures.isInLibraryClasses(opened, roots.beam),
        )
    }
}
