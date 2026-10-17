package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile
import java.util.concurrent.Callable

/** The leg's inputs, which an ask of [ProjectModules] in the tests passes unless it names others. */
internal val legInputs: ProjectModules.Inputs by lazy { ProjectModules.Inputs(legLevel(), legKernel) }

internal fun expansion(holder: ProjectModules, file: PsiFile, inputs: ProjectModules.Inputs = legInputs): FileExpansion =
    ReadAction.nonBlocking(Callable { holder.expansion(file as ElixirFile, inputs) }).executeSynchronously()

internal fun exports(
    holder: ProjectModules,
    asker: PsiFile,
    atom: String,
    inputs: ProjectModules.Inputs = legInputs,
): ModuleExports = ReadAction.nonBlocking(Callable { holder.exports(asker, atom, inputs) }).executeSynchronously()

/** [file]'s expansion from a holder that has cached nothing. */
internal fun freshExpansion(project: Project, file: PsiFile, inputs: ProjectModules.Inputs = legInputs): FileExpansion =
    expansion(ProjectModules(project), file, inputs)

/** Each module [expansion] of [file] compiled, one per line: how it ended, the errors it reported, and its exports. */
internal fun render(file: PsiFile, expansion: FileExpansion): String {
    val text = file.text
    val source = { node: ElixirAst -> node.meta.origin.let { text.substring(it.startOffset, it.endOffset) }.lines().first() }
    val top = when (val top = expansion.top) {
        is Expansion.Expanded -> "expanded"
        is Expansion.Error -> "error ${top.kind} `${source(top.at)}`"
        is Expansion.Unported -> "unported `${source(top.at)}`"
        is Expansion.Opaque -> "opaque `${source(top.at)}`"
    }
    val modules = generateSequence(expansion.modules) { results -> results.flatMap { it.nested }.ifEmpty { null } }
        .flatten()
        .map { result ->
            val ended = when (val ended = result.ended) {
                ExpansionResult.Ended.Compiled -> "compiled"
                ExpansionResult.Ended.Tainted -> "tainted"
                is ExpansionResult.Ended.Raised -> "raised ${ended.error.kind} `${source(ended.error.at)}`"
                is ExpansionResult.Ended.Crashed -> "crashed ${ended.error.kind} ${ended.exception}"
                is ExpansionResult.Ended.Stopped -> "stopped `${source(ended.at)}` ${stop(result, ended.at)}"
            }
            val errors = result.errors.joinToString(",") { it.kind }

            "${result.module} $ended errors[$errors] loads ${LoadedModules.exports(result)}"
        }

    return (sequenceOf("top $top") + modules).joinToString("\n")
}

/**
 * What [result] stopped at: the macro an opaque call dispatches to, or the form of a node that isn't ported, since a node
 * a macro's output holds has the call's text.
 */
private fun stop(result: ExpansionResult, at: ElixirAst): String {
    val opaque = result.opaque.firstOrNull { it.at === at }
    if (opaque != null) return "opaque ${opaque.dispatch.receiver}.${opaque.dispatch.name}"

    val form = when (val callee = (at as? ElixirAst.Call)?.callee) {
        is ElixirAst.Literal.Atom -> callee.name
        null -> at::class.simpleName
        else -> callee::class.simpleName
    }

    return "unported $form"
}

/** Replaces [file]'s text, as typing would, and commits it. */
internal fun edit(project: Project, file: PsiFile, text: String) {
    val manager = PsiDocumentManager.getInstance(project)

    WriteCommandAction.runWriteCommandAction(project) {
        manager.getDocument(file)!!.setText(text)
        manager.commitAllDocuments()
    }
}
