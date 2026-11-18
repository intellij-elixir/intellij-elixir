package org.elixir_lang.beam

import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.NameArity
import org.elixir_lang.beam.chunk.beam_documentation.Docs
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.Definition
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition.DefaultArgumentClause

/** The function a compiled export is one of, as Elixir's definition table keyed it when the module was compiled. */
internal object CompiledIdentity {
    /**
     * [name]`/`[arity]'s in the `.beam` [file]: from `Docs`, as IEx's `h` reads it, else from the debug info; the export
     * itself for another language's `.beam`, one with neither chunk, or an export neither places.
     */
    fun of(file: VirtualFile, name: String, arity: Int): NameArity =
        BeamReader.read(file) { reader -> of(reader, name, arity) } ?: NameArity(name, arity)

    /** [name]`/`[arity]'s in the `.beam` [reader] reads, as [of] a file. */
    fun of(reader: BeamReader, name: String, arity: Int): NameArity {
        val documentation = reader.documentationResult.valueOrNull
        val docs = documentation?.elixirDocs
        // Another language's module records no Elixir definition in either chunk.
        if (documentation != null && docs == null) return NameArity(name, arity)

        return docs?.let { fromDocs(it, name, arity) } ?: fromDebugInfo(reader, name, arity) ?: NameArity(name, arity)
    }

    /** The export's own `Docs` entry, else the entry whose defaults cover its arity. */
    private fun fromDocs(docs: Docs, name: String, arity: Int): NameArity? =
        KINDS.firstNotNullOfOrNull { kind ->
            (docs.documented(kind, name, arity) ?: docs.covering(kind, name, arity))?.let { NameArity(name, it.arity) }
        }

    /** The export's own definition, else, where `elixir_def` generated it for another's defaults, the one its clause calls. */
    private fun fromDebugInfo(reader: BeamReader, name: String, arity: Int): NameArity? {
        val definitions = (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList ?: return null
        val own = definitions.firstOrNull { it.name == name && it.arity == arity } ?: return null

        return DefaultArgumentClause.target(own)?.arity?.let { NameArity(name, it) }
    }

    /** Whether `elixir_def` generated [definition] for another's defaults: [DefaultArgumentClause.target] is another. */
    fun generated(definition: Definition): Boolean = DefaultArgumentClause.target(definition) !== definition

    /** `Docs`' kinds of exported definition. */
    private val KINDS = listOf("function", "macro")
}
