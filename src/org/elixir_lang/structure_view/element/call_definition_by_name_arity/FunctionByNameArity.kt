package org.elixir_lang.structure_view.element.call_definition_by_name_arity


import com.intellij.ide.util.treeView.smartTree.TreeElement
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.*
import org.elixir_lang.structure_view.element.Exception
import org.elixir_lang.structure_view.element.modular.Modular

import com.intellij.openapi.util.Pair.pair
import org.elixir_lang.NameArity

class FunctionByNameArity(size: Int, treeElementList: MutableList<TreeElement>, modular: Modular) :
        TreeElementList(size, treeElementList, modular, Timed.Time.RUN) {
    var exception: Exception? = null
        set(exception) {
            field = exception!!
            treeElementList.add(exception)
        }

    fun addDelegationToTreeElementList(delegationCall: Call) =
            addToTreeElementList(Delegation(modular, delegationCall))

    /** The function [eexFunctionFrom] declares is one entry with its clauses and `@spec`s, when its name is literal. */
    fun addEExFunctionFromToCallDefinition(eexFunctionFrom: EExFunctionFrom) {
        val nameArity = eexFunctionFrom.nameArity()

        if (nameArity != null) {
            putNew(nameArity).eexFunctionFrom(eexFunctionFrom)
        } else {
            treeElementList.add(eexFunctionFrom)
        }
    }

    fun addSpecificationToCallDefinition(moduleAttributeDefinition: Call) {
        assert(moduleAttributeDefinition is AtUnqualifiedNoParenthesesCall<*>)

        CallDefinitionSpecification.moduleAttributeNameArity(moduleAttributeDefinition)?.let { nameArity ->
            putNew(nameArity).specification(moduleAttributeDefinition as AtUnqualifiedNoParenthesesCall<*>)
        }
    }

    override fun addToTreeElementList(callDefinition: CallDefinition) {
        val exception = this.exception

        if (exception != null && org.elixir_lang.psi.Exception.isCallback(pair(callDefinition.name(), callDefinition.arity))) {
            exception.callback(callDefinition)
        } else {
            super.addToTreeElementList(callDefinition)
        }
    }

    private fun addHeadToCallDefinition(call: Call): CallDefinition {
        val name = call.functionName()!!
        val arity = call.resolvedFinalArity()
        val nameArity = NameArity(name, arity)

        return putNew(nameArity).apply {
            head(call)
        }
    }

    private fun addToTreeElementList(delegation: Delegation) {
        for (head in delegation.callDefinitionHeadCallList()) {
            val callDefinition = addHeadToCallDefinition(head)
            delegation.definition(callDefinition)
        }

        treeElementList.add(delegation)
    }
}
