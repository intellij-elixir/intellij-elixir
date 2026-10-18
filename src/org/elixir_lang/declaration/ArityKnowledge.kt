package org.elixir_lang.declaration

/** What is known about a declaration's arities. */
sealed class ArityKnowledge {
    data class Exact(val arity: Int) : ArityKnowledge()

    /** From default arguments. */
    data class Range(val minimum: Int, val maximum: Int) : ArityKnowledge()

    /** Any arity from [minimum] up, as after `unquote_splicing`; `Open(0)` is any arity. */
    data class Open(val minimum: Int) : ArityKnowledge()

    /** Not yet knowable, as for arguments given by a module attribute. */
    data object Unknown : ArityKnowledge()

    /** The arities a declaration accepts: each of [arities], and every arity from [from] up. */
    data class Listing(val arities: List<Int>, val from: Int? = null)

    /** The one expansion of what is known into arities, which [Unknown] leaves empty. */
    fun listing(): Listing =
        when (this) {
            is Exact -> Listing(listOf(arity))
            is Range -> Listing((minimum..maximum).toList())
            is Open -> Listing(emptyList(), minimum)
            Unknown -> Listing(emptyList())
        }
}
