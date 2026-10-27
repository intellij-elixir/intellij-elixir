package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementRenderer
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
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.model.psi.ElixirUsageQueries
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

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

private fun lookupElement(visible: Visible, appendParentheses: Boolean): LookupElement? {
    val name = visible.lookupName
    val form = when (val declared = visible.declaration?.declared) {
        is Declared.Compiled -> Form.CLAUSE
        is Declared.Source -> declared.form
        null -> (visible.element as? Call)?.let(DeclaringForm::syntacticForm)
    } ?: return null
    val renderer = renderer(form, visible) ?: return null
    val builder = LookupElementBuilder.createWithSmartPointer(name, visible.element).withRenderer(renderer).let {
        if (form == Form.EXCEPTION) it else it.withLookupStrings(ElixirUsageQueries.lookupStrings(name, visible.element))
    }

    return if (appendParentheses) builder.withInsertHandler(CallDefinitionClauseInsertHandler.at(arity(visible))) else builder
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
