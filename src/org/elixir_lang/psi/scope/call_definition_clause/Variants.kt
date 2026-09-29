package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.code_insight.completion.insert_handler.CallDefinitionClause as CallDefinitionClauseInsertHandler
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.scope.CallDefinitionClause
import org.elixir_lang.structure_view.element.Callback

/**
 * [appendParentheses] is threaded through so a capture's `&name/arity` (which reuses this same walk,
 * see [org.elixir_lang.reference.CaptureNameArity]) stays a bare name - a capture names a function, it
 * does not call one.
 */
class Variants(private val appendParentheses: Boolean) : CallDefinitionClause() {
    private var lookupElementByPsiElementName: MutableMap<Pair<PsiElement, String>, LookupElement> = mutableMapOf()

    private val lookupElementCollection: Collection<LookupElement>
        get() = lookupElementByPsiElementName.values

    /**
     * Called on every [Call] where [org.elixir_lang.structure_view.element.CallDefinitionClause. is] is
     * `true` when checking tree with [.execute]
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean {
        val entranceCallDefinitionClause = state.get(ENTRANCE_CALL_DEFINITION_CLAUSE)

        if ((entranceCallDefinitionClause == null || !element.isEquivalentTo(entranceCallDefinitionClause)) && element is Named) {
            addCallDefinitionClauseToLookupElementByPsiElement(element)
        }

        return true
    }

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean {
        // BEAM-decompiled call definitions are never the entrance clause (which is always source),
        // so the entrance guard from executeOnCallDefinitionClause does not apply here.
        if (element.isExported()) {
            addCallDefinitionToLookupElementByPsiElement(element)
        }

        return true
    }

    private fun addCallDefinitionToLookupElementByPsiElement(element: BeamCallDefinition) {
        // MaybeExported documents exportedName() as null only when isExported() is false, which the
        // sole caller checks.
        val name = element.exportedName() ?: return

        lookupElementByPsiElementName.computeIfAbsent(element to name) { (el, n) ->
            LookupElementBuilder.createWithSmartPointer(n, el)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause(n))
                .withInsertHandlerIfAppendingParentheses()
        }
    }

    private fun addCallDefinitionClauseToLookupElementByPsiElement(named: Named) {
        named.name?.let { name ->
            lookupElementByPsiElementName.computeIfAbsent(named to name) { (element, name) ->
                LookupElementBuilder.createWithSmartPointer(
                        name,
                        element
                ).withRenderer(
                        org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause(name)
                ).withInsertHandlerIfAppendingParentheses()
            }
        }
    }

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        Callback.headCall(element)
                ?.let { it as? Named }
                ?.let { addCallbackToLookupElementByPsiElement(element, it) }

        return true
    }

    private fun addCallbackToLookupElementByPsiElement(element: AtUnqualifiedNoParenthesesCall<*>, head: Named) =
            head.name?.let { name ->
                lookupElementByPsiElementName.computeIfAbsent(head to name) { (_, name) ->
                    LookupElementBuilder.createWithSmartPointer(
                            name,
                            element
                    ).withRenderer(
                            org.elixir_lang.code_insight.lookup.element_renderer.Callback(name)
                    ).withInsertHandlerIfAppendingParentheses()
                }
            }

    override fun executeOnDelegation(element: Call, state: ResolveState): Boolean {
        addDeclarations(element, CallableDeclaration.Form.DELEGATION, state) { name ->
            LookupElementBuilder.createWithSmartPointer(
                    name,
                    element
            ).withRenderer(
                    org.elixir_lang.code_insight.lookup.element_renderer.Delegation(name)
            )
        }

        return true
    }

    override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean {
        addDeclarations(element, CallableDeclaration.Form.EEX_FUNCTION_FROM, state) { name ->
            LookupElementBuilder.createWithSmartPointer(
                    name,
                    element
            ).withRenderer(
                    org.elixir_lang.code_insight.lookup.element_renderer.EExFunctionFrom(name)
            )
        }

        return true
    }

    override fun executeOnException(element: Call, state: ResolveState): Boolean {
        Exception.NAME_ARITY_LIST.forEach { nameArity ->
            val name = nameArity.name

            lookupElementByPsiElementName.computeIfAbsent(element to name) { (element, name) ->
                LookupElementBuilder.createWithSmartPointer(
                        name,
                        element
                ).withRenderer(
                        org.elixir_lang.code_insight.lookup.element_renderer.exception.CallDefinitionClause(nameArity)
                ).withInsertHandlerIfAppendingParentheses()
            }
        }

        return true
    }

    override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean {
        // `Generator.isEmbed` admits only these two names today; refuse rather than mislabel a third.
        val renderer: (String) -> LookupElementRenderer<LookupElement> = when (element.functionName()) {
            "embed_template" -> { name -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedTemplate(name) }
            "embed_text" -> { name -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedText(name) }
            else -> return true
        }

        addDeclarations(element, CallableDeclaration.Form.GENERATOR_EMBED, state) { name ->
            LookupElementBuilder.createWithSmartPointer(name, element).withRenderer(renderer(name))
        }

        return true
    }

    /**
     * The shape [executeOnDelegation], [executeOnEExFunctionFrom] and [executeOnMixGeneratorEmbed] share. Applies the
     * insert handler the same way every other builder here does, so a capture (`appendParentheses` false) stays bare.
     */
    private fun addDeclarations(
        call: Call,
        form: CallableDeclaration.Form,
        state: ResolveState,
        lookupElement: (name: String) -> LookupElementBuilder,
    ) {
        for ((name, _) in CallableDeclaration.declarations(call, form, state)) {
            lookupElementByPsiElementName.computeIfAbsent(call to name) { (_, n) ->
                lookupElement(n).withInsertHandlerIfAppendingParentheses()
            }
        }
    }

