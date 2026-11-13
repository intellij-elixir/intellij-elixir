package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.beam.binaryToTerm
import org.elixir_lang.elixir_surface.LegManifest
import java.io.File

/**
 * A Mix project a fixture test lays out in [project] and compiles with the dependencies, `MIX_HOME` and `MIX_ARCHIVES`
 * that Gradle's `test` names by the system properties `elixir.<properties>.deps`, `.mixHome` and `.mixArchives`, and the
 * directory [oracle] its oracle scripts write into. Both are in a fresh directory named [name] under `build/tmp`, not
 * under the test framework's temporary directory, which it empties after each test. The dependencies must hold
 * [dependency].
 */
internal class MixFixture(name: String, properties: String, dependency: String, projectName: String = "project") {
    private val deps = File(property("elixir.$properties.deps"))

    init {
        if (!File(deps, dependency).isDirectory) {
            throw AssertionError("This test needs the fixture's dependencies in $deps, which `get${properties.replaceFirstChar(Char::uppercaseChar)}Deps` fetches.")
        }
    }

    private val root = FileUtil.createTempDirectory(File("build/tmp").absoluteFile.apply { mkdirs() }, name, null, true)
    val project = File(root, projectName).apply { mkdirs() }
    val oracle = File(root, "oracle").apply { mkdirs() }

    // Mix reads any MIX_ variable the test JVM inherits; these are the fixture's own.
    private val environment = System.getenv().filterKeys { !it.startsWith("MIX_") } + mapOf(
        "MIX_HOME" to property("elixir.$properties.mixHome"),
        "MIX_ARCHIVES" to property("elixir.$properties.mixArchives"),
        "MIX_ENV" to "dev",
    )

    /** [project], as the test laid it out, with the dependencies and a `test` directory, compiled: `mix compile`'s output. */
    fun compile(): ProcessOutput {
        FileUtil.copyDir(deps, File(project, "deps"))
        File(project, "test").mkdirs()

        // Apart, so the output is the project's own.
        MixProcess.run(project, environment, listOf("deps.compile"), timeoutMinutes = 15)

        return MixProcess.run(project, environment, listOf("compile"))
    }

    /** The oracle script `oracle/[script]`, run in [project], which writes into [oracle], given it by [variable]. */
    fun runOracle(variable: String, script: String = "oracle.exs") {
        MixProcess.run(project, environment + (variable to oracle.path), listOf("run", "--no-start", "--no-compile", "oracle/$script"))
    }

    private fun property(name: String): String =
        System.getProperty(name) ?: throw AssertionError("This test needs the system property $name, which Gradle's `test` sets.")
}

/** Whether the fixture at [fixture] has a lock in its `locks/` for the leg's Elixir, as its Gradle fetch asks. */
internal fun hasLegLock(fixture: File): Boolean = File(fixture, "locks/${LegManifest.environment("ELIXIR_VERSION")}.lock").isFile

/** The term an oracle wrote to [file]. */
internal fun etf(file: File): OtpErlangObject = binaryToTerm(file.readBytes(), 0).first

/**
 * The `file:line`s of the clauses of [name] in [declared], an oracle's list of `{name, arity, file, line}`, each once, in
 * order, space-separated.
 */
internal fun declaredAt(declared: OtpErlangObject, name: String): String =
    (declared as OtpErlangList).elements().map { it as OtpErlangTuple }
        .filter { (it.elementAt(0) as OtpErlangAtom).atomValue() == name }
        .map { "${(it.elementAt(2) as OtpErlangBinary).binaryValue().decodeToString()}:${(it.elementAt(3) as OtpErlangLong).intValue()}" }
        .distinct()
        .sorted()
        .joinToString(" ")
