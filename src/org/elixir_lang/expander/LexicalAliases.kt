package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call

/**
 * The aliases in lexical scope at a PSI element, read from the forms written before it as [aliasStore] classifies them,
 * without an index or the expander.
 */
internal object LexicalAliases {
    /**
     * The module [name], an alias or an atom, names through the aliases in scope at it; `null` where it names none, or
     * where those aliases aren't known. A name rooted at `Elixir.`, or an atom, is known whatever the forms before it.
     */
    @RequiresReadLock
    fun moduleNamedBy(name: PsiElement): String? {
        val level = ElixirLanguageLevelResolver.languageLevelFor(name)
        val lowered = ElementLowering.lower(name)
        val table = at(name, level) ?: AliasTable.EMPTY.takeIf { isRooted(lowered) } ?: return null

        return moduleOf(lowered, table, level)
    }

    /**
     * The aliases in scope at [element]: the forms written before it in its file and in each scope holding it, folded
     * through [storeAlias], with the alias each enclosing `defmodule` stores where it is written. `null` where a form
     * before it may store any alias, or stores one that can't be read. Inside a module whose name isn't read, such as
     * a `defimpl`'s, `__MODULE__` isn't known.
     */
    @RequiresReadLock
    private fun at(element: PsiElement, level: ElixirLanguageLevel): AliasTable? {
        val holders = element.parents(false).toSet()
        val implementations = holders.filterIsInstance<Call>().filter(Implementation::`is`)
        // Source order: an enclosing module starts after the forms written before it and before those inside it.
        val forms = (aliasingForms(element, level) + implementations).sortedBy { it.textRange.startOffset }
        var table = AliasTable.EMPTY
        // The module `__MODULE__` names, `null` at the top of the file, and whether it is read.
        var module: String? = null
        var moduleRead = true

        for (form in forms) {
            ProgressManager.checkCanceled()

            var defined: Pair<String, AliasTable>? = null
            when (aliasStore(form, level)) {
                AliasStore.NONE -> {}
                AliasStore.MAY_STORE -> return null
                AliasStore.DEFINES -> {
                    defined = (if (moduleRead) defining(form, table, module, level) else null) ?: return null
                    table = defined.second
                }
                AliasStore.STORES, AliasStore.REMOVES ->
                    table = directive(ElementLowering.lower(form) as? ElixirAst.Call ?: return null, table, level, module)
                        ?: return null
            }

            if (form in holders) {
                module = defined?.first
                moduleRead = defined != null
            }
        }

        return table
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
