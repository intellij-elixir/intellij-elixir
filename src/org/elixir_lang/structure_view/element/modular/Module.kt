package org.elixir_lang.structure_view.element.modular

import org.elixir_lang.psi.CallableDeclaration
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.impl.locationString
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.structure_view.ChildCall
import org.elixir_lang.structure_view.element.*
import org.elixir_lang.structure_view.element.call_definition_by_name_arity.FunctionByNameArity
import org.elixir_lang.structure_view.element.call_definition_by_name_arity.MacroByNameArity
import org.elixir_lang.structure_view.node_provider.Used
import org.jetbrains.annotations.Contract
import java.util.*

/**
 *
 * @param parent the parent [Module] or [org.elixir_lang.structure_view.element.Quote] that scopes
 * `call`.
 * @param call the `Kernel.defmodule/2` call nested in `parent`.
 */
open class Module(protected val parent: Modular?, call: Call) : Element<Call>(call), Modular {
    constructor(call: Call) : this(null, call)

    override fun getChildren(): Array<TreeElement> = callChildren(this, navigationItem)

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation {
        return org.elixir_lang.navigation.item_presentation.modular.Module(location(), navigationItem)
    }

    protected fun location(): String? =
            parent?.presentation.let { it as? Parent }?.locatedPresentableText ?: navigationItem.locationString()

    companion object {
        @RequiresReadLock
        fun addClausesToCallDefinition(
                call: Call,
                name: String,
                arityInterval: ArityInterval,
                callDefinitionByNameArity: MutableMap<NameArity, CallDefinition>,
                modular: Modular,
                definer: CallableDeclaration.Definer,
                callDefinitionInserter: (CallDefinition) -> Unit
        ) {
            val time = org.elixir_lang.structure_view.element.CallDefinitionClause.time(definer)

            for (arity in arityInterval.closed()) {
                ProgressManager.checkCanceled()
                val nameArity = NameArity(name, arity)

                callDefinitionByNameArity.computeIfAbsent(nameArity) { (name, arity) ->
                    CallDefinition(
                        modular,
                        time,
                        name,
                        arity
                    ).also {
                        callDefinitionInserter(it)
                    }
                }.clause(call, definer)
            }
        }

        @RequiresReadLock
        fun callChildren(modular: Modular, call: Call): Array<TreeElement> {
            val childCalls = CallDefinitionClause.modularChildCalls(call).toTypedArray()
            return childCallTreeElements(modular, childCalls, ResolveState.initial().put(ENTRANCE, call).putInitialVisitedElement(call))
        }

        @RequiresReadLock
        @JvmStatic
        fun elementDescription(call: Call, location: ElementDescriptionLocation): String? =
                when(location) {
                    UsageViewLongNameLocation.INSTANCE -> {
                        val enclosingCall = CallDefinitionClause.enclosingModularMacroCall(call)
                        // indirect recursion through ElementDescriptionUtil.getElementDescription because it is @NotNull and will
                        // default to element text when not implemented, so a bug, but not an error will result.
                        val relative = ElementDescriptionUtil.getElementDescription(call, UsageViewShortNameLocation.INSTANCE)

                        if (enclosingCall != null) {
                            val qualified = ElementDescriptionUtil.getElementDescription(enclosingCall, location)
                            "$qualified.$relative"
                        } else {
                            relative
                        }
                    }
                    UsageViewShortNameLocation.INSTANCE -> call.name
                    UsageViewTypeLocation.INSTANCE -> "module"
                    else -> null
                }


        @JvmStatic
        fun nameIdentifier(call: Call): PsiElement? = call.primaryArguments()?.firstOrNull()?.stripAccessExpression()

        @Contract(pure = true)
        private fun childCallTreeElements(modular: Modular, childCalls: Array<Call>?, resolveState: ResolveState): Array<TreeElement> {
            var treeElements: Array<TreeElement>? = null

            if (childCalls != null) {
                val childCallQueue = ArrayDeque(Arrays.asList(*childCalls))
                val length = childCalls.size
                val treeElementList = ArrayList<TreeElement>(length)
                val functionByNameArity = FunctionByNameArity(length, treeElementList, modular)
                val macroByNameArity = MacroByNameArity(length, treeElementList, modular)
                val overridableSet = HashSet<Overridable>()
                val useSet = HashSet<org.elixir_lang.structure_view.element.Use>()

                val accumulator = ChildCall.Accumulator(
                        modular,
                        treeElementList,
                        functionByNameArity,
                        macroByNameArity,
                        overridableSet,
                        useSet,
                        childCallQueue
                )

                while (!childCallQueue.isEmpty()) {
                    ChildCall.build(accumulator, childCallQueue.remove(), resolveState)
                }

                for (overridable in overridableSet) {
                    for (treeElement in overridable.children) {
                        (treeElement as CallReference).let { callReference ->
                            callReference.arity()?.let { arity ->
                                callReference
                                        .name()
                                        .let { name -> NameArity(name, arity) }
                                        .let { nameArity ->
                                            // `defoverridable` names a macro as well as a function
                                            (functionByNameArity[nameArity] ?: macroByNameArity[nameArity])
                                                ?.apply { isOverridable = true }
                                        }
                            }
                        }
                    }
                }

                val useCollection = HashSet<TreeElement>(useSet.size)
                useCollection.addAll(useSet)
                val nodesFromUses = Used.provideNodesFromChildren(useCollection)
                val moduleDefinitionByKey = Used.definitionByKey(treeElementList)

                for ((key, useDefinition) in Used.definitionByKey(nodesFromUses)) {
                    if (useDefinition.isOverridable) {
                        moduleDefinitionByKey[key]?.override = true
                    }
                }

                treeElements = treeElementList.toTypedArray()
            }

            return treeElements ?: emptyArray()
        }

    }

}

private inline fun CallDefinition.also(block: (CallDefinition) -> Unit): CallDefinition {
    block(this)
    return this
}
