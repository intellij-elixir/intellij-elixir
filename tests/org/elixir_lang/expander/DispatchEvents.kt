package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * The dispatches the compiler's tracer saw, as `Quoter.Compiled.events` holds them, normalised so each supported
 * release gives one event per dispatch, as [ExpansionObserver.dispatched] reports it:
 *
 * - only dispatch events of the case module, less the probes' own, `:elixir_utils.noop/0` and
 *   `Module.compile_definition_attributes/6`;
 * - each receiver and name mapped through the leg's committed `inline/3` table, since Elixir reports the name as
 *   called up to 1.15.5 in some places and after `inline/3` in others;
 * - up to 1.17, the `remote_function` that repeats the `imported_function` just before it dropped.
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
    ) {
        fun key(bodyLine: Int): String =
            "${line?.let { it - bodyLine + 1 }} $kind ${if (receiver.isEmpty()) "" else "$receiver."}$name/$arity" +
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
    private val REMOTE_KINDS = setOf(IMPORTED_FUNCTION, "imported_macro", REMOTE_FUNCTION, "remote_macro")
    private val LOCAL_KINDS = setOf("local_function", "local_macro")
}
