package org.elixir_lang.snapshot

import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.system.OS
import org.elixir_lang.elixir_surface.LegManifest.environment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Elixir's own tests as a corpus of compiler facts: the test directories of [APPLICATIONS] in the elixir-lang/elixir
 * tree the parser tests download for the leg, under `ELIXIR_PARSING_CORPUS`.
 */
object CompilerCorpus {
    /** Each application to its test directory in the tree, which holds its `test_helper.exs`. */
    val APPLICATIONS = mapOf(
        "elixir" to "lib/elixir/test/elixir",
        "ex_unit" to "lib/ex_unit/test",
        "eex" to "lib/eex/test",
        "iex" to "lib/iex/test",
        "logger" to "lib/logger/test",
    )

    /** Some of the tests define functions by OS as they compile, and Windows runs the Elixir of a Linux leg. */
    val measured: Boolean = OS.CURRENT != OS.Windows

    /** The leg's elixir-lang/elixir tree. */
    val tree: File by lazy {
        val trees = File(environment("ELIXIR_PARSING_CORPUS"), "elixir-lang").listFiles { file ->
            file.isDirectory && file.name.startsWith("elixir@")
        }.orEmpty()

        trees.singleOrNull() ?: throw AssertionError("Expected one elixir-lang/elixir tree, found ${trees.toList()}")
    }

    /** Each application's facts, generated once for the JVM. */
    val facts: Map<String, List<String>> by lazy { generate() }

    /** Runs `generate.exs` over a copy of [tree], since the test helpers write into it, one process per application. */
    private fun generate(): Map<String, List<String>> {
        val copy = FileUtil.createTempDirectory("compiler-corpus-tree", null)
        val generated = FileUtil.createTempDirectory("compiler-corpus", null)
        val processes = mutableListOf<Process>()

        try {
            FileUtil.copyDir(tree, copy)
            val logs = APPLICATIONS.map { (application, testDirectory) ->
                val input = File(copy, testDirectory)
                val log = File(generated, "$application.log")
                val arguments = listOf(generated.path, application, input.path, File(input, "test_helper.exs").path)
                processes += ProcessBuilder(CompilerFacts.elixir().path, "generate.exs", *arguments.toTypedArray())
                    .directory(File(CompilerFacts.ROOT))
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()

                application to log
            }

            return logs.zip(processes).associate { (generating, process) ->
                val (application, log) = generating
                val finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)

                if (!finished || process.exitValue() != 0) {
                    val outcome = if (finished) "exited ${process.exitValue()}" else "timed out"
                    val output = log.readText().takeLast(LOG_TAIL)

                    throw AssertionError("generate.exs over $application's tests $outcome:\n$output")
                }

                application to File(generated, "${environment("ELIXIR_VERSION")}/$application.facts").readLines()
            }
        } finally {
            processes.forEach(Process::destroyForcibly)
            FileUtil.delete(copy)
            FileUtil.delete(generated)
        }
    }

    private const val TIMEOUT_MINUTES = 10L
    private const val LOG_TAIL = 10_000
}
