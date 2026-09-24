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
import org.elixir_lang.NameArity
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.mix.Generator
import org.elixir_lang.psi.scope.CallDefinitionClause
import org.elixir_lang.psi.scope.Reach

/**
 * [appendParentheses] is threaded through so a capture's `&name/arity` (which reuses this same walk,
 * see [org.elixir_lang.reference.CaptureNameArity]) stays a bare name - a capture names a function, it
 * does not call one.
 *
 * [remote] offers only what the module walked from [Reach.exports], as a remote use sees it: a bare name, as a capture
 * or MFA tuple writes one, names a function.
 */
class Variants(private val appendParentheses: Boolean, private val remote: Boolean = false) : CallDefinitionClause() {
    // What a module imports it does not export.
    override val followsImports: Boolean get() = !remote

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

        if (entranceCallDefinitionClause == null || !element.isEquivalentTo(entranceCallDefinitionClause)) {
            addDeclarations(element, CallableDeclaration.Form.CLAUSE, state) { declaration ->
                LookupElementBuilder.createWithSmartPointer(declaration.name, element)
                    .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause(declaration.name))
            }
        }

        return true
    }

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean {
        // BEAM-decompiled call definitions are never the entrance clause (which is always source),
        // so the entrance guard from executeOnCallDefinitionClause does not apply here. An `import`
        // brings in only what is exported.
        if (!remote || exported(element, state)) {
            addCallDefinitionToLookupElementByPsiElement(element)
        }

        return true
    }

    private fun exported(element: PsiElement, state: ResolveState): Boolean =
        Reach.exports(Reach.of(element, state), element, runtime = !appendParentheses)

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

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        // A `@callback` declares what another module defines, so its own module does not export it.
        if (remote) return true

        addDeclarations(element, CallableDeclaration.Form.CALLBACK, state) { declaration ->
            LookupElementBuilder.createWithSmartPointer(declaration.name, element)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.Callback(declaration.name))
        }

        return true
    }

    override fun executeOnDelegation(element: Call, state: ResolveState): Boolean {
        addDeclarations(element, CallableDeclaration.Form.DELEGATION, state) { declaration ->
            LookupElementBuilder.createWithSmartPointer(declaration.name, element)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.Delegation(declaration.name))
        }

        return true
    }

    override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean {
        addDeclarations(element, CallableDeclaration.Form.EEX_FUNCTION_FROM, state) { declaration ->
            LookupElementBuilder.createWithSmartPointer(declaration.name, element)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.EExFunctionFrom(declaration.name))
        }

        return true
    }

    override fun executeOnException(element: Call, state: ResolveState): Boolean {
        addDeclarations(element, CallableDeclaration.Form.EXCEPTION, state) { declaration ->
            val nameArity = NameArity(declaration.name, declaration.arityInterval?.minimum ?: 0)

            LookupElementBuilder.createWithSmartPointer(declaration.name, element)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.exception.CallDefinitionClause(nameArity))
        }

        return true
    }

    override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean {
        val renderer: (String) -> LookupElementRenderer<LookupElement> = when (Generator.Embed.of(element)) {
            Generator.Embed.TEMPLATE -> { name -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedTemplate(name) }
            Generator.Embed.TEXT -> { name -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedText(name) }
            null -> return true
        }

        addDeclarations(element, CallableDeclaration.Form.GENERATOR_EMBED, state) { declaration ->
            LookupElementBuilder.createWithSmartPointer(declaration.name, element).withRenderer(renderer(declaration.name))
        }

        return true
    }

    private fun addDeclarations(
        call: Call,
        form: CallableDeclaration.Form,
        state: ResolveState,
        lookupElement: (CallableDeclaration.Declaration) -> LookupElementBuilder,
    ) {
        if (remote && !exported(call, state)) return

        val compileTime = CallableDeclaration.Declared.Source(call, form).capabilities?.compileTime

        for (declaration in CallableDeclaration.declarations(call, form, state)) {
            if (Import.admits(state, declaration.name, declaration.nameArityInterval().arityInterval, compileTime)) {
                lookupElementByPsiElementName.computeIfAbsent(call to declaration.name) {
                    lookupElement(declaration).withInsertHandlerIfAppendingParentheses().also {
                        it.putUserData(CallDefinitionClauseInsertHandler.FORM, form)
                    }
                }
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

        /** What a remote use of [modular] can name: what it [Reach.exports], each declaration once per name. */
        fun remoteLookupElementList(modular: Call, appendParentheses: Boolean): List<LookupElement> =
            lookupElementList(modular, null, appendParentheses, remote = true, maxScope = modular)

        private fun lookupElementList(
            entrance: PsiElement,
            entranceCallDefinitionClause: Call?,
            appendParentheses: Boolean,
            remote: Boolean = false,
            maxScope: PsiElement = entrance.containingFile
        ): List<LookupElement> {
            val variants = Variants(appendParentheses, remote)

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
                    maxScope,
                    resolveState
            )
            val lookupElementList = ArrayList<LookupElement>()
            lookupElementList.addAll(variants.lookupElementCollection)

            return lookupElementList
        }
    }
}
