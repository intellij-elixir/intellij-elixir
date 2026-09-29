package org.elixir_lang.psi.scope

object WhileIn {
    inline fun <T> whileIn(array: Array<T>, keepProcessing: (element: T) -> Boolean): Boolean {
        var accumlatedKeepProcessing = true

        for (element in array) {
            accumlatedKeepProcessing = keepProcessing(element)

            if (!accumlatedKeepProcessing) {
                break
            }
        }

        return accumlatedKeepProcessing
    }

    inline fun <T> whileIn(collection: Collection<T>, keepProcessing: (element: T) -> Boolean): Boolean {
        var accumlatedKeepProcessing = true

        for (element in collection) {
            accumlatedKeepProcessing = keepProcessing(element)

            if (!accumlatedKeepProcessing) {
                break
            }
        }

        return accumlatedKeepProcessing
    }

    inline fun <T> whileIn(sequence: Sequence<T>, keepProcessing: (element: T) -> Boolean): Boolean {
        var accumlatedKeepProcessing = true

        for (element in sequence) {
            accumlatedKeepProcessing = keepProcessing(element)

            if (!accumlatedKeepProcessing) {
                break
            }
        }

        return accumlatedKeepProcessing
    }

    /**
     * Visits every one of [elements], whatever each visit answers, as a module's definitions are all seen before a stop
     * counts, so a bodiless head's clauses after it are reached; whether every visit asked to go on.
     */
    inline fun <T> throughout(elements: Sequence<T>, visit: (element: T) -> Boolean): Boolean =
        elements.fold(true) { every, element -> visit(element) && every }

    inline fun <T> throughout(elements: Iterable<T>, visit: (element: T) -> Boolean): Boolean =
        throughout(elements.asSequence(), visit)

    /** [throughout] for a [walk] that stops when its visitor says to: it is given one that never does. */
    inline fun <T, S> throughout(walk: ((T, S) -> Boolean) -> Boolean, crossinline visit: (T, S) -> Boolean): Boolean {
        var every = true
        walk { element, state -> every = visit(element, state) && every; true }

        return every
    }
}
