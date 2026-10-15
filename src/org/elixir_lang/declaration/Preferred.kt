package org.elixir_lang.declaration

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.psi.Use as UseCall
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Resolver
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.structure_view.element.Overridable

/**
 * A candidate [found] at a use, and, for a delegation, what it delegates to, preferred as [found] is: through any
 * delegation it reaches, so a target that is a delegation too comes with what that delegation delegates to.
 */
data class Preferred(val found: Found, val delegatedTo: List<Found>) {
    /**
     * What [found] delegates to at what its use passes on: the admitted of [delegatedTo]. A target only at another arity,
     * which the step keeps when nothing is admitted, is not delegated to.
     */
    val admittedDelegatedTo: List<Found>
        get() = delegatedTo.filter { it.candidate.applicability.admitted }

    /** What [found] delegates to itself: [admittedDelegatedTo] less what a target that is a delegation too delegates to. */
    @get:RequiresReadLock
    val directlyDelegatedTo: List<Found>
        get() = admittedDelegatedTo.filter { target -> target.via.none { it != found.element && Delegation.`is`(it) } }
}

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

/**
 * [delegation] as the step prefers it at [use], the use its own head is, from [feature]'s source: with what it delegates
 * to.
 */
@RequiresReadLock
fun atItsHead(delegation: Call, use: Use.Named, feature: Feature): Preferred? =
    preferred(use, sourceFor(feature).candidates(use, false), false).firstOrNull { it.found.element == delegation }

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

/**
 * An overridable declaration goes when user definitions in the same module replace every arity it declares: a head
 * with defaults keeps the arities no user replaces.
 */
internal fun <T> overridden(list: List<T>, found: (T) -> Found): List<T> {
    val users = list.map(found).filter { role(it) == Role.USER }

    return list.filterNot { element ->
        val overridable = found(element)
        val declaration = overridable.candidate.declaration
        val interval = declaration.arity.arityInterval()
        val maximum = interval?.maximum

        isOverridable(overridable) && interval != null && maximum != null &&
            (interval.minimum..maximum).all { arity ->
                users.any {
                    it.candidate.declaration.name == declaration.name &&
                        it.candidate.declaration.overlaps(ArityKnowledge.Exact(arity)) &&
                        it.declaringModuleName() == overridable.declaringModuleName()
                }
            }
    }
}

/** Whether a later user definition in [found]'s module with its name and arity replaces it. */
@RequiresReadLock
fun isOverridable(found: Found): Boolean = role(found) == Role.OVERRIDABLE

/** What a declaration is to [overridden]. */
private enum class Role { USER, OVERRIDABLE, NONE }

private fun role(found: Found): Role =
    when (val declared = found.candidate.declaration.declared) {
        is Declared.Compiled -> Role.USER
        is Declared.Source ->
            when (declared.form) {
                Form.CLAUSE, Form.DELEGATION -> if (followedByItsDefoverridable(found)) Role.OVERRIDABLE else Role.USER
                Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> Role.USER
                // The two functions `defexception` declares, `exception/1` and `message/1`.
                Form.EXCEPTION -> Role.OVERRIDABLE
                // A contract, not a body.
                Form.CALLBACK -> Role.NONE
            }
    }

/**
 * `defoverridable` takes only what is defined when it runs, so it makes overridable a definition it follows in the same
 * body, a `use`'s `quote` included, and what a `use` it follows injects, a `use` inside another's `quote` included.
 */
private fun followedByItsDefoverridable(found: Found): Boolean {
    val definition = found.element as? Call ?: return false
    val uses = if (found.candidate.reach == Reach.USE) found.via.filter { UseCall.`is`(it) } else emptyList()
    val declaration = found.candidate.declaration

    return (listOf(definition) + uses)
        .asSequence()
        .flatMap { position -> generateSequence(position.nextSibling) { it.nextSibling } }
        .filterIsInstance<Call>()
        .filter { Overridable.`is`(it) }
        .flatMap { FunctionArityKeywordPair.occurrencesIn(it) }
        .any { it.name == declaration.name && declaration.overlaps(ArityKnowledge.Exact(it.arity)) }
}

/** Whether the two share an arity. An `Unknown` arity shares none, so a declaration of it stays. */
private fun Declaration.overlaps(otherArity: ArityKnowledge): Boolean {
    val interval = arity.arityInterval()
    val otherInterval = otherArity.arityInterval()

    return interval != null && otherInterval != null && interval.overlaps(otherInterval)
}
