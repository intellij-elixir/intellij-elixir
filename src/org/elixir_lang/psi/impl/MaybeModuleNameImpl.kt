package org.elixir_lang.psi.impl

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.SyntacticCall

object MaybeModuleNameImpl {
    /*
     * Whether this is an argument in `defmodule <argument> do end` call.
     */
    @JvmStatic
    fun isModuleName(accessExpression: ElixirAccessExpression): Boolean {
        val parent = accessExpression.parent
        var isModuleName = false

        if (parent is ElixirNoParenthesesOneArgument) {

            isModuleName = parent.isModuleName
        }

        return isModuleName
    }

    @JvmStatic
    fun isModuleName(qualifiableAlias: QualifiableAlias): Boolean {
        val parent = qualifiableAlias.parent
        val siblingCount = parent.children.size - 1
        var isModuleName = false

        /* check that this qualifiableAlias is the only child so subsections of alias chains don't say they are module
           names. */
        if (siblingCount == 0 && parent is MaybeModuleName) {

            isModuleName = parent.isModuleName
        }

        return isModuleName
    }

    /*
     * Whether this is an argument in `defmodule <argument> do end` call.
     */
    @RequiresReadLock
    @JvmStatic
    fun isModuleName(noParenthesesOneArgument: ElixirNoParenthesesOneArgument): Boolean {
        val parent = noParenthesesOneArgument.parent
        var isModuleName = false

        if (parent is ElixirUnmatchedUnqualifiedNoParenthesesCall) {

            isModuleName = Module.isDefmodule(SyntacticCall.of(parent))
        }

        return isModuleName
    }
}
