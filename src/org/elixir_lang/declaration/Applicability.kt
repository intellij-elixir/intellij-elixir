package org.elixir_lang.declaration

import org.elixir_lang.psi.arityInterval

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

        /**
         * How [declaration] applies at a use of [arity] arguments that reached it as [reachedAs] through the
         * `defdelegate` heads of arities [crossed], from the use outward. [named] is whether everything but the arity
         * fits: the names along the way, and each `import` crossed bringing in what it reaches.
         */
        fun of(declaration: Declaration, reachedAs: String, named: Boolean, arity: Int, crossed: List<ArityKnowledge>): Applicability? {
            val fits = fits(declaration, arity, crossed)

            return if (named && fits == null) OPAQUE else of(declaration, reachedAs, named && fits == true)
        }

        /**
         * How [declaration] applies at a use with no name yet and [arity] arguments, reached as [searched] through
         * the `defdelegate` heads of arities [crossed], from the use outward. Nothing such a use reaches is valid, so
         * its arity decides; behind a delegation only the name it delegates to is a candidate.
         */
        fun ofAnyName(declaration: Declaration, searched: String?, arity: Int, crossed: List<ArityKnowledge>): Applicability? {
            if (searched != null && searched != declaration.name) return null

            return if (fits(declaration, arity, crossed) == false) WRONG_ARITY else OPAQUE
        }

        /**
         * Whether [declaration] takes what a use of [arity] arguments passes it through the `defdelegate` heads of
         * arities [crossed], from the use outward: each head must take the arity reaching it. `null` when a head has
         * no largest arity, so what reaches [declaration] is not known, or when [declaration]'s own arity is unknown.
         */
        private fun fits(declaration: Declaration, arity: Int, crossed: List<ArityKnowledge>): Boolean? {
            var reaching = arity

            for (head in crossed) {
                if (!head.admits(reaching)) return false
                // A head fills in its defaults, so it calls its target with all its arguments.
                reaching = head.arityInterval()?.maximum ?: return null
            }

            return declaration.arity.arityInterval()?.contains(reaching)
        }
    }
}

/** Whether a declaration of this arity takes [arity] arguments; an unknown arity may take any. */
fun ArityKnowledge.admits(arity: Int): Boolean = arityInterval()?.contains(arity) != false
