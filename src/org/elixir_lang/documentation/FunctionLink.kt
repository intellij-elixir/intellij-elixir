package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.Resolver
import org.elixir_lang.structure_view.element.Callback

/**
 * The one reader of the definitions a plain or `c:` function link names. A `null` module is a link within the
 * context's module; module and name are as written in the link.
 */
internal object FunctionLink {
    /** The definitions the link names, valid or not. */
    fun results(context: PsiElement, module: String?, name: String, arity: Int): List<ResolveResult> {
        val relative = identifierAtomName(name) { languageLevelFor(context) }

        return if (module != null) {
            MarkdownFlavourDescriptor
                .modulars(context, module)
                .flatMap { modular -> MultiResolve.resolveResults(relative, arity, false, modular, atom = true) }
        } else {
            context
                .let { it as? Call }
                ?.let { call -> call.takeIf(::isModular) ?: enclosingModularMacroCall(call) }
                ?.let { modular -> MultiResolve.resolveResults(relative, arity, false, modular, atom = true) }
                ?.toList()
                .orEmpty()
        }
    }

    /** Whether the link names a definition; a [callback] link names only a callback. */
    fun exists(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): Boolean =
        named(results(context, module, name, arity), callback).any(ResolveResult::isValidResult)

    /** The definition the link opens; a [callback] link opens only a callback. */
    fun target(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): PsiElement? =
        named(results(context, module, name, arity), callback)
            .let { Resolver.preferred(context, false, it) }
            .firstNotNullOfOrNull { it.element }

    private fun named(results: List<ResolveResult>, callback: Boolean): List<ResolveResult> =
        results.filter { resolveResult ->
            !callback || resolveResult.element?.let { it as? Call }?.let(Callback.Companion::`is`) ?: false
        }
}
