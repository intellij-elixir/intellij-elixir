package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.code_insight.CallSignatures
import org.elixir_lang.psi.ElixirParenthesesArguments
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.isCallSite

/**
 * Before each positional argument of a call with parentheses, the name of the parameter it fills, where one definition
 * has the call's arity and its names are recorded.
 */
internal class ParameterNameHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector

    private object Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is Call) return
            val arguments = element.primaryArguments() ?: return
            // Only a call with parentheses: the line `mix format`'s `locals_without_parens` draws.
            if (arguments.firstOrNull()?.parent !is ElixirParenthesesArguments) return
            // A keyword tail gets no name, so a call with only one needs no resolving.
            val positional = arguments.mapIndexedNotNull { index, argument ->
                argument?.takeUnless { it is QuotableKeywordList }?.let { index to it }
            }
            if (positional.isEmpty()) return
            if (!element.isCallSite()) return
            val arity = element.resolvedPrimaryArity() ?: return
            val signature = CallSignatures.of(element).singleOrNull { arity in it.nameArityInterval.arityInterval } ?: return
            if (!signature.namesRecorded) return
            val positions = CallSignatures.positions(element, signature) ?: return

            for ((index, argument) in positional) {
                val parameter = positions.getOrNull(index)?.let(signature.parameters::getOrNull) ?: continue
                val name = ParameterText.withoutDefault(parameter) ?: parameter

                if (name != "_" && !argument.textMatches(name) && ParameterText.isName(name)) sink.addTextBefore(argument, "$name:", FORMAT)
            }
        }
    }
}

private val FORMAT = HintFormat.default.withColorKind(HintColorKind.Parameter)
