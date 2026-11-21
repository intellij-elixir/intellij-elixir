package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.SymbolPointerInlayActionPayload
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.launch
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.util.ElixirCoroutineService

/** Opens the module a hint's [SymbolPointerInlayActionPayload] points to, which the platform has no handler for. */
@Suppress("UnstableApiUsage")
internal class SymbolNavigationHandler : InlayActionHandler {
    @RequiresEdt
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val project = e.editor.project ?: return
        payload as SymbolPointerInlayActionPayload

        project.service<ElixirCoroutineService>().scope.launch { navigate(project, payload) }
    }

    internal suspend fun navigate(project: Project, payload: SymbolPointerInlayActionPayload) {
        val request = readAction { (payload.pointer.dereference() as? ModuleSymbol)?.sourceNavigationRequest() } ?: return

        // Every argument is passed: 2026.3 drops this overload's defaults, so a call through them fails there.
        NavigationService.getInstance(project).navigate(request, NavigationOptions.defaultOptions(), null)
    }

    companion object {
        const val HANDLER_ID: String = "org.elixir_lang.inlay_hints.symbol.navigation"
    }
}
