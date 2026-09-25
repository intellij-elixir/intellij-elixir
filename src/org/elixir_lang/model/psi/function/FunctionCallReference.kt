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
import org.elixir_lang.psi.DelegationPrecedence
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
        val all = Callable(call).multiResolve(false).toList()

        val valid = all.filter { it.isValidResult }

        return if (valid.isEmpty()) offeredDeclarations(call) else functionSymbolsReached(valid, callArity)
    }
}

/**
 * What a call that resolves to nothing valid can still be offered: each declaration it names that has a [FunctionSymbol],
 * marked as offered to a call that does not compile. A `defexception`, a `Mix.Generator` embed or a compiled definition
 * has none yet, so it is named in the inspection's message but not offered here.
 */
@RequiresReadLock
private fun offeredDeclarations(call: Call): List<FunctionSymbol> =
    RejectedCall.named(call)
        // A declaration with defaults is one declaration, offered once, at the most arguments the call could mean.
        .mapNotNull { named -> (named.declaration as? Call)?.let { FunctionSymbol.at(it, named.arities.max()) }?.firstOrNull() }
        .map { it.offeredToARejectedCall() }

/**
 * The function symbols a call or capture resolving to [resolved] reaches at [arity], in one precedence order: clauses,
 * then `defprotocol` functions, then what declares a function without a clause.
 */
@RequiresReadLock
internal fun functionSymbolsReached(resolved: List<ResolveResult>, arity: Int): Collection<Symbol> {
    // The scope walk also follows a `defdelegate`'s `to:`; Go To follows it from the delegation the use names.
    return DelegationPrecedence.named<Symbol>(resolved.mapNotNull { it.element }, arity, FunctionSymbol::fromDelegation) {
        declaredSymbolsReached(resolved, arity)
    }
}

/** The symbols [resolved] reaches at [arity] that are not a `defdelegate`'s. */
@RequiresReadLock
private fun declaredSymbolsReached(resolved: List<ResolveResult>, arity: Int): List<Symbol> {
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

    val functionSymbols = clauses.flatMap { FunctionSymbol.at(it, arity) }
    if (functionSymbols.isNotEmpty()) return functionSymbols

    // Clauses directly inside a `defprotocol` are owned by ProtocolFunction, not FunctionSymbol
    // (FunctionSymbol.fromClause returns empty for them). A qualified protocol call
    // `Protocol.function(args)` therefore resolves here.
    val protocolFunctions = clauses.flatMap { ProtocolFunction.at(it, arity) }
    if (protocolFunctions.isNotEmpty()) return protocolFunctions

    // Last, what declares a function without a clause, such as an EEx `function_from_*`.
    return resolved
        .mapNotNull { it.element as? Call }
        .filterNot { it in clauses }
        .flatMap { FunctionSymbol.at(it, arity) }
}
