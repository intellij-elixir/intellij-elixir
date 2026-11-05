package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.util.system.OS
import org.elixir_lang.expander.interpreter.Filename
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `Run.absolute`, the one place a path is read as absolute or not, as `filename:absname/1` reads it on the OS the
 * SDK runs as: each shape against Elixir's own answer on this host, so a leg on each OS measures that OS's column, and
 * both columns as `filename.erl` gives them.
 */
class RunAbsoluteTest : ProbeTestCase() {
    /** `filename:pathtype/1` of each shape, and `filename:absname/2` of it, the same path `join/1` tidies `Run.absolute`'s into. */
    fun testEachShapeIsReadAsElixirReadsItOnThisOS() {
        val base = if (OS.CURRENT == OS.Windows) "c:/w" else "/w"
        val run = run(OS.CURRENT, base)
        val source = SHAPES.joinToString("\n") { shape ->
            val path = elixirString(shape)
            val absolute = elixirString(run.absolute(shape)!!)

            "IntellijElixir.Quoter.Probe.send(__ENV__, " +
                "{:filename.pathtype($path), :filename.absname($path, ${elixirString(base)}), :filename.join([$absolute])})"
        }
        val compiled = harness.compileSource(source)

        assertEquals("compile status", OtpErlangAtom("ok"), compiled.status)

        for ((shape, message) in SHAPES.zip(compiled.messages)) {
            val (pathtype, absname, joined) = (message as OtpErlangTuple).elements()

            assertEquals(shape, (pathtype as OtpErlangAtom).atomValue(), Filename.pathtype(shape.toByteArray(), OS.CURRENT).atom)
            assertEquals(shape, utf8(absname), utf8(joined))
        }
    }

    fun testAShapeOnUnix() =
        assertAbsolute(
            OS.Linux, "/w",
            "/x" to "/x", "c:/x" to "/w/c:/x", "c:x" to "/w/c:x", "//host/x" to "//host/x", "x" to "/w/x", "\\x" to "/w/\\x",
        )

    /** A root alone takes the working directory's drive, and a drive alone its directory where it is that drive. */
    fun testAShapeOnWindows() =
        assertAbsolute(
            OS.Windows, "c:/w",
            "/x" to "c:/x", "c:/x" to "c:/x", "c:x" to "c:/w/x", "//host/x" to "//host/x", "x" to "c:/w/x", "\\x" to "c:/x",
            "C:x" to "c:/w/x", "d:x" to null,
        )

    /** Where the working directory isn't known, only an absolute path is. */
    fun testAShapeWhereTheWorkingDirectoryIsUnknown() = assertAbsolute(OS.Windows, null, "c:/x" to "c:/x", "/x" to null, "x" to null)

    private fun assertAbsolute(os: OS, base: String?, vararg shapes: Pair<String, String?>) {
        val run = run(os, base)

        for ((shape, absolute) in shapes) assertEquals("$os $shape", absolute, run.absolute(shape))
    }

    private fun run(os: OS, base: String?): Run =
        Run(
            ElixirLanguageLevel.of("1.20.4"), ExpansionObserver.NONE, ExpanderTestCase.NO_EXPORTS, ExpanderTestCase.NO_STRUCTS, NO_APP_ENV,
            file = { "${base ?: "/w"}/s.ex" }, directories = { base }, os = os,
        )

    private companion object {
        val SHAPES = listOf("/x", "c:/x", "c:x", "//host/x", "x")
    }
}
