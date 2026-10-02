package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
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

private val legEbins: List<File> by lazy {
    val otp = File(LegManifest.environment("ERLANG_SDK_HOME"), "lib")
        .listFiles { app -> app.isDirectory }
        .orEmpty()
        .map { File(it, "ebin") }

    listOf(LegManifest.ebin()) + otp
}

private fun legBeam(module: String): File? = legEbins.map { File(it, "$module.beam") }.firstOrNull(File::isFile)

private fun moduleStruct(module: String): ModuleStruct =
    when (val exports = legExports.of(module)) {
        ModuleExports.Absent -> ModuleStruct.Absent
        ModuleExports.Unreadable -> ModuleStruct.Unreadable
        is ModuleExports.Present ->
            if (STRUCT in exports.functions) {
                val beam = legBeam(module)!!
                val struct = BeamReader.readResult(beam.readBytes(), beam.path) { reader ->
                    ((reader.debugInfoResult as? ReadResult.Present)?.value as? V1)?.struct
                        ?.takeUnless { it == NIL }
                        ?.let(ModuleStruct::from)
                }

                (struct as? ReadResult.Present)?.value ?: ModuleStruct.Unreadable
            } else {
                ModuleStruct.Absent
            }
    }

/** What `__info__` gives for a module that has it, and `module_info(exports)` for one that doesn't. */
private fun moduleExports(beam: File): ModuleExports =
    BeamReader.readResult(beam.readBytes(), beam.path) { reader ->
        when (val exports = reader.exportsResult) {
            is ReadResult.Present -> {
                val byMacro = exports.value.macroNameAritySortedSetByMacro()
                val functions = byMacro[DEF].orEmpty().map { it.toNameArity() }
                val macros = byMacro[DEFMACRO].orEmpty().map { it.toNameArity() }

                if (INFO in functions) {
                    ModuleExports.Present(functions.filterNot { it in ModuleExports.NOT_IN_INFO }, macros, hasInfo = true)
                } else {
                    ModuleExports.Present(functions, macros, hasInfo = false)
                }
            }
            ReadResult.Absent, is ReadResult.Unreadable -> ModuleExports.Unreadable
        }
    }
        .let { (it as? ReadResult.Present)?.value ?: ModuleExports.Unreadable }

private val INFO = NameArity("__info__", 1)
private val STRUCT = NameArity("__struct__", 1)
private val NIL = OtpErlangAtom("nil")
