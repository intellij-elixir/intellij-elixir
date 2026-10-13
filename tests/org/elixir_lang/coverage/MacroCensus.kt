package org.elixir_lang.coverage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.system.OS
import org.elixir_lang.Module
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.Dispatch
import org.elixir_lang.expander.Summaries
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The macros Elixir's compiler dispatched while compiling a Mix project and its dependencies, each told apart as
 * modelled or [Opaque][isOpaque] by the expander's own summary registry.
 */
internal class MacroCensus(val elixirVersion: String, val project: String, val events: List<Event>) {
    enum class Side { APP, DEP }

    /** One `:imported_macro`, `:remote_macro` or `:local_macro` trace event. */
    data class Event(
        val dispatch: Dispatch,
        val file: String,
        /** `null` where the compiler recorded none: `defguard` builds its `Kernel.Utils.defguard/2` call without one. */
        val line: Int?,
        /** `null` where the compiler recorded none. */
        val column: Int?,
        val app: String,
        val library: String,
        /** Whether the project, not a dependency, was compiling. */
        val own: Boolean,
        /** Whether the compiler parsed with columns on. */
        val parsedWithColumns: Boolean,
    ) {
        val side: Side get() = if (own) Side.APP else Side.DEP

        /** Whether no summary models the macro, which stops the expander at its call. */
        val isOpaque: Boolean get() = Summaries.of(dispatch) == null

        val macro: String get() = "${Module.indexName(dispatch.receiver)}.${dispatch.name}/${dispatch.arity}"
    }

    /** The split between events with and without a column holds only where every event was parsed with columns on. */
    val splitsByColumn: Boolean = events.all { it.parsedWithColumns }

    fun report(): String {
        val lines = mutableListOf(
            "# Elixir $elixirVersion\t$project\t${if (splitsByColumn) "split by column" else "no column split"}",
            if (splitsByColumn) {
                "# opaque: side, library, macro, with a column, without a column"
            } else {
                "# opaque: side, library, macro, count"
            },
            if (splitsByColumn) {
                "# total: side, library, modelled with a column, modelled without, opaque with a column, opaque without"
            } else {
                "# total: side, library, modelled, opaque"
            }
        )

        events
            .filter { it.isOpaque }
            .groupBy { Triple(it.side, it.library, it.macro) }
            .toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
            .forEach { (key, group) ->
                lines += "opaque\t${key.first.name.lowercase()}\t${key.second}\t${key.third}\t${counts(group)}"
            }

        events
            .groupBy { it.side to it.library }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, group) ->
                val (opaque, modelled) = group.partition { it.isOpaque }

                lines += "total\t${key.first.name.lowercase()}\t${key.second}\t${counts(modelled)}\t${counts(opaque)}"
            }

        return lines.joinToString("\n", postfix = "\n")
    }

    private fun counts(group: List<Event>): String =
        if (splitsByColumn) {
            val (with, without) = group.partition { it.column != null }

            "${with.size}\t${without.size}"
        } else {
            "${group.size}"
        }

    companion object {
        const val TRACER = "testData/org/elixir_lang/coverage/tracer.exs"
        private const val OUTPUT = "ELIXIR_LANG_CENSUS_OUTPUT"

        /**
         * Compiles [project], its dependencies included, under the tracer into a temporary build directory, so that
         * neither its `_build` nor its sources are written. Fetches nothing: its dependencies must be fetched.
         * Mix's home and archives are empty temporary directories.
         */
        fun run(project: File, parentEnvironment: Map<String, String> = System.getenv()): MacroCensus {
            val scratch = FileUtil.createTempDirectory("macro-census", null)

            try {
                val output = File(scratch, "events.tsv")
                val bin = File(environment("ELIXIR_LANG_ELIXIR_PATH"), "bin")
                val elixir = File(bin, if (OS.CURRENT == OS.Windows) "elixir.bat" else "elixir")

                fun mix(vararg arguments: String): ProcessOutput {
                    val commandLine = GeneralCommandLine(elixir.path, "-r", File(TRACER).absolutePath, File(bin, "mix").path, *arguments)
                        .withWorkingDirectory(project.absoluteFile.toPath())
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                        // Outranks MIX_BUILD_ROOT, so an ambient value would send the build, and every dependency's, there.
                        .withEnvironment(parentEnvironment.filterKeys { !it.equals("MIX_BUILD_PATH", ignoreCase = true) })
                        .withEnvironment("MIX_BUILD_ROOT", File(scratch, "build").path)
                        .withEnvironment(OUTPUT, output.path)
                        // The test JVM inherits the Mix home and archives of the shell's Elixir, whose Hex archive another leg cannot load.
                        .withEnvironment("MIX_HOME", File(scratch, "mix-home").apply { mkdirs() }.path)
                        .withEnvironment("MIX_ARCHIVES", File(scratch, "mix-archives").apply { mkdirs() }.path)
                    val result = CapturingProcessHandler(commandLine).runProcess(TimeUnit.MINUTES.toMillis(5).toInt())

                    check(!result.isTimeout) { "mix ${arguments.first()} timed out:\n${result.stdout}\n${result.stderr}" }
                    check(result.exitCode == 0) { "mix ${arguments.first()} failed:\n${result.stdout}\n${result.stderr}" }

                    return result
                }

                // An umbrella's children compile in their own directories, so they would be counted as dependencies.
                check(!mix("run", "--no-compile", "--no-deps-check", "--no-start", "-e", "IO.write(Mix.Project.umbrella?())").stdout.trim().endsWith("true")) {
                    "${project.name} is an umbrella project; name one of its apps instead."
                }

                mix("compile")

                return MacroCensus(
                    environment("ELIXIR_VERSION"),
                    project.name,
                    if (output.isFile) output.readLines().map(::event) else emptyList()
                )
            } finally {
                FileUtil.delete(scratch)
            }
        }

        fun event(line: String): Event {
            val fields = line.split('\t')
            val dispatch = Dispatch(
                when (fields[0]) {
                    "imported_macro" -> Dispatch.Kind.IMPORTED_MACRO
                    "remote_macro" -> Dispatch.Kind.REMOTE_MACRO
                    "local_macro" -> Dispatch.Kind.LOCAL_MACRO
                    else -> error("Unknown event kind in: $line")
                },
                fields[1],
                fields[2],
                fields[3].toInt()
            )

            return Event(
                dispatch,
                fields[4],
                fields[5].toIntOrNull(),
                fields[6].toIntOrNull(),
                fields[7],
                fields[8],
                fields[9].toBoolean(),
                fields[10].toBoolean()
            )
        }
    }
}
