package org.elixir_lang.psi.scope.module_attribute

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.psi.scope.ModuleAttribute
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.visitedElementSet

class MultiResolve(private val name: String) : ModuleAttribute() {
    override fun executeOnDeclaration(declaration: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        declaration.name?.let { declaredName ->
            val nameMatch = NameMatch.of(name, declaredName, declaration)

            if (nameMatch != NameMatch.NONE) {
                resolveResultOrderedSet.add(
                    declaration, declaration.text, nameMatch == NameMatch.EXACT, state.visitedElementSet()
                )
            }
        }

        // keep searching in case there the module attribute is accumulating
        return true
    }

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    companion object {
        /** @param name normalized here, once, so every declaration is compared the same way. */
        fun resolveResultOrderedSet(name: String, entrance: PsiElement): ResolveResultOrderedSet {
            val multiResolve = MultiResolve(NameMatch.query(name, entrance))

            val resolveState = ResolveState.initial().put(ENTRANCE, entrance).putInitialVisitedElement(entrance)
            val maxScope = entrance.containingFile

            PsiTreeUtil.treeWalkUp(multiResolve, entrance, maxScope, resolveState)

            return multiResolve.resolveResultOrderedSet
        }
    }
}
