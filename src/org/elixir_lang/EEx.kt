package org.elixir_lang

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall

object EEx {
    fun isFunctionFrom(call: Call, state: ResolveState): Boolean =
        isFunctionFromShaped(SyntacticCall.of(call)) && resolvesToEEx(call, state)

    /** Named and called like a `function_from_*`, before resolving whether it is `EEx`'s. */
    fun isFunctionFromShaped(call: SyntacticCall): Boolean =
        when (call.functionName()) {
            FUNCTION_FROM_FILE_ARITY_RANGE.name -> call.resolvedFinalArity() in FUNCTION_FROM_FILE_ARITY_RANGE.arityRange
            FUNCTION_FROM_STRING_ARITY_RANGE.name ->
                call.resolvedFinalArity() in FUNCTION_FROM_STRING_ARITY_RANGE.arityRange
            else -> false
        }

    private fun resolvesToEEx(call: Call, state: ResolveState): Boolean =
            resolvesToModularName(call, state, "EEx")

    // function_from_file(kind, name, file, args \\ [], options \\ [])
    val FUNCTION_FROM_FILE_ARITY_RANGE = NameArityRange("function_from_file", 3..5)
    // function_from_string(kind, name, source, args \\ [], options \\ [])
    val FUNCTION_FROM_STRING_ARITY_RANGE = NameArityRange("function_from_string", 3..5)
}
