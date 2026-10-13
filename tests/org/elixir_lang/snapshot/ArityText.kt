package org.elixir_lang.snapshot

import org.elixir_lang.declaration.ArityKnowledge

/** The arities as the snapshots write them: `n`, `min..max`, `min..`, or `?` when not yet knowable. */
fun ArityKnowledge.text(): String =
    when (this) {
        is ArityKnowledge.Exact -> "$arity"
        is ArityKnowledge.Range -> "$minimum..$maximum"
        is ArityKnowledge.Open -> "$minimum.."
        ArityKnowledge.Unknown -> "?"
    }
