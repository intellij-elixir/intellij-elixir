package org.elixir_lang.beam.chunk.debug_info

import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.Atoms
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.AbstractCodeCompileOptions
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Type
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.beam.type.VisibilityNameArity
import org.elixir_lang.model.psi.type.TypeBuiltins.BUILTIN_ARITY_BY_NAME
import org.elixir_lang.psi.Protocol
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

    /**
     * Whether the types of the `.beam` that [reader] reads were read, and which, [parentStub] being its module's stub
     * with its call stubs built.
     */
    fun read(parentStub: ModuleStub<*>, reader: BeamReader, atoms: Atoms): Answer =
        when {
            // Built-in types are faked as being defined in `erlang`, so built-in type resolution points at a
            // single location.
            atoms.moduleName() == "erlang" -> Answer.Read(builtinTypes())
            // Protocol type (`t`) definitions are handled specially during resolution, not as beam type stubs.
            Protocol.`is`(parentStub) -> Answer.NotRead
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
     * Enumerates a module's `@type`/`@opaque`/`@typep` definitions from the Erlang abstract code, the same source
     * and with the same name/arity/visibility the decompiler renders them from (see `Decompiler.appendTypes` and
     * `attribute.Type`).  `ModuleImpl.setMirror` pairs each type stub to the decompiled mirror `@type` call by name
     * and arity, so the produced stub `(name, arity)` set must equal what the mirror declares; deriving both from
     * `attribute.Type` keeps them consistent by construction.
     *
     * A beam whose debug info holds no Erlang abstract code with a `-module` form is not read: an Elixir-compiled one,
     * whose debug info is `elixir_erl`, one whose debug info is absent or unreadable, one compiled without `debug_info`,
     * whose abstract code is `none`, and one compiled from Core Erlang, whose abstract code is `[]`.
     */
    private fun abstractCodeTypes(reader: BeamReader): Answer {
        val attributes = (reader.debugInfo as? AbstractCodeCompileOptions)?.attributes?.takeIf { it.module != null }
            ?: return Answer.NotRead

        return attributes
            .macroStringAttributes
            .filterIsInstance<Type>()
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
            .let(Answer::Read)
    }

    private fun visibility(type: Type): Visibility =
        when (type.elixirAttributeName) {
            Visibility.OPAQUE.moduleAttribute -> Visibility.OPAQUE
            Visibility.PRIVATE.moduleAttribute -> Visibility.PRIVATE
            else -> Visibility.PUBLIC
        }
}
