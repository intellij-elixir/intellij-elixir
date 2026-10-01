package org.elixir_lang.psi.impl.call

import org.elixir_lang.Module.NO_VALUE
import org.elixir_lang.Module.nest
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.__MODULE__
import org.elixir_lang.psi.impl.ModuleName
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.stub.type.call.Stub.isModular

object CanonicallyNamedImpl {
    @RequiresReadLock
    fun canonicalName(stubBased: StubBased<*>): String? = canonicalName(SyntacticCall.of(stubBased))

    /** Elixir does not nest an implementation in the module around it, as it does a module or protocol. */
    @RequiresReadLock
    fun canonicalName(call: SyntacticCall): String? =
        if (isModular(call)) {
            if (Implementation.`is`(call)) {
                Implementation.name(call)
                    ?: "${Implementation.protocolName(call) ?: NO_VALUE}.${Implementation.forText(call) ?: NO_VALUE}"
            } else {
                val moduleName = moduleName(call)
                val canonicalNameSuffix = moduleName?.name
                val enclosing = enclosingModularMacroCall(call)

                moduleName?.takeIf { it.absolute }?.name ?: if (canonicalNameSuffix != null && isModuleRelative(canonicalNameSuffix)) {
                    expandModule(canonicalNameSuffix, call) ?: NO_VALUE
                } else if (enclosing != null) {
                    nest(enclosing.canonicalName() ?: NO_VALUE, canonicalNameSuffix ?: NO_VALUE)
                } else {
                    canonicalNameSuffix ?: NO_VALUE
                }
            }
        } else {
            call.name()
        }

    @RequiresReadLock
    fun canonicalNameSet(stubBased: StubBased<*>): Set<String> = canonicalNameSet(SyntacticCall.of(stubBased))

    @RequiresReadLock
    fun canonicalNameSet(call: SyntacticCall): Set<String> =
        if (isModular(call)) {
            if (Implementation.`is`(call)) {
                Implementation.nameCollection(call)?.toSet()
                    ?: setOf("${Implementation.protocolName(call) ?: NO_VALUE}.$NO_VALUE")
            } else {
                val moduleName = moduleName(call)
                val canonicalNameSuffix = moduleName?.name ?: NO_VALUE

                if (moduleName?.absolute == true) {
                    setOf(moduleName.name)
                } else if (isModuleRelative(canonicalNameSuffix)) {
                    setOf(expandModule(canonicalNameSuffix, call) ?: NO_VALUE)
                } else {
                    enclosingModularMacroCall(call)
                        ?.canonicalNameSet()
                        ?.map { canonicalNamePrefix -> nest(canonicalNamePrefix, canonicalNameSuffix) }
                        ?.toSet()
                        ?: setOf(canonicalNameSuffix)
                }
            }
        } else {
            call.name()?.let { setOf(it) } ?: emptySet()
        }

    /** At the top level `__MODULE__` is `nil`, which Elixir drops from an alias. */
    @RequiresReadLock
    fun expandModule(name: String, call: SyntacticCall): String? =
        if (isModuleRelative(name)) {
            val relative = name.removePrefix(__MODULE__).removePrefix(".")

            enclosingModuleName(call)?.let { if (relative.isEmpty()) it else nest(it, relative) } ?: relative.ifEmpty { null }
        } else {
            name
        }

    /**
     * The module a module or protocol [call] names, read without expansion; `null` for any other [call], or when its
     * first argument names no module. An absolute name is not nested in the module around it.
     */
    @RequiresReadLock
    fun moduleName(call: SyntacticCall): ModuleName? =
        if (Module.`is`(call) || Protocol.`is`(call)) Module.moduleName(call) else null

    private fun isModuleRelative(name: String): Boolean = name == __MODULE__ || name.startsWith("$__MODULE__.")

    /** Inside a `quote`, `__MODULE__` is the module the quote is injected into. */
    private fun enclosingModuleName(call: SyntacticCall): String? {
        var enclosing = enclosingModularMacroCall(call)

        while (enclosing != null) {
            when {
                QuoteMacro.`is`(enclosing) -> return NO_VALUE
                isModular(enclosing) -> return enclosing.canonicalName() ?: NO_VALUE
            }

            enclosing = enclosingModularMacroCall(enclosing)
        }

        return null
    }
}
