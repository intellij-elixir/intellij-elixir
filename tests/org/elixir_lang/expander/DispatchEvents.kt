package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * The dispatches the compiler's tracer saw, as `Quoter.Compiled.events` holds them, normalised so each supported
 * release gives one event per dispatch, as [ExpansionObserver.dispatched] reports it, and one per import a `quote`
 * traces, as [ExpansionObserver.quotedImport] reports it:
 *
 * - only dispatch and `imported_quoted` events of the case module, less the probes' own, `:elixir_utils.noop/0` and
 *   `Module.compile_definition_attributes/6`;
 * - each dispatch's receiver and name mapped through the leg's committed `inline/3` table, since Elixir reports the
 *   name as called up to 1.15.5 in some places and after `inline/3` in others; an `imported_quoted` event's module is
 *   the import's, and isn't mapped;
 * - up to 1.17, the `remote_function` that repeats the `imported_function` just before it dropped. A quoted import's
 *   `imported_function` would also drop a same-key `remote_function` just after it on its line, so cases keep the two
 *   on separate lines.
 *
 * Each is keyed by its line, counted from the case body's first, and `env.function` when it has one.
 */
internal object DispatchEvents {
    /** [module]'s normalised dispatches in [events], with lines counted from [bodyLine]. */
    fun of(events: List<OtpErlangObject>, module: String, probeModule: String, bodyLine: Int): List<String> {
        val version = LegManifest.environment("ELIXIR_VERSION")
        val inline = RewriteManifests.inline(version)
        val repeats = ElixirLanguageLevel.of(version).elixir < ElixirLanguageLevel.of(REPEAT_DROPPED).elixir
        val normalised = mutableListOf<Event>()
        var previous: Event? = null

        for (event in events.mapNotNull(::event)) {
            if (event.module != module || event.receiver == probeModule || isInternal(event)) continue

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

    /** [dispatch] of [node], keyed as [of] keys an event, its line counted from the body's first. */
    fun key(node: ElixirAst, dispatch: Dispatch): String =
        Event(
            dispatch.kind.name.lowercase(),
            line(node.meta),
            dispatch.receiver,
            dispatch.name,
            dispatch.arity,
            null,
            "nil"
        ).key(1)

    /** The import `quote` traced for [node], keyed as [of] keys an event, its line counted from the body's first. */
    fun key(node: ElixirAst, kind: QuotedImportKind, module: String, name: String, arities: List<Int>): String =
        if (kind == QuotedImportKind.IMPORTED_QUOTED) {
            Event(IMPORTED_QUOTED, line(node.meta), module, name, 0, null, "nil", arities).key(1)
        } else {
            Event(kind.name.lowercase(), line(node.meta), module, name, arities.single(), null, "nil").key(1)
        }

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
    ) {
        fun key(bodyLine: Int): String =
            "${line?.let { it - bodyLine + 1 }} $kind ${if (receiver.isEmpty()) "" else "$receiver."}$name" +
                (arities?.let { " $it" } ?: "/$arity") +
                if (function == "nil") "" else " in $function"
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
            )
            else -> null
        }
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

    private fun isInternal(event: Event): Boolean =
        event.receiver == "elixir_utils" && event.name == "noop" && event.arity == 0 ||
            event.receiver == "Elixir.Module" && event.name == "compile_definition_attributes" && event.arity == 6

    private fun keyword(list: OtpErlangList, key: String): OtpErlangObject? =
        list.elements().firstNotNullOfOrNull { entry ->
            (entry as? OtpErlangTuple)?.takeIf { it.arity() == 2 && it.elementAt(0) == OtpErlangAtom(key) }?.elementAt(1)
        }

    private fun line(meta: Meta): Int? = meta.keys.filterIsInstance<Meta.Key.Location>().firstOrNull()?.position?.line

    /** The release from which an imported function is dispatched once, not again as a remote call. */
    private const val REPEAT_DROPPED = "1.18.0-rc.0"
    private const val IMPORTED_FUNCTION = "imported_function"
    private const val REMOTE_FUNCTION = "remote_function"
    private const val IMPORTED_QUOTED = "imported_quoted"
    private val REMOTE_KINDS = setOf(IMPORTED_FUNCTION, "imported_macro", REMOTE_FUNCTION, "remote_macro")
    private val LOCAL_KINDS = setOf("local_function", "local_macro")
}
