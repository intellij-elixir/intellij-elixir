package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.ElixirSyntaxHighlighter
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.UnqualifiedBracketOperation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.reference.Callable.Companion.BIT_STRING_TYPES
import org.elixir_lang.reference.Callable.Companion.isBitStreamSegmentOption
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.safeMultiResolve
import org.elixir_lang.safelyResolve

/**
 * Annotates callables.
 */
internal class Callable : Annotator, DumbAware {
    /**
     * Annotates the specified PSI element.
     * It is guaranteed to be executed in non-reentrant fashion.
     * I.e there will be no call of this method for this instance before previous call get completed.
     * Multiple instances of the annotator might exist simultaneously, though.
     *
     * @param element to annotate.
     * @param holder  the container which receives annotations created by the plugin.
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        element.accept(
            object : PsiElementVisitor() {
                /*
                 * Public Instance Methods
                 */

                private fun visitCall(call: Call) {
                    if (!(call is AtOperation || call is AtUnqualifiedNoParenthesesCall<*>)) {
                        visitNonModuleAttributeCall(call)
                    }
                }

                /*
                 * Private Instance Methods
                 */

                private fun visitCallDefinitionClause(call: Call) {
                    // A clause has no reference, so its `def(macro)?p?` keyword is resolved apart, for Kernel PREDEFINED highlighting
                    visitPlainCall(call, org.elixir_lang.reference.Callable.definer(call))

                    CallDefinitionClause.head(call)?.let { head ->
                        visitCallDefinitionHead(head, call)
                    }
                }

                /*
                 * Private Instance Methods
                 */

                private fun visitCallDefinitionHead(head: PsiElement, clause: Call) {
                    val stripped = org.elixir_lang.structure_view.element.CallDefinitionHead.strip(head)

                    if (stripped is Call) {
                        visitStrippedCallDefinitionHead(stripped, clause)
                    }
                }

                override fun visitElement(element: PsiElement) {
                    when (element) {
                        is Call -> visitCall(element)
                        is UnqualifiedBracketOperation -> visitUnqualifiedBracketOperation(element)
                    }
                }

                private fun visitNonModuleAttributeCall(call: Call) {
                    if (CallDefinitionClause.`is`(call)) {
                        visitCallDefinitionClause(call)
                    } else {
                        visitPlainCall(call, call.reference)
                    }
                }

                private fun visitPlainCall(call: Call, reference: PsiReference?) {
                    if (reference != null) {
                        val resolvedCollection =
                            when (reference) {
                                is org.elixir_lang.reference.Callable -> hits(reference)
                                is CaptureNameArity -> hits(reference)
                                is PsiPolyVariantReference ->
                                    safeMultiResolve(reference, false)
                                        .filter { it.isValidResult }
                                        .mapNotNull(ResolveResult::getElement)
                                        .map { Hit(it, null) }
                                else ->
                                    reference.resolve()?.let { resolved ->
                                        setOf(Hit(resolved, null))
                                    }
                            }

                        if (!resolvedCollection.isNullOrEmpty()) {
                            highlight(call, reference.rangeInElement, resolvedCollection, holder)
                        } else if (call.hasDoBlockOrKeyword()) {
                            /* Even though it can't be resolved, it is called like a macro, so highlight like one */
                            call.functionNameElement()?.let { functionNameElement ->
                                highlight(
                                    functionNameElement.textRange,
                                    holder,
                                    ElixirSyntaxHighlighter.MACRO_CALL
                                )
                            }
                        }
                    } else if (isBitStreamSegmentOption(call)) {
                        val name = call.name

                        if (name != null && BIT_STRING_TYPES.contains(name)) {
                            highlight(call, holder, ElixirSyntaxHighlighter.TYPE)
                        }
                    }
                }

                private fun visitStrippedCallDefinitionHead(stripped: Call, clause: Call) {
                    stripped.functionNameElement()?.let { functionNameElement ->
                        val textAttributeKey = ElixirSyntaxHighlighter.declarationKey(
                            CallDefinitionClause.capabilities(clause)?.presentation
                        )

                        if (textAttributeKey != null) {
                            highlight(functionNameElement, holder, textAttributeKey)
                        }
                    }
                }

