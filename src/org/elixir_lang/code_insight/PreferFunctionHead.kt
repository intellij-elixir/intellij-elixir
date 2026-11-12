package org.elixir_lang.code_insight

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.beam_documentation.Docs
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.beam.decompiler.signatureParameters
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.clauseOrder
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.documentation.BeamDocsHelper
import org.elixir_lang.psi.ArityInterval
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
 * One entry per function name of a module, as completion offers them: [head] of each [preferFunctionHeads] pick of
 * [clauses], then, for each name no clause has, the first of [delegations] by that name.
 */
fun <T> eachNameOnce(clauses: Iterable<Call>, delegations: Iterable<Pair<String, T>>, head: (String, Call) -> T): List<T> {
    val heads = preferFunctionHeads(clauses)

    return heads.map { (name, clause) -> head(name, clause) } +
        delegations.filter { (name, _) -> name !in heads }.distinctBy { (name, _) -> name }.map { (_, delegation) -> delegation }
}

/**
 * One [Signature] per definition among [preferred], in the order found. A definition is a module's name and the
 * arities it covers, so a bodiless head with default arguments and the clauses below it are one, and `foo/1` and
 * `foo/2`, or the `foo/2` of two modules, are two. A definition is named by the signature Elixir records for it; where
 * none is recorded, it is shown by its bare function head, else by the clause that declares the most defaults, else by
 * the first.
 */
@RequiresReadLock
fun signatures(preferred: List<Preferred>): List<Signature> {
    // Each `.beam`'s `Docs` chunk is read once, however many of its definitions are shown.
    val docsByFile = mutableMapOf<VirtualFile, Docs?>()
    val docsOf = { file: VirtualFile ->
        if (file in docsByFile) docsByFile[file] else docs(file).also { docsByFile[file] = it }
    }

    return preferred
        .mapNotNull { Signature.of(it.found)?.let { signature -> signature to it.found } }
        .groupBy({ it.first.definition to it.second.declaringModuleName() }, { it })
        .values
        .map { group ->
            recordedParameters(group, docsOf)?.let { recorded ->
                // Elixir's merge keeps only the first clause's defaults, so a call binds by the clause that declares them.
                val declaring = group.maxBy { it.first.defaults }.first

                declaring.copy(parameters = recorded, binding = declaring.parameters)
            } ?: preferHead(group, call = { it.second.element as? Call }, defaultsOf = { it.first.defaults }).first
        }
}

/**
 * The parameters of the signature Elixir records for the one definition [group] declares: for source, the one its
 * clauses give in source order, by the same rule for a private function, which Elixir records none for; for a `.beam`,
 * the one its `Docs` chunk holds, as Quick Documentation titles it. `null` where no signature is recorded.
 */
private fun recordedParameters(group: List<Pair<Signature, Found>>, docsOf: (VirtualFile) -> Docs?): List<String>? {
    val (name, arities) = group.first().first.nameArityInterval
    val first = group.first().second

    return when (first.candidate.declaration.declared) {
        // The `Docs` chunk's signature at the definition's full arity, which is what keys it, read without decompiling.
        is Declared.Compiled ->
            (first.head.written as? BeamCallDefinition)?.let { head ->
                val full = arities.definition().first

                head.containingFile.virtualFile
                    ?.let(docsOf)
                    ?.let { docs -> BeamDocsHelper.signatures(docs, head, name, ArityInterval(full, full)).firstOrNull() }
                    ?.let(::signatureParameters)
                    // A signature that is no call, as an operator's `left <~> right`, names no parameters to read.
                    ?.takeIf { full in ParameterText.arities(it) }
            }
        is Declared.Source -> {
            val clauses = group.mapNotNull { (_, found) ->
                val form = (found.candidate.declaration.declared as? Declared.Source)?.form ?: return@mapNotNull null
                val head = found.head.written ?: return null

                // Where a clause is defined decides which names the merge keeps, so without it there are none.
                RecordedSignature.Clause(form, head, found.clauseOrder() ?: return null)
            }

            RecordedSignature.of(first.declaringModuleName(), name, arities, clauses)?.parameters
        }
    }
}

/** The `Docs` chunk of [file], a `.beam`. */
private fun docs(file: VirtualFile): Docs? = BeamReader.read(file) { reader -> reader.documentation?.docs }

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
