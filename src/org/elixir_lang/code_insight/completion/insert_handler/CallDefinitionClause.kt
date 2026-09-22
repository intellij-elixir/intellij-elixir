package org.elixir_lang.code_insight.completion.insert_handler

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause as CallDefinitionClausePsi
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.Exception as ElixirException
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.InMatch
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * Inserts a call-definition-clause completion's target as `name(a, b)`, with each parameter a live
 * template placeholder (selectable, tabbable), instead of an empty `name()` - which creates a call at
 * an arity nothing defines, so every code-intelligence feature that resolves the call goes dark. A
 * target with no parameters still gets a bare `()`.
 *
 * Attached at nine sites: the remote/BEAM qualified and `defdelegate` ones in
 * [org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements] and
 * [org.elixir_lang.code_insight.lookup.element.CallDefinitionClause], and the seven local/unqualified
 * ones in [org.elixir_lang.psi.scope.call_definition_clause.Variants]. Each site's target PSI shape
 * differs, so [parameters] dispatches on it independently of whatever produced the [LookupElement].
 */
object CallDefinitionClause : InsertHandler<LookupElement> {
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
            is BeamCallDefinition -> Signature.of(psiElement).parameters
            is Call -> callParameters(psiElement, item.lookupString)
            else -> null
        }

    /** Exhaustive over [CallableDeclaration.Form], so a new form does not compile until it says what it inserts. */
    private fun callParameters(call: Call, lookupString: String): List<String>? =
        when (CallableDeclaration.formOf(call, ResolveState.initial())) {
            CallableDeclaration.Form.CLAUSE -> callDefinitionClauseParameters(call)
            CallableDeclaration.Form.CALLBACK -> callbackParameters(call)
            CallableDeclaration.Form.DELEGATION -> delegationParameters(call)
            CallableDeclaration.Form.EXCEPTION -> exceptionParameters(lookupString)
            CallableDeclaration.Form.EEX_FUNCTION_FROM -> eexFunctionFromParameters(call)
            CallableDeclaration.Form.GENERATOR_EMBED -> embedParameters(call)
            null -> null
        }

    /**
     * The clause's own head, stripped of a `name \\ default` default-value operation down to `name` -
     * [Signature.of] keeps the full `name \\ default` text unchanged (it also backs the Parameter Info
     * hint and the lookup tail text, where showing the default is useful), but inserting that text
     * verbatim at a call site is a syntax error: `\\` is only legal in a definition head.
     */
    private fun callDefinitionClauseParameters(call: Call): List<String>? =
        CallDefinitionClausePsi
            .head(call)
            ?.let { CallDefinitionHead.strip(it) }
            ?.let { it as? Call }
            ?.finalArguments()
            ?.map(::stripDefaultValue)

    /**
     * The `@callback`/`@macrocallback` spec head's own arguments, stripped of a `name :: type`
     * annotation down to `name` - the developer is about to write a value where the spec wrote a type.
     */
    private fun callbackParameters(call: Call): List<String>? =
        (call as? AtUnqualifiedNoParenthesesCall<*>)
            ?.let { Callback.headCall(it) }
            ?.finalArguments()
            ?.map { argument -> (argument as? Type)?.leftOperand()?.text ?: argument.text }

    /**
     * The delegate's own head, e.g. `values(map)` in `defdelegate values(map), to: Mod` - the same head
     * [org.elixir_lang.code_insight.lookup.element_renderer.Delegation] renders as tail text, stripped
     * of a default value for the same reason as [callDefinitionClauseParameters].
     */
    private fun delegationParameters(call: Call): List<String> =
        CallableDeclaration
            .delegationHead(call)
            ?.let { it as? Call }
            ?.finalArguments()
            ?.map(::stripDefaultValue)
            ?: emptyList()

    /** `name \\ default` (an [InMatch] operation) down to `name`; any other argument, its own text. */
    private fun stripDefaultValue(argument: PsiElement): String =
        (argument as? InMatch)?.leftOperand()?.text ?: argument.text

    /**
     * Fixed by the `Exception` behaviour's own two callback shapes, same as the completion renderer's
     * tail text - the underlying `defexception` call is shared by both hooks, so only the lookup string
     * (the name actually being inserted) tells them apart.
     */
    private fun exceptionParameters(lookupString: String): List<String> =
        when (lookupString) {
            ElixirException.EXCEPTION.name -> listOf(ElixirException.MESSAGE.name)
            ElixirException.MESSAGE.name -> listOf(ElixirException.EXCEPTION.name)
            else -> emptyList()
        }

    /**
     * The macro's own `[:a, :b]` argument-name list - `EEx.function_from_file`/`function_from_string`
     * generates a function with exactly these names, not derivable from any PSI head. Defaults to `[]`,
     * matching [org.elixir_lang.structure_view.element.EExFunctionFrom]'s own arity computation.
     */
    private fun eexFunctionFromParameters(call: Call): List<String> =
        call.finalArguments()?.let { arguments ->
            if (arguments.size >= 4) {
                // One placeholder per element: the list's length is the arity, even where a name is unknown.
                (arguments[3].stripAccessExpression() as? ElixirList)
                    ?.children
                    ?.mapIndexed { index, child ->
                        (child.stripAccessExpression() as? ElixirAtom)?.literalName() ?: "arg${index + 1}"
                    }
            } else {
                emptyList()
            }
        } ?: emptyList()

    /**
     * Fixed by `Mix.Generator`'s own two macros - `embed_template` defines a function of `assigns`,
     * `embed_text` of nothing, same as the completion renderer's tail text.
     */
    private fun embedParameters(call: Call): List<String> =
        when (call.functionName()?.removePrefix("embed_")) {
            "template" -> listOf("assigns")
            else -> emptyList()
        }
}
