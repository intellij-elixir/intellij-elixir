package org.elixir_lang.model.psi.atom

import org.elixir_lang.psi.impl.nameRangeInAtom
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.DelegationPrecedence
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve as CallDefinitionClauseMultiResolve
import org.elixir_lang.reference.Resolver as ReferenceResolver

@Suppress("UnstableApiUsage")
class AtomReference(
    atom: ElixirAtom,
    /**
     * The module element from the MFA tuple or apply/3 - either:
     * - a [org.elixir_lang.psi.QualifiableAlias] for Elixir-style modules (`Enum`, `MyApp.Worker`)
     * - an [ElixirAtom] (unquoted) for Erlang-style modules (`:math`, `:lists`)
     */
    private val moduleElement: PsiElement,
    private val rangeInElement: TextRange = atom.nameRangeInAtom(),
    private val arity: Int
) : PsiReferenceBase<ElixirAtom>(atom, atom.nameRangeInAtom()), PsiPolyVariantReference, PsiSymbolReference {
    private val functionName: String?
        get() = myElement.literalName()

    override fun getVariants(): Array<Any> {
        val modulars = moduleElement.maybeModularNameToModulars(
            maxScope = myElement.containingFile,
            useCall = null,
            incompleteCode = true
        )

        return callDefinitionClauseLookupElements(modulars, appendParentheses = false).toTypedArray()
    }

    override fun getAbsoluteRange(): TextRange =
        rangeInElement.shiftRight(myElement.textRange.startOffset)

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return ResolveCache
            .getInstance(myElement.project)
            .resolveWithCaching(this, Resolver, false, incompleteCode)
    }

    override fun resolve(): PsiElement? =
        ReferenceResolver.preferred(myElement, false, multiResolve(false).toList())
            .firstOrNull()
            ?.element

    override fun isSoft(): Boolean = true

    /**
     * The functions the atom names. A `defdelegate` is what an MFA naming it names, though its `to:` is reached too: the
     * delegation is renamed and searched, and Go To from it follows `to:`, as from a call.
     */
    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        val name = functionName ?: return emptyList()
        val modulars = moduleElement.maybeModularNameToModulars(
            maxScope = myElement.containingFile,
            useCall = null,
            incompleteCode = false
        )

        if (modulars.isEmpty()) return emptyList()

        return modulars
            .flatMap { modular ->
                CallDefinitionClauseMultiResolve.resolveResults(name, arity, false, modular)
            }
            .mapNotNull { visitedResult -> visitedResult.element.takeIf { reachable(it, name) } }
            .let { elements ->
                DelegationPrecedence.named(elements, arity, AtomSymbol::fromDeclaration) {
                    elements
                        .flatMap { element ->
                            when (element) {
                                is Call -> AtomSymbol.fromDeclaration(element)
                                is BeamCallDefinition -> AtomSymbol.fromBeamCallDefinition(element)
                                else -> emptyList()
                            }
                        }
                        // A definition covering several arities yields a symbol per arity; the MFA names one.
                        .filter { it.arity == arity }
                }
            }
            .distinct()
    }

    private object Resolver : ResolveCache.PolyVariantResolver<AtomReference> {
        override fun resolve(reference: AtomReference, incompleteCode: Boolean): Array<ResolveResult> {
            val name = reference.functionName ?: return ResolveResult.EMPTY_ARRAY
            val modulars = reference.moduleElement.maybeModularNameToModulars(
                maxScope = reference.myElement.containingFile,
                useCall = null,
                incompleteCode = false
            )

            if (modulars.isEmpty()) return ResolveResult.EMPTY_ARRAY

            return modulars
                .flatMap { modular ->
                    CallDefinitionClauseMultiResolve.resolveResults(name, reference.arity, incompleteCode, modular)
                }
                .filter { visitedResult -> reachable(visitedResult.element, name) }
                .let { results -> DelegationPrecedence.navigated(results) { it.element } }
                .map { visitedResult -> PsiElementResolveResult(visitedResult.element, visitedResult.isValidResult) }
                // A compiled function's arities share one decompiled head: keep the arity called, whichever came first.
                .let { results ->
                    ReferenceResolver.onePerKeyPreferringValid(results) {
                        it.element.containingFile?.virtualFile to it.element.textRange
                    }
                }
                .toTypedArray()
        }
    }
}


/**
 * Whether an MFA tuple or `apply/3` naming [name] reaches [element]: a remotely callable function of that name,
 * whichever form - source or compiled - defines it.
 */
@RequiresReadLock
private fun reachable(element: PsiElement, name: String): Boolean {
    val state = ResolveState.initial()
    val declared = CallableDeclaration.declaredOf(element, state) ?: return false

    return declared.capabilities?.remoteCallable == true && declared.definitions(state).any { it.name == name }
}
