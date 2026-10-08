package org.elixir_lang.model.psi.words

import com.intellij.model.search.LeafOccurrenceMapper
import com.intellij.model.search.SearchWordQueryBuilder
import com.intellij.psi.PsiElement
import com.intellij.util.Query
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * Maps one occurrence of a searched word, the `leaf` holding it and the occurrence's start inside the leaf, to the
 * results it stands for.
 *
 * Plugin-owned, so a search that finds its occurrences other than through the platform's word search can call it
 * without a `LeafOccurrence`, which only the platform may make.
 */
internal fun interface WordOccurrenceMapper<T : Any> {
    @RequiresReadLock
    fun map(leaf: PsiElement, offsetInLeaf: Int): Collection<T>
}

/** [SearchWordQueryBuilder.buildQuery], for a [WordOccurrenceMapper]. */
internal fun <T : Any> SearchWordQueryBuilder.buildQueryFromLeaves(mapper: WordOccurrenceMapper<T>): Query<out T> =
    buildQuery(LeafOccurrenceMapper { (_, leaf, offsetInLeaf) -> mapper.map(leaf, offsetInLeaf) })
