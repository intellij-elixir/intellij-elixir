package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.Evaluation
import org.elixir_lang.expander.interpreter.Thrown
import org.elixir_lang.expander.interpreter.UnknownBinary
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `Macro.Env.stacktrace/1`, whose `file:` is `Path.relative_to_cwd/1` of the env's file where Elixir compiled it: the
 * directory of its nearest `mix.exs`, which the oracle's own working directory can't stand in for.
 */
class EnvStacktraceTest {
    @Test
    fun `a file under its root is relative to it`() {
        assertEquals(
            stacktrace(atom(MODULE), atom("bar"), 2, "lib/foo.ex"),
            call(env(file = "$ROOT/lib/foo.ex")),
        )
    }

    @Test
    fun `a file outside its root stays as given`() {
        assertEquals(
            stacktrace(atom(MODULE), atom("bar"), 2, "/elsewhere/foo.ex"),
            call(env(file = "/elsewhere/foo.ex")),
        )
    }

    @Test
    fun `a file whose root isn't known declines`() {
        assertThrows(Declined::class.java) { call(env(file = "$ROOT/lib/foo.ex"), CompileDirectories.UNKNOWN) }
    }

    @Test
    fun `no module gives the compiler's frame`() {
        assertEquals(
            stacktrace(atom("elixir_compiler"), atom("__FILE__"), 1, "lib/foo.ex"),
            call(env(module = atom("nil"), function = atom("nil"), file = "$ROOT/lib/foo.ex")),
        )
    }

    @Test
    fun `no function gives the module body's frame`() {
        assertEquals(
            stacktrace(atom(MODULE), atom("__MODULE__"), 0, "lib/foo.ex"),
            call(env(function = atom("nil"), file = "$ROOT/lib/foo.ex")),
        )
    }

    @Test
    fun `a field that isn't known declines`() {
        assertThrows(Declined::class.java) { call(env(file = "$ROOT/lib/foo.ex", fileTerm = UnknownBinary)) }
    }

    @Test
    fun `a term that is no Macro Env raises function_clause`() {
        val thrown = assertThrows(Thrown::class.java) { call(atom("nil")) }

        assertEquals(atom("function_clause"), thrown.reason)
    }

    private fun call(env: OtpErlangObject, directories: CompileDirectories = CompileDirectories { ROOT }): OtpErlangObject? {
        val run = Run(level, ExpansionObserver.NONE, { ModuleExports.Absent }, { ModuleStruct.Absent }, directories = directories)
        val evaluation = Evaluation(NODE, ExState.empty(level), Env.empty(level, KernelImports(emptyList(), emptyList())), run)

        return evaluation.natives.call("Elixir.Macro.Env", "stacktrace", listOf(env))
    }

    private fun env(
        module: OtpErlangObject = atom(MODULE),
        function: OtpErlangObject = OtpErlangTuple(arrayOf(atom("bar"), OtpErlangLong(2))),
        file: String,
        fileTerm: OtpErlangObject = OtpErlangBinary(file.toByteArray()),
    ): OtpErlangMap =
        OtpErlangMap(
            arrayOf(atom("__struct__"), atom("module"), atom("function"), atom("line"), atom("file")),
            arrayOf(atom("Elixir.Macro.Env"), module, function, OtpErlangLong(LINE), fileTerm),
        )

    private fun stacktrace(module: OtpErlangAtom, function: OtpErlangAtom, arity: Long, file: String): OtpErlangList {
        val location = OtpErlangList(
            arrayOf(
                OtpErlangTuple(arrayOf(atom("file"), OtpErlangList(file.codePoints().toArray().map { OtpErlangLong(it.toLong()) }.toTypedArray()))),
                OtpErlangTuple(arrayOf(atom("line"), OtpErlangLong(LINE))),
            ),
        )

        return OtpErlangList(arrayOf(OtpErlangTuple(arrayOf(module, function, OtpErlangLong(arity), location))))
    }

    private fun atom(name: String) = OtpErlangAtom(name)

    private val level = ElixirLanguageLevel.of("1.20.4")

    private companion object {
        const val MODULE = "Elixir.Foo"
        const val ROOT = "/project"
        const val LINE = 7L
        val NODE: ElixirAst = ElixirAst.fromOtp(OtpErlangAtom("nil"), TextRange.EMPTY_RANGE)!!
    }
}
