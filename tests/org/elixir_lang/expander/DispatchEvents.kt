package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * The dispatches and struct expansions the compiler's tracer saw, as `Quoter.Compiled.events` holds them, normalised
 * so each supported release gives one event per dispatch, as [ExpansionObserver.dispatched] reports it, one per import
 * a `quote` traces, as [ExpansionObserver.quotedImport] reports it, and one per struct, as
 * [ExpansionObserver.structExpanded] does:
 *
 * - only dispatch, `imported_quoted` and `struct_expansion` events of the case module and the modules nested in it,
 *   from the case body's first line on, less the probes' and the definition hook's own, `:elixir_utils.noop/0`,
 *   `Module.compile_definition_attributes/6`, and the calls the output of `defmodule` and `def*` makes:
 *   `:elixir_module`'s, `:elixir_def`'s and `Kernel.LexicalTracker.read_cache/2`, and the calls compiled code didn't
 *   make (see [generated]);
 * - each dispatch's receiver and name mapped through the leg's committed `inline/3` table, since Elixir reports the
 *   name as called up to 1.15.5 in some places and after `inline/3` in others; an `imported_quoted` event's module is
 *   the import's, and isn't mapped;
 * - up to 1.17, the `remote_function` that repeats the `imported_function` just before it dropped. A quoted import's
 *   `imported_function` would also drop a same-key `remote_function` just after it on its line, so cases keep the two
 *   on separate lines.
 *
 * Each is keyed by its line, counted from the case body's first, and `env.function` when it has one. [top] takes the
 * dispatches outside any module the same way, keyed by file line.
 */
internal object DispatchEvents {
    /**
     * [module]'s normalised dispatches in [events], with lines counted from [bodyLine]. [clauses] are what the
     * definition hook saw of each clause, by module, when the case module has the hook.
     */
    fun of(
        events: List<OtpErlangObject>,
        module: String,
        probeModule: String,
        bodyLine: Int,
        hookModule: String,
        clauses: Map<String, List<ProbeHarness.ClauseAttributes>> = emptyMap(),
    ): List<String> =
        normalised(events, bodyLine, clauses) { event ->
            (event.module == module || event.module?.startsWith("$module.") == true) &&
                (event.line == null || event.line >= bodyLine) &&
                event.receiver != probeModule &&
                event.receiver != hookModule
        }

    /** The normalised dispatches in [events] outside any module, before [endLine] if there is one, keyed by line. */
    fun top(events: List<OtpErlangObject>, endLine: Int?): List<String> =
        normalised(events, 1, emptyMap()) { event ->
            event.module == NIL && (endLine == null || event.line == null || event.line < endLine)
        }

    private fun normalised(
        events: List<OtpErlangObject>,
        bodyLine: Int,
        clauses: Map<String, List<ProbeHarness.ClauseAttributes>>,
        include: (Event) -> Boolean,
    ): List<String> {
        val version = LegManifest.environment("ELIXIR_VERSION")
        val inline = RewriteManifests.inline(version)
        val repeats = ElixirLanguageLevel.of(version).elixir < ElixirLanguageLevel.of(REPEAT_DROPPED).elixir
        val normalised = mutableListOf<Event>()
        var previous: Event? = null
        val parsed = events.mapNotNull(::event)
        val callbacksTraced = ElixirLanguageLevel.of(version).elixir >= ElixirLanguageLevel.of(CALLBACKS_TRACED).elixir
        val generated = generated(parsed, clauses.takeIf { callbacksTraced }.orEmpty())

        for ((index, event) in parsed.withIndex()) {
            if (!include(event) || isInternal(event) || index in generated) continue

            val mapped = inline[Triple(event.receiver, event.name, event.arity)]
                ?.takeIf { event.arities == null }
                ?.let { (receiver, name) -> event.copy(receiver = receiver, name = name) }
                ?: event
            val last = previous

            previous = mapped

            if (repeats &&
                last != null &&
                last.kind == IMPORTED_FUNCTION &&
                mapped.kind == REMOTE_FUNCTION &&
                last.line == mapped.line &&
                last.receiver == mapped.receiver &&
                last.name == mapped.name &&
                last.arity == mapped.arity
            ) {
                continue
            }

            normalised.add(mapped)
        }

        return normalised.map { it.key(bodyLine) }
    }

    /** [dispatch] of [node] in [function], keyed as [of] keys an event, its line counted from [bodyLine]. */
    fun key(node: ElixirAst, dispatch: Dispatch, function: NameArity?, bodyLine: Int): String =
        Event(
            dispatch.kind.name.lowercase(),
            line(node.meta),
            // A local event names no receiver.
            if (dispatch.kind.name.lowercase() in LOCAL_KINDS) "" else dispatch.receiver,
            dispatch.name,
            dispatch.arity,
            null,
            function?.let { "${it.name}/${it.arity}" } ?: "nil"
        ).key(bodyLine)

