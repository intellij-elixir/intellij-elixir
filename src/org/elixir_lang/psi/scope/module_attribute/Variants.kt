package org.elixir_lang.psi.scope.module_attribute

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.model.psi.ElixirUsageQueries
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.psi.scope.ModuleAttribute

/**
 * Collects the in-scope module-attribute declarations offered when completing a `@<prefix><caret>`
 * reference. Walks up from the usage (via the shared [ModuleAttribute] scope processor, so
 * `use`-injected attributes are included and non-referencing/reserved attributes are skipped just as
 * in resolution) and yields one [LookupElement] per declared attribute name.
 *
 * The lookup string is the **bare** name as written (no leading `@`), because the completion prefix is the text
 * after the `@` operator and the `@` the user already typed stays in place.
 *
 * The completion counterpart of [MultiResolve]; the module-attribute analogue of
 * [org.elixir_lang.psi.scope.variable.Variants].
 */
class Variants : ModuleAttribute() {
    private val lookupElementByName = mutableMapOf<String, LookupElement>()

    override fun executeOnDeclaration(declaration: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        declaration.name?.removePrefix("@")?.let { name ->
            // Dedup by name: an accumulating attribute is declared several times and a nested/`use`d
            // attribute can be shadowed, but the popup only shows the name, so each appears once.
            // `treeWalkUp` visits the nearest (innermost/shadowing) declaration first, so
            // `computeIfAbsent` keeps that one as the navigation target.
            lookupElementByName.computeIfAbsent(name) {
                LookupElementBuilder
                    .createWithSmartPointer(declaration.atIdentifier.identifierName(), declaration)
                    .withLookupStrings(ElixirUsageQueries.lookupStrings(name, declaration))
            }
        }

        // keep searching so every in-scope attribute is offered
        return true
    }

    companion object {
        fun lookupElementList(entrance: PsiElement): List<LookupElement> {
            val variants = Variants()

            val resolveState = ResolveState.initial().put(ENTRANCE, entrance).putInitialVisitedElement(entrance)

            PsiTreeUtil.treeWalkUp(variants, entrance, entrance.containingFile, resolveState)

            return variants.lookupElementByName.values.toList()
        }
    }
}
