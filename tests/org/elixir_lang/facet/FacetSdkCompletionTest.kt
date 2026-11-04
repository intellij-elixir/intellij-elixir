package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryTestCase.Companion.ERLANG_STDLIB_EBIN
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import java.io.File

/**
 * Completion reaches an SDK's own modules when that SDK is assigned the way a small IDE assigns one -
 * through Settings -> Languages & Frameworks -> Elixir, which writes [Facet.sdk].
 *
 * `ErlangModuleCompletionTest` pins the same `:math.s` -> `sqrt` gesture against the same `math.beam`,
 * but attaches the `ebin` directory as a module library directly.
 *
 * Both root types get a test, because they carry different things and a fix can restore one without
 * the other: `CLASSES` holds the compiled `ebin` directories, while an Elixir SDK's `defmodule`
 * declarations are stubbed from the `.ex` files under `SOURCES`.
 */
class FacetSdkCompletionTest : PlatformTestCase() {

    private val added = mutableListOf<Sdk>()

    override fun setUp() {
        super.setUp()
        ProcessOutput.isSmallIdeOverride = true
        SdksService.getInstance()!!.resetForTests()
        ensureElixirFacet()
        assignSdk()
    }

    override fun tearDown() {
        runAll(
            {
                WriteAction.run<Throwable> {
                    FacetManager.getInstance(module).getFacetByType(Facet.ID)?.let { it.sdk = null }
                }
            },
            {
                WriteAction.run<Throwable> {
                    val table = ProjectJdkTable.getInstance()
                    added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
                }
                added.clear()
            },
            { SdksService.getInstance()!!.resetForTests() },
            { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() },
            { ProcessOutput.isSmallIdeOverride = null },
            { super.tearDown() },
        )
    }

    private fun ensureElixirFacet() {
        val facetManager = FacetManager.getInstance(module)

        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    /**
     * Registers an Elixir SDK carrying both root types a real one has, then assigns it exactly as
     * `facet.configurable.Project`'s per-module page does.
     */
    private fun assignSdk() {
        val classesRoot = beamFixtureRoot()
        val sourcesRoot = stdlibSourceRoot()

        val sdk = ProjectJdkImpl("Elixir Facet Completion Test", ElixirSdkType.instance)

        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(classesRoot, OrderRootType.CLASSES)
                addRoot(sourcesRoot, OrderRootType.SOURCES)
                commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk)
        }

        added.add(sdk)
        SdksService.getInstance()!!.resetForTests()

        WriteAction.run<Throwable> {
            FacetManager.getInstance(module).getFacetByType(Facet.ID)!!.sdk = sdk
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** The real `math.beam` directory, standing in for an SDK `ebin` CLASSES root. */
    private fun beamFixtureRoot(): VirtualFile {
        val ebinDirectory = ERLANG_STDLIB_EBIN
        assertTrue("Fixture directory not found at ${ebinDirectory.absolutePath}", ebinDirectory.isDirectory)

        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, ebinDirectory.path)

        val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebinDirectory)
        assertNotNull("Could not find fixture directory ${ebinDirectory.absolutePath} in VFS", ebin)

        return ebin!!
    }

    /**
     * A stand-in for an Elixir install's `lib/<app>/lib` sourcepath root, holding one `.ex` file
     * whose `defmodule` is what completion has to find.
     *
     * Named `Stdlib` rather than `IO` so that a hit cannot have come from anywhere but this root.
     *
     * The fixture is a committed directory **outside** the project, like `BeamLibraryTestCase`'s
     * `ebin`, and deliberately not `tempDirFixture`: that fixture writes inside the project's own
     * content root, where the file is indexed as project source no matter what the SDK is attached
     * to.
     */
    private fun stdlibSourceRoot(): VirtualFile {
        val sourceDirectory = SDK_SOURCEPATH_LIB
        assertTrue("Fixture directory not found at ${sourceDirectory.absolutePath}", sourceDirectory.isDirectory)

        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, sourceDirectory.path)

        val sourceRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceDirectory)
        assertNotNull("Could not find fixture directory ${sourceDirectory.absolutePath} in VFS", sourceRoot)

        return sourceRoot!!
    }

    companion object {
        /**
         * Stands in for an Elixir install's `lib/<app>/lib` sourcepath root - the shape
         * `ElixirSdkPathConfigurator.addSourcePaths` builds, and where the standard library's
         * `defmodule` declarations live.
         */
        private val SDK_SOURCEPATH_LIB =
            File("testData/org/elixir_lang/facet/sdk_sourcepath/lib/elixir/lib").absoluteFile
    }

    /**
     * `:math.s<caret>` offers `sqrt` when `:math` came from the module's assigned Elixir SDK rather
     * than from a library attached directly.
     */
    fun testAssignedSdkClasspathModuleFunctionsAreOffered() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    :math.s<caret>
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull(
            "No completion popup after `:math.` - nothing from the assigned SDK's classpath reached the index",
            strings
        )
        assertTrue(
            "Expected `sqrt` from the assigned SDK's ebin among the completions, got: ${strings!!.sorted()}",
            strings.contains("sqrt")
        )
    }

    /**
     * `Stdlib.stdlib_<caret>` offers `stdlib_puts` - a `defmodule` reached through the assigned SDK's
     * **sourcepath**.
     *
     * This is the shape the standard library actually arrives in, and the one the reports describe:
     * Elixir's stubs come from `.ex` sources rather than `.beam`.
     * The `:math` case above cannot show that, since a BEAM module arrives through `CLASSES`.
     */
    fun testAssignedSdkSourcepathModuleFunctionsAreOffered() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    Stdlib.stdlib_<caret>
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull(
            "No completion popup after `Stdlib.` - nothing from the assigned SDK's sourcepath reached the index",
            strings
        )
        assertTrue(
            "Expected `stdlib_puts` from the assigned SDK's sourcepath, got: ${strings!!.sorted()}",
            strings.contains("stdlib_puts")
        )
    }
}
