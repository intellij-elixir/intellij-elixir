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
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.Definition
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition.DefaultArgumentClause
import org.elixir_lang.expander.DefinitionTable.Entry
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import org.elixir_lang.toOtpErlangList

/**
 * A module read from its `.beam`, as [ExpansionResult] is one compiled from source: what it defines, its macros' clauses
 * and its deprecations.
 *
 * @property module the module's name as atom text
 * @property table what it defines: from its debug info, else from `Docs`, else its exports
 * @property deprecations what its `__info__(:deprecated)` lists, as its exports read it
 */
internal data class CompiledModule(
    val module: String,
    val table: DefinitionTable,
    val bodies: Bodies,
    val deprecations: Deprecations,
) {
    sealed interface Bodies {
        /**
         * Each macro's debug info clauses, in order, each as Elixir quotes an `fn` clause: `{:->, meta, [args, body]}`,
         * with guards joined into the arguments by `when`. Every node's origin is that clause in the mirror.
         */
        data class Read(val clauses: Map<NameArity, List<ElixirAst>>) : Bodies

        /** The `.beam` holds no clauses: compiled without debug info, stripped, or Erlang. */
        data object Unreadable : Bodies
    }

    companion object {
        /**
         * The module [beam], read from [path], whose decompiled source is [mirror], lowered at [level], with the
         * [deprecations] its exports' `ModuleExports.Present.deprecated` read; null where the `.beam` can't be read or
         * [mirror] lacks a definition it exports.
         */
        @RequiresReadLock
        fun of(
            beam: ByteArray,
            path: String,
            mirror: ElixirFile,
            level: ElixirLanguageLevel,
            deprecations: Deprecations,
        ): CompiledModule? {
            val modular = mirror.modulars().singleOrNull() ?: return null
            val lowering = Lowering.of(mirror, level)

            return (BeamReader.readResult(beam, path) { Builder(it, modular, lowering, deprecations).build() } as? ReadResult.Present)
                ?.value
        }
    }
}

