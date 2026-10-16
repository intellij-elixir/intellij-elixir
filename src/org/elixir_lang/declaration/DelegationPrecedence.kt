package org.elixir_lang.declaration

/**
 * Which of a `defdelegate` and what it delegates to a use means, where resolution reaches both. A use names the
 * delegation, and quick documentation shows the delegation's own `@doc` where it has one, as that says what the
 * function means there.
 */
object DelegationPrecedence {
    /** Whether [narrowed] can stand in for what was reached: a list of delegations alone names no definition. */
    fun <T> standsIn(narrowed: List<T>, isDelegation: (T) -> Boolean): Boolean = narrowed.any { !isDelegation(it) }

    /** What quick documentation shows for [reached]: the first delegation with its own `@doc`, else [otherwise]. */
    fun <T> documented(
        reached: List<T>,
        isDelegation: (T) -> Boolean,
        hasOwnDoc: (T) -> Boolean,
        otherwise: () -> T?
    ): T? = reached.firstOrNull { isDelegation(it) && hasOwnDoc(it) } ?: otherwise()
}
