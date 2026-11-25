package org.elixir_lang.model.psi.module_attribute

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.module.ModuleReference
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.__module__.currentModularAt
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ModuleName

/** `@for` or `@protocol` inside a `defimpl`, which Elixir defines without a declaration in the source. */
@Suppress("UnstableApiUsage")
class ImplementationAttributeReference(
    private val call: Call,
    private val implementation: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = resolve(implementation, moduleNames(call, implementation))

    companion object {
        private const val FOR = "for"
        private const val PROTOCOL = "protocol"
        val NAMES = listOf(FOR, PROTOCOL)

        /** The `defimpl` whose module [usage] is read in. */
        @RequiresReadLock
        fun implementation(usage: Call): Call? = currentModularAt(usage)?.takeIf { Implementation.`is`(it) }

        /** The `defimpl` [usage] reads `@for` or `@protocol` of, or `null` when it reads neither in one. */
        @RequiresReadLock
        fun readImplementation(usage: Call): Call? = usage.takeIf { it.functionName() in NAMES }?.let(::implementation)

        /** The modules [usage], `@for` or `@protocol` in [implementation], names, each as written. */
        @RequiresReadLock
        fun moduleNames(usage: Call, implementation: Call): Collection<ModuleName> =
            when (usage.functionName()) {
                FOR -> Implementation.forModuleNames(implementation).orEmpty()
                PROTOCOL -> listOfNotNull(Implementation.protocolModuleName(implementation))
                else -> emptyList()
            }

        /** The modules [moduleNames], as a read of `@for` or `@protocol` in [implementation] names them, resolve to. */
        @RequiresReadLock
        fun resolve(implementation: Call, moduleNames: Collection<ModuleName>): List<Symbol> =
            moduleNames.flatMap { module -> ModuleReference.resolve(implementation, module) }.distinct()
    }
}
