package org.elixir_lang.expander

/**
 * Values by key, within [bound]: past it, the least recently used are evicted, and one heavier than [bound] is not
 * held. An evicted value is read again by whoever asks for it.
 *
 * @param weigh the bytes a value retains
 */
internal class ClauseCache<K : Any, V : Any>(private val bound: Long, private val weigh: (V) -> Long) {
    private class Weighed<V>(val value: V, val weight: Long)

    private val entries = LinkedHashMap<K, Weighed<V>>(16, 0.75f, true)
    private var total = 0L

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    operator fun set(key: K, value: V) {
        val weighed = Weighed(value, weigh(value))

        synchronized(this) {
            entries.remove(key)?.let { total -= it.weight }
            if (weighed.weight > bound) return

            entries[key] = weighed
            total += weighed.weight

            val eldest = entries.values.iterator()
            while (total > bound && eldest.hasNext()) {
                total -= eldest.next().weight
                eldest.remove()
            }
        }
    }
}