                private fun visitUnqualifiedBracketOperation(unqualifiedBracketOperation: UnqualifiedBracketOperation) {
                    val identifier = unqualifiedBracketOperation.identifier

                    identifier.reference?.let { it as PsiPolyVariantReference }?.let { reference ->
                        val resolvedElements = reference.multiResolve(false).filter { it.isValidResult }
                            .map { Hit((it as PsiElementResolveResult).element, null) }

                        if (resolvedElements.isNotEmpty()) {
                            highlight(identifier, reference.rangeInElement, resolvedElements, holder)
                        }
                    }
                }
            }
        )
    }

    private class Hit(val element: PsiElement, val declared: Found?)

    /** What `multiResolve` is built from, with the declared part read from the highlighting source. */
    private fun hits(reference: org.elixir_lang.reference.Callable): List<Hit> =
        safelyResolve(reference, emptyList()) {
            val answer = org.elixir_lang.reference.resolver.Callable
                .answer(reference.element, false, Feature.HIGHLIGHTING)

            hits(answer.projection, answer.results())
        }

    /** What a capture's name resolves to, read from the highlighting source. */
    private fun hits(reference: CaptureNameArity): List<Hit> =
        safelyResolve(reference, emptyList()) {
            Use.capture(reference.nameElement, reference.arity)
                ?.let { Resolved.projection(it, false, feature = Feature.HIGHLIGHTING) }
                ?.let { projection -> hits(projection, Resolved.results(projection)) }
                .orEmpty()
        }

    private fun hits(projection: List<Found>, results: List<ResolveResult>): List<Hit> {
        val declared = mutableMapOf<PsiElement, Found>()

        projection.forEach { declared.putIfAbsent(it.element, it) }

        return results
            .filter { it.isValidResult }
            .mapNotNull(ResolveResult::getElement)
            .map { Hit(it, declared[it]) }
    }

    private fun callHighlight(resolved: Call, previousCallHighlight: CallHighlight?): CallHighlight? =
        ElixirSyntaxHighlighter.callKey(CallDefinitionClause.capabilities(resolved)?.presentation)
            ?.let { CallHighlight.nullablePut(previousCallHighlight, arrayOf(it)) }
            ?: variableHighlight(resolved, previousCallHighlight)

    private fun callHighlight(hit: Hit, previousCallHighlight: CallHighlight?): CallHighlight? =
        when {
            hit.declared != null -> callHighlight(hit.declared, previousCallHighlight)
            hit.element is Call -> callHighlight(hit.element, previousCallHighlight)
            else -> variableHighlight(hit.element, previousCallHighlight)
        }

    private fun callHighlight(declared: Found, previousCallHighlight: CallHighlight?): CallHighlight? =
        ElixirSyntaxHighlighter.callKey(declared.candidate.declaration.capabilities.presentation)
            ?.let { CallHighlight.nullablePut(previousCallHighlight, referrerTextAttributesKeys(declared, it)) }
            ?: previousCallHighlight

    private fun variableHighlight(resolved: PsiElement, previousCallHighlight: CallHighlight?): CallHighlight? =
        when (VariableSymbol.classify(resolved)) {
            VariableSymbol.Kind.IGNORED ->
                CallHighlight.nullablePut(previousCallHighlight, IGNORED_VARIABLE_TEXT_ATTRIBUTE_KEYS)
            VariableSymbol.Kind.PARAMETER ->
                CallHighlight.nullablePut(previousCallHighlight, PARAMETER_TEXT_ATTRIBUTE_KEYS)
            VariableSymbol.Kind.VARIABLE ->
                CallHighlight.nullablePut(previousCallHighlight, VARIABLE_TEXT_ATTRIBUTE_KEYS)
            null -> previousCallHighlight
        }

    private fun callHighlight(hits: Collection<Hit>): CallHighlight? =
        hits.fold(null) { acc: CallHighlight?, hit ->
            callHighlight(hit, acc)
        }

    private fun highlight(
        referrer: PsiElement,
        rangeInReferrer: TextRange,
        hits: Collection<Hit>,
        annotationHolder: AnnotationHolder
    ) {
        val callHighlight = callHighlight(hits)

        if (callHighlight != null) {
            val referrerTextAttributesKeys = callHighlight.referrerTextAttributeKeys

            if (referrerTextAttributesKeys != null) {
                highlight(referrer, rangeInReferrer, annotationHolder, *referrerTextAttributesKeys)
            }
        }
    }

    private fun highlight(
        element: PsiElement,
        annotationHolder: AnnotationHolder,
        textAttributesKey: TextAttributesKey
    ) {
        highlight(element.textRange, annotationHolder, textAttributesKey)
    }

    private fun highlight(
        element: PsiElement,
        rangeInElement: TextRange,
        annotationHolder: AnnotationHolder,
        vararg textAttributesKeys: TextAttributesKey
    ) {
        val elementRangeInDocument = element.textRange
        val startOffset = elementRangeInDocument.startOffset

        val rangeInElementInDocument = TextRange(
            startOffset + rangeInElement.startOffset,
            startOffset + rangeInElement.endOffset
        )

        highlight(rangeInElementInDocument, annotationHolder, *textAttributesKeys)
    }

    /**
     * Highlights `textRange` with the given `textAttributesKey`.
     *
     * @param textRange          textRange in the document to highlight
     * @param annotationHolder   the container which receives annotations created by the plugin.
     * @param textAttributesKeys text attributes to apply to the `node`.
     */
    private fun highlight(
        textRange: TextRange,
        annotationHolder: AnnotationHolder,
        vararg textAttributesKeys: TextAttributesKey
    ) {
        if (textAttributesKeys.isNotEmpty()) {
            val editorColorsScheme = EditorColorsManager.getInstance().globalScheme
            val mergedTextAttributes = textAttributesKeys.fold(null) { acc: TextAttributes?, textAttributesKey ->
                val textAttributes = editorColorsScheme.getAttributes(textAttributesKey)

                TextAttributes.merge(acc, textAttributes)
            }!!

            Highlighter.highlight(annotationHolder, textRange, mergedTextAttributes)
        }
    }

    private class CallHighlight private constructor(val referrerTextAttributeKeys: Array<TextAttributesKey>?) {
        fun put(referrerTextAttributeKeys: Array<TextAttributesKey>?): CallHighlight {
            val updatedReferrerTextAttributeKeys = bestReferrerTextAttributeKeys(referrerTextAttributeKeys)

            return if (updatedReferrerTextAttributeKeys contentEquals this.referrerTextAttributeKeys) {
                this
            } else {
                CallHighlight(
                    updatedReferrerTextAttributeKeys
                )
            }
        }

        private fun bestReferrerTextAttributeKeys(referrerTextAttributeKeys: Array<TextAttributesKey>?): Array<TextAttributesKey>? =
            if (this.referrerTextAttributeKeys != null) {
                if (referrerTextAttributeKeys != null) {
                    var currentPredefined = false

                    for (textAttributesKey in this.referrerTextAttributeKeys) {
                        if (textAttributesKey === ElixirSyntaxHighlighter.PREDEFINED_CALL) {
                            currentPredefined = true
                            break
                        }
                    }

                    if (currentPredefined) {
                        this.referrerTextAttributeKeys
                    } else {
                        referrerTextAttributeKeys
                    }
                } else {
                    this.referrerTextAttributeKeys
                }
            } else {
                referrerTextAttributeKeys
            }

        companion object {
            fun nullablePut(
                callHighlight: CallHighlight?,
                referrerTextAttributeKeys: Array<TextAttributesKey>?
            ): CallHighlight =
                callHighlight?.put(
                    referrerTextAttributeKeys
                ) ?: CallHighlight(
                    referrerTextAttributeKeys
                )
        }
    }

    companion object {
        private val IGNORED_VARIABLE_TEXT_ATTRIBUTE_KEYS = arrayOf(ElixirSyntaxHighlighter.IGNORED_VARIABLE)
        private val PARAMETER_TEXT_ATTRIBUTE_KEYS = arrayOf(ElixirSyntaxHighlighter.PARAMETER)

        private val PREDEFINED_MODULE_NAMES = setOf(KERNEL, KERNEL_SPECIAL_FORMS)
        private val VARIABLE_TEXT_ATTRIBUTE_KEYS = arrayOf(ElixirSyntaxHighlighter.VARIABLE)

        private fun referrerTextAttributesKeys(declared: Found, key: TextAttributesKey): Array<TextAttributesKey> =
            if (declared.declaringModuleName() in PREDEFINED_MODULE_NAMES) {
                arrayOf(key, ElixirSyntaxHighlighter.PREDEFINED_CALL)
            } else {
                arrayOf(key)
            }
    }
}
