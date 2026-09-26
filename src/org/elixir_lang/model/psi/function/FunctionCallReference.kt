package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
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
        val callArity = call.resolvedFinalArity()
        val resolved = Callable(call).multiResolve(false)
            .filter { it.isValidResult }
        return functionSymbolsReached(resolved, callArity)
    }
}

/**
 * The function symbols a call or capture resolving to [resolved] reaches at [arity], in one precedence order: clauses,
 * then `defprotocol` functions, then what declares a function without a clause.
 */
@RequiresReadLock
internal fun functionSymbolsReached(resolved: List<ResolveResult>, arity: Int): Collection<Symbol> {
    // A use resolving to a `defdelegate` names it, though the scope walk also follows its `to:`: the delegation is
    // what a rename renames, and Go To follows `to:` from it.
    val delegations = resolved
        .mapNotNull { it.element as? Call }
        .filter { CallableDeclaration.isForm(it, CallableDeclaration.Form.DELEGATION) }
        .flatMap { FunctionSymbol.fromDelegation(it) }
        .filter { it.arity == arity }
        .map { it.reachedFromAUse() }
    if (delegations.isNotEmpty()) return delegations

    val clauses = resolved
        .mapNotNull { result ->
            when (val element = result.element) {
                // A source `def`/`defmacro` clause is already a `Call`, while a decompiled beam function exposes
                // the equivalent clause as its navigation element (the `.beam` mirror), so both flow through the
                // same `FunctionSymbol.fromClause` pipeline and compare equal by module/name/arity/macro.
                is Call -> element
                is BeamCallDefinition -> element.navigationElement as? Call
                else -> null
            }
        }
        .filter { CallDefinitionClause.`is`(it) }

    // Only navigate to the clause(s) whose arity matches this specific call site.
    // fromClause() expands multi-arity defs (via default args); without this filter,
    // Ctrl+Click on `foo(x)` would offer both `foo/1` and `foo/2` as targets.
    val functionSymbols = clauses
        .flatMap { FunctionSymbol.fromClause(it) }
        .filter { it.arity == arity }
    if (functionSymbols.isNotEmpty()) return functionSymbols

    // Clauses directly inside a `defprotocol` are owned by ProtocolFunction, not FunctionSymbol
    // (FunctionSymbol.fromClause returns empty for them). A qualified protocol call
    // `Protocol.function(args)` therefore resolves here.
    val protocolFunctions = clauses
        .flatMap { ProtocolFunction.fromClause(it) }
        .filter { it.arity == arity }
    if (protocolFunctions.isNotEmpty()) return protocolFunctions

    // Last, what declares a function without a clause, such as an EEx `function_from_*`.
    return resolved
        .mapNotNull { it.element as? Call }
        .filterNot { it in clauses }
        .flatMap { FunctionSymbol.fromDeclaration(it) }
        .filter { it.arity == arity }
}
