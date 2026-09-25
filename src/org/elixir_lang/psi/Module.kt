package org.elixir_lang.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.Contract

object Module {
    @RequiresReadLock
    @JvmStatic
    fun `is`(call: Call): Boolean =
            (call.isCallingMacro(Module.KERNEL, Function.DEFMODULE, 2) &&
                    /**
                     * See https://github.com/intellij-elixir/intellij-elixir/issues/1301
                     *
                     * Check that the this is not the redefinition of defmodule in distillery
                     */
                    ApplicationManager
                            .getApplication()
                            .runReadAction(Computable { !CallableDeclaration.isHead(call) })) ||
                    call.isCalling(Module.MODULE, Function.CREATE, 3)

    @RequiresReadLock
    @Contract(pure = true)
    fun name(call: Call): String = call.primaryArguments()!!.first()!!.text

    /** [name], or `null` for a module call written without one. */
    @RequiresReadLock
    fun nameOrNull(call: Call): String? = call.primaryArguments()?.firstOrNull()?.text
}
