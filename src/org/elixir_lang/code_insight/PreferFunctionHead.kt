package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * Given a list of [CallDefinitionClause] calls (potentially multiple clause heads for the same function),
 * groups them by name and selects one representative per function - preferring **bare function heads**
 * (those without a `do` block or keyword) over implementation clauses.
 *
 * A bare function head like `def map_every(enumerable, nth, fun)` has canonical parameter names,
 * while implementation clauses like `def map_every([], nth, _fun) when is_integer(nth) and nth > 1, do: []`
 * have pattern-matched literals and guards that are implementation details.
 *
 * Groups by **name only**, collapsing different arities into one entry.
 * Use for completion where one entry per function name is desired.
 *
 * @return a map from function name to the best representative clause
 */
fun preferFunctionHeads(clauses: Iterable<Call>): Map<String, Call> =
    clauses
        .mapNotNull { call ->
            CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                ?.let { it.name to call }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, group) -> preferFunctionHead(group) }

/**
 * Like [preferFunctionHeads] but groups by **(name, arityInterval)**, preserving separate entries
 * for functions with the same name but different arities (e.g., `foo/1` and `foo/2`).
 *
 * Use for parameter info where each arity should appear as a separate hint. The caller picks which
 * functions [clauses] define: this only chooses among the clauses of each one.
 *
 * @return a list of best representative clauses, one per name+arity combination
 */
fun preferFunctionHeadsByArity(clauses: Iterable<Call>): List<Call> =
    clauses
        .mapNotNull { call ->
            CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                ?.let { it to call }
        }
        .groupBy({ it.first }, { it.second })
        .map { (_, group) -> preferFunctionHead(group) }

/**
 * From a list of clauses for the same function, prefer the bare function head (no `do` block/keyword)
 * over implementation clauses. Falls back to the first clause if no bare head exists.
 */
private fun preferFunctionHead(clauses: List<Call>): Call =
    clauses.firstOrNull { !it.hasDoBlockOrKeyword() } ?: clauses.first()
