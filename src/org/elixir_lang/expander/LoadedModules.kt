package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.AbstractCodeCompileOptions
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import java.util.Arrays

/** What a loaded module exports and its struct, from the [ExpansionResult] that compiled it or from its `.beam`. */
internal object LoadedModules {
    /** [result]'s module as Elixir loads it once it compiles. */
    fun exports(result: ExpansionResult): ModuleExports {
        val table = result.table
        val named = { kind: DefinitionTable.Kind ->
            table.entries.filterValues { it.kind == kind }.keys.sortedWith(NAME_ARITY_ORDER)
        }

        return when (loading(result)) {
            Loading.UNREADABLE -> ModuleExports.Unreadable
            Loading.LOADED -> ModuleExports.Present(
                named(DefinitionTable.Kind.DEF),
                named(DefinitionTable.Kind.DEFMACRO),
                hasInfo = true,
                behaviour = behaviour(result),
                deprecated = deprecations(result.attributes),
            )
            Loading.ABSENT -> ModuleExports.Absent
        }
    }

    /**
     * What `behaviour_info(:callbacks)` gives once [result]'s module loads, from the callbacks in its attributes when
     * the body ends: each name and arity once, as a macro's when any of them is a `@macrocallback`. It can't be read
     * where the module defines `behaviour_info/1` itself, a callback isn't known, or a callback is a private
     * definition's, which Elixir drops when nothing calls it.
     */
    private fun behaviour(result: ExpansionResult): ModuleExports.Behaviour {
        val definitions = result.table.entries

        if (ModuleExports.BEHAVIOUR_INFO in definitions) return ModuleExports.Behaviour.Unreadable

        val functions = result.attributes.callbacks ?: return ModuleExports.Behaviour.Unreadable
        val macros = result.attributes.macrocallbacks?.toSet() ?: return ModuleExports.Behaviour.Unreadable
        val callbacks = functions + macros

        if (callbacks.any { definitions[it]?.kind?.public == false }) return ModuleExports.Behaviour.Unreadable

        return if (callbacks.isEmpty()) {
            ModuleExports.Behaviour.None
        } else {
            ModuleExports.Behaviour.Callbacks(
                callbacks.distinct().map {
                    ProgressManager.checkCanceled()

                    if (it in macros) ModuleExports.macroExport(it) else it
                },
            )
        }
    }

    /**
     * Each definition `@deprecated` marked, with the reason a call reports: of the reasons its clauses gave, the
     * lowest, as `get_deprecated/1` sorts them. One reason that isn't a known binary makes the whole module's
     * [Deprecations.Unreadable].
     */
    private fun deprecations(log: AttributeLog): Deprecations {
        val reasons = log.definitions.mapValues { (_, attributes) ->
            ProgressManager.checkCanceled()

            attributes.reasons.map { ((it as? AttributeValue.Known)?.term as? Term.Binary)?.bytes ?: return Deprecations.Unreadable }
        }

        return Deprecations.Known(
            reasons.mapNotNull { (nameArity, reasons) ->
                reasons.minWithOrNull { a, b -> Arrays.compareUnsigned(a, b) }?.let { nameArity to String(it, Charsets.UTF_8) }
            }.toMap(),
        )
    }

    /** [result]'s module's struct as Elixir loads it once it compiles. */
    fun struct(result: ExpansionResult): ModuleStruct =
        when (loading(result)) {
            Loading.UNREADABLE -> ModuleStruct.Unreadable
            Loading.LOADED -> recorded(result.struct, result.table)
            Loading.ABSENT -> ModuleStruct.Absent
        }

    /** The [struct] a module's `defstruct` recorded, or where it recorded none, `Unreadable` if [table] defines what builds the struct itself. */
    fun recorded(struct: ModuleStruct?, table: DefinitionTable): ModuleStruct =
        struct ?: if (table.entries.keys.any(::buildsStruct)) ModuleStruct.Unreadable else ModuleStruct.Absent

    private enum class Loading { UNREADABLE, LOADED, ABSENT }

    /** How [result]'s module loads, where the project compiles: an error from uncertain inputs is assumed not to raise. */
    private fun loading(result: ExpansionResult): Loading =
        when {
            result.ended.endsRun -> Loading.ABSENT
            result.definitionsKnown -> Loading.LOADED
            else -> Loading.UNREADABLE
        }

    /**
     * The exports of a module the index names in [file] that its expansion didn't compile: unreadable where the run
     * [stoppedShort] of one Elixir may have compiled, and `null` where the file leaves the name no module, so it answers
     * as it would without the file.
     */
    fun uncompiledExports(file: FileExpansion): ModuleExports? = ModuleExports.Unreadable.takeIf { stoppedShort(file) }

    /**
     * Whether [file]'s run may have skipped a module Elixir compiled: a module's run [skips] one, or its top stopped
     * where its run went on to the end. A module after where the run [ended][FileExpansion.endsRun] is never compiled.
     */
    private fun stoppedShort(file: FileExpansion): Boolean =
        file.modules.any(::skips) || !file.endsRun && (file.top is Expansion.Unported || file.top is Expansion.Opaque)