    /**
     * The import `quote` traced for [node] in [function], keyed as [of] keys an event, its line counted from [bodyLine].
     */
    fun key(
        node: ElixirAst,
        kind: QuotedImportKind,
        module: String,
        name: String,
        arities: List<Int>,
        function: NameArity?,
        bodyLine: Int,
    ): String {
        val inFunction = function?.let { "${it.name}/${it.arity}" } ?: NIL

        val event = if (kind == QuotedImportKind.IMPORTED_QUOTED) {
            Event(IMPORTED_QUOTED, line(node.meta), module, name, 0, null, inFunction, arities)
        } else {
            Event(kind.name.lowercase(), line(node.meta), module, name, arities.single(), null, inFunction)
        }

        return event.key(bodyLine)
    }

    /**
     * The expansion of [node], a struct of [module] given [keys], in [function], keyed as [of] keys a
     * `struct_expansion` event, its line counted from [bodyLine].
     */
    fun structKey(node: ElixirAst, module: String, keys: List<String>, function: NameArity?, bodyLine: Int): String =
        Event(
            STRUCT_EXPANSION,
            line(node.meta),
            module,
            "",
            0,
            null,
            function?.let { "${it.name}/${it.arity}" } ?: NIL,
            keys = keys,
        ).key(bodyLine)

    /** Whether [key] is a macro's dispatch. */
    fun isMacro(key: String): Boolean = key.split(" ")[1].endsWith("_macro")

    /** The line of [key]. */
    fun line(key: String): Int? = key.substringBefore(" ").toIntOrNull()

    private data class Event(
        val kind: String,
        val line: Int?,
        val receiver: String,
        val name: String,
        val arity: Int,
        val module: String?,
        val function: String,
        /** An `imported_quoted` event's arities, in place of [arity]. */
        val arities: List<Int>? = null,
        /** A `struct_expansion`'s keys, as written. */
        val keys: List<String>? = null,
        /** The traced meta, for an event read from a trace. */
        val meta: OtpErlangObject? = null,
    ) {
        val isCompileDefinitionAttributes: Boolean
            get() = receiver == "Elixir.Module" && name == "compile_definition_attributes" && arity == 6

        fun key(bodyLine: Int): String {
            val target = when {
                keys != null -> "$receiver ${keys.joinToString(", ", "[", "]")}"
                else -> "${if (receiver.isEmpty()) "" else "$receiver."}$name" + (arities?.let { " $it" } ?: "/$arity")
            }

            return "${line?.let { it - bodyLine + 1 }} $kind $target" + if (function == "nil") "" else " in $function"
        }
    }

    private fun event(pair: OtpErlangObject): Event? {
        val (event, env) = (pair as OtpErlangTuple).elements()
        val tuple = event as? OtpErlangTuple ?: return null
        val kind = (tuple.elementAt(0) as? OtpErlangAtom)?.atomValue() ?: return null
        val envMap = env as OtpErlangMap
        val module = (envMap.get(OtpErlangAtom("module")) as? OtpErlangAtom)?.atomValue()
        val function = envMap.get(OtpErlangAtom("function"))?.let(::function) ?: "nil"
        val line = ((tuple.elementAt(1) as? OtpErlangList)?.let { keyword(it, "line") } as? OtpErlangLong)?.intValue()

        return when (kind) {
            in REMOTE_KINDS -> Event(
                kind,
                line,
                (tuple.elementAt(2) as OtpErlangAtom).atomValue(),
                (tuple.elementAt(3) as OtpErlangAtom).atomValue(),
                (tuple.elementAt(4) as OtpErlangLong).intValue(),
                module,
                function,
                meta = tuple.elementAt(1),
            )
            IMPORTED_QUOTED -> Event(
                kind,
                line,
                (tuple.elementAt(2) as OtpErlangAtom).atomValue(),
                (tuple.elementAt(3) as OtpErlangAtom).atomValue(),
                0,
                module,
                function,
                integers(tuple.elementAt(4)),
            )
            in LOCAL_KINDS -> Event(
                kind,
                line,
                "",
                (tuple.elementAt(2) as OtpErlangAtom).atomValue(),
                (tuple.elementAt(3) as OtpErlangLong).intValue(),
                module,
                function,
                meta = tuple.elementAt(1),
            )
            STRUCT_EXPANSION -> Event(
                kind,
                line,
                (tuple.elementAt(2) as OtpErlangAtom).atomValue(),
                "",
                0,
                module,
                function,
                keys = (tuple.elementAt(3) as OtpErlangList).elements().map(::structKey),
            )
            else -> null
        }
    }

    /** A struct's key as the expander traces it: an atom by its name, a binary or an integer as `inspect/1` gives it. */
    private fun structKey(term: OtpErlangObject): String =
        when (term) {
            is OtpErlangAtom -> term.atomValue()
            is OtpErlangBinary -> "\"${String(term.binaryValue(), Charsets.ISO_8859_1)}\""
            else -> term.toString()
        }

    /** A list of integers, which the term encoding sends as a string when each is below 256. */
    private fun integers(term: OtpErlangObject): List<Int> =
        when (term) {
            is OtpErlangString -> term.stringValue().codePoints().toArray().toList()
            is OtpErlangList -> term.elements().map { (it as OtpErlangLong).intValue() }
            else -> throw AssertionError("not a list of integers: $term")
        }

