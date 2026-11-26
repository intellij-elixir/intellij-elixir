package org.elixir_lang.beam.chunk.debug_info

import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1 as ElixirErlV1
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.AbstractCodeCompileOptions
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Type
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.beam.type.VisibilityNameArity
import org.elixir_lang.model.psi.type.TypeBuiltins.BUILTIN_ARITY_BY_NAME
import org.elixir_lang.type.Visibility
import java.util.*

object TypeDefinitions {
    /** Whether a `.beam`'s types could be read from it. */
    sealed interface Answer {
        /** Read: [types] is the types its type stubs are built from, in stub order, possibly none. */
        class Read(val types: Collection<VisibilityNameArity>) : Answer

        /** Not read, so which types it defines isn't known. */
        data object NotRead : Answer
    }

    /** Whether the types of the `.beam` that [reader] reads were read, and which. */
    fun read(reader: BeamReader, moduleName: String): Answer =
        when {
            // Built-in types are faked as being defined in `erlang`, so built-in type resolution points at a
            // single location.
            moduleName == "erlang" -> Answer.Read(builtinTypes())
            else -> abstractCodeTypes(reader)
        }

    private fun builtinTypes(): SortedSet<VisibilityNameArity> {
        val sortedSet = TreeSet<VisibilityNameArity>()

        for ((name, arities) in BUILTIN_ARITY_BY_NAME) {
            // Reserved-word names are commented out by the decompiler (no valid Elixir `@type`), so they
            // have no mirror `@type` call to pair with - don't emit a stub that setMirror can't resolve.
            if (ReservedTypeName.isReserved(name)) continue

            for (arity in arities) {
                sortedSet.add(VisibilityNameArity(Visibility.PUBLIC, name, arity))
            }
        }

        return sortedSet
    }

    /**
     * The `-type`, `-opaque` and `-nominal` forms of the debug info of the `.beam` that [reader] reads, or null when
     * none can be read: the module's own abstract code, or the specs `elixir_erl` writes, which are Erlang abstract
     * forms too. Debug info that is absent (`none`), of another shape, or without a `-module` form (Core Erlang's `[]`)
     * is not read.
     *
     * The type stubs and the decompiled mirror both read this, so `ModuleImpl.setMirror` pairs them by construction.
     */
    fun typeForms(reader: BeamReader): List<Type>? =
        when (val debugInfo = reader.debugInfo) {
            is AbstractCodeCompileOptions -> debugInfo.attributes.takeIf { it.module != null }?.types
            is ElixirErlV1 -> debugInfo.typeSpecificationAttributes?.types
            else -> null
        }

    private fun abstractCodeTypes(reader: BeamReader): Answer =
        typeForms(reader)
            ?.mapNotNull { type ->
                // Reserved-word type names are commented out by the decompiler (no valid Elixir `@type`),
                // so they have no mirror `@type` call - drop the stub to keep setMirror consistent.
                type.name
                    ?.takeUnless { ReservedTypeName.isReserved(it) }
                    ?.let { name -> VisibilityNameArity(visibility(type), name, type.arity) }
            }
            ?.groupBy(VisibilityNameArity::visibility)
            ?.values
            ?.flatMap { it.toSortedSet() }
            ?.let(Answer::Read)
            ?: Answer.NotRead

    private fun visibility(type: Type): Visibility =
        when (type.elixirAttributeName) {
            Visibility.OPAQUE.moduleAttribute -> Visibility.OPAQUE
            Visibility.PRIVATE.moduleAttribute -> Visibility.PRIVATE
            else -> Visibility.PUBLIC
        }
}
