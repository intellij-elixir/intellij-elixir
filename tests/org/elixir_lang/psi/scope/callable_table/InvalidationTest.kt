package org.elixir_lang.psi.scope.callable_table

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.scope.CallableTable
import org.elixir_lang.psi.scope.WalkTestSupport
import org.elixir_lang.psi.scope.WalkTestSupport.location
import org.elixir_lang.sdk.SdkFixtures
import java.io.File

/**
 * An edit changes the next answer, whether it is to the module holding the use or to a module that one imports: a
 * module's table holds the declarations of every module its walk reaches. So does a change to what the walk reads
 * from compiled modules, and an edit to a copy of a file, as completion makes one.
 */
class InvalidationTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            CallableTable.enabled = true
        } finally {
            super.tearDown()
        }
    }

    fun testEditingAnImportedModule() = assertEditingAnImportedModule()
    fun testEditingAnImportedModuleWithoutTable() = withoutTable(::assertEditingAnImportedModule)
    fun testEditingTheOwnModule() = assertEditingTheOwnModule()
    fun testEditingTheOwnModuleWithoutTable() = withoutTable(::assertEditingTheOwnModule)

    fun testEditingACopy() {
        val file = myFixture.configureByText(
            "copied.ex",
            """
            defmodule Copied do
              def use_two, do: two()
              def one, do: 1
            end
            """.trimIndent()
        )
        val copy = file.copy() as PsiFile
        assertFalse(copy.isPhysical)

        assertEquals(emptyList<String>(), files(copy, "two()"))

        insert(copy, "  def one", "  def two, do: 2\n")

        assertEquals(listOf("copied.ex"), files(copy, "two()"))
    }

    fun testReplacingABeam() {
        val directory = FileUtil.createTempDirectory("replaced", null)
        File(REPLACED, "v1/$BEAM").copyTo(File(directory, BEAM))

        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "replaced", directory) { root ->
            val importer = myFixture.configureByText(
                "replaced_user.ex",
                """
                defmodule Replaced.User do
                  def use_two, do: two()

                  import CallableTable.Replaced
                end
                """.trimIndent()
            )

            assertEquals(emptyList<String>(), files(importer, "two()"))

            WriteAction.runAndWait<Throwable> { root.findChild(BEAM)!!.setBinaryContent(File(REPLACED, "v2/$BEAM").readBytes()) }
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            assertEquals(listOf(BEAM), files(importer, "two()"))
        }
    }

    fun testAddingAnSdkRoot() {
        val sdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Callable table roots", SdkFixtures.fakeHome("elixir/callable-table-roots")),
            testRootDisposable
        )
        val previous = ModuleRootManager.getInstance(myFixture.module).sdk
        ModuleRootModificationUtil.setModuleSdk(myFixture.module, sdk)
        Disposer.register(testRootDisposable) { ModuleRootModificationUtil.setModuleSdk(myFixture.module, previous) }
        val importer = myFixture.configureByText(
            "sdk_user.ex",
            """
            defmodule SdkRoots.User do
              def go, do: new()

              import :queue
            end
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), files(importer, "new()"))

        VfsRootAccess.allowRootAccess(testRootDisposable, QUEUE.absolutePath)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(QUEUE.absoluteFile)!!
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(root, OrderRootType.CLASSES)
                commitChanges()
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals(listOf("queue.beam"), files(importer, "new()"))
    }

    private fun assertEditingAnImportedModule() {
        val exporter = myFixture.addFileToProject(
            "exporter.ex",
            """
            defmodule Exporter do
              def one, do: 1
            end
            """.trimIndent()
        )
        // After the uses: an earlier `import` is also reached by the walk over a use's previous siblings, not the table.
        val importer = myFixture.configureByText(
            "importer.ex",
            """
            defmodule Importer do
              def use_one, do: one()
              def use_two, do: two()

              import Exporter
            end
            """.trimIndent()
        )

        assertEquals(listOf("exporter.ex:2:3"), answer(importer, "one()"))
        assertEquals(emptyList<String>(), answer(importer, "two()"))

        insert(exporter, "  def one", "  def two, do: 2\n")

        assertEquals(listOf("exporter.ex:2:3"), answer(importer, "two()"))
    }

    private fun assertEditingTheOwnModule() {
        val file = myFixture.configureByText(
            "own.ex",
            """
            defmodule Own do
              def use_one, do: one()
              def use_two, do: two()
              def one, do: 1
            end
            """.trimIndent()
        )

        assertEquals(listOf("own.ex:4:3"), answer(file, "one()"))
        assertEquals(emptyList<String>(), answer(file, "two()"))

        insert(file, "  def one", "  def two, do: 2\n")

        assertEquals(listOf("own.ex:4:3"), answer(file, "two()"))
    }

    private fun answer(file: PsiFile, use: String): List<String> {
        val offset = file.text.indexOf(use).also { check(it >= 0) { "no `$use` in ${file.name}" } }
        val reference = file.viewProvider.findReferenceAt(offset, ElixirLanguage) as PsiPolyVariantReference

        return reference.multiResolve(false).filter { it.isValidResult }.map { location(it.element) }
    }

    /** The files of [use]'s valid answers. */
    private fun files(file: PsiFile, use: String): List<String> {
        val offset = file.text.indexOf(use).also { check(it >= 0) { "no `$use` in ${file.name}" } }
        val reference = file.viewProvider.findReferenceAt(offset, ElixirLanguage) as PsiPolyVariantReference

        return reference.multiResolve(false).filter { it.isValidResult }.map { it.element!!.containingFile.name }.distinct()
    }

    private fun insert(file: PsiFile, before: String, text: String) {
        // A copy's document is its view provider's, as completion's is.
        val document = file.viewProvider.document!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.indexOf(before), text)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun withoutTable(block: () -> Unit) {
        CallableTable.enabled = false
        block()
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    private companion object {
        const val BEAM = "Elixir.CallableTable.Replaced.beam"
        val REPLACED = File("testData/org/elixir_lang/psi/scope/callable_table/replaced")
        val QUEUE = File("testData/org/elixir_lang/psi/scope/callable_table/library_roots/lib")
    }
}
