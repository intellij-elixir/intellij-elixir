package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/**
 * What a call defines: one answer for a `def*` clause and for a `defdelegate`, which defines the public function its
 * head names.
 */
object DefinitionDeclaration {
    @RequiresReadLock
    fun `is`(call: Call): Boolean = CallDefinitionClause.`is`(call) || Delegation.`is`(call)

    /** [call]'s declaration under [state], `null` when [call] defines nothing. */
    @RequiresReadLock
    fun of(call: Call, state: ResolveState): Declaration? =
        CallDefinitionClause.declaration(call, state) ?: Delegation.declaration(call, state)

    @RequiresReadLock
    fun nameIdentifier(call: Call): PsiElement? =
        when {
            CallDefinitionClause.`is`(call) -> CallDefinitionClause.nameIdentifier(call)
            Delegation.`is`(call) -> Delegation.nameIdentifier(call)
            else -> null
        }
}
