package org.elixir_lang.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * [CallableTable.of]'s [com.intellij.psi.util.CachedValueProvider.Result] must be invalidated by an edit
 * to a module the table's build walked into via `import`/`use`, not only by an edit to the modular's own
 * file - [CallableTable.buildUnguarded] resolves `Import`/`Use.treeWalkUp`'s target module by name and
 * records its `Call`s as [CallableTable.Entry]s, so the table's data spans both files even though only one
 * of them is the `modular` the table is cached against.
 */
class CallableTableCrossFileInvalidationTest : PlatformTestCase() {
    fun testEditingAnImportedModuleInvalidatesTheImportingModulesTable() {
        myFixture.addFileToProject(
            "exporter.ex",
            """
            defmodule Exporter do
              def one, do: 1
            end
            """.trimIndent()
        )

        myFixture.configureByText(
            "importer.ex",
            """
            defmodule Importer do
              import Exporter
            end
            """.trimIndent()
        )

        val modular = modularCall("Importer")

        assertEquals(1, CallableTable.of(modular).declaring("one").size)
        assertTrue(
            "`two/0` must not be offered before it exists in Exporter",
            CallableTable.of(modular).declaring("two").isEmpty()
        )

        val exporterFile = myFixture.findFileInTempDir("exporter.ex")
        val exporterPsiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(exporterFile)!!
        val document = PsiDocumentManager.getInstance(project).getDocument(exporterPsiFile)!!

        WriteCommandAction.runWriteCommandAction(project) {
            val insertAt = document.text.indexOf("  def one")
            document.insertString(insertAt, "  def two, do: 2\n")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        assertEquals(
            "editing Exporter (a different file than Importer, the modular the table is cached against) " +
                "must invalidate Importer's own CallableTable - it was built by walking into Exporter",
            1,
            CallableTable.of(modular).declaring("two").size
        )
    }

    private fun modularCall(name: String): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { isModular(it) && it.text.startsWith("defmodule $name ") }
}
