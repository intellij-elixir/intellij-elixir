package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal fun legLevel(): ElixirLanguageLevel = ElixirLanguageLevel.of(LegManifest.environment("ELIXIR_VERSION"))

internal val legKernel: KernelImports by lazy {
    val beam = File(LegManifest.ebin(), "Elixir.Kernel.beam")
    val read = KernelImports.read(beam.readBytes(), beam.path)

    (read as? ReadResult.Present)?.value ?: throw AssertionError("$beam: $read")
}

/** The leg's Elixir modules, and the OTP modules under `ERLANG_SDK_HOME`'s `lib/<app>/ebin`. */
internal val legExports: Exports by lazy {
    val otp = File(LegManifest.environment("ERLANG_SDK_HOME"), "lib")
        .listFiles { app -> app.isDirectory }
        .orEmpty()
        .map { File(it, "ebin") }
    val ebins = listOf(LegManifest.ebin()) + otp
    val cache = ConcurrentHashMap<String, ModuleExports>()

    Exports { module ->
        cache.computeIfAbsent(module) {
            ebins.map { File(it, "$module.beam") }.firstOrNull(File::isFile)?.let(::moduleExports)
                ?: ModuleExports.Absent
        }
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
