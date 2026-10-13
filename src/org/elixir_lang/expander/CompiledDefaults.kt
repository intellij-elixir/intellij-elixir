package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO

/** How many of the arities below its own each definition of a compiled module answers to through default arguments. */
internal object CompiledDefaults {
    /**
     * From the debug info's definitions, or from `Docs` for a module compiled without them; null for a module with
     * neither, as an Erlang module has.
     */
    fun of(reader: BeamReader): Map<NameArity, Int>? = fromDebugInfo(reader) ?: fromDocs(reader)

    /** Each definition's, private ones included. */
    fun fromDebugInfo(reader: BeamReader): Map<NameArity, Int>? =
        (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList?.mapNotNull { definition ->
            val name = definition.name ?: return@mapNotNull null
            val arity = definition.arity ?: return@mapNotNull null

            NameArity(name, arity) to definition.defaults
        }?.toMap()

    /** Each exported definition's that `Docs` has an entry for: an arity a default covers has none of its own. */
    fun fromDocs(reader: BeamReader): Map<NameArity, Int>? {
        val docs = reader.documentationResult.valueOrNull?.takeIf { it.beamLanguage == "elixir" }?.docs ?: return null
        val exports = reader.exportsResult.valueOrNull?.macroNameAritySortedSetByMacro() ?: return null

        return KINDS.flatMap { (macro, kind) ->
            exports[macro].orEmpty().mapNotNull { export ->
                docs.documented(kind, export.name, export.arity)?.let { NameArity(export.name, export.arity) to it.defaults() }
            }
        }.toMap()
    }

    /** `Docs`' kind for each kind of exported definition. */
    private val KINDS = listOf(DEF to "function", DEFMACRO to "macro")
}
