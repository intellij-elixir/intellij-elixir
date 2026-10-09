package org.elixir_lang.code_insight.completion.insert_handler

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ResolveState
import org.elixir_lang.Arity
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.Callback

/**
 * Inserts a call-definition-clause completion's target as `name(a, b)`, with each parameter a live
 * template placeholder (selectable, tabbable), instead of an empty `name()` - which creates a call at
 * an arity nothing defines, so every code-intelligence feature that resolves the call goes dark. A
 * target with no parameters still gets a bare `()`.
 *
 * Attached at nine sites: the two remote/BEAM qualified and `defdelegate` ones through [QualifiedName], and
 * the seven local/unqualified ones in [org.elixir_lang.psi.scope.call_definition_clause.Variants]. Each site's
 * target PSI shape differs, so [parameters] dispatches on it independently of whatever produced the
 * [LookupElement].
 */
class CallDefinitionClause private constructor(private val arity: Arity?) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val tailOffset = context.tailOffset
        val document = context.document
        val documentTextLength = document.textLength

        val insertParentheses = if (documentTextLength > tailOffset) {
            val firstChar = document.getText(TextRange(tailOffset, tailOffset + 1))[0]
            firstChar != ' ' && firstChar != '(' && firstChar != '['
        } else {
            true
        }

        if (insertParentheses) {
            val parameters = parameters(item).orEmpty()

            if (parameters.isEmpty()) {
                document.insertString(tailOffset, "()")
                context.editor.caretModel.moveToOffset(tailOffset + 1)
            } else {
                insertParameterTemplate(context, tailOffset, parameters)
            }

            /* The caret now sits where the first argument goes (or, with a template, the platform has
               already put it at the first placeholder), but nothing has asked for the parameter hint:
               an open lookup consumes the keystroke that accepted the completion, so the platform's
               typed handler - which asks on every `(` and `,` - never runs. Ask here, as the completion
               that inserted the parentheses. */
            AutoPopupController
                .getInstance(context.project)
                .autoPopupParameterInfo(context.editor, null)
        }
    }

    private fun insertParameterTemplate(context: InsertionContext, offset: Int, parameters: List<String>) {
        val template: Template = TemplateManager.getInstance(context.project).createTemplate("", "")
        template.isToReformat = false

        /* Each variable's name (not just its default text) is what the live-template engine keys a
           tab stop's value on: two variables sharing a name are mirrored, not independent - typing
           into one silently overwrites the other on the next recalculation. Elixir parameters collide
           on name legitimately and often (`def area(_, _)`), so the tab stop's identity (`p0`, `p1`,
           ...) is kept distinct from its displayed default (the real parameter name). */
        template.addTextSegment("(")
        parameters.forEachIndexed { index, name ->
            if (index != 0) template.addTextSegment(", ")
            template.addVariable("p$index", TextExpression(name), true)
        }
        template.addTextSegment(")")

        context.editor.caretModel.moveToOffset(offset)
        TemplateManager.getInstance(context.project).startTemplate(context.editor, template)
    }

    /**
     * `null` when nothing here knows this candidate's parameters; the caller then inserts a bare `()`,
     * same as a target already known to take none.
     */
    private fun parameters(item: LookupElement): List<String>? =
        when (val psiElement = item.psiElement) {
            is BeamCallDefinition -> Signature.parametersAt(psiElement)
            is Call -> callParameters(psiElement, item.lookupString)
            else -> null
        }

    private fun callParameters(call: Call, name: String): List<String>? =
        when (val form = DeclaringForm.syntacticForm(call) ?: DeclaringForm.resolvingForm(call, ResolveState.initial())) {
            Form.CALLBACK -> callbackParameters(call)
            null -> null
            else -> Declarations.named(form, call, name)?.let { Signature.of(it, call) }?.parameters?.let(::atArity)
        }

    /**
     * The head's parameters at this handler's arity, each without its `name \\ default` default: that text inserted
     * verbatim at a call site is a syntax error, as `\\` is only legal in a definition head.
     */
    private fun atArity(parameters: List<String>): List<String> {
        val target = arity ?: parameters.size

        return ParameterText.covered(parameters, target) ?: ParameterText.reaching(parameters, target)
    }

    /**
     * The `@callback`/`@macrocallback` spec head's own arguments, stripped of a `name :: type`
     * annotation down to `name` - the developer is about to write a value where the spec wrote a type.
     */
    private fun callbackParameters(call: Call): List<String>? =
        (call as? AtUnqualifiedNoParenthesesCall<*>)
            ?.let { Callback.headCall(it) }
            ?.finalArguments()
            ?.map { argument -> (argument as? Type)?.leftOperand()?.text ?: argument.text }

    companion object {
        val WHOLE_HEAD = CallDefinitionClause(null)

        fun at(arity: Arity?): CallDefinitionClause = arity?.let(::CallDefinitionClause) ?: WHOLE_HEAD
    }
}
