package org.elixir_lang.psi

import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.ModuleName
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.Contract

object Module {
    @RequiresReadLock
    @JvmStatic
    fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun `is`(call: SyntacticCall): Boolean =
            (call.isCallingMacro(Module.KERNEL, Function.DEFMODULE, 2) &&
                    /**
                     * See https://github.com/intellij-elixir/intellij-elixir/issues/1301
                     *
                     * Check that the this is not the redefinition of defmodule in distillery
                     */
                    call.argumentOf()?.let { CallDefinitionClause.capabilities(it)?.quotesArguments } != true) ||
                    isCreate(call)

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
