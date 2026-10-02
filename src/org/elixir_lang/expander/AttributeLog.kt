package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * What one module's body did to its attributes.
 *
 * @property effects each effect of the module body, in the order the body runs them
 * @property reads each read in a definition, in the order the definitions are expanded
 * @property final the attributes when the module body ends, which is what `@before_compile` sees
 * @property definitions what each definition took from the attributes, keyed as [DefinitionTable] keys it
 * @property unnamed what each definition whose name isn't known took, in order
 * @property accumulating the attributes that accumulate when the module body ends
 */
internal data class AttributeLog(
    val effects: List<Logged>,
    val reads: List<Read>,
    val final: Map<String, AttributeValue>,
    val definitions: Map<NameArity, DefinitionAttributes>,
    val unnamed: List<DefinitionAttributes>,
    val accumulating: Set<String>,
) {
    /** The module's `@behaviour` when its body ends, newest first. */
    val behaviours: AttributeValue?
        get() = final["behaviour"]

    /** Each named definition's clauses' `@impl`, in order, for the definitions that have one. */
    val impls: Map<NameArity, List<Impl>>
        get() = definitions.mapValues { it.value.impls }.filterValues { it.isNotEmpty() }

    /**
     * @property at the call that has the effect: the `@` that built it, or the `Module` call
     * @property statement whether [at] is a statement of the module body, so it runs once, where the expander places
     *   it
     */
    data class Logged(val effect: Effect, val at: ElixirAst, val statement: Boolean)

    /** @property value what `@` injected */
    data class Read(val name: String, val at: ElixirAst, val owner: ExpansionResult.Owner, val value: Term)

    /** One clause's `@impl`. */
    data class Impl(val kind: DefinitionTable.Kind, val line: Int, val value: AttributeValue)

    /**
     * @property impls each clause's `@impl`, in order
     * @property doc the doc a public definition keeps, or `null` where it keeps none
     * @property deprecated the reason it is deprecated, or `null` where it isn't
     */
    data class DefinitionAttributes(
        val impls: List<Impl>,
        val doc: AttributeValue?,
        val deprecated: AttributeValue?,
    )

    companion object {
        val EMPTY = AttributeLog(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptySet())
    }
}
