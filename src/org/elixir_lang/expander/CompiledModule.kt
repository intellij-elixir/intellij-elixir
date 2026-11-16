package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.CompiledIdentity
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.expander.DefinitionTable.Entry
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.toOtpErlangList

/**
 * A module read from its `.beam`'s debug info, as [ExpansionResult] is one compiled from source: what it defines, its
 * definitions' clauses and its deprecations.
 *
 * @property module the module's name as atom text
 * @property table what it defines
 * @property clauses each definition's debug info clauses, in order, by the kind debug info stores each as, its name and
 *   arity: each as Elixir quotes an `fn` clause, `{:->, meta, [args, body]}`, with guards joined into the arguments by
 *   `when`, and every node's origin [TextRange.EMPTY_RANGE]
 * @property deprecations what its `__info__(:deprecated)` lists, as its exports read it
 */
internal data class CompiledModule(
    val module: String,
    val table: DefinitionTable,
    val clauses: Map<MacroNameArity, List<ElixirAst>>,
    val deprecations: Deprecations,
) {
    companion object {
        /**
         * The module [beam], read from [path], its table at [level], with the [deprecations] its exports'
         * `ModuleExports.Present.deprecated` read; null where the `.beam` can't be read or holds no Elixir debug info.
         */
        fun of(beam: ByteArray, path: String, level: ElixirLanguageLevel, deprecations: Deprecations): CompiledModule? =
            (BeamReader.readResult(beam, path) { fromDebugInfo(it, level, deprecations) } as? ReadResult.Present)?.value
    }
}

/** Each definition's clauses in [modular], a mirror's module, by the kind each stores, name and arity, in source order. */
@RequiresReadLock
internal fun mirroredClauses(modular: Call): Map<MacroNameArity, List<Call>> =
    Modular.callDefinitionClauseCallSequence(modular).mapNotNull { call ->
        ProgressManager.checkCanceled()

        mirroredKey(call)?.let { it to call }
    }.groupBy({ it.first }, { it.second })

/** Each definition's clauses' ranges in [modular], a mirror's module, as [mirroredClauses] gives them. */
@RequiresReadLock
internal fun mirroredRanges(modular: Call): Map<MacroNameArity, List<TextRange>> =
    mirroredClauses(modular).mapValues { (_, calls) -> calls.map { it.textRange } }

/**
 * Where [clause] is in the mirror whose module is [modular] and whose [mirroredRanges] are [ranges]: its clause, or the
 * module where the mirror has no such clause.
 */
@RequiresReadLock
internal fun mirroredRange(clause: CompiledClause, modular: Call, ranges: Map<MacroNameArity, List<TextRange>>): TextRange =
    ranges[clause.key]?.getOrNull(clause.index) ?: modular.textRange

/** The definition a mirror's clause [call] is one of: the kind it stores, its name and arity; `null` for no clause. */
@RequiresReadLock
private fun mirroredKey(call: Call): MacroNameArity? {
    val kind = CallDefinitionClause.definer(call)?.let { storedKind(it.keyword) } ?: return null
    val (name, arities) = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return null

    return MacroNameArity(kind.keyword, name, arities.maximum ?: arities.minimum)
}

/** [reader]'s [CompiledModule], its table at [level]; null where a definition can't be read. */
private fun fromDebugInfo(reader: BeamReader, level: ElixirLanguageLevel, deprecations: Deprecations): CompiledModule? {
    val module = reader.atomsResult.valueOrNull?.moduleName() ?: return null
    val definitions = (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList ?: return null
    val table = DefinitionTable(level)
    val bodies = mutableMapOf<MacroNameArity, List<ElixirAst>>()

    for (definition in definitions) {
        ProgressManager.checkCanceled()

        val key = definition.macroNameArity ?: return null
        val clauses = (definition.clausesTerm as? OtpErlangList)?.elements()?.map { it as? OtpErlangTuple ?: return null }
            ?: return null
        val at = dbgiHead(key, clauses.firstOrNull() ?: return null) ?: return null

        table.put(
            key.toNameArity(),
            entry(table, Kind.of(key.macro), at, clauses.size, definition.defaults, CompiledIdentity.generated(definition)),
        )

        bodies[key] = clauses.map { fnClause(it) ?: return null }
    }

    return CompiledModule(module, table, bodies, deprecations)
}

/**
 * An entry of [table] as a compiled module holds it: in source order, its clauses already checked by its compile, and
 * [at], the head it was read with, as its call.
 */
private fun entry(table: DefinitionTable, kind: Kind, at: ElixirAst, clauses: Int, defaults: Int, default: Boolean): Entry =
    Entry(kind, listOf(DefinitionTable.Head(at, table.stored(at), null, null)), clauses, defaults, default, ordered = true, checksClauses = false)

/** The head [key]'s debug info [clause] is written with. */
private fun dbgiHead(key: MacroNameArity, clause: OtpErlangTuple): ElixirAst? =
    ElixirAst.fromOtp(OtpErlangTuple(arrayOf(OtpErlangAtom(key.name), clause.elementAt(0), clause.elementAt(1))), TextRange.EMPTY_RANGE)

/**
 * The debug info [clause] `{meta, args, guards, body}` as Elixir quotes an `fn` clause: guards nest as `when`, right to
 * left, after the arguments, as `elixir_utils:extract_splat_guards/1` reads.
 */
private fun fnClause(clause: OtpErlangTuple): ElixirAst? {
    val (meta, arguments, guards, body) = clause.elements()
    val guardList = elementsOf(guards) ?: return null
    val argumentList = elementsOf(arguments) ?: return null
    val head =
        if (guardList.isEmpty()) {
            argumentList
        } else {
            listOf(whenOf(argumentList + guardList.reduceRight { guard, nested -> whenOf(listOf(guard, nested)) }))
        }

    return ElixirAst.fromOtp(
        OtpErlangTuple(arrayOf(ARROW, meta, OtpErlangList(arrayOf(OtpErlangList(head.toTypedArray()), body)))),
        TextRange.EMPTY_RANGE,
    )
}

/** The elements of the list [term], which a list of bytes decodes to an [OtpErlangString] as. */
private fun elementsOf(term: OtpErlangObject): List<OtpErlangObject>? =
    when (term) {
        is OtpErlangList, is OtpErlangString -> term.toOtpErlangList().elements().toList()
        else -> null
    }

private fun whenOf(arguments: List<OtpErlangObject>): OtpErlangTuple =
    OtpErlangTuple(arrayOf(WHEN, OtpErlangList(), OtpErlangList(arguments.toTypedArray())))

private val ARROW = OtpErlangAtom("->")
private val WHEN = OtpErlangAtom("when")
