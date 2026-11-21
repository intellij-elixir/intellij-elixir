package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.SymbolPointerInlayActionPayload
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.model.psi.module.ModuleSymbol
import java.util.concurrent.Callable

@Suppress("UnstableApiUsage")
class SymbolNavigationHandlerTest : LightTestCase() {
    fun testItIsRegisteredUnderItsHandlerId() {
        assertInstanceOf(InlayActionHandler.getActionHandler(SymbolNavigationHandler.HANDLER_ID), SymbolNavigationHandler::class.java)
    }

    fun testItOpensTheModule() {
        val target = myFixture.addFileToProject("target.ex", TARGET)
        val payload = payload(target)
        myFixture.configureByText("test.ex", "defmodule User do\nend\n")

        navigate(payload)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(target.virtualFile, editor.virtualFile)
        assertEquals(TARGET.indexOf("Target"), editor.caretModel.offset)
    }

    fun testADeadPointerOpensNothing() {
        val target = myFixture.addFileToProject("target.ex", TARGET)
        val payload = payload(target)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(target)!!
            document.deleteString(0, document.textLength)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.configureByText("test.ex", "defmodule User do\nend\n")

        navigate(payload)

        assertEquals(myFixture.file.virtualFile, FileEditorManager.getInstance(project).selectedEditor!!.file)
        assertEquals(0, myFixture.editor.caretModel.offset)
    }

    private fun payload(target: PsiFile): SymbolPointerInlayActionPayload =
        ReadAction.nonBlocking(Callable {
            val start = TARGET.indexOf("Target")
            SymbolPointerInlayActionPayload(ModuleSymbol(target, TextRange.from(start, "Target".length), "Target").createPointer())
        }).executeSynchronously()

    private fun navigate(payload: SymbolPointerInlayActionPayload) =
        runWithModalProgressBlocking(ModalTaskOwner.project(project), "Navigate") {
            SymbolNavigationHandler().navigate(project, payload)
        }

    private companion object {
        const val TARGET = "defmodule Target do\nend\n"
    }
}
