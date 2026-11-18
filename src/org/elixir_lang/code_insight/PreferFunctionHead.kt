package org.elixir_lang.code_insight

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.beam_documentation.Docs
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.beam.decompiler.signatureParameters
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.definition
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.documentation.Documented
import org.elixir_lang.documentation.BeamDocsHelper
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
 * function Elixir keys, so a bodiless head with default arguments and the clauses below it are one, as are a head's
 * defaults and an override of the arity they key, and `foo/1` and `foo/2`, or the `foo/2` of two modules, are two. A
 * declaration of no known identity, as one of an open arity, is a definition alone. A definition is named by the
 * signature Elixir records for it; where none is recorded, it is shown by its bare function head, else by the clause
 * that declares the most defaults, else by the first.
 */
@RequiresReadLock
fun signatures(preferred: List<Preferred>): List<Signature> {
    val docsByFile = mutableMapOf<VirtualFile, Docs?>()
    val docsOf = { file: VirtualFile ->
        if (file in docsByFile) docsByFile[file] else docs(file).also { docsByFile[file] = it }
    }
    // Each arity of a name is its own definition, and each asks its module's copy for the same replaced finds.
    val replacedByCopy = mutableMapOf<Pair<PsiElement, String>, List<Found>>()
    val replacedOf = { found: Found ->
        val name = found.candidate.declaration.name

        Documented.copy(found)
            ?.let { copy -> replacedByCopy.getOrPut(copy to name) { sourceFor(Feature.PARAMETER_INFO).replaced(copy, name).orEmpty() } }
            .orEmpty()
    }

    return preferred
        .mapNotNull { Signature.of(it.found)?.let { signature -> signature to it.found } }
        .groupBy { (_, found) -> found.definition() }
        .map { (definition, group) ->
            recordedParameters(group, definition.identity, docsOf, replacedOf)?.let { recorded ->
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
private fun recordedParameters(
    group: List<Pair<Signature, Found>>,
    identity: NameArity?,
    docsOf: (VirtualFile) -> Docs?,
    replacedOf: (Found) -> List<Found>
): List<String>? {
    val first = group.first().second
    val (name, full) = identity ?: return null

    return when (first.candidate.declaration.declared) {
        // The `Docs` chunk's signature at the definition's full arity, which is what keys it, read without decompiling.
        is Declared.Compiled ->
            (first.head.written as? BeamCallDefinition)?.let { head ->
                head.containingFile.virtualFile
                    ?.let(docsOf)
                    ?.let { docs -> BeamDocsHelper.signatures(docs, head, name, full).firstOrNull() }
                    ?.let(::signatureParameters)
                    // A signature that is no call, as an operator's `left <~> right`, names no parameters to read.
                    ?.takeIf { full in ParameterText.arities(it) }
            }
        is Declared.Source -> RecordedSignature.ofOverridden(group.map { it.second }, full, replacedOf(first))?.parameters
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
