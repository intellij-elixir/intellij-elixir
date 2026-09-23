package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.code_insight.preferFunctionHeads
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCalls
import org.elixir_lang.code_insight.lookup.element.CallDefinitionClause as CallDefinitionClauseLookupElement
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause as CallDefinitionClauseRenderer
import com.intellij.psi.ResolveState
import org.elixir_lang.psi.CallDefinitionClause as CallDefinitionClausePsi
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.code_insight.lookup.element_renderer.Delegation as DelegationRenderer
import org.elixir_lang.code_insight.completion.insert_handler.CallDefinitionClause as CallDefinitionClauseInsertHandler

/**
 * The function-name [LookupElement]s a modular ([scope]) offers when completing a **remote**
 * reference: one entry per public function name, preferring bare function heads. Only public
 * (exported) functions are offered because a remote / MFA dispatch (`Mod.fun(...)`,
 * `apply(Mod, :fun, args)`, `{Mod, :fun, arity}`) can never reach a private function - a private
 * function is callable only through a local unqualified call inside its own module. Handles both
 * source modules ([Call]) and BEAM-decompiled modules ([BeamModule]); any other element type yields
 * nothing.
 *
 * @param appendParentheses when `true` (qualified `Mod.<caret>` call completion) the inserted name is
 *   followed by `()`; when `false` (MFA atom `:<caret>` completion) only the bare name is inserted,
 *   because an atom is a name, not a call.
 *
 * Shared by qualified `Mod.<caret>` completion
 * ([org.elixir_lang.code_insight.completion.provider.CallDefinitionClause]) and MFA atom completion
 * ([org.elixir_lang.model.psi.atom.AtomReference.getVariants]).
 */
fun callDefinitionClauseLookupElements(
    scope: PsiElement,
    appendParentheses: Boolean = true
): Iterable<LookupElement> = when (scope) {
    is Call -> callDefinitionClauseLookupElements(scope, appendParentheses)
    is BeamModule -> callDefinitionClauseLookupElements(scope, appendParentheses)
    else -> emptyList()
}

/**
 * The remote-completion [LookupElement]s offered by the set of [modulars] a modular name resolved to
 * (via [org.elixir_lang.psi.impl.maybeModularNameToModulars]). Source modules ([Call]) are preferred
 * over BEAM-decompiled stubs ([BeamModule]) so a module available in both forms is not offered twice.
 *
 * @see callDefinitionClauseLookupElements for the per-modular contract and [appendParentheses].
 */
fun callDefinitionClauseLookupElements(
    modulars: Collection<PsiElement>,
    appendParentheses: Boolean = true
): List<LookupElement> {
    val sourceModulars = modulars.filterIsInstance<Call>()
    val effectiveModulars = if (sourceModulars.isNotEmpty()) sourceModulars else modulars

    return effectiveModulars.flatMap { callDefinitionClauseLookupElements(it, appendParentheses) }
}

private fun callDefinitionClauseLookupElements(scope: Call, appendParentheses: Boolean): Iterable<LookupElement> {
    val childCalls = scope.macroChildCalls()

    val publicClauses = childCalls
        .filter { CallableDeclaration.isForm(it, CallableDeclaration.Form.CLAUSE) }
        .filter { CallDefinitionClausePsi.isPublic(it) }

    val clauseLookupElements = preferFunctionHeads(publicClauses).map { (name, bestClause) ->
        name to lookupElement(name, bestClause, appendParentheses)
    }
    val clauseNames = clauseLookupElements.map { (name, _) -> name }.toSet()

    return clauseLookupElements.map { (_, lookupElement) -> lookupElement } +
        delegationLookupElements(childCalls, clauseNames, appendParentheses)
}

/**
 * The [LookupElement]s for functions this module declares only with `defdelegate`.
 *
 * [CallableDeclaration.Form.DELEGATION] and [CallableDeclaration.Form.CLAUSE] are disjoint, so delegates need their own pass or they
 * are never offered. Names already in [clauseNames] are skipped so a `def` keeps its richer
 * presentation; visibility is not filtered because there is no `defdelegatep`.
 *
 * [appendParentheses] is threaded through so a delegate inserts `Mod.values()` and opens parameter
 * info like a `def`, and stays a bare name for an MFA atom, where a name is not a call.
 */
private fun delegationLookupElements(
    childCalls: Array<Call>,
    clauseNames: Set<String>,
    appendParentheses: Boolean
): List<LookupElement> =
    childCalls
        .filter { CallableDeclaration.isForm(it, CallableDeclaration.Form.DELEGATION) }
        .mapNotNull { delegation ->
            CallableDeclaration
                .declarations(delegation, CallableDeclaration.Form.DELEGATION, ResolveState.initial())
                .firstOrNull()
                ?.name
                ?.takeIf { it !in clauseNames }
                ?.let { name -> name to delegation }
        }
        .distinctBy { (name, _) -> name }
        .map { (name, delegation) ->
            LookupElementBuilder
                .createWithSmartPointer(name, delegation.inOriginalFile())
                .withRenderer(DelegationRenderer(name))
                .let { if (appendParentheses) it.withInsertHandler(CallDefinitionClauseInsertHandler) else it }
        }

private fun callDefinitionClauseLookupElements(moduleImpl: BeamModule, appendParentheses: Boolean): Iterable<LookupElement> =
    moduleImpl.callDefinitions()
        .filter { it.isExported }
        .mapNotNull { callDefinition ->
            // MaybeExported documents exportedName() as null only when isExported() is false.
            callDefinition.exportedName()?.let { lookupElement(it, callDefinition, appendParentheses) }
        }

private fun lookupElement(name: String, element: PsiElement, appendParentheses: Boolean): LookupElement =
    element.inOriginalFile().let { originalElement ->
        if (appendParentheses) {
            CallDefinitionClauseLookupElement.createWithSmartPointer(name, originalElement)
        } else {
            LookupElementBuilder
                .createWithSmartPointer(name, originalElement)
                .withRenderer(CallDefinitionClauseRenderer(name))
        }
    }

/**
 * [CompletionUtil.getOriginalOrSelf] hands back the copy rather than null when it cannot map, so a lookup
 * element built from its result silently pins the throwaway file. It fails two ways here: the declaration
 * enclosing the caret has a same-class node that runs past the translated end, and one the trailing dot
 * swallowed has no node of its own at all.
 */
private fun PsiElement.inOriginalFile(): PsiElement {
    val mapped = CompletionUtil.getOriginalOrSelf(this)
    val copyFile = mapped.containingFile ?: return mapped

    if (copyFile.isPhysical) return mapped

    val startOffset = mapped.textRange?.startOffset ?: return mapped
    val originalLeaf = copyFile.findElementAt(startOffset)
        ?.let(CompletionUtil::getOriginalOrSelf)
        ?.takeIf { it.containingFile?.isPhysical == true }
        ?: return mapped
    val originalOffset = originalLeaf.textRange.startOffset

    val sameClass = generateSequence(originalLeaf) { it.parent }
        .takeWhile { it !is PsiFile }
        .firstOrNull { mapped.javaClass.isInstance(it) && it.textRange?.startOffset == originalOffset }

    return sameClass ?: originalLeaf
}
