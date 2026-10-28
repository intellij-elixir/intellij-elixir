package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.NameArity
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code_insight.completion.insert_handler.CallDefinitionClause as CallDefinitionClauseInsertHandler
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause as CallDefinitionClauseRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.Callback as CallbackRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.Delegation as DelegationRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.EExFunctionFrom as EExFunctionFromRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.exception.CallDefinitionClause as ExceptionRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedTemplate as EmbedTemplateRenderer
import org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedText as EmbedTextRenderer
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.admits
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.model.psi.ElixirUsageQueries
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/**
 * What unqualified completion offers at [position], from what is visible there. [capturedArity] is the `/arity` of a
 * capture being completed: it keeps the names a capture of that arity may name, inserted without parentheses.
 */
@RequiresReadLock
fun unqualifiedLookupElements(position: Call, capturedArity: Int? = null): List<LookupElement> =
    unqualifiedLookupElements(
        sourceFor(Feature.COMPLETION).visible(position).filter { capturedArity == null || it.capturable(capturedArity) },
        appendParentheses = capturedArity == null
    )

/** Whether `&name/[arity]` may name this: an arity it brings in, else one its declaration admits. */
private fun Visible.capturable(arity: Int): Boolean =
    importedArities?.let { arity in it } ?: declaration?.arity?.admits(arity) ?: true

/**
 * What unqualified completion offers from [visible]: one lookup for each name Elixir can write unquoted and the element
 * that declares it, from that pair's first entry. [appendParentheses] is `false` where the name is not a call, as in a
 * capture.
 */
@RequiresReadLock
fun unqualifiedLookupElements(visible: List<Visible>, appendParentheses: Boolean): List<LookupElement> {
    ThreadingAssertions.assertReadAccess()

    return visible
        .filter { callableUnqualified(it.lookupName) }
        .distinctBy { it.lookupName to it.element }
        .mapNotNull { lookupElement(it, appendParentheses) }
}

/** A name Elixir can write only quoted is not offered unqualified. */
fun callableUnqualified(name: String): Boolean = InspectAtom.classify(name) != InspectAtom.Class.OTHER

private fun lookupElement(visible: Visible, appendParentheses: Boolean): LookupElement? =
    builder(visible, visible.element)?.let {
        if (appendParentheses) it.withInsertHandler(CallDefinitionClauseInsertHandler.at(arity(visible))) else it
    }

/**
 * What completion offers at [position] after a qualifier, from what is visible there: one lookup for each entry,
 * including a name Elixir can write only quoted, written by [insertHandler]. The lookup points into the file being
 * edited, not into completion's copy of it.
 */
@RequiresReadLock
fun qualifiedLookupElements(position: CompletionPosition, insertHandler: QualifiedName): List<LookupElement> {
    ThreadingAssertions.assertReadAccess()

    return sourceFor(Feature.COMPLETION).visible(position).mapNotNull { visible ->
        builder(visible, visible.element.inOriginalFile())?.withInsertHandler(insertHandler)
    }
}

private fun builder(visible: Visible, element: PsiElement): LookupElementBuilder? {
    val name = visible.lookupName
    val form = when (val declared = visible.declaration?.declared) {
        is Declared.Compiled -> Form.CLAUSE
        is Declared.Source -> declared.form
        null -> (visible.element as? Call)?.let(DeclaringForm::syntacticForm)
    } ?: return null
    val renderer = renderer(form, visible) ?: return null

    return LookupElementBuilder.createWithSmartPointer(name, element).withRenderer(renderer).let {
        if (form == Form.EXCEPTION) it else it.withLookupStrings(ElixirUsageQueries.lookupStrings(name, element))
    }
}

private fun renderer(form: Form, visible: Visible): LookupElementRenderer<LookupElement>? {
    val name = visible.lookupName

    return when (form) {
        Form.CLAUSE -> CallDefinitionClauseRenderer(name)
        Form.CALLBACK -> CallbackRenderer(name)
        Form.DELEGATION -> DelegationRenderer(name)
        Form.EEX_FUNCTION_FROM -> EExFunctionFromRenderer(name)
        Form.EXCEPTION -> (visible.declaration?.arity as? ArityKnowledge.Exact)?.let { ExceptionRenderer(NameArity(name, it.arity)) }
        // `Generator.isEmbed` admits only these two names.
        Form.GENERATOR_EMBED -> when ((visible.element as Call).functionName()?.removePrefix("embed_")) {
            "template" -> EmbedTemplateRenderer(name)
            "text" -> EmbedTextRenderer(name)
            else -> null
        }
    }
}

/** The highest arity the `import` brings in, else the declared maximum; `null` where the arities are not closed. */
private fun arity(visible: Visible): Arity? =
    visible.importedArities?.maxOrNull()
        ?: when (val arity = visible.declaration?.arity) {
            is ArityKnowledge.Exact -> arity.arity
            is ArityKnowledge.Range -> arity.maximum
            is ArityKnowledge.Open, ArityKnowledge.Unknown, null -> null
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
