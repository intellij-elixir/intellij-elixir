package org.elixir_lang.lowering

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.LongAdder

/** How much lowering work is done, so that tests can bound its cost by counts rather than by wall time. */
internal object LoweringCounters {
    @Volatile
    var counting = false
        @TestOnly set

    /** Elements asked for through [ElementLowering.lower]. */
    val requests = LongAdder()

    /** Calls of `Lowering.lower(element)`, recursion included. */
    val elementLowerings = LongAdder()

    /** Whole-file tokenizations. */
    val tokenizations = LongAdder()

    /** Line indexes [ElementLowering] builds. */
    val lineIndexes = LongAdder()

    fun count(counter: LongAdder) {
        if (counting) counter.increment()
    }

    @TestOnly
    fun reset() {
        listOf(requests, elementLowerings, tokenizations, lineIndexes).forEach(LongAdder::reset)
    }
}
