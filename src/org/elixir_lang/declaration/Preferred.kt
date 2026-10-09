package org.elixir_lang.declaration

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Resolver
import org.elixir_lang.structure_view.element.Delegation

/** A candidate [found] at a use, and, for a delegation, what it delegates to, preferred as [found] is. */
data class Preferred(val found: Found, val delegatedTo: List<Found>)

/**
 * [found] at [use] in the one order of preference: each delegation target paired with its delegation, then the
 * admitted unless [incompleteCode], then the user's declaration over the one it overrides. The last two also order each
 * [Preferred.delegatedTo].
 */
@RequiresReadLock
@Suppress("UNUSED_PARAMETER")
fun preferred(use: Use, found: List<Found>, incompleteCode: Boolean): List<Preferred> =
    overridden(admitted(paired(found), incompleteCode, Preferred::found), Preferred::found)
        .map { preferred ->
            preferred.copy(delegatedTo = overridden(admitted(preferred.delegatedTo, incompleteCode) { it }) { it })
        }

/** Each delegation target goes with its delegation, and nothing is dropped. */
@RequiresReadLock
internal fun paired(found: List<Found>): List<Preferred> {
    val (targets, named) = found.partition { it.candidate.reach.delegationTarget }
    val targetsByDelegation = targets.groupBy { target -> target.via.firstOrNull { Delegation.`is`(it) } }
    val paired = mutableSetOf<Call?>()

    val preferred = named.map { delegation ->
        val element = delegation.element as? Call
        val delegatedTo =
            if (element != null && element !in paired && delegation.isDelegation()) {
                paired += element
                targetsByDelegation[element].orEmpty()
            } else {
                emptyList()
            }

        Preferred(delegation, delegatedTo)
    }

    // A target whose delegation is not a candidate is a defect of the source that found it.
    val unpaired = targetsByDelegation.filterKeys { it !in paired }.values.flatten().map { Preferred(it, emptyList()) }

    return preferred + unpaired
}

private fun Found.isDelegation(): Boolean =
    (candidate.declaration.declared as? Declared.Source)?.form == Form.DELEGATION

/** Only what a complete call can use, unless the code is incomplete. */
internal fun <T> admitted(list: List<T>, incompleteCode: Boolean, found: (T) -> Found): List<T> =
    if (incompleteCode) {
        list
    } else {
        Resolver.preferValid(list) { found(it).candidate.applicability.admitted }
    }

/** An overridable declaration goes beside a user definition of its name and arity in the same module. */
internal fun <T> overridden(list: List<T>, found: (T) -> Found): List<T> {
    val users = list.map(found).filter { role(it) == Role.USER }

    return list.filterNot { element ->
        val overridable = found(element)
        val declaration = overridable.candidate.declaration

        isOverridable(overridable) &&
            users.any {
                it.candidate.declaration.name == declaration.name &&
                    it.candidate.declaration.overlaps(declaration) &&
                    it.declaringModuleName() == overridable.declaringModuleName()
            }
    }
}

/** Whether a later user definition in [found]'s module with its name and arity replaces it. */
fun isOverridable(found: Found): Boolean = role(found) == Role.OVERRIDABLE

/** What a declaration is to [overridden]. */
private enum class Role { USER, OVERRIDABLE, NONE }

private fun role(found: Found): Role =
    when (val declared = found.candidate.declaration.declared) {
        is Declared.Compiled -> Role.USER
        is Declared.Source ->
            when (declared.form) {
                Form.CLAUSE, Form.DELEGATION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> Role.USER
                // The two functions `defexception` declares, `exception/1` and `message/1`.
                Form.EXCEPTION -> Role.OVERRIDABLE
                // A contract, not a body.
                Form.CALLBACK -> Role.NONE
            }
    }

/** Whether the two share an arity. An `Unknown` arity shares none, so a declaration of it stays. */
private fun Declaration.overlaps(other: Declaration): Boolean {
    val interval = arity.arityInterval()
    val otherInterval = other.arity.arityInterval()

    return interval != null && otherInterval != null && interval.overlaps(otherInterval)
}
