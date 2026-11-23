package org.elixir_lang.psi.__module__

import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.psi.stub.index.AllName

object Resolver : ResolveCache.PolyVariantResolver<Reference> {
    override fun resolve(reference: Reference, incompleteCode: Boolean): Array<ResolveResult> =
        resolve(reference.element, reference.useCall)

    @RequiresReadLock
    private fun resolve(call: Call, useCall: Call?): Array<ResolveResult> {
        currentModularAt(call)?.let { return arrayOf(PsiElementResolveResult(it)) }

        return when {
            enclosingQuote(call) == null -> ResolveResult.EMPTY_ARRAY
            useCall != null -> Use.modulars(useCall).map(::PsiElementResolveResult).toTypedArray()
            else -> specialForms(call)
        }
    }

    /** `Kernel.SpecialForms.__MODULE__/0`, in source and in `.beam`: a `quote` body's module is its caller's. */
    @RequiresReadLock
    private fun specialForms(call: Call): Array<ResolveResult> {
        val project = call.project
        val state = ResolveState.initial().put(ENTRANCE, call).putInitialVisitedElement(call)
        val resolveResultList = mutableListOf<ResolveResult>()

        StubIndex
            .getInstance()
            .processElements(
                AllName.KEY,
                "__MODULE__",
                project,
                GlobalSearchScope.allScope(project),
                NamedElement::class.java
            ) { namedElement ->
                if (namedElement is BeamCallDefinition) {
                    namedElement.parent.let { module ->
                        if (module.name == KERNEL_SPECIAL_FORMS && namedElement.exportedArity(state) == 0) {
                            resolveResultList.add(PsiElementResolveResult(namedElement))
                        }
                    }
                } else if (namedElement is Call) {
                    if (CallDefinitionClause.`is`(namedElement) && CallDefinitionClause.nameArityInterval(
                            namedElement,
                            state
                        )?.arityInterval?.contains(0) == true
                    ) {
                        enclosingModularMacroCall(namedElement)?.let { modularCall ->
                            if (Module.`is`(modularCall) && Module.name(modularCall) == KERNEL_SPECIAL_FORMS) {
                                resolveResultList.add(PsiElementResolveResult(namedElement))
                            }
                        }
                    }
                }

                true
            }

        return resolveResultList.toTypedArray()
    }
}
