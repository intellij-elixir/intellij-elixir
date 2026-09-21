package org.elixir_lang

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
                            resolvesToQualifiedModularName(call, state, "EEx")
                FUNCTION_FROM_STRING_ARITY_RANGE.name ->
                    call.resolvedFinalArity() in FUNCTION_FROM_STRING_ARITY_RANGE.arityRange &&
                            resolvesToQualifiedModularName(call, state, "EEx")
                else -> false
            }
        } ?: false

    // function_from_file(kind, name, file, args \\ [], options \\ [])
    val FUNCTION_FROM_FILE_ARITY_RANGE = NameArityRange("function_from_file", 3..5)
    // function_from_string(kind, name, source, args \\ [], options \\ [])
    val FUNCTION_FROM_STRING_ARITY_RANGE = NameArityRange("function_from_string", 3..5)

    data class DeclaredNameArity(val name: Name?, val arity: Int?)

    /**
     * The name and arity `function_from_file`/`function_from_string` declare, read from [call]'s own
     * arguments rather than parsed from a call-definition head. Either can be `null`: the name when
     * argument 1 isn't a literal atom, the arity when argument 3 isn't a literal list.
     */
    @RequiresReadLock
    fun declaredNameArity(call: Call): DeclaredNameArity {
        val arguments = call.finalArguments()
        val name = arguments?.getOrNull(1)?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()
        val arity = when {
            arguments == null -> null
            arguments.size >= 4 -> arguments[3].stripAccessExpression().let { it as? ElixirList }?.children?.size
            else -> 0
        }
        return DeclaredNameArity(name, arity)
    }
}
