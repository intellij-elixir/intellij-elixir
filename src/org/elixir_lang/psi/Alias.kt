package org.elixir_lang.psi

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpression

object Alias {
    @JvmStatic
    fun `is`(call: Call): Boolean =
            call.isCalling(KERNEL, ALIAS)

    /**
     * Returns the fully-qualified module name from the first argument of an `alias` call,
     * e.g. `"IcWeb.Router.Helpers"` for `alias IcWeb.Router.Helpers`.
     */
    @RequiresReadLock
    fun aliasTargetName(aliasCall: Call): String? {
        val finalArgs = aliasCall.finalArguments() ?: return null
        if (finalArgs.isEmpty()) return null

        return when (val firstArg = finalArgs[0].stripAccessExpression()) {
            is QualifiableAlias -> firstArg.fullyQualifiedName()
            else -> moduleName(firstArg)?.name
        }
    }

    /**
     * Returns the short name introduced by an alias call -- either the explicit
     * `as:` name or the last segment of the target module.
     */
    @RequiresReadLock
    fun aliasNameIntroducedBy(aliasCall: Call): String? {
        val asValue = aliasCall.keywordArgument("as")?.stripAccessExpression()
        if (asValue != null) {
            return moduleName(asValue)?.name
        }

        return aliasTargetName(aliasCall)
            ?.split(".")
            ?.lastOrNull()
    }
}
