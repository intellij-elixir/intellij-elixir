package org.elixir_lang.code_insight

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase

/**
 * A `defdelegate` in an IDE module the caller's module depends on, as an umbrella app does, is shown by its own head,
 * not by the target it reaches.
 */
class ModuleDelegationParameterInfoTest : HeavyTestCase() {
    fun testADelegationInADependencyModuleShowsItsHead() {
        val callerModule = createModule("a")
        val dependency = createModule("b")
        ModuleRootModificationUtil.addDependency(callerModule, dependency)

        addFile(
            dependency,
            "b.ex",
            """
            defmodule A.Impl do
              def snoc(q, x), do: {q, x}
            end

            defmodule B do
              defdelegate snoc(q, x \\ nil), to: A.Impl
            end
            """.trimIndent()
        )
        val caller = addFile(
            callerModule,
            "caller.ex",
            """
            defmodule Caller do
              def calls(q), do: B.snoc(q)
            end
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val document = FileDocumentManager.getInstance().getDocument(caller)!!
        val editor = EditorFactory.getInstance().createEditor(document, project)

        try {
            editor.caretModel.moveToOffset(document.text.indexOf("snoc(") + "snoc(".length)

            assertEquals(
                listOf("q, x \\\\ nil"),
                parameterInfoSignatures(editor, PsiManager.getInstance(project).findFile(caller)!!)
            )
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    private fun addFile(module: Module, name: String, text: String): VirtualFile {
        val directory = tempDir.createVirtualDir()
        PsiTestUtil.addSourceRoot(module, directory)

        return WriteAction.computeAndWait<VirtualFile, Throwable> {
            directory.createChildData(this, name).also { VfsUtil.saveText(it, text) }
        }
    }
}
