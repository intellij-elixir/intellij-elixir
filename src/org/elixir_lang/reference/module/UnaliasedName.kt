package org.elixir_lang.reference.module

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.impl.indexName

object UnaliasedName {
    @RequiresReadLock
    fun unaliasedName(namedElement: PsiNamedElement): String? =
            if (namedElement is QualifiableAlias) {
                unaliasedName(namedElement)
            } else if (namedElement is ElixirAtom) {
                namedElement.indexName()
            } else if (namedElement is Call && namedElement.isCalling(KERNEL, Function.__MODULE__, 0)) {
                __MODULE__
                    .reference(namedElement, useCall = null)
                    .resolve()
                    ?.let { it as? PsiNamedElement }
                    ?.name
            } else {
                namedElement.name
            }

    private fun down(element: PsiElement): String? =
            when (element) {
                is Call ->
                    element
                        .maybeModularNameToModulars(null)
                        .mapNotNull { it.name }
                        .toSet()
                        .singleOrNull()

                is ElixirAtom -> element.indexName()
                is QualifiableAlias -> element.name
                // Anything else, such as a string, names no module
                else -> null
            }

    private fun unaliasedName(qualifiableAlias: QualifiableAlias): String? =
            when (val position = Alias.position(qualifiableAlias)) {
                is Alias.Position.InFirstArgument ->
                    when {
                        position.firstArgument === qualifiableAlias -> qualifiableAlias.name
                        // `Prefix` or `Suffix` of `alias Prefix.{Suffix}`
                        position.firstArgument is QualifiedMultipleAliases -> qualifiableAlias.fullyQualifiedName()
                        else -> null
                    }

                is Alias.Position.InAsValue ->
                    if (position.asValue === qualifiableAlias) {
                        position.firstArgument?.let(::down)
                    } else {
                        null
                    }

                is Alias.Position.Elsewhere ->
                    null

                null ->
                    qualifiableAlias.name
            }
}