    /**
     * Whether to continue searching after each Module's children have been searched.
     *
     * @return `true` to keep searching up the PSI tree; `false` to stop searching.
     */
    override fun keepProcessing(): Boolean = true

    private fun LookupElementBuilder.withInsertHandlerIfAppendingParentheses(): LookupElementBuilder =
        if (appendParentheses) withInsertHandler(CallDefinitionClauseInsertHandler) else this


    companion object {
        private val ENTRANCE_CALL_DEFINITION_CLAUSE = Key<Call>("ENTRANCE_CALL_DEFINITION_CLAUSE")

        @JvmStatic
        @JvmOverloads
        fun lookupElementList(entrance: Call, appendParentheses: Boolean = true): List<LookupElement> {
            val parameter = Parameter.putParameterized(Parameter(entrance))
            val entranceCallDefinitionClause: Call? = if (parameter.isCallDefinitionClauseName) {
                parameter.parameterized as Call?
            } else {
                null
            }

            return lookupElementList(entrance, entranceCallDefinitionClause, appendParentheses)
        }

        @JvmStatic
        @JvmOverloads
        fun lookupElementList(entrance: ElixirIdentifier, appendParentheses: Boolean = true): List<LookupElement> =
            lookupElementList(entrance, null, appendParentheses)

        private fun lookupElementList(
            entrance: PsiElement,
            entranceCallDefinitionClause: Call?,
            appendParentheses: Boolean
        ): List<LookupElement> {
            val variants = Variants(appendParentheses)

            val resolveState = ResolveState
                    .initial()
                    .put(ENTRANCE, entrance)
                    .put(ENTRANCE_CALL_DEFINITION_CLAUSE, entranceCallDefinitionClause)
                    .putInitialVisitedElement(entrance)

            if (entranceCallDefinitionClause != null) {
                resolveState.putVisitedElement(entranceCallDefinitionClause)
            }

            PsiTreeUtil.treeWalkUp(
                    variants,
                    entrance,
                    entrance.containingFile,
                    resolveState
            )
            val lookupElementList = ArrayList<LookupElement>()
            lookupElementList.addAll(variants.lookupElementCollection)

            return lookupElementList
        }
    }
}
