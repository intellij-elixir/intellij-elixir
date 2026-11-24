package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpressions
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

object Alias {
    @JvmStatic
    fun `is`(call: Call): Boolean =
            call.isCalling(KERNEL, ALIAS)

    /** The module an `alias` call names, read through its parentheses: `Foo.Bar` in `alias (Foo.Bar), as: B`. */
    @RequiresReadLock
    fun firstArgument(aliasCall: Call): PsiElement? =
            aliasCall.finalArguments()?.firstOrNull()?.stripAccessExpressionAndParentheses()

    /**
     * The name an `alias` or `require` call's `as:` gives, read through its parentheses as [firstArgument] is, since
     * Elixir quotes `(B)` as `B`.
     */
    @RequiresReadLock
    fun asValue(call: Call): PsiElement? = call.keywordArgument("as")?.stripAccessExpressionAndParentheses()

    /** Which part of an enclosing `alias` call an element is in. */
    sealed interface Position {
        /** [firstArgument] is the element or holds it. */
        class InFirstArgument(val firstArgument: PsiElement) : Position

        /** [asValue] is the element or holds it. */
        class InAsValue(val asValue: PsiElement, val firstArgument: PsiElement?) : Position

        /** In the call, but in neither: a later argument, or an option that is not the `as:` that counts. */
        data object Elsewhere : Position
    }

    /**
     * Where [element] is in the `alias` call that encloses it, or `null` when the closest call around it is not an
     * `alias` call. This is the one climb from an alias to its call, which reads the call's parts as [firstArgument] and
     * [asValue] do.
     */
    @RequiresReadLock
    fun position(element: PsiElement): Position? = positionIn(element, ::`is`)

    /** [position] for the closest call around [element] when [isDirective] holds of it. */
    @RequiresReadLock
    private fun positionIn(element: PsiElement, isDirective: (Call) -> Boolean): Position? =
        PsiTreeUtil.getParentOfType(element, Call::class.java)?.takeIf(isDirective)?.let { call ->
            val firstArgument = firstArgument(call)

            firstArgument?.takeIf { it.holds(element) }?.let { Position.InFirstArgument(it) }
                ?: asValue(call)?.takeIf { it.holds(element) }?.let { Position.InAsValue(it, firstArgument) }
                ?: Position.Elsewhere
        }

    private fun PsiElement.holds(element: PsiElement): Boolean = PsiTreeUtil.isAncestor(this, element, false)

    /**
     * Returns the fully-qualified module name from the first argument of an `alias` call,
     * e.g. `"IcWeb.Router.Helpers"` for `alias IcWeb.Router.Helpers`.
     */
    @RequiresReadLock
    fun aliasTargetName(aliasCall: Call): String? =
        when (val firstArg = firstArgument(aliasCall) ?: return null) {
            is QualifiableAlias -> firstArg.fullyQualifiedName()
            else -> moduleName(firstArg)?.name
        }

    /**
     * Returns the short name introduced by an alias call -- either the explicit
     * `as:` name or the last segment of the target module.
     */
    @RequiresReadLock
    fun aliasNameIntroducedBy(aliasCall: Call): String? {
        val asValue = asValue(aliasCall)
        if (asValue != null) {
            return moduleName(asValue)?.name
        }

        return aliasTargetName(aliasCall)
            ?.split(".")
            ?.lastOrNull()
    }

    /**
     * Every short name an `alias` or `require` call introduces: [aliasNameIntroducedBy]'s one, or the last segment of
     * each member of `Foo.{A, B.C}` (`A` and `C`), which that function leaves `null`. `null` when one can't be read, as
     * with `alias unquote(module)` or a member that is no alias.
     */
    @RequiresReadLock
    fun aliasNamesIntroducedBy(aliasCall: Call): List<String>? =
        when (val module = firstArgument(aliasCall)) {
            is QualifiedMultipleAliases ->
                (module.unqualified() as? ElixirMultipleAliases)
                    ?.children
                    ?.stripAccessExpressions()
                    ?.map { aliasedName(it) ?: return null }

            else -> aliasNameIntroducedBy(aliasCall)?.let(::listOf)
        }
}
