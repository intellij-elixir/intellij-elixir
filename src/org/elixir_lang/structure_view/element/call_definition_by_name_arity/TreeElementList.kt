package org.elixir_lang.structure_view.element.call_definition_by_name_arity

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Name
import org.elixir_lang.NameArity
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.Timed
import org.elixir_lang.structure_view.element.modular.Modular

/**
 * A [CallDefinitionByNameArity] that inserts the [org.elixir_lang.structure_view.element.CallDefinition] it
 * generates into a `List<TreeElement>`.
 */
open class TreeElementList(
        size: Int,
        protected val treeElementList: MutableList<TreeElement>,
        protected val modular: Modular,
        private val time: Timed.Time
) : HashMap<NameArity, CallDefinition>(size), CallDefinitionByNameArity {
    @RequiresReadLock
    fun addClausesToCallDefinition(call: Call) {
        val definer = org.elixir_lang.psi.CallableDeclaration.definerOf(call) ?: return

        org.elixir_lang.psi.CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                ?.let { (name, arityInterval) ->
                    addClausesToCallDefinition(call, definer, name, arityInterval)
                }
    }

    private fun addClausesToCallDefinition(
            call: Call,
            definer: org.elixir_lang.psi.CallableDeclaration.Definer,
            name: Name,
            arityInterval: ArityInterval
    ) {
        for (arity in arityInterval.closed()) {
            NameArity(name, arity).let { putNew(it) }.clause(call, definer)
        }
    }

    open fun addToTreeElementList(callDefinition: CallDefinition) {
        treeElementList.add(callDefinition)
    }

    /**
     * Generates a [CallDefinition] for the given `nameArity` if it does not exist.
     *
     *
     * The [CallDefinition] is
     *
     * @param nameArity
     * @return pre-existing [CallDefinition] or new [CallDefinition] add to the `List<TreeElement>`
     */
    override fun putNew(nameArity: NameArity): CallDefinition =
    // Don't use `computeIfAbsent` because `addToTreeElementList` needs to be called only when absent, but after
            // `put`, which would be after `computeIfAbsent` computer returned.
            get(nameArity) ?: CallDefinition(
                    modular,
                    time,
                    nameArity.name,
                    nameArity.arity
            ).also {
                put(nameArity, it)
                addToTreeElementList(it)
            }
}

private fun CallDefinition.also(block: (CallDefinition) -> Unit): CallDefinition {
    block(this)
    return this
}
