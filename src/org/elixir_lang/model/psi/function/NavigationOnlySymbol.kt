package org.elixir_lang.model.psi.function

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationTarget

/**
 * [target], a definition a use may not reach, as one of several copies or an index find: Go To lands on it, but it is
 * no search target and no rename target, so neither Find Usages nor rename starts from it.
 */
@Suppress("UnstableApiUsage")
data class NavigationOnlySymbol(val target: Symbol) : NavigatableSymbol {
    override fun createPointer(): Pointer<out NavigationOnlySymbol> = Pointer.delegatingPointer(target.createPointer(), ::NavigationOnlySymbol)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        SymbolNavigationService.getInstance().getNavigationTargets(project, target)
}
