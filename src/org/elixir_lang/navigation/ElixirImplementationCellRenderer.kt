package org.elixir_lang.navigation

import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.psi.PsiElement
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/**
 * Concise cell renderer for the protocol gutter icon's *Go To Implementation* popup.
 *
 * That popup is opened by `NavigationGutterIconBuilder`/`PsiElementListNavigator`, which renders each
 * target through a [com.intellij.ide.util.PsiElementListCellRenderer] and never consults
 * [ElixirGotoTargetPresentationProvider]. Left to the default renderer, each `def`/`defmacro` clause shows
 * the same long, redundant location string that made the popup unusable. Reuse [ElixirClausePresentation]
 * so the gutter popup matches the Ctrl+Alt+B popup: clause head + implementing type.
 *
 * Targets that declare nothing (e.g. decompiled `.beam` definitions) fall back to the default rendering.
 */
internal class ElixirImplementationCellRenderer : DefaultPsiElementCellRenderer() {
    override fun getElementText(element: PsiElement): String =
        (element as? Call)?.let(ElixirClausePresentation::headText)
            ?: super.getElementText(element)

    override fun getContainerText(element: PsiElement, name: String?): String? =
        element.asDeclaringCall()?.let(ElixirClausePresentation::containerText)
            ?: super.getContainerText(element, name)

    private fun PsiElement.asDeclaringCall(): Call? = (this as? Call)?.takeIf { DeclaringForm.shapedForm(it) != null }
}
