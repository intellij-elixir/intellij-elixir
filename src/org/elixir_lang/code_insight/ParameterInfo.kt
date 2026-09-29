package org.elixir_lang.code_insight

import com.intellij.lang.parameterInfo.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.isExactName

class ParameterInfo : ParameterInfoHandler<Arguments, Signature> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): Arguments? =
        findArguments(context)

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): Arguments? =
        findArguments(context)

    override fun showParameterInfo(element: Arguments, context: CreateParameterInfoContext) {
        PsiTreeUtil.getParentOfType(element, Call::class.java)?.let { call ->
            val resolveResults = call.references.flatMap { reference ->
                if (reference is PsiPolyVariantReference) {
                    reference.multiResolve(true).toList()
                } else {
                    listOfNotNull(reference.resolve()?.let { PsiElementResolveResult(it) })
                }
            }
            // Resolved as incomplete code, which also returns functions the name is only a prefix of, so `reduce`
            // would otherwise be described by `reduce_while` as well; with no name to match on, every result stays.
            val resolved = resolveResults
                .filter { call.functionName() == null || it.isExactName() }
                .mapNotNull { it.element }

            val signatures = signatures(resolved)

            if (signatures.isNotEmpty()) {
                context.itemsToShow = signatures.toTypedArray()
                context.showHint(element, element.textRange.startOffset, this)
            }
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

            val stringBuilder = StringBuilder()
            var start = 0
            var end = 0

            p.parameters.forEachIndexed { index, parameter ->
                if (index != 0) {
                    stringBuilder.append(", ")
                }

                if (index == currentParameterIndex) {
                    start = stringBuilder.length
                }

                stringBuilder.append(parameter)

                if (index == currentParameterIndex) {
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

    /* Deduplicate by (name, arity), preferring bare function heads (no do block) over implementation clauses.

       The references are resolved as incomplete code so that a call whose arguments are not typed yet resolves
       at all, which is exactly when the hint is wanted: resolving them completely collapses `foo/1` and `foo/2`
       to a single arity, and does not drop the prefix matches either.

       A `.beam` definition is read from its stub: this runs on the EDT, and decompiling a module to reach its
       mirror can take hundreds of milliseconds. */
    private fun signatures(resolved: List<PsiElement>): List<Signature> {
        val clauses = resolved.filterIsInstance<Call>().filter { CallDefinitionClause.`is`(it) }
        val beamDefinitions = resolved.filterIsInstance<BeamCallDefinition>()

        return preferFunctionHeadsByArity(clauses).mapNotNull { Signature.of(it) } +
            beamDefinitions
                .map { Signature.of(it) }
                .distinctBy { it.nameArityInterval }
    }
}
