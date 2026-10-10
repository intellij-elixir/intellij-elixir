package org.elixir_lang.declaration

/** Whether a declaration applies at a use. */
enum class Applicability {
    VALID,
    WRONG_ARITY,
    PRIVATE,

    /** Not decidable yet, as for [ArityKnowledge.Unknown]. */
    OPAQUE;

    /** Whether a use with a candidate this applicable compiles, or may: what a preference keeps, and no rejection names. */
    val admitted: Boolean get() = this == VALID || this == OPAQUE

    companion object {
        /**
         * How [declaration] applies at a use that resolution reached it by the name [reachedAs] and found [valid] or
         * not. `null` means the declaration is not a candidate for the use, as a name the use only starts is not; it
         * is not a fifth state.
         */
        fun of(declaration: Declaration, reachedAs: String, valid: Boolean): Applicability? =
            when {
                valid -> VALID
                declaration.name != reachedAs -> null
                declaration.arity == ArityKnowledge.Unknown -> OPAQUE
                else -> WRONG_ARITY
            }
    }
}
