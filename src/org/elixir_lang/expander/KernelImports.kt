package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO

/**
 * What `Kernel.__info__(:functions)` and `__info__(:macros)` give, which `elixir_dispatch` imports into every env.
 * Each list is sorted by name and then arity, as `__info__` gives it.
 */
data class KernelImports(val functions: List<NameArity>, val macros: List<NameArity>) {
    companion object {
        /**
         * Kernel's imports, from its `.beam`'s export table. Every export is a function except a `MACRO-` one, and
         * `__info__(:functions)` leaves out [ModuleExports.NOT_IN_INFO].
         *
         * `null` when [content] is not a `.beam`; [ReadResult.Absent] without an export table; [ReadResult.Unreadable]
         * when the export table can't be read. Nothing is reported, since the `.beam` is the SDK's.
         */
        fun read(content: ByteArray, path: String): ReadResult<KernelImports>? =
            BeamReader.readResult(content, path) { reader ->
                when (val exports = reader.exportsResult) {
                    is ReadResult.Present -> {
                        check(exports.value.callDefinitionCollection.all { it.name != null }) { "an export has no name" }

                        val byMacro = exports.value.macroNameAritySortedSetByMacro()

                        KernelImports(
                            functions = byMacro[DEF].orEmpty()
                                .map { it.toNameArity() }
                                .filterNot { it in ModuleExports.NOT_IN_INFO },
                            macros = byMacro[DEFMACRO].orEmpty().map { it.toNameArity() },
                        )
                    }
                    ReadResult.Absent -> null
                    is ReadResult.Unreadable -> throw exports.cause
                }
            }
    }
}
