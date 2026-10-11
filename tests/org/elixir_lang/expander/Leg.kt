package org.elixir_lang.expander

import org.elixir_lang.beam.ReadResult
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The leg's Elixir, and its Erlang/OTP when the test JVM is told it: an empty or missing version leaves it unknown. */
internal fun legLevel(): ElixirLanguageLevel =
    ElixirLanguageLevel.of(LegManifest.environment("ELIXIR_VERSION"), System.getenv("ERLANG_VERSION"))

internal val legKernel: KernelImports by lazy {
    val beam = File(LegManifest.ebin(), "Elixir.Kernel.beam")
    val read = KernelImports.read(beam.readBytes(), beam.path)

    (read as? ReadResult.Present)?.value ?: throw AssertionError("$beam: $read")
}

/** The leg's Elixir modules, and the OTP modules under `ERLANG_SDK_HOME`'s `lib/<app>/ebin`. */
internal val legExports: Exports by lazy {
    val cache = ConcurrentHashMap<String, ModuleExports>()

    Exports { module ->
        cache.computeIfAbsent(module) { legBeam(module)?.let(::moduleExports) ?: ModuleExports.Absent }
    }
}

/** The structs of [legExports]' modules, from each beam's `Dbgi` chunk. */
internal val legStructs: Structs by lazy {
    val cache = ConcurrentHashMap<String, ModuleStruct>()

    Structs { module -> cache.computeIfAbsent(module, ::moduleStruct) }
}

private val legEbins: List<File> by lazy { listOf(LegManifest.ebin()) + appEbins("ERLANG_SDK_HOME") }

/** Each app's `ebin` under the `lib` of the install the leg's [variable] names. */
internal fun appEbins(variable: String): List<File> =
    File(LegManifest.environment(variable), "lib").listFiles { app -> app.isDirectory }.orEmpty().map { File(it, "ebin") }

internal fun legBeam(module: String): File? = legEbins.map { File(it, "$module.beam") }.firstOrNull(File::isFile)

private fun moduleStruct(module: String): ModuleStruct =
    LoadedModules.struct(legExports.of(module)) { legBeam(module)!!.let { it.readBytes() to it.path } }

private fun moduleExports(beam: File): ModuleExports = LoadedModules.exports(beam.readBytes(), beam.path)

/** Every module the leg's Elixir and OTP ebins hold, as atom text. */
internal fun legModules(): List<String> =
    legEbins.flatMap { ebin -> ebin.listFiles { file -> file.extension == "beam" }.orEmpty().map { it.nameWithoutExtension } }
