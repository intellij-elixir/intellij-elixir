package org.elixir_lang.code_insight

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.protocol.ProtocolImplementation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call


internal class GotoSuper : CodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        file
                .findElementAt(editor.caretModel.offset)
                ?.let { focusedElement -> PsiTreeUtil.getNonStrictParentOfType(focusedElement, Call::class.java) }
                ?.let { invoke(project, editor, it) }
    }

    @RequiresReadLock
    fun invoke(project: Project, editor: Editor, call: Call) {
        // Normalize caret-level calls to the enclosing definition.
        val definition = if (DefinitionDeclaration.`is`(call)) {
            call
        } else {
            generateSequence(call.parent) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { DefinitionDeclaration.`is`(it) }
                ?: return
        }

        // Direct structural navigation, no call.reference indirection
        val defimpl = CallDefinitionClause.enclosingModularMacroCall(definition) ?: return
        if (!Implementation.`is`(defimpl)) return

        val implementer = DefinitionDeclaration.of(definition, ResolveState.initial()) ?: return

        val protocols = mutableListOf<PsiElement>()
        Implementation.processProtocols(defimpl) { protocol -> protocols.add(protocol) }

        val targets = protocols
                .asSequence()
                .filterIsInstance<Call>()
                .flatMap { defprotocol ->
                    CallDefinitionClause.modularChildCalls(defprotocol).asSequence()
                }
                .filter(CallDefinitionClause::`is`)
                .filter { protocolCallDefinitionClause ->
                    DefinitionDeclaration.of(protocolCallDefinitionClause, ResolveState.initial())
                            ?.let { member -> ProtocolImplementation.implementedBy(member, implementer) }
                            ?: false
                }
                .toList()
                .toTypedArray()

        if (targets.isNotEmpty()) {
            PsiTargetNavigator(targets)
                    .title("Choose Protocol Function")
                    .tabTitle("Protocol Functions of ${definition.functionName()}/${definition.resolvedFinalArity()}")
                    .navigate(editor, "Choose Protocol Function")
        }
    }
}
