package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirMultipleAliases
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.QualifiedMultipleAliases
import org.elixir_lang.psi.call.Call

/**
 * The aliases in lexical scope at a PSI element, read from the forms written before it as [aliasStore] classifies them,
 * without an index or the expander.
 */
internal object LexicalAliases {
    /** The [module] a name names, and whether every form before it was [known]. */
    class Named(val module: String, val known: Boolean)

    /**
     * The module [name], an alias or an atom, names through the aliases in scope at it; `null` where it names none, or
     * where those aliases aren't known.
     */
    @RequiresReadLock
    fun moduleNamedBy(name: PsiElement): String? = named(name)?.takeIf(Named::known)?.module

    /**
     * The module [name], an alias, an atom or a member of a multi-alias, names through the aliases in scope at it, read
     * as [at] folds them where a form before it isn't known, and `__MODULE__` as the module around it; `null` where it
     * names none. A name rooted at `Elixir.`, or an atom, is known whatever the forms before it.
     */
    @RequiresReadLock
    fun named(name: PsiElement): Named? {
        val level = ElixirLanguageLevelResolver.languageLevelFor(name)
        val scope = at(name, level)
        val lowered = ElementLowering.lower(name)
        val module = memberOf(name, scope, level) ?: moduleOf(lowered, scope.table, level, scope.module) ?: return null

        return Named(module, scope.known || isRooted(lowered))
    }

    /**
     * The module [name] names as a member of a multi-alias, `Sub` in `Inner.{Sub}`: its base through [scope], then the
     * member as written; `null` for any other name.
     */
    private fun memberOf(name: PsiElement, scope: Scope, level: ElixirLanguageLevel): String? {
        val members = PsiTreeUtil.skipParentsOfType(name, ElixirAccessExpression::class.java) as? ElixirMultipleAliases
            ?: return null
        val multi = PsiTreeUtil.skipParentsOfType(members, ElixirAccessExpression::class.java) as? QualifiedMultipleAliases
            ?: return null
        val index = members.children.indexOf(PsiTreeUtil.findPrevParent(members, name))

        return (ElementLowering.lower(multi) as? ElixirAst.Call)?.let { multiAliased(it, scope.table, level, scope.module) }
            ?.getOrNull(index)
    }

    /**
     * The aliases in scope at an element, whether every form before it was [known], and the [module] `__MODULE__` names
     * there, `null` where it isn't read.
     */
    private class Scope(val table: AliasTable, val known: Boolean, val module: String?)

    /**
     * The aliases in scope at [element]: the forms written before it in its file and in each scope holding it, folded
     * through [storeAlias], with the alias each enclosing `defmodule` stores where it is written. A form that may store
     * any alias, or stores one that can't be read, is read as storing none, and the aliases aren't known. Inside a
     * module whose name isn't read, such as a `defimpl`'s, `__MODULE__` isn't known.
     */
    @RequiresReadLock
    private fun at(element: PsiElement, level: ElixirLanguageLevel): Scope {
        val holders = element.parents(false).toSet()
        val implementations = holders.filterIsInstance<Call>().filter(Implementation::`is`)
        // Source order: an enclosing module starts after the forms written before it and before those inside it.
        val forms = (aliasingForms(element, level) + implementations).sortedBy { it.textRange.startOffset }
        var table = AliasTable.EMPTY
        // The module `__MODULE__` names, `null` at the top of the file, and whether it is read.
        var module: String? = null
        var moduleRead = true
        var known = true

        for (form in forms) {
            ProgressManager.checkCanceled()

            val store = aliasStore(form, level)
            val defined = if (store == AliasStore.DEFINES && moduleRead) defining(form, table, module, level) else null
            val after = when (store) {
                AliasStore.NONE -> table
                AliasStore.MAY_STORE -> null
                AliasStore.DEFINES -> defined?.second
                AliasStore.STORES, AliasStore.REMOVES ->
                    (ElementLowering.lower(form) as? ElixirAst.Call)?.let { directive(it, table, level, module) }
            }

            if (after == null) known = false else table = after
            if (form in holders) {
                module = defined?.first
                moduleRead = defined != null
            }
        }

        return Scope(table, known, module)
    }

    /**
     * The module the `defmodule` or `defprotocol` [call] defines inside the module [enclosing], and [table] after the
     * directive it expands where it is written; `null` where its name can't be read. A name through `__MODULE__` is
     * read as [enclosing], as [moduleOf] reads it.
     */
    private fun defining(
        call: Call,
        table: AliasTable,
        enclosing: String?,
        level: ElixirLanguageLevel,
    ): Pair<String, AliasTable>? {
        val name = ElementLowering.lower(Alias.firstArgument(call) ?: return null)
        val expanded = moduleOf(name, table, level, enclosing) ?: return null
        val defined = aliasDefmodule(name, expanded, enclosing) ?: return null

        return directive(definingDirective(name, defined, enclosing, level), table, level, enclosing)
            ?.let { defined.full to it }
    }

    /**
     * [table] after the `alias`, `require` or `import` [call] in the module [enclosing], as [storedBy] reads it; `null`
     * where it reads none.
     */
    private fun directive(
        call: ElixirAst.Call,
        table: AliasTable,
        level: ElixirLanguageLevel,
        enclosing: String?,
    ): AliasTable? {
        val directive = (call.callee as? ElixirAst.Literal.Atom)?.name?.let(Directive::of) ?: return null

        return when (val stored = storedBy(directive, call.arguments ?: return null, table, level, enclosing)) {
            is AliasStored.Stored -> stored.table
            AliasStored.Untouched -> table
            is AliasStored.Error, null -> null
        }
    }
}
