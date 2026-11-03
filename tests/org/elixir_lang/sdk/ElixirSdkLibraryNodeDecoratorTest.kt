package org.elixir_lang.sdk

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * Tests for [ElixirSdkLibraryNodeDecorator].
 *
 * Every OTP application an SDK ships keeps its beams in a directory called `ebin`, so External
 * Libraries listed them all under that one name and no node said which application it belonged to.
 */
class ElixirSdkLibraryNodeDecoratorTest : PlatformTestCase() {
    fun testClassRootIsNamedAfterItsOtpApplication() {
        val iex = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/iex/ebin")
        val logger = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/logger/ebin")
        registerElixirSdk(classRoots = listOf(iex, logger))

        assertEquals("iex", presentableTextOf(iex))
        assertEquals("logger", presentableTextOf(logger))
    }

    fun testSourceRootIsNamedAfterItsOtpApplication() {
        val elixirSource = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/elixir/lib")
        val erlangSource = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/stdlib-7.1/src")
        registerElixirSdk(sourceRoots = listOf(elixirSource, erlangSource))

        assertEquals("elixir", presentableTextOf(elixirSource))
        assertEquals("stdlib-7.1", presentableTextOf(erlangSource))
    }

    fun testEbinOutsideAnySdkKeepsItsDirectoryName() {
        val sdkEbin = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/iex/ebin")
        val depEbin = myFixture.tempDirFixture.findOrCreateDir("_build/dev/lib/my_dep/ebin")
        registerElixirSdk(classRoots = listOf(sdkEbin))

        assertNull("A dep's ebin is not an SDK root and must keep its own name", presentableTextOf(depEbin))
    }

    fun testRootAddedToARegisteredSdkIsNamedAfterItsOtpApplication() {
        val iex = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/iex/ebin")
        val logger = myFixture.tempDirFixture.findOrCreateDir("elixir_home/lib/logger/ebin")
        val sdk = registerElixirSdk(classRoots = listOf(iex))
        assertEquals("iex", presentableTextOf(iex))

        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(logger, OrderRootType.CLASSES)
                commitChanges()
            }
        }

        assertEquals("logger", presentableTextOf(logger))
    }

    private fun registerElixirSdk(
        classRoots: List<VirtualFile> = emptyList(),
        sourceRoots: List<VirtualFile> = emptyList(),
    ): Sdk {
        val sdk = ProjectJdkImpl("Test Elixir SDK", ElixirSdkType.instance)
        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                homePath = myFixture.tempDirFixture.findOrCreateDir("elixir_home").path
                classRoots.forEach { addRoot(it, OrderRootType.CLASSES) }
                sourceRoots.forEach { addRoot(it, OrderRootType.SOURCES) }
                commitChanges()
            }
        }
        return SdkFixtures.register(sdk, testRootDisposable)
    }

    private fun presentableTextOf(directory: VirtualFile): String? {
        val psiDirectory = PsiManager.getInstance(project).findDirectory(directory)!!
        val node = PsiDirectoryNode(project, psiDirectory, ViewSettings.DEFAULT)
        val presentation = PresentationData()

        ElixirSdkLibraryNodeDecorator().decorate(node, presentation)

        return presentation.presentableText
    }
}
