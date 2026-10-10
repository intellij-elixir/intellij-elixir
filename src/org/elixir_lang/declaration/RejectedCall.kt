package org.elixir_lang.declaration

/** Why a call has no valid target: each near miss it names, with the arities that one accepts. */
object RejectedCall {
    /** [candidate] accepts each of [arities], and every arity from [from] up. */
    data class Named<T>(val candidate: T, val arities: List<Int>, val from: Int? = null)

    /**
     * What a call with [candidates] names, which may be nothing; `null` where the call is not rejected, as one is valid
     * or cannot be decided. It names a declaration at the wrong arity, never what a `defdelegate` delegates to, which
     * the call does not name, and for a [remote] call only what the module exports. An arity is accepted where
     * [admits] says the path it was reached through brings it in, as an `import only:` may bring in some arities of a
     * function; a head open from some arity is brought in whole.
     */
    fun <T> named(
        candidates: List<T>,
        candidate: (T) -> Candidate,
        remote: Boolean,
        admits: (T, Int) -> Boolean
    ): List<Named<T>>? {
        if (candidates.any { candidate(it).applicability.admitted }) return null

        return candidates.mapNotNull { element ->
            val (declaration, reach, applicability) = candidate(element)

            if (applicability != Applicability.WRONG_ARITY ||
                reach.delegationTarget ||
                (remote && !reach.exports(declaration.capabilities))
            ) {
                return@mapNotNull null
            }

            when (val arity = declaration.arity) {
                is ArityKnowledge.Exact -> listOf(arity.arity)
                is ArityKnowledge.Range -> (arity.minimum..arity.maximum).toList()
                is ArityKnowledge.Open -> return@mapNotNull Named(element, emptyList(), arity.minimum)
                ArityKnowledge.Unknown -> emptyList()
            }
                .filter { admits(element, it) }
                .takeIf { it.isNotEmpty() }
                ?.let { Named(element, it) }
        }
    }
}
