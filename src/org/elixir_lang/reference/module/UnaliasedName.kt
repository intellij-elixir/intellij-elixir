package org.elixir_lang.reference.module

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module.partial
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.QualifiableAliasImpl
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.impl.indexName

object UnaliasedName {
    @RequiresReadLock
    fun unaliasedName(namedElement: PsiNamedElement): String? =
            when (namedElement) {
                is QualifiableAlias -> unaliasedName(namedElement)
                is ElixirAtom -> namedElement.indexName()
                is Call if __MODULE__.`is`(namedElement) -> moduleName(namedElement)
                else -> namedElement.name
            }

    /**
     * The module the first argument of an `alias` or `require` call names, read as an `alias` reads it. Null where it
     * names no one module, such as a string.
     */
    @RequiresReadLock
    fun firstArgumentName(first: PsiElement): String? =
            when (first) {
                is Call if __MODULE__.`is`(first) -> moduleName(first)
                is Call ->
                    first
                        .maybeModularNameToModulars(null)
                        .mapNotNull { it.name }
                        .toSet()
                        .singleOrNull()

                is ElixirAtom -> first.indexName()
                is QualifiableAlias -> aliasedName(first)
                // Anything else, such as a string, names no module
                else -> null
            }

    /**
     * The module [qualifiableAlias] names, as Elixir quotes it: `Elixir.Foo` is `Foo`, and `.Foo` is `Foo`. Null when a
     * qualifier names no one module.
     */
    private fun aliasedName(qualifiableAlias: QualifiableAlias): String? {
        var named = true
        val name = QualifiableAliasImpl.fullyQualifiedName(qualifiableAlias) { call ->
            callQualifierName(call) ?: "".also { named = false }
        }

        return if (named) partial(name) else null
    }

    /**
     * The module a `__MODULE__` call names, as a module's name expands it: `?` in a `quote`, whose caller's it is. Null
     * at the top level, where it is `nil`, and where it names no one module, as in a `defimpl` for a list or for `[]`.
     */
    private fun moduleName(call: Call): String? = moduleNames(call)?.singleOrNull()

    private fun moduleNames(call: Call): Set<String>? =
            CanonicallyNamedImpl.enclosingModuleNames(SyntacticCall.of(call))

    /**
     * As a qualifier, a top-level `__MODULE__` is the empty segment that [partial] drops, as Elixir drops `nil`. Null
     * where it names no one module.
     */
    private fun callQualifierName(call: Call): String? =
            if (__MODULE__.`is`(call)) {
                when (val names = moduleNames(call)) {
                    null -> ""
                    else -> names.singleOrNull()
                }
            } else {
                QualifiableAliasImpl.resolvedQualifierName(call)
            }

    private fun unaliasedName(qualifiableAlias: QualifiableAlias): String? =
            when (val position = Alias.position(qualifiableAlias)) {
                is Alias.Position.InFirstArgument ->
                    // The module itself, or `Prefix` or `Suffix` of `alias Prefix.{Suffix}`
                    if (position.firstArgument === qualifiableAlias ||
                            position.firstArgument is QualifiedMultipleAliases) {
                        aliasedName(qualifiableAlias)
                    } else {
                        null
                    }

                is Alias.Position.InAsValue ->
                    if (position.asValue === qualifiableAlias) {
                        position.firstArgument?.let(::firstArgumentName)
                    } else {
                        null
                    }

                is Alias.Position.Elsewhere ->
                    null

                null ->
                    qualifiableAlias.name
            }
}
