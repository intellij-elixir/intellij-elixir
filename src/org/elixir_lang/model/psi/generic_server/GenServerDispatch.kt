package org.elixir_lang.model.psi.generic_server

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.navigation.ElixirClausePresentation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpression

/**
 * Resolves GenServer / `Process` message send sites to the matching handler clause(s) in the enclosing
 * module, using naive "skeleton" matching (constant atom message vs. constant atom first-parameter
 * pattern).
 *
 * Supported dispatch trios (message is always the second argument, index 1):
 * - `GenServer.call/2`       -> `handle_call/3`
 * - `GenServer.cast/2`       -> `handle_cast/2`
 * - `Process.send/3`         -> `handle_info/2`
 * - `Process.send_after/3`   -> `handle_info/2`
 */
internal object GenServerDispatch {
    private const val GEN_SERVER = "GenServer"
    private const val PROCESS = "Process"

    private data class Dispatch(val handlerName: String, val handlerArity: Int)

    /**
     * Returns one [GenServerHandlerTarget] per handler clause whose first-parameter atom matches
     * [atom], or an empty list if [atom] is not the message argument of a supported send site, or no
     * handler matches.
     */
    @RequiresReadLock
    fun handlerTargetsForRequestAtom(atom: ElixirAtom): List<GenServerHandlerTarget> {
        val messageName = atom.name ?: return emptyList()

        val (sendCall, dispatch) = enclosingSendSite(atom) ?: return emptyList()
        val arguments = sendCall.finalArguments() ?: return emptyList()
        // The message is always the second argument (index 1) of the send site.
        if (arguments.size < 2 || !PsiTreeUtil.isAncestor(arguments[1], atom, false)) return emptyList()

        val module = enclosingModule(sendCall) ?: return emptyList()

        return Modular
            .callDefinitionClauseCallSequence(module)
            .filter { clause -> clauseMatches(clause, dispatch, messageName) }
            .mapNotNull { clause -> targetFor(clause) }
            .distinct()
            .toList()
    }

    @RequiresReadLock
    private fun enclosingSendSite(atom: PsiElement): Pair<Call, Dispatch>? {
        var element: PsiElement? = atom.parent
        while (element != null) {
            ProgressManager.checkCanceled()
            if (element is Call) {
                dispatchFor(element)?.let { return element to it }
            }
            element = element.parent
        }
        return null
    }

    @RequiresReadLock
    private fun dispatchFor(call: Call): Dispatch? = when {
        call.isCalling(GEN_SERVER, "call", 2) -> Dispatch("handle_call", 3)
        call.isCalling(GEN_SERVER, "cast", 2) -> Dispatch("handle_cast", 2)
        call.isCalling(PROCESS, "send", 3) -> Dispatch("handle_info", 2)
        call.isCalling(PROCESS, "send_after", 3) -> Dispatch("handle_info", 2)
        else -> null
    }

    @RequiresReadLock
    private fun enclosingModule(call: Call): Call? =
        generateSequence(call.parent) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { org.elixir_lang.psi.Module.`is`(it) }

    @RequiresReadLock
    private fun clauseMatches(clause: Call, dispatch: Dispatch, messageName: String): Boolean {
        val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return false
        if (nameArity.name != dispatch.handlerName) return false
        if (dispatch.handlerArity !in nameArity.arityInterval) return false
        if (CallDefinitionClause.isMacro(clause)) return false
        val head = CallDefinitionClause.head(clause) as? Call ?: return false
        val firstParam = head.primaryArguments()?.firstOrNull()?.stripAccessExpression() ?: return false
        val paramAtom = firstParam as? ElixirAtom ?: return false
        return paramAtom.name == messageName
    }

    @RequiresReadLock
    private fun targetFor(clause: Call): GenServerHandlerTarget? {
        val nameIdentifier = CallDefinitionClause.nameIdentifier(clause) ?: return null
        val presentation = runCatching { ElixirClausePresentation.elementText(clause) }
            .getOrElse { if (it is ProcessCanceledException) throw it else nameIdentifier.text }
        return GenServerHandlerTarget(clause.containingFile, nameIdentifier.textRange, presentation)
    }
}
