package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code_insight.completion.insert_handler.CallDefinitionClause as CallDefinitionClauseInsertHandler
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.scope.CallDefinitionClause
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback

/**
 * [appendParentheses] is threaded through so a capture's `&name/arity` (which reuses this same walk,
 * see [org.elixir_lang.reference.CaptureNameArity]) stays a bare name - a capture names a function, it
 * does not call one. [recordsVisible] also records a [Visible] beside each lookup element.
 */
class Variants(private val appendParentheses: Boolean, private val recordsVisible: Boolean = false) : CallDefinitionClause() {
    private var lookupElementByPsiElementName: MutableMap<Pair<PsiElement, String>, LookupElement> = mutableMapOf()
    private val visibleByPsiElementName: MutableMap<Pair<PsiElement, String>, Visible> = mutableMapOf()

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
            addCallDefinitionClauseToLookupElementByPsiElement(element, state)
            element.name?.let { name -> recordVisible(Form.CLAUSE, element, state) { if (it == name) element else null } }
        }

        return true
    }

    /**
     * Records each declaration [form] gives [declaring] under the same key as its lookup element: the element [key]
     * gives for its spelling, if any, and that spelling.
     */
    private fun recordVisible(form: Form, declaring: Call, state: ResolveState, key: (String) -> PsiElement?) {
        if (!recordsVisible) return

        for ((text, declaration) in Declarations.of(form, declaring, state)) {
            key(text)?.let { element ->
                visibleByPsiElementName.computeIfAbsent(element to text) {
                    Visible(text, declaration?.name, declaration, declaring)
                }
            }
        }
    }

    override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean {
        // BEAM-decompiled call definitions are never the entrance clause (which is always source),
        // so the entrance guard from executeOnCallDefinitionClause does not apply here.
        if (element.isExported()) {
            addCallDefinitionToLookupElementByPsiElement(element)

            if (recordsVisible) {
                element.exportedName()?.let { name ->
                    visibleByPsiElementName.computeIfAbsent(element to name) {
                        val declaration = element.declaration()

                        Visible(name, declaration.name, declaration, element)
                    }
                }
            }
        }

        return true
    }

    private fun addCallDefinitionToLookupElementByPsiElement(element: BeamCallDefinition) {
        // MaybeExported documents exportedName() as null only when isExported() is false, which the
        // sole caller checks.
        val name = element.exportedName()?.takeIf(::callableUnqualified) ?: return

        lookupElementByPsiElementName.computeIfAbsent(element to name) { (el, n) ->
            LookupElementBuilder.createWithSmartPointer(n, el)
                .withRenderer(org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause(n))
                .withInsertHandlerIfAppendingParentheses()
        }
    }

    private fun addCallDefinitionClauseToLookupElementByPsiElement(named: Named, state: ResolveState) {
        named.name?.takeIf(::callableUnqualified)?.let { name ->
            lookupElementByPsiElementName.computeIfAbsent(named to name) { (element, name) ->
                LookupElementBuilder.createWithSmartPointer(
                        name,
                        element
                ).withRenderer(
                        org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause(name)
                ).withInsertHandlerIfAppendingParentheses { Import.highestImportedArity(named, state) }
            }
        }
    }

    override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean {
        Callback.headCall(element)
                ?.let { it as? Named }
                ?.let { head ->
                    addCallbackToLookupElementByPsiElement(element, head)
                    recordVisible(Form.CALLBACK, element, state) { head }
                }

        return true
    }

    private fun addCallbackToLookupElementByPsiElement(element: AtUnqualifiedNoParenthesesCall<*>, head: Named) =
            head.name?.takeIf(::callableUnqualified)?.let { name ->
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
        element.finalArguments()?.takeIf { it.size == 2 }?.let { arguments ->
            val head = arguments[0]

            CallDefinitionHead.nameArityInterval(head, state)?.let { headNameArityInterval ->
                val headName = headNameArityInterval.name
                recordVisible(Form.DELEGATION, element, state) { head }

                if (callableUnqualified(headName)) lookupElementByPsiElementName.computeIfAbsent(head to headName) { (_, headName) ->
                    LookupElementBuilder.createWithSmartPointer(
                            headName,
                            element
                    ).withRenderer(
                            org.elixir_lang.code_insight.lookup.element_renderer.Delegation(headName)
                    ).withInsertHandlerIfAppendingParentheses { Import.highestImportedArity(element, state) }
                }
            }
        }

        return true
    }

    override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean {
        recordVisible(Form.EEX_FUNCTION_FROM, element, state) { element }
        element.finalArguments()?.let { arguments ->
            arguments[1]
                .stripAccessExpression()
                .let { it as? ElixirAtom }
                ?.let(::quotedAtomValue)
                ?.takeIf(::callableUnqualified)
                ?.let { name ->
                    lookupElementByPsiElementName.computeIfAbsent(element to name) { (_, name) ->
                        LookupElementBuilder.createWithSmartPointer(
                                name,
                                element
                        ).withRenderer(
                                org.elixir_lang.code_insight.lookup.element_renderer.EExFunctionFrom(name)
                        ).withInsertHandlerIfAppendingParentheses()
                    }
                }
       }

        return true
    }

    override fun executeOnException(element: Call, state: ResolveState): Boolean {
        recordVisible(Form.EXCEPTION, element, state) { element }
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
        recordVisible(Form.GENERATOR_EMBED, element, state) { element }
        element.finalArguments()?.first()?.stripAccessExpression()?.let { it as? ElixirAtom }?.let(::quotedAtomValue)?.let { prefix ->
            val suffix = element.functionName()!!.removePrefix("embed_")
            val name = "${prefix}_${suffix}".takeIf(::callableUnqualified) ?: return true
            // `Generator.isEmbed` admits only these two names.
            val renderer = when (suffix) {
                "template" -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedTemplate(name)
                "text" -> org.elixir_lang.code_insight.lookup.element_renderer.mix.generator.EmbedText(name)
                else -> null
            } ?: return true

            lookupElementByPsiElementName.computeIfAbsent(element to name) { (element, name) ->
                LookupElementBuilder
                        .createWithSmartPointer(name, element)
                        .withRenderer(renderer)
                        .withInsertHandlerIfAppendingParentheses()
            }
        }

        return true
    }

    /**
     * Whether to continue searching after each Module's children have been searched.
     *
     * @return `true` to keep searching up the PSI tree; `false` to stop searching.
     */
    override fun keepProcessing(): Boolean = true

    private fun LookupElementBuilder.withInsertHandlerIfAppendingParentheses(arity: () -> Arity? = { null }): LookupElementBuilder =
        if (appendParentheses) withInsertHandler(CallDefinitionClauseInsertHandler.at(arity())) else this

    /** A name Elixir can write only quoted is not offered here. */
    private fun callableUnqualified(name: String): Boolean = InspectAtom.classify(name) != InspectAtom.Class.OTHER


    companion object {
        private val ENTRANCE_CALL_DEFINITION_CLAUSE = Key<Call>("ENTRANCE_CALL_DEFINITION_CLAUSE")

        @JvmStatic
        @JvmOverloads
        fun lookupElementList(entrance: Call, appendParentheses: Boolean = true): List<LookupElement> =
            lookupElementList(entrance, entranceCallDefinitionClause(entrance), appendParentheses)

        /** The lookup elements at [position], and the [Visible] beside each, from one walk. */
        @RequiresReadLock
        fun lookupElementsAndVisible(position: PsiElement): Pair<List<LookupElement>, List<Visible>> {
            ThreadingAssertions.assertReadAccess()

            val variants = Variants(appendParentheses = true, recordsVisible = true)
            walk(variants, position, (position as? Call)?.let(::entranceCallDefinitionClause))

            return variants.lookupElementCollection.toList() to variants.visibleByPsiElementName.values.toList()
        }

        private fun entranceCallDefinitionClause(entrance: Call): Call? {
            val parameter = Parameter.putParameterized(Parameter(entrance))

            return if (parameter.isCallDefinitionClauseName) {
                parameter.parameterized as Call?
            } else {
                null
            }
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
            walk(variants, entrance, entranceCallDefinitionClause)
            val lookupElementList = ArrayList<LookupElement>()
            lookupElementList.addAll(variants.lookupElementCollection)

            return lookupElementList
        }

        private fun walk(variants: Variants, entrance: PsiElement, entranceCallDefinitionClause: Call?) {
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
        }
    }
}
