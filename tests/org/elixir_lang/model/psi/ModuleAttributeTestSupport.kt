package org.elixir_lang.model.psi

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.util.containers.ContainerUtil
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.Model

internal fun CodeInsightTestFixture.attributes(): List<AtUnqualifiedNoParenthesesCall<*>> = runReadAction {
    PsiTreeUtil.findChildrenOfType(file, AtUnqualifiedNoParenthesesCall::class.java).toList()
}

internal fun CodeInsightTestFixture.structureViewElements(): List<Any> {
    val elements = ContainerUtil.createLockFreeCopyOnWriteList<Any>()

    fun walk(element: StructureViewTreeElement) {
        elements.add(element)
        element.children.forEach { child -> if (child is StructureViewTreeElement) walk(child) }
    }

    runReadAction { walk(Model(file as ElixirFile, null).root) }

    return elements.toList()
}

internal fun CodeInsightTestFixture.resolvedSymbolsAtCaret(): List<Symbol> {
    val offset = caretOffset
    val host = enclosingCallAtCaret()!!

    return PsiSymbolReferenceService.getService()
        .getReferences(host)
        .filter { it.absoluteRange.containsOffset(offset) }
        .flatMap { it.resolveReference() }
}

internal fun CodeInsightTestFixture.resolvedCallbacksAtCaretDef(): List<Callback> {
    val defClause = enclosingCallAtCaret { CallDefinitionClause.`is`(it) }!!

    return PsiSymbolReferenceService.getService()
        .getReferences(defClause)
        .flatMap { it.resolveReference() }
        .filterIsInstance<Callback>()
}
