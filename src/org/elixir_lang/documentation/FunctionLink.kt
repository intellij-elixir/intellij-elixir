package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.Resolver
import org.elixir_lang.structure_view.element.Callback

/**
 * The one reader of the definitions a plain or `c:` function link names. A `null` module is a link within the
 * context's module; module and name are as written in the link.
 */
internal object FunctionLink {
    /** The definitions the link names, valid when a call of the name would resolve to them. */
    fun results(context: PsiElement, module: String?, name: String, arity: Int): List<ResolveResult> {
        val relative = DocumentationLink.atomName(name, context)

        return entrances(context, module).flatMap { entrance ->
            sourceFor(Feature.DOCUMENTATION)
                .candidates(Use.link(context, entrance, relative, arity), incompleteCode = false)
                .groupBy { it.element }
                .map { (element, found) ->
                    Resolved.result(element, found.filter { it.candidate.applicability.admitted })
                }
        }
    }

    /** The modules the link is read from. */
    private fun entrances(context: PsiElement, module: String?): List<PsiElement> =
        if (module != null) {
            MarkdownFlavourDescriptor.modulars(context, module)
        } else {
            context
                .let { it as? Call }
                ?.let { call -> call.takeIf(::isModular) ?: enclosingModularMacroCall(call) }
                ?.let(::listOf)
                .orEmpty()
        }

    /** Whether the link names a definition; a [callback] link names only a callback. */
    fun exists(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): Boolean =
        named(results(context, module, name, arity), callback).any(ResolveResult::isValidResult)

    /**
     * The definition the link opens; a [callback] link opens only a callback. Among valid results it is picked as a
     * call's target is. With none, it is the first, which is the name at another arity.
     */
    fun target(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): PsiElement? {
        val preferred = named(results(context, module, name, arity), callback)
            .let { Resolver.preferred(context, false, it) }

        return if (preferred.any(ResolveResult::isValidResult)) {
            Resolved.target(preferred)
        } else {
            preferred.firstNotNullOfOrNull { it.element }
        }
    }

    private fun named(results: List<ResolveResult>, callback: Boolean): List<ResolveResult> =
        results.filter { resolveResult ->
            !callback || resolveResult.element?.let { it as? Call }?.let(Callback.Companion::`is`) ?: false
        }
}
