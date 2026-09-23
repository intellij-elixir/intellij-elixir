package org.elixir_lang.model.psi.function

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.scope.Reach

/**
 * What a call that resolves to nothing valid names: each declaration of the exact name it calls, with the arities the
 * call could reach it at - those an `import` it came through brings in. Not what a `defdelegate` delegates to, which
 * the call does not name, and for a remote call only what the module exports.
 */
internal object RejectedCall {
    class Named(val declaration: PsiElement, val arities: List<Int>)

    @RequiresReadLock
    fun named(call: Call): List<Named> {
        val name = call.functionName() ?: return emptyList()
        val remote = call is Qualified

        return org.elixir_lang.reference.resolver.Callable.candidates(call)
            .filter { candidate -> Reach.named(candidate.reach, candidate.element, remote) }
            .mapNotNull { candidate ->
                val declaration = candidate.element
                val imports = candidate.visitedElementSet.filterIsInstance<Call>().filter { Import.`is`(it) }
                val declared = CallableDeclaration.declaredOf(declaration, ResolveState.initial())
                val compileTime = declared?.capabilities?.compileTime
                val arities = declared
                    ?.definitions(ResolveState.initial())
                    .orEmpty()
                    .filter { it.name == name }
                    .flatMap { it.arityInterval?.closed()?.toList().orEmpty() }
                    .filter { arity -> imports.all { Import.admits(it, name, arity, compileTime) } }
                    .distinct()
                    .sorted()

                arities.takeIf { it.isNotEmpty() }?.let { Named(declaration, it) }
            }
    }
}
