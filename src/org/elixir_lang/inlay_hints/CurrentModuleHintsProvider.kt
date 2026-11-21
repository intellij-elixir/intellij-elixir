package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.psi.__MODULE__
import org.elixir_lang.psi.impl.getModuleNames

/** After each `__MODULE__`, the module it names, where its module has one name with a value. */
internal class CurrentModuleHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector

    private object Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (!__MODULE__.isUse(element)) return
            val text = singleModuleText(element.getModuleNames()) ?: return

            sink.addTextAfter(element, text)
        }
    }
}
