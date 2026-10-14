package org.elixir_lang

import junit.framework.TestCase.assertTrue

/**
 * Asserts that the work counted for each use, under each of [keys], doesn't grow with a module's size. Every use after
 * the first counts the same at every size; the first may pay a linear build, at most twice linear from the smallest
 * size's.
 *
 * @param perSize each size's counts, one map per use, in the order the uses were asked
 */
fun <K> assertSizeIndependent(perSize: Map<Int, List<Map<K, Long>>>, keys: Collection<K>) {
    val failures = mutableListOf<String>()
    val sizes = perSize.keys.sorted()
    val smallest = sizes.first()
    val uses = perSize.getValue(smallest).size

    for (key in keys) {
        val later = perSize.mapValues { (_, perUse) -> perUse.drop(1).map { it.getValue(key) } }
        if (later.values.distinct().size > 1) failures += "$key uses 2..$uses differ by size: $later"

        val firstSmall = perSize.getValue(smallest)[0].getValue(key)
        for (size in sizes.drop(1)) {
            val first = perSize.getValue(size)[0].getValue(key)
            val bound = (firstSmall + 1) * (size / smallest) * 2
            if (first > bound) failures += "$key first use at $size = $first > linear bound $bound"
        }
    }

    assertTrue(failures.joinToString("\n"), failures.isEmpty())
}
