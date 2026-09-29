package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.Arity
import org.elixir_lang.Name
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import org.elixir_lang.structure_view.element.CallDefinitionSpecification.Companion.typeNameArity

object CallDefinitionClause : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.CallDefinitionClause> {
    override fun resolve(callDefinitionClause: org.elixir_lang.reference.CallDefinitionClause,
                         incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return enclosingModularMacroCall(callDefinitionClause.moduleAttribute)?.let(CallableDeclaration::definitionsIn)?.let { siblings ->
            if (siblings.isNotEmpty()) {
                val nameArity = typeNameArity(callDefinitionClause.element) ?: return emptyArray()
                val name = nameArity.name
                val arity = nameArity.arity

                siblings
                    .flatMap { (call, definitions) ->
                        definitions.mapNotNull { definitionToResolveResult(call, name, arity, it) }
                    }
                    .toTypedArray()
            } else {
                null
            }
        } ?: emptyArray()
    }

    private fun definitionToResolveResult(call: Call,
                                          name: Name,
                                          arity: Arity,
                                          definition: CallableDeclaration.Declaration): PsiElementResolveResult? =
        MultiResolve
            .reached(name, definition.name, definition.accepts(arity), incompleteCode = false)
            ?.let { validResult -> PsiElementResolveResult(call, validResult) }
}
