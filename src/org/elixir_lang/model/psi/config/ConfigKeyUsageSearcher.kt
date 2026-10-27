package org.elixir_lang.model.psi.config

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScopeUtil
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import java.util.concurrent.Callable

/** Find Usages of a [ConfigKeySymbol]: its reads, and the config lines that set it. */
@Suppress("UnstableApiUsage")
internal class ConfigKeyUsageSearcher : UsageSearcher {
    // Every hook is overridden, as in `ElixirSymbolUsageSearcher`, for the Plugin Verifier's override-only check.
    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? = null

    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> = emptyList()

    override fun collectSearchRequests(parameters: UsageSearchParameters): Collection<Query<out Usage>> =
        listOfNotNull((parameters.target as? ConfigKeySymbol)?.let { Usages(parameters.project, it, parameters.searchScope) })

    private class Usages(
        private val project: Project,
        private val symbol: ConfigKeySymbol,
        private val searchScope: SearchScope,
    ) : AbstractQuery<PsiUsage>() {
        override fun processResults(consumer: Processor<in PsiUsage>): Boolean =
            ReadAction.nonBlocking(Callable {
                val scope = GlobalSearchScopeUtil.toGlobalSearchScope(searchScope, project)
                val reads = ConfigReads.of(project, symbol.app, symbol.key, scope).filter { it.decided }.map { it.element to it.range }
                val settings =
                    ConfigSettings.of(project, symbol.app, symbol.key, ConfigFiles.scope(project).intersectWith(scope)).map { it to Keys.range(it) }

                // The global scope bounds whole files; a local scope bounds elements within them too.
                (reads + settings)
                    .filter { (element, _) -> PsiSearchScopeUtil.isInScope(searchScope, element) }
                    .all { (element, range) -> consumer.process(PsiUsage.textUsage(element, range)) }
            }).executeSynchronously()
    }
}
