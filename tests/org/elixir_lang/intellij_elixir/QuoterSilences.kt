package org.elixir_lang.intellij_elixir

/**
 * The quoter's consecutive silences. A daemon that dies mid-run cannot be caught by
 * [Quoter.assertAvailable], which reads a marker written before the run, so every remaining quoting
 * test would wait the full timeout - about 1,900 of them, which at this budget exceeds the CI job's
 * own limit and reports nothing useful. Giving up after a few silences in a row keeps that loud.
 */
class QuoterSilences {
    var count = 0
        private set

    /** An answer of any kind ends the run of silences. */
    fun answered() {
        count = 0
    }

    /** Counts a silence, and says whether this one reached [LIMIT]. */
    fun silent(): Boolean {
        count++

        return count >= LIMIT
    }

    companion object {
        const val LIMIT = 3
    }
}
