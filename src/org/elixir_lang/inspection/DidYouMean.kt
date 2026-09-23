package org.elixir_lang.inspection

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.scope.Reach
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import java.text.BreakIterator

/**
 * What the compiler suggests for a remote call of a name its module does not define: `did_you_mean` in Elixir's
 * `exception.ex`, with the `string:jaro_similarity/2` that `String.jaro_distance/2` delegates to. It does not leave out
 * deprecated exports, as the compiler does, and orders ties by name and arity, where the compiler lists macros, then
 * functions.
 */
internal object DidYouMean {
    private const val FUNCTION_THRESHOLD = 0.77
    private const val MAX_SUGGESTIONS = 5

    /** `name/arity` of what [call]'s module exports near the name it calls; none for a local call, as the compiler. */
    @RequiresReadLock
    fun suggestions(call: Call): List<String> {
        val qualified = call as? Qualified ?: return emptyList()
        val name = call.functionName() ?: return emptyList()

        return suggestions(name, qualified.qualifiedToModulars().flatMap { exports(it) })
    }

    /** [exports]' `name/arity`s near enough to [name]: the nearest five, sorted by name. */
    fun suggestions(name: String, exports: Collection<Pair<String, Int>>): List<String> =
        exports
            .distinct()
            // Orders ties.
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (export, arity) -> Triple(jaroDistance(name, export), export, arity) }
            .filter { (distance, _, _) -> distance >= FUNCTION_THRESHOLD }
            .sortedByDescending { (distance, _, _) -> distance }
            .take(MAX_SUGGESTIONS)
            .sortedBy { (_, export, _) -> export }
            .map { (_, export, arity) -> "$export/$arity" }

    /** What [modular] [Reach.exports]. Cached until the next change, as every misspelled call of the module asks. */
    @RequiresReadLock
    private fun exports(modular: PsiElement): List<Pair<String, Int>> =
        CachedValuesManager.getCachedValue(modular) {
            CachedValueProvider.Result.create(computeExports(modular), PsiModificationTracker.MODIFICATION_COUNT)
        }

    @RequiresReadLock
    private fun computeExports(modular: PsiElement): List<Pair<String, Int>> =
        MultiResolve.resolveResults(null, 0, true, modular)
            .filter { Reach.exports(it.reach, it.element) }
            .mapNotNull { CallableDeclaration.declaredOf(it.element, ResolveState.initial()) }
            .flatMap { it.definitions(ResolveState.initial()) }
            .flatMap { declaration ->
                declaration.arityInterval?.closed()?.map { declaration.name to it }.orEmpty()
            }

    /** `String.jaro_distance/2`: `string:jaro_similarity/2` over graphemes, but 1.0 for equal strings. */
    fun jaroDistance(first: String, second: String): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0

        val firsts = graphemes(first)
        val seconds = graphemes(second)
        // Strictly within this of the same index.
        val distance = maxOf(firsts.size, seconds.size) / 2
        val matchedSeconds = BooleanArray(seconds.size)
        val firstMatches = mutableListOf<String>()

        firsts.forEachIndexed { index, grapheme ->
            val match = (maxOf(0, index - distance + 1)..minOf(seconds.size - 1, index + distance - 1))
                .firstOrNull { !matchedSeconds[it] && seconds[it] == grapheme }

            if (match != null) {
                matchedSeconds[match] = true
                firstMatches += grapheme
            }
        }

        val matches = firstMatches.size
        if (matches == 0) return 0.0

        val secondMatches = seconds.filterIndexed { index, _ -> matchedSeconds[index] }
        val transpositions = firstMatches.zip(secondMatches).count { (first, second) -> first != second }

        return (matches.toDouble() / firsts.size +
            matches.toDouble() / seconds.size +
            (matches - transpositions / 2.0) / matches) / 3
    }

    private fun graphemes(string: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(string) }

        return generateSequence(iterator.first() to iterator.next()) { (_, end) -> end to iterator.next() }
            .takeWhile { (_, end) -> end != BreakIterator.DONE }
            .map { (start, end) -> string.substring(start, end) }
            .toList()
    }
}
