package org.elixir_lang.model.psi

import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.util.Query

/**
 * Shared [UsageSearcher] for Elixir [ElixirSymbolWithUsages] targets. A thin shim: it unwraps
 * [UsageSearchParameters] and delegates to [ElixirUsageQueries], where the search logic lives.
 */
@Suppress("UnstableApiUsage")
internal class ElixirSymbolUsageSearcher : UsageSearcher {
    // Explicit no-op overrides of Searcher's @ApiStatus.OverrideOnly hooks (matching their platform
    // defaults) so the Kotlin compiler's generic-specialization bridge for UsageSearcher targets
    // these overrides instead of synthesizing a bridge into Searcher's own default implementation -
    // the latter trips Plugin Verifier's override-only-usages check even though no source code ever
    // calls them. All real search logic lives in [ElixirUsageQueries].
    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? = null

    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> = emptyList()

    override fun collectSearchRequests(parameters: UsageSearchParameters): Collection<Query<out Usage>> {
        val target = parameters.target as? ElixirSymbolWithUsages ?: return emptyList()
        return ElixirUsageQueries.searchRequests(parameters.project, target, parameters.searchScope)
            .map { query -> query.filtering { (it as? ElixirPsiUsage)?.purpose != ElixirPsiUsage.Purpose.RENAME } }
    }
}
