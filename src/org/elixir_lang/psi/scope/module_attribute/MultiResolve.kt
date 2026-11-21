package org.elixir_lang.psi.scope.module_attribute

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.psi.scope.ModuleAttribute
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.visitedElementSet

class MultiResolve(private val name: String) : ModuleAttribute() {
    override fun executeOnDeclaration(declaration: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        declaration.name?.let { declaredName ->
            if (declaredName.startsWith(name)) {
                val validResult = declaredName == name

                resolveResultOrderedSet.add(declaration, declaration.text, validResult, state.visitedElementSet())
            }
        }

        // keep searching in case there the module attribute is accumulating
        return true
    }

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    companion object {
        @RequiresReadLock
        fun resolveResultOrderedSet(name: String, entrance: PsiElement): ResolveResultOrderedSet {
            val multiResolve = MultiResolve(name)

            walkUp(multiResolve, entrance)

            return multiResolve.resolveResultOrderedSet
        }
    }
}

/**
 * Walks [processor] up from [entrance] through what has run before it. Code an `unquote` escapes runs where its `quote`
 * is, after what the `unquote` runs before it, so the code that `quote` returns is not walked.
 */
@RequiresReadLock
internal fun walkUp(processor: PsiScopeProcessor, entrance: PsiElement) {
    val state = ResolveState.initial().put(ENTRANCE, entrance).putInitialVisitedElement(entrance)
    val escaped = Unquote.escaped(entrance)

    PsiTreeUtil.treeWalkUp(entrance, entrance.containingFile) { scope, lastParent ->
        ProgressManager.checkCanceled()

        escaped != null && isReturnedCode(escaped, scope, entrance) ||
            scope.processDeclarations(processor, state, lastParent ?: entrance, entrance)
    }
}

/** Whether [scope] is code a `quote` returns, one in [escaped] or [escaped] itself, that [entrance] escapes. */
private fun isReturnedCode(escaped: Call, scope: PsiElement, entrance: PsiElement): Boolean =
    PsiTreeUtil.isAncestor(escaped, scope, false) &&
        Unquote.quoting(scope)?.let { PsiTreeUtil.isAncestor(escaped, it, false) && Unquote.isUnquotedIn(it, entrance) } == true
