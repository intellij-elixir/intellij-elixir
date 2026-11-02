package org.elixir_lang.ui.code_intelligence

/** What a gesture gave back: the answer as shown, or nothing, as the gesture never started. */
sealed interface Reading {
    data class Read(val text: String) : Reading {
        override fun toString() = text
    }

    data class NotStarted(val why: String) : Reading {
        override fun toString() = "not started: $why"
    }
}

/** The answer a user should get, on both sides. */
sealed interface Answer {
    fun holds(text: String): Boolean

    /** The text exactly. */
    data class Is(val text: String) : Answer {
        override fun holds(text: String) = text == this.text
        override fun toString() = text
    }

    /** A reading of one item per line, holding each of [items]. */
    class Includes(vararg val items: String) : Answer {
        override fun holds(text: String) = text.lines().containsAll(items.asList())
        override fun toString() = "items including ${items.joinToString(", ")}"
    }
}

/** A row known to fail on one side: the issue that owns the fix, and what the row read when that was recorded. */
data class Known(val owner: String, val reading: Reading)

sealed interface Verdict {
    data object Pass : Verdict
    data class Fail(val reason: String) : Verdict
    data class Known(val owner: String, val reading: String) : Verdict
}

/**
 * The one rule. A right answer passes unless a known failure says otherwise, so a fix shows as that row failing until its
 * known failure goes. A wrong answer fails unless known. `not started` is never an answer: it passes as known only where
 * the known failure recorded it.
 */
fun verdict(answer: Answer, known: Known?, reading: Reading): Verdict = when {
    reading is Reading.Read && answer.holds(reading.text) ->
        if (known == null) Verdict.Pass else Verdict.Fail("fixed: drop the known failure (${known.owner})")
    known == null -> Verdict.Fail(if (reading is Reading.Read) "expected $answer, read $reading" else "$reading")
    reading is Reading.NotStarted && known.reading !is Reading.NotStarted ->
        Verdict.Fail("$reading, where ${known.owner}'s known failure reads ${known.reading}")
    else -> Verdict.Known(known.owner, "$reading")
}
