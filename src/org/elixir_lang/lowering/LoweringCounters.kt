package org.elixir_lang.lowering

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.LongAdder

/** How much lowering work is done, so that tests can bound its cost by counts rather than by wall time. */
internal object LoweringCounters {
    @Volatile
    var counting = false
        @TestOnly set

    /** Elements asked for through [ElementLowering.lower]. */
    val requests = LongAdder()

    /** Calls of `Lowering.lower(element)` during a request, for an element outside the one requested. */
    val foreignLowerings = LongAdder()

    /** Whole-file tokenizations. */
    val tokenizations = LongAdder()

    /** Line indexes [ElementLowering] builds. */
    val lineIndexes = LongAdder()

    @PublishedApi
    internal val requested = ThreadLocal<PsiElement>()

    fun count(counter: LongAdder) {
        if (counting) counter.increment()
    }

    /** Counts [lower] as a request for [element], so that lowering anything outside [element] counts as foreign. */
    inline fun <T> request(element: PsiElement, lower: () -> T): T {
        if (!counting) return lower()

        requests.increment()
        val outer = requested.get()
        requested.set(element)

        return try {
            lower()
        } finally {
            requested.set(outer)
        }
    }

    fun countLowering(element: PsiElement) {
        if (counting && requested.get()?.let { PsiTreeUtil.isAncestor(it, element, false) } == false) {
            foreignLowerings.increment()
        }
    }

    @TestOnly
    fun reset() {
        listOf(requests, foreignLowerings, tokenizations, lineIndexes).forEach(LongAdder::reset)
    }
}
