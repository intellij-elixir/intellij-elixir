package org.elixir_lang.psi.scope.module_attribute

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.Unquote
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
        fun resolveResultOrderedSet(name: String, entrance: PsiElement): ResolveResultOrderedSet {
            val multiResolve = MultiResolve(name)

            val resolveState = ResolveState.initial().put(ENTRANCE, entrance).putInitialVisitedElement(entrance)
            val maxScope = entrance.containingFile

            // An `unquote` in a `quote` runs where the `quote` is, so the walk starts there.
            PsiTreeUtil.treeWalkUp(multiResolve, Unquote.escaped(entrance) ?: entrance, maxScope, resolveState)

            return multiResolve.resolveResultOrderedSet
        }
    }
}
