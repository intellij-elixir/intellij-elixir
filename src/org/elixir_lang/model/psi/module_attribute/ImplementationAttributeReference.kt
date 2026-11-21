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
    override fun resolveReference(): Collection<Symbol> =
        when (call.functionName()) {
            FOR ->
                Implementation.forNames(implementation)
                    .orEmpty()
                    .flatMap { forName -> ModuleReference.resolve(implementation, forName) }
                    .distinct()
            PROTOCOL ->
                Implementation.protocolName(implementation)
                    ?.let { protocolName -> ModuleReference.resolve(implementation, protocolName) }
                    .orEmpty()
            else -> emptyList()
        }

    companion object {
        private const val FOR = "for"
        private const val PROTOCOL = "protocol"
        val NAMES = listOf(FOR, PROTOCOL)

        /** The `defimpl` whose module [usage] is read in. */
        @RequiresReadLock
        fun implementation(usage: Call): Call? = currentModularAt(usage)?.takeIf { Implementation.`is`(it) }

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
