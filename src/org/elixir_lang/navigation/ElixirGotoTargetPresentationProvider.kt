package org.elixir_lang.navigation

import com.intellij.codeInsight.navigation.GotoTargetPresentationProvider
import com.intellij.ide.util.ModuleRendererFactory
import com.intellij.ide.util.PlatformModuleRendererFactory
import com.intellij.openapi.util.Iconable
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.util.TextWithIcon
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call

/**
 * Renders Elixir `def`/`defmacro` clauses meaningfully in *Go To Implementation* / *Go To Target* popups.
 *
 * The default rendering (`GotoTargetHandler`'s fallback) derives each row from the clause's
 * [com.intellij.navigation.ItemPresentation], whose location string chains every enclosing module's
 * presentation into a long, redundant string (protocol prefix + absolute file path + `defmodule ...`).
 * With dozens of protocol implementations this makes every row wider than the screen, so the popup clips.
 * Collapsing every clause to `name/arity` is no better: sibling clauses become indistinguishable.
 *
 * Rows are rendered via [ElixirClausePresentation]; the gutter icon popup uses the same presentation
 * through [ElixirImplementationCellRenderer].
 *
 * Only declaring calls with head text are handled; anything else returns `null` so the platform falls back
 * to its default, leaving Structure View and other renderers untouched.
 */
internal class ElixirGotoTargetPresentationProvider : GotoTargetPresentationProvider {
    @RequiresReadLock
    @RequiresBackgroundThread
    override fun getTargetPresentation(element: PsiElement, differentNames: Boolean): TargetPresentation? {
        val call = element as? Call ?: return null
        val text = ElixirClausePresentation.headText(call) ?: return null
        val moduleTextWithIcon = getModuleTextWithIcon(element)

        return TargetPresentation
            .builder(text)
            .icon(element.getIcon(Iconable.ICON_FLAG_VISIBILITY or Iconable.ICON_FLAG_READ_STATUS))
            .containerText(ElixirClausePresentation.containerText(call))
            .locationText(moduleTextWithIcon?.text, moduleTextWithIcon?.icon)
            .presentation()
    }

    /**
     * Equivalent of the `@ApiStatus.Internal` `com.intellij.ide.util.PsiElementListCellRenderer.getModuleTextWithIcon`.
     *
     * The default [PlatformModuleRendererFactory] adds no location information (matching the internal helper's own
     * guard), so only a custom [ModuleRendererFactory]'s text/icon is used.
     */
    private fun getModuleTextWithIcon(element: Call): TextWithIcon? {
        val factory = ModuleRendererFactory.findInstance(element)
        return if (factory is PlatformModuleRendererFactory) null
        else factory.getModuleTextWithIcon(element)
    }
}
