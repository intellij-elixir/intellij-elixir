package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.code_insight.completion.insert_handler.CallDefinitionClause as CallDefinitionClauseInsertHandler
import org.elixir_lang.psi.scope.Reach
import org.elixir_lang.psi.scope.call_definition_clause.Variants
import org.elixir_lang.code_insight.lookup.element.CallDefinitionClause as CallDefinitionClauseLookupElement
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause as CallDefinitionClauseRenderer

/**
 * The function-name [LookupElement]s a modular ([scope]) offers when completing a **remote**
 * reference: one entry per name it [Reach.exports], preferring bare function heads. Handles both
 * source modules ([Call]) and BEAM-decompiled modules ([BeamModule]); any other element type yields
 * nothing.
 *
 * @param appendParentheses when `true` (qualified `Mod.<caret>` call completion) the inserted name is
 *   followed by `()`; when `false` (an MFA atom or a capture) only the bare name is inserted, because
 *   it names a function rather than calling one, so only functions are offered.
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

/**
 * One item per name the module exports: a `def`'s over another form's, its bodiless head over its clauses. The walk is of
 * the completion copy, where the caret's dummy identifier keeps the declarations after it apart; each item then points
 * into the user's file.
 */
private fun callDefinitionClauseLookupElements(scope: Call, appendParentheses: Boolean): Iterable<LookupElement> =
    Variants.remoteLookupElementList(scope, appendParentheses)
        .groupBy { it.lookupString }
        .map { (_, sameName) -> sameName.minBy(::precedence) }
        .map { lookupElement ->
            val builder = lookupElement as? LookupElementBuilder
            val element = builder?.psiElement

            if (builder != null && element != null) {
                builder.withPsiElement(element.inOriginalFile()).also { builder.copyUserDataTo(it) }
            } else {
                lookupElement
            }
        }

private fun precedence(lookupElement: LookupElement): Int =
    when {
        lookupElement.getUserData(CallDefinitionClauseInsertHandler.FORM) != CallableDeclaration.Form.CLAUSE -> 2
        (lookupElement.psiElement as? Call)?.hasDoBlockOrKeyword() == false -> 0
        else -> 1
    }

private fun callDefinitionClauseLookupElements(moduleImpl: BeamModule, appendParentheses: Boolean): Iterable<LookupElement> =
    moduleImpl.callDefinitions()
        .filter { Reach.exports(Reach.OWN, it, runtime = !appendParentheses) }
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
