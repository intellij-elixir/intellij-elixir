package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.declaringModuleName
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
        .mapValues { (_, group) ->
            val intervals = group.associateWith { CallDefinitionClause.nameArityInterval(it, ResolveState.initial())?.arityInterval }

            preferHead(
                group,
                call = { it },
                definitionOf = { clause -> intervals[clause]?.definition() },
                defaultsOf = { clause -> intervals[clause]?.defaults() ?: 0 }
            )
        }

/**
 * One [Signature] per definition among [preferred], in the order found. A definition is a module's name and the
 * arities it covers, so a bodiless head with default arguments and the clauses below it are one, and `foo/1` and
 * `foo/2`, or the `foo/2` of two modules, are two. A definition with a bare function head is shown by it, else by the
 * clause that declares the most defaults, else by the first.
 */
@RequiresReadLock
fun signatures(preferred: List<Preferred>): List<Signature> =
    preferred
        .mapNotNull { Signature.of(it.found)?.let { signature -> signature to it.found } }
        .groupBy({ it.first.definition to it.second.declaringModuleName() }, { it })
        .values
        .map { group -> preferHead(group, call = { it.second.element as? Call }, defaultsOf = { it.first.defaults }).first }

/**
 * The clause that speaks for a function: the bare function head (no `do` block or keyword) if there is one, else the
 * clause that declares the most defaults among those of the first clause's [definitionOf], else the first. Completion,
 * whose group is a name, and Parameter Info, whose group is one definition, both choose by this.
 */
private fun <T> preferHead(
    group: List<T>,
    call: (T) -> Call?,
    definitionOf: (T) -> Any? = { null },
    defaultsOf: (T) -> Int
): T =
    group.firstOrNull { call(it)?.hasDoBlockOrKeyword() == false }
        ?: group.filter { definitionOf(it) == definitionOf(group.first()) }.maxBy(defaultsOf)
