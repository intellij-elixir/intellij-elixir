package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.SymbolPointerInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import org.elixir_lang.Module
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.code_insight.attributeDeclaration
import org.elixir_lang.documentation.moduleAttributeValue
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.QuotedLiteral
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.model.psi.module_attribute.ImplementationAttributeReference
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.__module__.currentModularAt
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.moduleAttributeCall

/**
 * After each read of a module attribute, the literal value it reads; after `@for` and `@protocol` in a `defimpl`, the
 * module each names, where that name has a value.
 */
@Suppress("UnstableApiUsage")
internal class ModuleAttributeValueHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector

    private object Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is AtOperation) return
            val read = moduleAttributeCall(element) as? UnqualifiedNoArgumentsCall<*> ?: return
            val implementation = ImplementationAttributeReference.readImplementation(read)
            val (source, actionData) = when {
                implementation != null -> implementationHint(read, implementation)
                // A read in a `quote` reads the attribute of the module the code is injected into.
                currentModularAt(read) != null -> valueHint(element)
                else -> null
            } ?: return
            val text = shown(ParameterText.normalised(source)) ?: return

            sink.addTextAfter(element, text, actionData)
        }

        private fun implementationHint(read: Call, implementation: Call): Pair<String, InlayActionData?>? {
            val moduleNames = ImplementationAttributeReference.moduleNames(read, implementation)
            val text = singleModuleText(moduleNames.map { it.name }) ?: return null
            val actionData = (ImplementationAttributeReference.resolve(implementation, moduleNames).singleOrNull() as? ModuleSymbol)?.let {
                InlayActionData(SymbolPointerInlayActionPayload(it.createPointer()), SymbolNavigationHandler.HANDLER_ID)
            }

            return text to actionData
        }

        private fun valueHint(read: AtOperation): Pair<String, InlayActionData>? {
            val declaration = attributeDeclaration(read) ?: return null
            val value = declaration.moduleAttributeValue() ?: return null
            if (!QuotedLiteral.of(ElementLowering.lower(value), ElixirLanguageLevelResolver.languageLevelFor(value))) return null

            return value.text to InlayActionData(
                PsiPointerInlayActionPayload(SmartPointerManager.createPointer(declaration)),
                PsiPointerInlayActionNavigationHandler.HANDLER_ID
            )
        }
    }
}

/** A longer text is cut to one less, then an ellipsis. */
private const val MAX_LENGTH = 40

/** [text], cut past [MAX_LENGTH] code points, or `null` when it spans lines. */
private fun shown(text: String): String? = when {
    StringUtil.containsLineBreak(text) -> null
    text.codePointCount(0, text.length) > MAX_LENGTH -> text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH - 1)) + Typography.ellipsis
    else -> text
}

/** The one module in [names], as Elixir writes it, when its name has a value. */
internal fun singleModuleText(names: Collection<String>): String? =
    names.singleOrNull()?.takeIf { Module.atom(it) != null }?.let(Module::inspect)
