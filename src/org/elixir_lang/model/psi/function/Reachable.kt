package org.elixir_lang.model.psi.function

import com.intellij.openapi.progress.ProgressManager

/** [start] and every node its [edges] reach, each once, in the order first reached, so a cycle ends. */
internal fun <T> reachable(start: T, edges: (T) -> Iterable<T>): Set<T> {
    val visited = linkedSetOf(start)
    val pending = ArrayDeque(listOf(start))

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()

        for (next in edges(pending.removeFirst())) {
            if (visited.add(next)) pending.addLast(next)
        }
    }

    return visited
}
