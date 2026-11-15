package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.isContract
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * The one reader of the definitions a plain or `c:` function link names. A `null` module is a link within the
 * context's module; module and name are as written in the link.
 */
internal object FunctionLink {
    /** The step's answer for the link at each module it is read from; a [callback] link's, only its callbacks. */
    private fun step(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): List<Preferred> {
        val relative = DocumentationLink.atomName(name, context)

        return entrances(context, module).flatMap { entrance ->
            Resolved.step(Use.link(context, entrance, relative, arity), incompleteCode = false, feature = Feature.DOCUMENTATION) { found ->
                !callback || isContract(found)
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

    /**
     * The definitions the link names, valid when a call of the name would resolve to them; a [callback] link's, only
     * callbacks. A callback counts, as a link names one.
     */
    @RequiresReadLock
    fun results(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean = false): List<ResolveResult> =
        Resolved.results(Resolved.projection(step(context, module, name, arity, callback), Feature.DOCUMENTATION), contracts = true)

    /** Whether the link names a definition; a [callback] link names only a callback. */
    @RequiresReadLock
    fun exists(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): Boolean =
        results(context, module, name, arity, callback).any(ResolveResult::isValidResult)

    /**
     * What the link opens, seen from outside its module, as a use's [Documented.Choice] is. Among valid results it is the
     * one a call's target is, and past a delegation with no `@doc` the one target that delegation has by the same rule,
     * then each target past that. With no valid result it is the first, which is the name at another arity.
     */
    @RequiresReadLock
    fun opened(context: PsiElement, module: String?, name: String, arity: Int, callback: Boolean): Documented.Choice? {
        val step = step(context, module, name, arity, callback)
        val projection = Resolved.projection(step, Feature.DOCUMENTATION)
        val results = Resolved.results(projection, contracts = true)

        if (results.none(ResolveResult::isValidResult)) {
            return projection.firstOrNull()?.let { Documented.choice(step, it.element, use = null) }
        }

        val target = Resolved.target(results) ?: return null
        val preferred = step.firstOrNull { it.found.element == target }
        val delegatedTo = preferred?.admittedDelegatedTo.orEmpty()
        val one = Resolved.target(Resolved.results(delegatedTo, contracts = true))
        val throughOne = delegatedTo.filter { found -> found.element == one || one in found.via }
        val element = preferred?.let { Documented.documented(listOf(it.found) + throughOne) } ?: target

        return Documented.choice(step, element, use = null)
    }
}
