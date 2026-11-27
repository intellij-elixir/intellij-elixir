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

    /** A read `.beam`'s [declared] type forms and the [builtins] it stands for that those forms don't declare. */
    class Forms(val declared: List<Type>, val builtins: SortedSet<VisibilityNameArity>)

    /** Whether the types of the `.beam` that [reader] reads were read, and which. */
    fun read(reader: BeamReader, moduleName: String): Answer =
        forms(reader, moduleName)
            ?.let { Answer.Read(declaredTypes(it.declared) + it.builtins) }
            ?: Answer.NotRead

    /**
     * The type forms of the `.beam` that [reader] reads, [moduleName] being its module, or null when its types are not
     * read. The type stubs and the decompiled mirror both read this, so `ModuleImpl.setMirror` pairs them by
     * construction.
     */
    fun forms(reader: BeamReader, moduleName: String): Forms? {
        val typeForms = typeForms(reader)
        val builtins = if (moduleName == "erlang") undeclaredBuiltins(typeForms) else sortedSetOf()

        return if (typeForms == null && builtins.isEmpty()) null else Forms(typeForms.orEmpty(), builtins)
    }

    /**
     * The built-in types: `erlang` stands for them, so that built-in type resolution points at a single location, and
     * nothing else does. Each is public. Reserved-word names are left out: the decompiler can't write a `@type` for one,
     * so it has no mirror `@type` call for a stub to pair with.
     */
    private val BUILTINS: SortedSet<VisibilityNameArity> =
        BUILTIN_ARITY_BY_NAME
            .filterKeys { !ReservedTypeName.isReserved(it) }
            .flatMapTo(TreeSet()) { (name, arities) -> arities.map { VisibilityNameArity(Visibility.PUBLIC, name, it) } }

    /** The [BUILTINS] that [typeForms] don't declare, since `erlang.erl` declares some of its own. */
    private fun undeclaredBuiltins(typeForms: List<Type>?): SortedSet<VisibilityNameArity> {
        val declared = typeForms.orEmpty().mapNotNull { type -> type.name?.let { it to type.arity } }.toSet()

        return BUILTINS.filterTo(TreeSet()) { (it.name to it.arity) !in declared }
    }

    /**
     * The `-type`, `-opaque` and `-nominal` forms of the debug info of the `.beam` that [reader] reads, or null when
     * none can be read: the module's own abstract code, or the specs `elixir_erl` writes, which are Erlang abstract
     * forms too. Debug info that is absent (`none`), of another shape, or without a `-module` form (Core Erlang's `[]`)
     * is not read.
     */
    private fun typeForms(reader: BeamReader): List<Type>? =
        when (val debugInfo = reader.debugInfo) {
            is AbstractCodeCompileOptions -> debugInfo.attributes.takeIf { it.module != null }?.types
            is ElixirErlV1 -> debugInfo.typeSpecificationAttributes?.types
            else -> null
        }

    private fun declaredTypes(typeForms: List<Type>): List<VisibilityNameArity> =
        typeForms
            .mapNotNull { type ->
                // Reserved-word type names are commented out by the decompiler (no valid Elixir `@type`),
                // so they have no mirror `@type` call - drop the stub to keep setMirror consistent.
                type.name
                    ?.takeUnless { ReservedTypeName.isReserved(it) }
                    ?.let { name -> VisibilityNameArity(visibility(type), name, type.arity) }
            }
            .groupBy(VisibilityNameArity::visibility)
            .values
            .flatMap { it.toSortedSet() }

    private fun visibility(type: Type): Visibility =
        when (type.elixirAttributeName) {
            Visibility.OPAQUE.moduleAttribute -> Visibility.OPAQUE
            Visibility.PRIVATE.moduleAttribute -> Visibility.PRIVATE
            else -> Visibility.PUBLIC
        }
}
