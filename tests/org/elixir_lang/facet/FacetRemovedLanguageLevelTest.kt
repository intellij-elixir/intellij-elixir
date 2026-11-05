package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.PushedVersions
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.waitUntil
import java.util.concurrent.Callable

/** A module that stops being an Elixir module stops reading the versions pushed onto its directories. */
class FacetRemovedLanguageLevelTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ProcessOutput.isSmallIdeOverride = true
        SdksService.getInstance()!!.resetForTests()
    }

    override fun tearDown() {
        runAll(
            { SdksService.getInstance()!!.resetForTests() },
            { ProcessOutput.isSmallIdeOverride = null },
            { super.tearDown() },
        )
    }

    fun testAModuleWhoseFacetIsRemovedReadsItsFilesAtTheUnpushedLevel() {
        val root = ModuleRootManager.getInstance(module).contentRoots.single()
        val kept = PushedVersions.KEY.getPersistentValue(root)

        try {
            PushedVersions.KEY.setPersistentValue(root, null)
            val unpushed = levelOf(myFixture.tempDirFixture.createFile("unpushed.ex"))
            val pushed = ElixirLanguageLevel.parse("1.13.0")
            assertFalse("precondition: the SDK's level is not the unpushed one", pushed == unpushed)
            val sdk = SdkFixtures.registerAndWaitForFill(
                SdkFixtures.elixirSdk("Facet Removed Language Level Test", SdkFixtures.elixirHome("1.13.0")),
                testRootDisposable,
            )
            WriteAction.runAndWait<Throwable> {
                FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java)).sdk = sdk
            }
            waitUntil("precondition: the facet's SDK's versions are pushed onto the module's root") {
                PushedVersions.decode(PushedVersions.KEY.getPersistentValue(root)) == pushed
            }

            WriteAction.runAndWait<Throwable> {
                FacetUtil.deleteFacet(FacetManager.getInstance(module).getFacetByType(Facet.ID)!!)
            }
            SdkFixtures.settle()

            assertEquals(
                "a file of the module is read at the level last pushed while it had a facet",
                unpushed,
                levelOf(myFixture.tempDirFixture.createFile("after.ex")),
            )
        } finally {
            PushedVersions.KEY.setPersistentValue(root, kept)
        }
    }

    private fun levelOf(file: VirtualFile): ElixirLanguageLevel =
        ReadAction.nonBlocking(Callable {
            ElixirLanguageLevelResolver.languageLevelFor(PsiManager.getInstance(project).findFile(file)!!)
        }).executeSynchronously()
}
