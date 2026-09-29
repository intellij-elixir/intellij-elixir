package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * One representative clause per function ([FunctionSymbol.function]), as [preferFunctionHead] picks it: separate
 * entries for functions with the same name but different arities (e.g., `foo/1` and `foo/2`), while a head with
 * defaults and the clauses after it stay one.
 *
 * Use for parameter info where each arity should appear as a separate hint.
 *
 * @param name keeps only the clauses defining that function, or every clause when `null` because the
 * caller has no name to match on. Resolution returns more than the function called: a call to `reduce`
 * also resolves `reduce_while`, whether or not the reference is resolved as incomplete code, so a hint
 * built from the results unfiltered describes functions the user is not calling.
 * @return a list of best representative clauses, one per name+arity combination
 */
fun preferFunctionHeadsByArity(clauses: Iterable<Call>, name: String?): List<Call> =
    clauses
        .mapNotNull { call ->
            CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                ?.let { it to call }
        }
        .filter { (nameArityInterval, _) -> name == null || nameArityInterval.name == name }
        // A clause with no symbol of its own, such as a protocol's, stands for its own name and arities.
        .groupBy({ (nameArityInterval, call) -> FunctionSymbol.functionOf(call) ?: nameArityInterval }, { it.second })
        .map { (_, group) -> preferFunctionHead(group) }

/**
 * From a list of clauses for the same function, the bare function head (no `do` block/keyword), whose parameters are
 * names rather than patterns; the first clause if there is none.
 */
fun preferFunctionHead(clauses: List<Call>): Call =
    clauses.firstOrNull { !it.hasDoBlockOrKeyword() } ?: clauses.first()
