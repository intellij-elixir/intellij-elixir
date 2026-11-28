package org.elixir_lang.psi

import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.ModuleName
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.Contract

object Module {
    @RequiresReadLock
    @JvmStatic
    fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun `is`(call: SyntacticCall): Boolean = isDefmodule(call) || isCreate(call)

    @RequiresReadLock
    fun isDefmodule(call: Call): Boolean = isDefmodule(SyntacticCall.of(call))

    /** Whether [call] is `Kernel.defmodule/2`, the one of [is]'s calls that aliases its name's first segment. */
    @RequiresReadLock
    fun isDefmodule(call: SyntacticCall): Boolean =
            call.isCalling(Module.KERNEL, Function.DEFMODULE, 2) && isDefiningClause(call)

    /**
     * Whether [call], a `defmodule/2`, `defprotocol/2` or `defimpl`, defines its module: it has a `do`, as Elixir's one
     * clause of `defmodule` and of `defprotocol` takes, and [call] is no argument its caller quotes.
     */
    @RequiresReadLock
    fun isDefiningClause(call: SyntacticCall): Boolean = call.hasDoBlockOrKeyword() && !isQuotedArgument(call)

    /**
     * Whether [call] is an argument its caller quotes, as the head of distillery's `defmacro defmodule` is, and so no
     * call (https://github.com/intellij-elixir/intellij-elixir/issues/1301).
     */
    @RequiresReadLock
    private fun isQuotedArgument(call: SyntacticCall): Boolean =
        call.argumentOf()?.let { CallDefinitionClause.capabilities(it)?.quotesArguments } == true

    /** The innermost of [is]'s calls holding [element], [element] itself included. */
    @RequiresReadLock
    fun enclosing(element: PsiElement): Call? = element.parents(true).filterIsInstance<Call>().firstOrNull { `is`(it) }

    @RequiresReadLock
    fun isCreate(call: SyntacticCall): Boolean = call.isCalling(Module.MODULE, Function.CREATE, 3)

    @RequiresReadLock
    @Contract(pure = true)
    fun name(call: Call): String? = name(SyntacticCall.of(call))

    /** [moduleName]'s name, or the first argument's text when it quotes to no module name; `null` with no argument. */
    @RequiresReadLock
    @Contract(pure = true)
    fun name(call: SyntacticCall): String? = moduleName(call)?.name ?: call.firstPrimaryArgumentText()

    /**
     * The module the first argument names, read without expansion. `Module.create/3` takes an atom, which Elixir does
     * not nest.
     */
    @RequiresReadLock
    fun moduleName(call: SyntacticCall): ModuleName? =
        call.firstPrimaryArgumentModuleName()?.let { if (isCreate(call)) it.copy(absolute = true) else it }
}
