package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangObject

/** What one part of a value built from parts gives ([partsOf]): its value, a decline, or [R], a raise. */
internal sealed interface PartOutcome<out R> {
    class Value(val value: OtpErlangObject) : PartOutcome<Nothing>

    class Declines(val declined: Declined) : PartOutcome<Nothing>

    class Raises<out R>(val raise: R) : PartOutcome<R>
}

/** What running module-body code gave, as a part of a value built from parts. */
internal fun Evaluated.part(): PartOutcome<Evaluated.Raised> =
    when (this) {
        is Evaluated.Value -> PartOutcome.Value(value)
        is Evaluated.Declined ->
            PartOutcome.Declines(Declined("a part of a value", stops = declined?.stops == true).also { declined ->
                declined.given.add(given)
                declined.holds += holds
            })
        is Evaluated.Raised -> PartOutcome.Raises(this)
    }

/** What the parts of a value give ([partsOf]): their values, a decline, or [R], a raise. */
internal sealed interface PartsOutcome<out R> {
    class Values(val values: List<OtpErlangObject>) : PartsOutcome<Nothing>

    class Declines(val declined: Declined) : PartsOutcome<Nothing>

    class Raises<out R>(val raise: R) : PartsOutcome<R>
}

/**
 * The parts of a value built from them, each [taken][take] in turn: their values, or the first raise, after which none
 * runs. Where one declines, the value isn't built, and the parts after it still run: what it would have held is
 * [before], the values of the parts before it, what it holds, and what each part after it gives, as far as it goes.
 * Where a part after a decline raises, or ended ([Declined.ended]), none after it runs either, and the value is still
 * the decline, now ended.
 */
internal class InTurn<R>(private val before: List<OtpErlangObject> = emptyList(), declined: Declined? = null) {
    private val values = ArrayList<OtpErlangObject>()
    private var raises: PartOutcome.Raises<R>? = null

    /** The first part that declined, holding what the parts after it give. */
    var declined: Declined? = declined
        private set

    /** Whether a part raised or ended, so no part after it runs. */
    var raised: Boolean = declined?.ended == true
        private set

    val outcome: PartsOutcome<R>
        get() = declined?.let { PartsOutcome.Declines(it) }
            ?: raises?.let { PartsOutcome.Raises(it.raise) }
            ?: PartsOutcome.Values(values)

    /** Takes what the next part gave. */
    fun take(outcome: PartOutcome<R>) {
        val first = declined
        var raise: Thrown? = null

        when (outcome) {
            is PartOutcome.Value -> if (first == null) values += outcome.value else first.holds += outcome.value
            is PartOutcome.Declines -> {
                if (first == null) {
                    declined = outcome.declined.also { it.holds += before; it.holds += values }
                } else {
                    first.absorb(outcome.declined)
                }
                raised = outcome.declined.ended
                raise = outcome.declined.endedBy
            }
            is PartOutcome.Raises -> {
                if (first == null) raises = outcome
                raised = true
                raise = outcome.raise as? Thrown
            }
        }

        if (raised) declined?.end(raise)
    }
}

/**
 * [parts] of a value built from them, each given by [outcome] in turn ([InTurn]), with [before]. Where one declines,
 * [rest] runs after them.
 */
internal inline fun <P, R> partsOf(
    parts: List<P>,
    before: List<OtpErlangObject> = emptyList(),
    rest: List<P> = emptyList(),
    outcome: (P) -> PartOutcome<R>,
): PartsOutcome<R> {
    val turn = InTurn<R>(before)

    for (part in parts) {
        turn.take(outcome(part))
        if (turn.raised) return turn.outcome
    }

    if (turn.declined != null) {
        for (part in rest) {
            turn.take(outcome(part))
            if (turn.raised) break
        }
    }

    return turn.outcome
}

/** Adds what [rest], parts after one that [declined], give by [outcome] in turn ([InTurn]), to what it holds. */
internal inline fun <P, R> holdRest(declined: Declined, rest: List<P>, outcome: (P) -> PartOutcome<R>) {
    val turn = InTurn<R>(declined = declined)

    for (part in rest) {
        if (turn.raised) break
        turn.take(outcome(part))
    }
}
