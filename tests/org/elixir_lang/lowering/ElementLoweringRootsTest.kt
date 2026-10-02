package org.elixir_lang.lowering

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.psi.ElementFactory
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.settings.ElixirExperimentalSettings

/** [ElementLowering] on each kind of file root the IDE lowers elements in. */
class ElementLoweringRootsTest : PlatformTestCase() {
    override fun getTestDataPath(): String = RootGoldens.DIRECTORY

    fun testPlain() {
        myFixture.configureByFile("plain.ex")
        RootGoldens.assertQuotes("plain", myFixture.file)
    }

    fun testAnEditMovesTheLines() {
        myFixture.configureByFile("plain.ex")
        RootGoldens.assertQuotes("plain", myFixture.file)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "\n\n")
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        }

        RootGoldens.assertQuotes("plainAfterEdit", myFixture.file)
    }

    fun testEex() {
        myFixture.configureByFile("template.html.eex")
        RootGoldens.assertQuotes("eex", myFixture.file.viewProvider.getPsi(ElixirLanguage))
    }

    fun testHeex() {
        myFixture.configureByFile("template.html.heex")
        RootGoldens.assertQuotes("heex", myFixture.file.viewProvider.getPsi(ElixirLanguage))
    }

    fun testDocExample() {
        myFixture.configureByFile("doc.ex")
        val heredoc = PsiTreeUtil.findChildrenOfType(myFixture.file, HeredocLiteral::class.java).first()
        val injected = mutableListOf<PsiFile>()
        InjectedLanguageManager.getInstance(project).enumerateEx(heredoc, myFixture.file, true) { psi, _ ->
            if (psi.language == ElixirLanguage) injected += psi
        }

        RootGoldens.assertQuotes("doc", injected.singleOrNull())
    }

    fun testTemplateSigil() {
        val settings = ElixirExperimentalSettings.instance
        val original = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true

        try {
            InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)
            myFixture.configureByFile("sigil.ex")
            val injected = InjectedLanguageManager.getInstance(project)
                .findInjectedElementAt(myFixture.file, myFixture.file.text.indexOf(":b"))
                ?.containingFile

            RootGoldens.assertQuotes("sigil", injected?.viewProvider?.getPsi(ElixirLanguage))
        } finally {
            settings.state.enableHtmlInjection = original
        }
    }

    fun testCompletionCopy() {
        myFixture.configureByFile("plain.ex")
        RootGoldens.assertQuotes("plain", myFixture.file.copy() as PsiFile)
    }

    fun testDocumentationLinkFile() {
        RootGoldens.assertQuotes("documentationLink", ElementFactory.createFile(project, ":lists"))
    }
}
