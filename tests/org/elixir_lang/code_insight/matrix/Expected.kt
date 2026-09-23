package org.elixir_lang.code_insight.matrix

/**
 * One clause head as a user reads it: `def snoc(q, x) when is_list(q)`. [arity] counts every parameter, defaulted or
 * not.
 */
data class Head(val definer: String, val name: String, val parameters: List<String>, val defaults: Int, val guard: String?) {
    val arity: Int get() = parameters.size
    val signature: String get() = "$name(${parameters.joinToString(", ")})"
    val label: String get() = "$definer $signature" + (guard?.let { " when $it" } ?: "")

    /**
     * The same signature as something to type at a call: the parameter names without their defaults.
     *
     * `\\` declares a default and is only legal in a definition's head - written at a call it does not parse - so
     * completion inserting `snoc(q, x \\ nil)` would be inserting a syntax error. Documentation and parameter hints
     * describe the declaration and do show it, which is why only this one is stripped.
     */
    val callSignature: String
        get() = "$name(${parameters.joinToString(", ") { it.substringBefore(" \\\\ ") }})"

    fun covers(arity: Int): Boolean = arity in (parameters.size - defaults)..parameters.size
}

/** Elixir compares identifiers in NFC, so a name written decomposed is the same name as its precomposed form. */
fun nfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

/**
 * The clause heads a feature should describe for a definition.
 *
 * They come from real Elixir's own parse of the declaring source, so an expectation cannot be wrong in the same way
 * the plugin is wrong - a parser or decompiler bug would otherwise make the test agree with itself. `generate.exs`
 * records them rather than the suite asking at test time, which keeps that independence while leaving nothing in the
 * matrix that can vary with the Elixir a CI leg happens to configure.
 */
object Expected {
    /**
     * Every clause head of [name] covering [arity], as the author wrote it.
     *
     * The backing makes no difference here on purpose. A user asking what a function's parameters are, or how many
     * clauses it has, is entitled to the same answer whether it is their own file or a dependency's `.beam`, and
     * this suite is the safety net for a refactor of the code that answers - so it must describe the answer the
     * user wants, never the answer the current reader happens to give. Where a stripped `.beam` genuinely cannot
     * carry the names, the cell goes red like any other gap, and the day something recovers them (from an
     * `@spec`, say) it turns green. Lowering these to today's `p0, p1` did the reverse: it made an improvement
     * look like a regression.
     */
    fun heads(module: DeclaringModule, name: String, arity: Int): List<Head> =
        module.heads
            .map { Head(it.definer, it.name, it.parameters, it.defaults, it.guard) }
            .filter { nfc(it.name) == nfc(name) && it.covers(arity) }
}
