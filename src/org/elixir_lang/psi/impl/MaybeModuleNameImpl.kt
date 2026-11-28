package org.elixir_lang.psi.impl

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*

object MaybeModuleNameImpl {
    /*
     * Whether this is an argument in `defmodule <argument> do end` call.
     */
    @JvmStatic
    fun isModuleName(accessExpression: ElixirAccessExpression): Boolean =
        accessExpression.parent.let { it is ElixirNoParenthesesOneArgument && it.isModuleName }

    @JvmStatic
    fun isModuleName(qualifiableAlias: QualifiableAlias): Boolean =
        qualifiableAlias.parent.let { parent ->
            /* check that this qualifiableAlias is the only child so subsections of alias chains don't say they are module
               names. */
            parent.children.size == 1 && parent is MaybeModuleName && parent.isModuleName
        }

    /*
     * Whether this is an argument in `defmodule <argument> do end` call.
     */
    @RequiresReadLock
    @JvmStatic
    fun isModuleName(noParenthesesOneArgument: ElixirNoParenthesesOneArgument): Boolean =
        noParenthesesOneArgument.parent.let { it is ElixirUnmatchedUnqualifiedNoParenthesesCall && Module.isDefmodule(it) }
}
