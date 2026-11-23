package org.elixir_lang.code_insight

import com.intellij.lang.parameterInfo.*
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.call.Call

class ParameterInfo : ParameterInfoHandler<Arguments, Signature> {
    // The platform calls this off the UI thread and `showParameterInfo` on it, so the resolve and any `.beam` read
    // happen here.
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): Arguments? =
        findArguments(context)?.also { arguments ->
            PsiTreeUtil.getParentOfType(arguments, Call::class.java)?.let { context.itemsToShow = CallSignatures.of(it).toTypedArray() }
        }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): Arguments? =
        findArguments(context)

    override fun showParameterInfo(element: Arguments, context: CreateParameterInfoContext) {
        if (!context.itemsToShow.isNullOrEmpty()) {
            context.showHint(element, element.textRange.startOffset, this)
        }
    }

    override fun updateParameterInfo(parameterOwner: Arguments, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(
            ParameterInfoUtils.getCurrentParameterIndex(
                parameterOwner.node,
                context.offset,
                ElixirTypes.COMMA
            )
        )
    }

    override fun updateUI(p: Signature?, context: ParameterInfoUIContext) {
        if (p == null) {
            context.isUIComponentEnabled = false
        } else {
            val currentParameterIndex = context.currentParameterIndex
            val positions = PsiTreeUtil.getParentOfType(context.parameterOwner, Call::class.java)
                ?.let { CallSignatures.positions(it, p) }
            val highlightedIndex = positions?.getOrNull(currentParameterIndex)

            val stringBuilder = StringBuilder()
            var start = 0
            var end = 0

            p.parameters.forEachIndexed { index, parameter ->
                if (index != 0) {
                    stringBuilder.append(", ")
                }

                if (index == highlightedIndex) {
                    start = stringBuilder.length
                }

                stringBuilder.append(parameter)

                if (index == highlightedIndex) {
                    end = stringBuilder.length
                }
            }

            val disabled = positions != null && positions.size <= currentParameterIndex

            if (stringBuilder.isEmpty()) {
                stringBuilder.append("<no parameters>")
            }

            context.setupUIComponentPresentation(
                stringBuilder.toString(), start, end, disabled, false, true,
                context.defaultParameterColor
            )
        }
    }

    private fun findArguments(context: ParameterInfoContext): Arguments? =
        ParameterInfoUtils.findParentOfType(context.file, context.offset, Arguments::class.java)
}
