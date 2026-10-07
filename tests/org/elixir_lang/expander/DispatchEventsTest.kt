package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DispatchEvents] over traces the compiler gave that the expander can't reach yet. */
class DispatchEventsTest {
    /**
     * 1.20.4's trace of `defdelegate six(a, b, c, d, e, f), to: Cb, as: :on_def` under `@on_definition {Cb, :on_def}`:
     * the delegated call and the callback have the same meta and env, and only the callback is dropped.
     */
    @Test
    fun `a call to a callback with the clause's meta is kept`() {
        val call = event("remote_function", 3, CALLBACK, "on_def", 6, "six", 6)
        val events = listOf(
            call,
            event("remote_function", 3, CALLBACK, "on_def", 6, "six", 6),
            event("remote_function", 3, "Elixir.Module", "compile_definition_attributes", 6, "six", 6),
        )
        val clause = ProbeHarness.ClauseAttributes(
            "def", "six", 6, 3, emptyMap(),
            listOf(CALLBACK to "on_def", "Elixir.Module" to "compile_definition_attributes"),
        )
        val traced = ElixirLanguageLevel.of(LegManifest.environment("ELIXIR_VERSION")).elixir >=
            ElixirLanguageLevel.of("1.18.4").elixir
        val kept = "3 remote_function $CALLBACK.on_def/6 in six/6"

        assertEquals(
            if (traced) listOf(kept) else listOf(kept, kept),
            DispatchEvents.of(events, MODULE, "Elixir.T.Probe", 1, "Elixir.T.Hook", mapOf(MODULE to listOf(clause))),
        )
    }

    private companion object {
        const val MODULE = "Elixir.T.D"
        const val CALLBACK = "Elixir.T.Cb"

        fun event(
            kind: String,
            line: Int,
            receiver: String,
            name: String,
            arity: Int,
            function: String,
            functionArity: Int,
        ): OtpErlangObject {
            val meta = OtpErlangList(arrayOf<OtpErlangObject>(tuple(OtpErlangAtom("line"), long(line))))
            val dispatch = tuple(OtpErlangAtom(kind), meta, OtpErlangAtom(receiver), OtpErlangAtom(name), long(arity))
            val env = OtpErlangMap(
                arrayOf(OtpErlangAtom("module"), OtpErlangAtom("function")),
                arrayOf(OtpErlangAtom(MODULE), tuple(OtpErlangAtom(function), long(functionArity))),
            )

            return tuple(dispatch, env)
        }

        fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(arrayOf(*elements))

        fun long(value: Int) = OtpErlangLong(value.toLong())
    }
}
