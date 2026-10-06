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
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
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
        // Normalize caret-level calls to the enclosing def/defmacro clause.
        val callDefinitionClause = if (CallDefinitionClause.`is`(call)) {
            call
        } else {
            generateSequence(call.parent) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { CallDefinitionClause.`is`(it) }
                ?: return
        }

        // Direct structural navigation, no call.reference indirection
        val defimpl = CallDefinitionClause.enclosingModularMacroCall(callDefinitionClause) ?: return
        if (!Implementation.`is`(defimpl)) return

        val nameArityInterval = nameArityInterval(callDefinitionClause, ResolveState.initial()) ?: return

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
                    nameArityInterval(protocolCallDefinitionClause, ResolveState.initial())
                            ?.let { protocolNameArityInterval ->
                                protocolNameArityInterval.name == nameArityInterval.name &&
                                        protocolNameArityInterval.arityInterval.overlaps(nameArityInterval.arityInterval)
                            }
                            ?: false
                }
                .toList()
                .toTypedArray()

        if (targets.isNotEmpty()) {
            PsiTargetNavigator(targets)
                    .title("Choose Protocol Function")
                    .tabTitle("Protocol Functions of ${callDefinitionClause.functionName()}/${callDefinitionClause.resolvedFinalArity()}")
                    .navigate(editor, "Choose Protocol Function")
        }
    }
}
