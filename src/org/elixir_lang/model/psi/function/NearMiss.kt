package org.elixir_lang.model.psi.function

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.symbol.SearchTargetSymbol
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.refactoring.rename.symbol.RenameableSymbol

/**
 * The declaration [target] a use names but does not call, as at another arity. Go To and Find Usages read [target]; a
 * rename reads the use's [WrongArityUse], which refuses it.
 *
 * Equal by [target] alone.
 */
@Suppress("UnstableApiUsage")
class NearMiss(val target: Symbol, override val renameTarget: WrongArityUse) : NavigatableSymbol, SearchTargetSymbol, RenameableSymbol {
    init {
        require(target is SearchTarget) { "$target is no search target" }
    }

    override fun createPointer(): Pointer<out NearMiss> {
        val renameTargetPointer = renameTarget.createPointer()

        return Pointer.delegatingPointer(target.createPointer()) { restored ->
            renameTargetPointer.dereference()?.let { NearMiss(restored, it) }
        }
    }

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        SymbolNavigationService.getInstance().getNavigationTargets(project, target)

    override val searchTarget: SearchTarget get() = target as SearchTarget

    override fun equals(other: Any?): Boolean = other is NearMiss && other.target == target

    override fun hashCode(): Int = target.hashCode()

    override fun toString(): String = "NearMiss(target=$target)"
}