    /**
     * Whether [result]'s run, or a nested module's, may have skipped a module Elixir compiled: an expansion stopped, the
     * body's or a callback output's, after which nothing was queued, or a stop left `@before_compile` callbacks unrun.
     */
    private fun skips(result: ExpansionResult): Boolean {
        ProgressManager.checkCanceled()

        val ended = result.ended

        // A stop that may raise ended the run at or before what it skipped, so Elixir ran none of that.
        return ended is ExpansionResult.Ended.Stopped && ended.skipped && !ended.mayRaise || result.nested.any(::skips)
    }

    /** What `__info__` gives for a module that has it, and `module_info(exports)` for one that doesn't. */
    fun exports(beam: ByteArray, path: String): ModuleExports =
        BeamReader.readResult(beam, path) { reader ->
            when (val exports = reader.exportsResult) {
                is ReadResult.Present -> {
                    val byMacro = exports.value.macroNameAritySortedSetByMacro()
                    val functions = byMacro[DEF].orEmpty().map { it.toNameArity() }
                    val macros = byMacro[DEFMACRO].orEmpty().map { it.toNameArity() }
                    val behaviour = if (ModuleExports.BEHAVIOUR_INFO in functions) callbacks(reader) else ModuleExports.Behaviour.None
                    val deprecated = CompiledDeprecations.of(reader)

                    if (ModuleExports.INFO in functions) {
                        ModuleExports.Present(
                            functions.filterNot { it in ModuleExports.NOT_IN_INFO },
                            macros,
                            hasInfo = true,
                            behaviour,
                            deprecated,
                        )
                    } else {
                        ModuleExports.Present(functions, macros, hasInfo = false, behaviour, deprecated)
                    }
                }
                ReadResult.Absent, is ReadResult.Unreadable -> ModuleExports.Unreadable
            }
        }
            .let { (it as? ReadResult.Present)?.value ?: ModuleExports.Unreadable }

    /** The struct of a module that [exports] gives, from its [beam]'s `Dbgi`. */
    fun struct(exports: ModuleExports, beam: () -> Pair<ByteArray, String>): ModuleStruct =
        when (exports) {
            ModuleExports.Absent -> ModuleStruct.Absent
            ModuleExports.Unreadable -> ModuleStruct.Unreadable
            is ModuleExports.Present ->
                if (STRUCT in exports.functions) {
                    val (bytes, path) = beam()
                    val struct = BeamReader.readResult(bytes, path) { reader ->
                        ((reader.debugInfoResult as? ReadResult.Present)?.value as? V1)?.struct
                            ?.takeUnless { it == NIL }
                            ?.let(ModuleStruct::from)
                    }

                    (struct as? ReadResult.Present)?.value ?: ModuleStruct.Unreadable
                } else {
                    ModuleStruct.Absent
                }
        }

    /**
     * What `behaviour_info(:callbacks)` gives for a module that exports `behaviour_info/1`, from the `callback` forms its
     * `Dbgi` chunk keeps: an Elixir module's `@callback`s and `@macrocallback`s, or an Erlang module's `-callback`s. A
     * module with none, whose `behaviour_info/1` is written out, or with no abstract code, is
     * [ModuleExports.Behaviour.Unreadable].
     */
    private fun callbacks(reader: BeamReader): ModuleExports.Behaviour {
        val read = when (val info = (reader.debugInfoResult as? ReadResult.Present)?.value) {
            is V1 -> info.typeSpecifications?.callbacks?.let { callbacks ->
                (0 until callbacks.size()).map { callbacks[it] }.map { NameArity(it.function, it.arity.toInt()) }
            }
            is AbstractCodeCompileOptions -> info.abstractCode?.mapNotNull(::callbackForm)
            else -> null
        }

        return read?.takeIf { it.isNotEmpty() }?.let(ModuleExports.Behaviour::Callbacks)
            ?: ModuleExports.Behaviour.Unreadable
    }

    /** `{attribute, _, callback, {{Name, Arity}, _}}`'s name and arity. */
    private fun callbackForm(form: OtpErlangObject): NameArity? {
        val attribute = form as? OtpErlangTuple ?: return null

        if (attribute.arity() != 4 || (attribute.elementAt(0) as? OtpErlangAtom)?.atomValue() != "attribute") return null
        if ((attribute.elementAt(2) as? OtpErlangAtom)?.atomValue() != "callback") return null

        val head = (attribute.elementAt(3) as? OtpErlangTuple)?.elementAt(0) as? OtpErlangTuple ?: return null
        val name = (head.elementAt(0) as? OtpErlangAtom)?.atomValue() ?: return null
        val arity = (head.elementAt(1) as? OtpErlangLong)?.intValue() ?: return null

        return NameArity(name, arity)
    }

    private val STRUCT = NameArity("__struct__", 1)
    private val NIL = OtpErlangAtom("nil")
}
