package org.elixir_lang.model.psi.atom

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.Call
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
    private val rangeInElement: TextRange = contentTextRange(atom),
    private val arity: Int
) : PsiReferenceBase<ElixirAtom>(atom, contentTextRange(atom)), PsiPolyVariantReference, PsiSymbolReference {
    private val functionName: String?
        get() = myElement.name

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
            .map { visitedResult -> visitedResult.element }
            .flatMap { element ->
                when (element) {
                    is Call -> sourceCallSymbols(element, name)
                    is BeamCallDefinition -> AtomSymbol.fromBeamCallDefinition(element)
                    else -> emptyList()
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
                .flatMap { visitedResult ->
                    when (val element = visitedResult.element) {
                        is Call -> sourceCallResolveResults(element, name, visitedResult.isValidResult)
                        is BeamCallDefinition -> AtomSymbol.fromBeamCallDefinition(element).map {
                            PsiElementResolveResult(element, visitedResult.isValidResult)
                        }
                        else -> emptyList()
                    }
                }
                .distinctBy { it.element?.containingFile?.virtualFile to it.element?.textRange }
                .toTypedArray()
        }
    }
}

@RequiresReadLock
private fun sourceCallSymbols(call: Call, name: String): List<AtomSymbol> {
    if (!CallDefinitionClause.`is`(call)) return emptyList()
    val nameArity = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return emptyList()
    if (nameArity.name != name) return emptyList()
    if (CallDefinitionClause.isMacro(call)) return emptyList()
    return AtomSymbol.fromClause(call)
}

@RequiresReadLock
private fun sourceCallResolveResults(call: Call, name: String, validResult: Boolean): List<ResolveResult> {
    if (!CallDefinitionClause.`is`(call)) return emptyList()
    val nameArity = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return emptyList()
    if (nameArity.name != name) return emptyList()
    if (CallDefinitionClause.isMacro(call)) return emptyList()
    return listOf(PsiElementResolveResult(call, validResult))
}

internal fun contentTextRange(atom: ElixirAtom): TextRange {
    val atomNode = atom.node
    val lastChildNode = atomNode.lastChildNode ?: return TextRange(0, atom.textLength)
    val start = lastChildNode.startOffset - atomNode.startOffset
    val end = start + lastChildNode.textLength

    return TextRange(start, end)
}
