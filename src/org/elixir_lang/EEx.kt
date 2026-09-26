package org.elixir_lang

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFP
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

    /** The atom that names the defined function, when [declaredName] reads one from it. */
    @RequiresReadLock
    fun declaredNameAtom(call: Call): ElixirAtom? =
        call.finalArguments()?.getOrNull(1)?.stripAccessExpression()?.let { it as? ElixirAtom }
            ?.takeIf { it.literalName() != null }

    /** The name `function_from_file`/`function_from_string` defines, or `null` when argument 1 is no literal atom. */
    @RequiresReadLock
    fun declaredName(call: Call): Name? = declaredNameAtom(call)?.literalName()

    /** The `def*` the call defines its function with, from argument 0; `null` when that is not a literal kind. */
    @RequiresReadLock
    fun kind(call: Call): Name? =
        call.finalArguments()?.getOrNull(0)?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()

    /** The defined function's visibility, as its [kind] gives it; `null` when that is no `:def` or `:defp`. */
    @RequiresReadLock
    fun visibility(call: Call): Visibility? =
        kind(call)?.takeIf { it == DEF || it == DEFP }?.let { CallableDeclaration.Definer.of(it)?.visibility }

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
