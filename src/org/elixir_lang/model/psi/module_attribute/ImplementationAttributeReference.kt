package org.elixir_lang.model.psi.module_attribute

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.module.ModuleReference
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.__module__.currentModularAt
import org.elixir_lang.psi.call.Call

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
    override fun resolveReference(): Collection<Symbol> = resolve(implementation, names(call, implementation))

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

        /** The names of the modules [usage], `@for` or `@protocol` in [implementation], names. */
        @RequiresReadLock
        fun names(usage: Call, implementation: Call): Collection<String> =
            when (usage.functionName()) {
                FOR -> Implementation.forNames(implementation).orEmpty()
                PROTOCOL -> listOfNotNull(Implementation.protocolName(implementation))
                else -> emptyList()
            }

        /** The modules [names], as a read of `@for` or `@protocol` in [implementation] names them, resolve to. */
        @RequiresReadLock
        fun resolve(implementation: Call, names: Collection<String>): List<Symbol> =
            names.flatMap { name -> ModuleReference.resolve(implementation, name) }.distinct()

        /** What [usage] names, as text, when it is `@for` or `@protocol` in [implementation]. */
        @RequiresReadLock
        fun valueText(usage: Call, implementation: Call): String? {
            ThreadingAssertions.assertReadAccess()

            return when (usage.functionName()) {
                FOR -> Implementation.forText(implementation)
                PROTOCOL -> Implementation.protocolName(implementation)
                else -> null
            }
        }
    }
}
