package org.elixir_lang.code_insight

import com.intellij.lang.parameterInfo.*
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.preferred
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.call.Call

class ParameterInfo : ParameterInfoHandler<Arguments, Signature> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): Arguments? =
        findArguments(context)

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): Arguments? =
        findArguments(context)

    override fun showParameterInfo(element: Arguments, context: CreateParameterInfoContext) {
        val signatures = PsiTreeUtil.getParentOfType(element, Call::class.java)?.let(::signatures).orEmpty()

        if (signatures.isNotEmpty()) {
            context.itemsToShow = signatures.toTypedArray()
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
            // The head shows its defaults, but a call leaves some out: the argument binds the parameter left at the call's arity.
            val highlightedIndex = PsiTreeUtil.getParentOfType(context.parameterOwner, Call::class.java)
                ?.resolvedPrimaryArity()
                ?.let { ParameterText.positionsReaching(p.parameters, it).getOrNull(currentParameterIndex) }
                ?: currentParameterIndex

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

            val disabled = p.parameters.size <= currentParameterIndex

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

    // Incomplete code, so that a call whose arguments are not typed yet resolves at all, which is when the hint is
    // wanted: complete code keeps only the arity that fits what is typed.
    private fun signatures(call: Call): List<Signature> =
        when (val use = Use.of(call)) {
            is Use.Named ->
                signatures(preferred(use, sourceFor(Feature.PARAMETER_INFO).candidates(use, true), true))
            // A name-less use can be any function of the module, so it describes none.
            is Use.AnyName, null -> emptyList()
        }
}
