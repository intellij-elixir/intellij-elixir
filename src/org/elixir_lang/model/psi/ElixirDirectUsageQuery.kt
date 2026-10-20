@file:Suppress("UnstableApiUsage")

package org.elixir_lang.model.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import java.util.concurrent.Callable

/**
 * A [com.intellij.util.Query] that yields a single, pre-computed usage. Elixir counterpart of
 * Markdown's `MarkdownDirectUsageQuery`. Used for the self-declaration usage (and for
 * semantically-computed implementation usages that are not backed by a
 * [com.intellij.model.psi.PsiSymbolReference]), and for an edit only a rename makes.
 *
 * [processResults] runs in a blocking (non-suspend) context, so the read action uses the
 * cancellable non-suspend API per AGENTS.md § "Read action APIs" - the `Callable { }` wrapper is
 * required (a bare lambda binds to the deprecated `Runnable` overload).
 */
class ElixirDirectUsageQuery<T : Any>(private val usage: T) : AbstractQuery<T>() {
    override fun processResults(consumer: Processor<in T>): Boolean =
        ReadAction.nonBlocking(Callable { consumer.process(usage) }).executeSynchronously()
}