    private fun function(term: OtpErlangObject): String =
        if (term is OtpErlangTuple) {
            "${(term.elementAt(0) as OtpErlangAtom).atomValue()}/${(term.elementAt(1) as OtpErlangLong).intValue()}"
        } else {
            "nil"
        }

    /**
     * The indices in [events] of the calls compiled code didn't make: what Elixir calls for a module once its body has
     * run, which is every `env.function` of `nil` or `__info__/1` traced after the `:elixir_utils.noop/0` that ends the
     * body, but a `@before_compile` macro; and each `@on_definition` callback [clauses] name for a clause, which is
     * traced with the clause's meta and env before `Module.compile_definition_attributes/6`, Elixir's own. A call the
     * clause's body makes to a callback has the same meta and env, so only as many are dropped as the clause has
     * callbacks, nearest first.
     */
    private fun generated(events: List<Event>, clauses: Map<String, List<ProbeHarness.ClauseAttributes>>): Set<Int> {
        val ended = mutableSetOf<String?>()
        val generated = mutableSetOf<Int>()

        for ((module, indexed) in events.withIndex().groupBy { it.value.module }) {
            // Elixir runs the callbacks once per clause, in the order it stores them, so a function's nth
            // `compile_definition_attributes` is its nth clause.
            val remaining = clauses[module].orEmpty()
                .groupBy { "${it.name}/${it.arity}" }
                .mapValues { it.value.iterator() }

            for ((position, indexedEvent) in indexed.withIndex()) {
                val (index, event) = indexedEvent

                if (module in ended && event.function in POST_MODULE_FUNCTIONS && event.kind != REMOTE_MACRO) {
                    generated += index
                }

                if (event.isCompileDefinitionAttributes) {
                    remaining[event.function]?.takeIf { it.hasNext() }?.next()?.let { clause ->
                        generated += callbackTraces(indexed.subList(0, position), event, clause.callbacks)
                    }
                }

                if (event.receiver == "elixir_utils" && event.name == "noop" && event.arity == 0) ended += module
            }
        }

        return generated
    }

    /**
     * The indices of [callbacks]' traces among [before], the events of [stored]'s module before it: for each callback
     * but Elixir's own, the nearest arity-6 `remote_function` to it with [stored]'s meta and function, back to the
     * previous clause's.
     */
    private fun callbackTraces(
        before: List<IndexedValue<Event>>,
        stored: Event,
        callbacks: List<Pair<String, String>>,
    ): List<Int> {
        val unmatched = callbacks.filterNot { it == ELIXIR_CALLBACK }.toMutableList()
        val traces = mutableListOf<Int>()

        for ((index, event) in before.asReversed()) {
            if (unmatched.isEmpty() || event.isCompileDefinitionAttributes) break

            if (event.kind == REMOTE_FUNCTION && event.arity == 6 && event.meta == stored.meta &&
                event.function == stored.function && unmatched.remove(event.receiver to event.name)
            ) {
                traces += index
            }
        }

        return traces
    }

    private fun isInternal(event: Event): Boolean =
        event.receiver == "elixir_utils" && event.name == "noop" && event.arity == 0 ||
            event.receiver == "Elixir.Module" && event.name == "compile_definition_attributes" && event.arity == 6 ||
            event.receiver == "elixir_module" ||
            event.receiver == "elixir_def" ||
            event.receiver == "Elixir.Kernel.LexicalTracker" && event.name == "read_cache" && event.arity == 2

    private fun keyword(list: OtpErlangList, key: String): OtpErlangObject? =
        list.elements().firstNotNullOfOrNull { entry ->
            (entry as? OtpErlangTuple)?.takeIf { it.arity() == 2 && it.elementAt(0) == OtpErlangAtom(key) }?.elementAt(1)
        }

    private fun line(meta: Meta): Int? = meta.keys.filterIsInstance<Meta.Key.Location>().firstOrNull()?.position?.line

    /** The release from which Elixir traces each `@on_definition` callback it runs (`afe470466`). */
    private const val CALLBACKS_TRACED = "1.18.4"

    /** Elixir's own `@on_definition` entry, which every module starts with. */
    private val ELIXIR_CALLBACK = "Elixir.Module" to "compile_definition_attributes"

    /** The release from which an imported function is dispatched once, not again as a remote call. */
    private const val REPEAT_DROPPED = "1.18.0-rc.0"
    private const val NIL = "nil"
    private const val IMPORTED_FUNCTION = "imported_function"
    private const val REMOTE_FUNCTION = "remote_function"
    private const val IMPORTED_QUOTED = "imported_quoted"
    private const val STRUCT_EXPANSION = "struct_expansion"
    private const val REMOTE_MACRO = "remote_macro"

    /** The `env.function` of a call Elixir makes for a module once its body has run. */
    private val POST_MODULE_FUNCTIONS = setOf(NIL, "__info__/1")
    private val REMOTE_KINDS = setOf(IMPORTED_FUNCTION, "imported_macro", REMOTE_FUNCTION, "remote_macro")
    private val LOCAL_KINDS = setOf("local_function", "local_macro")
}
