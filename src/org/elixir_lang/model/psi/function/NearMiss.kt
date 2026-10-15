package org.elixir_lang.model.psi.function

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.symbol.SearchTargetSymbol
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationTarget

/**
 * The declaration [target] a use names but does not call, as at another arity. Go To and Find Usages read [target]; a
 * near miss is no rename target, so a rename at the use refuses it.
 */
@Suppress("UnstableApiUsage")
data class NearMiss(val target: Symbol) : NavigatableSymbol, SearchTargetSymbol {
    init {
        require(target is SearchTarget) { "$target is no search target" }
    }

    override fun createPointer(): Pointer<out NearMiss> = Pointer.delegatingPointer(target.createPointer(), ::NearMiss)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        SymbolNavigationService.getInstance().getNavigationTargets(project, target)

    override val searchTarget: SearchTarget get() = target as SearchTarget
}