/** One `.beam`'s [CompiledModule], with [modular] its module in the mirror. */
private class Builder(
    private val reader: BeamReader,
    private val modular: Call,
    private val lowering: Lowering,
    private val deprecations: Deprecations,
) {
    /** Each definition's clauses in the mirror, in source order. */
    private val mirrored: Map<MacroNameArity, List<Call>> =
        Modular.callDefinitionClauseCallSequence(modular).mapNotNull { call ->
            ProgressManager.checkCanceled()

            val definer = CallDefinitionClause.definer(call) ?: return@mapNotNull null
            val (name, arities) = CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                ?: return@mapNotNull null

            MacroNameArity(definer.keyword, name, arities.maximum ?: arities.minimum) to call
        }.groupBy({ it.first }, { it.second })

    fun build(): CompiledModule? {
        val module = reader.atomsResult.valueOrNull?.moduleName() ?: return null
        val definitions = (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList
        val table = DefinitionTable()
        val bodies =
            if (definitions != null) {
                fromDebugInfo(definitions, table)
            } else {
                if (!fromDocs(table) && !fromExports(table)) return null

                CompiledModule.Bodies.Unreadable
            } ?: return null

        return CompiledModule(module, table, bodies, deprecations)
    }

    /** [table] from [definitions], and their macros' clauses; null where one can't be read. */
    private fun fromDebugInfo(definitions: List<Definition>, table: DefinitionTable): CompiledModule.Bodies? {
        val read = definitions.map { definition ->
            val key = definition.macroNameArity ?: return null
            val clauses = (definition.clausesTerm as? OtpErlangList)?.elements()?.map { it as? OtpErlangTuple ?: return null }
                ?: return null

            Triple(definition, key, clauses)
        }
        val bodies = mutableMapOf<NameArity, List<ElixirAst>>()

        for ((definition, key, clauses) in read) {
            ProgressManager.checkCanceled()

            val mirror = mirrored[key].orEmpty()
            val kind = Kind.of(key.macro)
            val at = mirror.firstOrNull()?.let(::head)
                ?: dbgiHead(key, clauses.firstOrNull() ?: return null, origin(mirror, 0))
                ?: return null

            table.put(
                NameArity(key.name, key.arity),
                entry(kind, at, clauses.size, definition.defaults, isDefault(clauses)),
            )

            if (kind.macro) {
                bodies[NameArity(key.name, key.arity)] = clauses.mapIndexed { index, clause ->
                    fnClause(clause, origin(mirror, index)) ?: return null
                }
            }
        }

        return CompiledModule.Bodies.Read(bodies)
    }

    /**
     * Whether a definition was first stored for a default arity of another: whether the first of its [clauses] is the
     * one `elixir_def` generates for that one's defaults.
     */
    private fun isDefault(clauses: List<OtpErlangTuple>): Boolean =
        clauses.firstOrNull()?.let(DefaultArgumentClause::generated) != null

    /** [table] from `Docs`' functions and macros, and each arity their defaults cover; false for a module with no `Docs`. */
    private fun fromDocs(table: DefinitionTable): Boolean {
        val documented = CompiledDefaults.documented(reader) ?: return false

        for ((nameArity, kindDefaults) in documented) {
            val (macro, defaults) = kindDefaults

            put(table, MacroNameArity(macro, nameArity.name, nameArity.arity), defaults, default = false) ?: return false

            for (arity in DefinitionTable.defaultArities(nameArity.arity, defaults)) {
                if (NameArity(nameArity.name, arity) !in documented) {
                    put(table, MacroNameArity(macro, nameArity.name, arity), 0, default = true) ?: return false
                }
            }
        }

        return true
    }

    /** [table] from the exports, less those the compiler adds; false where they can't be read. */
    private fun fromExports(table: DefinitionTable): Boolean {
        val exports = reader.exportsResult.valueOrNull?.macroNameAritySortedSetByMacro() ?: return false

        for (macro in listOf(DEF, DEFMACRO)) {
            for (export in exports[macro].orEmpty()) {
                if (NameArity(export.name, export.arity) in ModuleExports.NOT_IN_INFO) continue

                put(table, export, 0, default = false) ?: return false
            }
        }

        return true
    }

    /** [key]'s entry, from its mirror clauses; null where the mirror has none. */
    private fun put(table: DefinitionTable, key: MacroNameArity, defaults: Int, default: Boolean): Unit? {
        val mirror = mirrored[key]?.takeIf { it.isNotEmpty() } ?: return null

        table.put(
            NameArity(key.name, key.arity),
            entry(Kind.of(key.macro), head(mirror.first()), mirror.size, defaults, default),
        )

        return Unit
    }

    /** An entry as a compiled module holds it: in source order, its clauses already checked by its compile. */
    private fun entry(kind: Kind, at: ElixirAst, clauses: Int, defaults: Int, default: Boolean): Entry =
        Entry(kind, at, clauses, defaults, default, ordered = true, checksClauses = false)

    private fun head(clause: Call): ElixirAst = lowering.lower(CallDefinitionClause.head(clause)!!)

    /** The [index]th of [mirror]'s clauses' range, or the module's where the mirror has no such clause. */
    private fun origin(mirror: List<Call>, index: Int): TextRange = mirror.getOrNull(index)?.textRange ?: modular.textRange

    /** The head [key]'s debug info [clause] is written with, at [origin], for a definition the mirror has no clause of. */
    private fun dbgiHead(key: MacroNameArity, clause: OtpErlangTuple, origin: TextRange): ElixirAst? =
        ElixirAst.fromOtp(OtpErlangTuple(arrayOf(OtpErlangAtom(key.name), clause.elementAt(0), clause.elementAt(1))), origin)

    /**
     * The debug info [clause] `{meta, args, guards, body}` as Elixir quotes an `fn` clause, with [origin] as every
     * node's: guards nest as `when`, right to left, after the arguments, as `elixir_utils:extract_splat_guards/1` reads.
     */
    private fun fnClause(clause: OtpErlangTuple, origin: TextRange): ElixirAst? {
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
            origin,
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

    private companion object {
        val ARROW = OtpErlangAtom("->")
        val WHEN = OtpErlangAtom("when")
    }
}
