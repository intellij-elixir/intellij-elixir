package org.elixir_lang

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.stripAccessExpression

object EEx {
    fun isFunctionFrom(call: Call, state: ResolveState): Boolean =
        call.functionName()?.let { functionName ->
            when (functionName) {
                FUNCTION_FROM_FILE_ARITY_RANGE.name ->
                    call.resolvedFinalArity() in FUNCTION_FROM_FILE_ARITY_RANGE.arityRange &&
                            resolvesToEEx(call, state)
                FUNCTION_FROM_STRING_ARITY_RANGE.name ->
                    call.resolvedFinalArity() in FUNCTION_FROM_STRING_ARITY_RANGE.arityRange &&
                            resolvesToEEx(call, state)
                else -> false
            }
        } ?: false

    private fun resolvesToEEx(call: Call, state: ResolveState): Boolean =
            resolvesToModularName(call, state, "EEx")

    // function_from_file(kind, name, file, args \\ [], options \\ [])
    val FUNCTION_FROM_FILE_ARITY_RANGE = NameArityRange("function_from_file", 3..5)
    // function_from_string(kind, name, source, args \\ [], options \\ [])
    val FUNCTION_FROM_STRING_ARITY_RANGE = NameArityRange("function_from_string", 3..5)

    /** The name `function_from_file`/`function_from_string` defines, or `null` when argument 1 is no literal atom. */
    @RequiresReadLock
    fun declaredName(call: Call): Name? =
        call.finalArguments()?.getOrNull(1)?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()

    /**
     * The `args` list's elements, which name the defined function's parameters and so fix its arity: empty when the
     * call gives none, `null` when argument 3 is no literal list.
     */
    @RequiresReadLock
    fun argumentList(call: Call): kotlin.collections.List<PsiElement>? {
        val arguments = call.finalArguments() ?: return null

        return if (arguments.size >= 4) {
            (arguments[3].stripAccessExpression() as? ElixirList)?.children?.toList()
        } else {
            emptyList()
        }
    }
}
