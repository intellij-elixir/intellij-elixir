package org.elixir_lang.expander

import com.intellij.util.system.OS
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The deprecation warnings the leg's Elixir and the expander give, each as its message and the line it is for. */
internal object DeprecationWarnings {
    /** The deprecation warnings Elixir gives while it expands [files], each a name and its source, compiled in order. */
    fun compiled(files: List<Pair<String, String>>): List<String> {
        val directory = Files.createTempDirectory("deprecation-probe").toFile()

        try {
            val paths = files.map { (name, source) -> File(directory, name).also { it.writeText(source) }.path }
            val stderr = File(directory, "stderr")
            val executable = if (OS.CURRENT == OS.Windows) "bin/elixir.bat" else "bin/elixir"
            val elixir = File(environment("ELIXIR_LANG_ELIXIR_PATH"), executable)
            val process = ProcessBuilder(listOf(elixir.path, PROBE.absolutePath) + paths)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(stderr)
                .start()

            try {
                assertTrue("timed out", process.waitFor(2, TimeUnit.MINUTES))
                assertEquals(stderr.readText(), 0, process.exitValue())

                return warningsIn(stderr.readText())
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly)
                process.destroyForcibly()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The warnings of each module [file] compiled, in its order. */
    fun of(file: FileExpansion): List<String> = file.modules.flatMap(::warnings)

    private fun warnings(result: ExpansionResult): List<String> =
        result.warnings.map(::render) + result.nested.flatMap(::warnings)

    private fun render(warning: Warning): String {
        val line = lineOf(warning.at.meta)

        return when (warning) {
            is Warning.Deprecated -> {
                val module = warning.module.removePrefix("Elixir.")

                if (warning.name == "__using__" && warning.arity == 1) {
                    "use $module is deprecated. ${warning.reason} line $line"
                } else {
                    "$module.${warning.name}/${warning.arity} is deprecated. ${warning.reason} line $line"
                }
            }
            is Warning.CompileEnv ->
                "Application.${warning.name}/${warning.arity} is discouraged in the module body, " +
                    "use Application.compile_env/3 instead line $line"
            is Warning.Emitted -> "${warning.message} line $line"
        }
    }

    private fun warningsIn(stderr: String): List<String> {
        val lines = stderr.lines()
        val starts = lines.indices.filter { WARNING.containsMatchIn(lines[it]) }

        return starts.mapIndexedNotNull { index, start ->
            val message = WARNING.find(lines[start])!!.groupValues[1]
            val block = lines.subList(start + 1, starts.getOrElse(index + 1) { lines.size })
            val line = block.firstNotNullOfOrNull { LOCATION.find(it)?.groupValues?.get(1) }

            "$message line $line".takeIf { DEPRECATION.containsMatchIn(message) }
        }
    }

    private val PROBE = File("testData/org/elixir_lang/expander/deprecation_probe/probe.exs")
    private val WARNING = Regex("""^\s*warning: (.*)$""")
    private val LOCATION = Regex("""\.ex:(\d+)""")
    private val DEPRECATION = Regex(""" is deprecated\. | is discouraged in the module body""")
}
