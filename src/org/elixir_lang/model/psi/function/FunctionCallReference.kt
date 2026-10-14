package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable

/**
 * Symbol reference from a call-site expression to the [FunctionSymbol] (or [ProtocolFunction]) it
 * invokes - powers "Go To Declaration" (Ctrl+Click) from a call site to the matching `def`/`defmacro`.
 *
 * Resolution delegates to the existing [Callable] scope-walking infrastructure (which handles
 * qualified calls, unqualified calls, captures, etc.) and wraps each resolved
 * `CallDefinitionClause` as a [FunctionSymbol] via [FunctionSymbol.fromClause], or - when the clause
 * is directly inside a `defprotocol` - as a [ProtocolFunction] via [ProtocolFunction.fromClause].
 *
 * This delegation is the intended, permanent design, not a stopgap: it mirrors the platform's own
 * [com.intellij.model.psi.PsiSymbolService] / `Psi2SymbolReference` bridge, which likewise resolves
 * a Symbol reference by calling `multiResolve` on a classic resolver and wrapping the results. We do
 * not route through `PsiSymbolService` because this class adds arity filtering, default-argument
 * expansion, and `defprotocol` routing, and yields rich domain symbols ([FunctionSymbol] /
 * [ProtocolFunction]) whose semantic `equals`/`hashCode` and navigation/rename/search behaviour the
 * generic `Psi2Symbol` wrapper cannot provide. The only migration debt here is the *package
 * location* of the shared scope-walker ([Callable] lives under `reference/`); it is relocated, not
 * replaced, by the final cleanup phase.
 */
@Suppress("UnstableApiUsage")
class FunctionCallReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        // Delegate to the legacy Callable scope-walker, which understands qualified calls,
        // unqualified calls within the lexical scope, captures, imports, etc.
        // Only the function(s) whose arity matches this specific call site: a clause expands to every arity its
        // default arguments give, and Ctrl+Click on `foo(x)` must not offer `foo/2` too.
        return FunctionSymbol.fromResolved(
            Callable(call).multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element },
            call.resolvedFinalArity()
        )
    }
}
