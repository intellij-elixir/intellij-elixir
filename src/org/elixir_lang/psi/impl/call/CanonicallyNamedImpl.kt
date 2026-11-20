package org.elixir_lang.psi.impl.call

import org.elixir_lang.Module.NO_VALUE
import org.elixir_lang.Module.nest
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
                val canonicalNameSuffix = moduleName?.name ?: NO_VALUE

                when {
                    isModuleRelative(canonicalNameSuffix) -> expandModule(canonicalNameSuffix, call) ?: NO_VALUE
                    moduleName?.absolute == true -> moduleName.name
                    else -> enclosingModuleName(call)?.let { nest(it, canonicalNameSuffix) } ?: canonicalNameSuffix
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
                Implementation.nameCollection(call)?.takeIf { it.isNotEmpty() }?.toSet()
                    ?: setOf("${Implementation.protocolName(call) ?: NO_VALUE}.$NO_VALUE")
            } else {
                val moduleName = moduleName(call)
                val canonicalNameSuffix = moduleName?.name ?: NO_VALUE

                when {
                    isModuleRelative(canonicalNameSuffix) ->
                        expandModuleNames(canonicalNameSuffix, call).mapTo(mutableSetOf()) { it ?: NO_VALUE }

                    moduleName?.absolute == true -> setOf(moduleName.name)
                    else -> enclosingModuleNames(call)
                        ?.mapTo(mutableSetOf()) { nest(it, canonicalNameSuffix) }
                        ?: setOf(canonicalNameSuffix)
                }
            }
        } else {
            call.name()?.let { setOf(it) } ?: emptySet()
        }

    /** At the top level `__MODULE__` is `nil`, which Elixir drops from an alias. */
    @RequiresReadLock
    fun expandModule(name: String, call: SyntacticCall): String? = expandModule(name) { enclosingModuleName(call) }

    /**
     * [name] with `__MODULE__` expanded at [call], once for each name [enclosingModuleNames] gives, so none in a
     * `defimpl` for `[]`; at the top level as [expandModule] gives it.
     */
    @RequiresReadLock
    fun expandModuleNames(name: String, call: SyntacticCall): Set<String?> =
        if (isModuleRelative(name)) {
            (enclosingModuleNames(call) ?: setOf(null)).mapTo(mutableSetOf()) { expandModule(name) { it } }
        } else {
            setOf(name)
        }

    private fun expandModule(name: String, module: () -> String?): String? =
        if (isModuleRelative(name)) {
            val relative = name.removePrefix(__MODULE__).removePrefix(".")

            module()?.let { if (relative.isEmpty()) it else nest(it, relative) } ?: relative.ifEmpty { null }
        } else {
            name
        }

    /**
     * The module a module or protocol [call] names, read without expansion except for an `unquote`; `null` for any
     * other [call], or when its first argument names no module. An absolute name is not nested in the module around it.
     */
    @RequiresReadLock
    private fun moduleName(call: SyntacticCall): ModuleName? =
        if (Module.`is`(call) || Protocol.`is`(call)) {
            call.firstPrimaryArgumentUnquote()?.let(::unquotedModuleName) ?: Module.moduleName(call)
        } else {
            null
        }

    /** The `unquote` gives an atom, which Elixir does not nest. */
    private fun unquotedModuleName(unquoted: SyntacticCall.Unquoted): ModuleName =
        ModuleName(unquoted.name?.let { expandModule(it) { nameOf(unquoted.runsIn) } } ?: NO_VALUE, absolute = true)

    private fun isModuleRelative(name: String): Boolean = name == __MODULE__ || name.startsWith("$__MODULE__.")

    private fun enclosingModuleName(call: SyntacticCall): String? = nameOf(call.enclosingQuoteOrModular())

    /**
     * What `__MODULE__` is at [call]: as [namesOf] gives it, one name for each module a `defimpl` around it defines,
     * and `null` at the top level.
     */
    @RequiresReadLock
    private fun enclosingModuleNames(call: SyntacticCall): Set<String>? = call.enclosingQuoteOrModular()?.let(::namesOf)

    /** What `__MODULE__` is in [moduleOrQuote]: unknown in a `quote`, which leaves it to the module it is injected into. */
    private fun nameOf(moduleOrQuote: SyntacticCall?): String? =
        moduleOrQuote?.let { if (QuoteMacro.`is`(it)) NO_VALUE else it.canonicalName() ?: NO_VALUE }

    /**
     * The names [moduleOrQuote] gives the code in it: as [nameOf], one for each module a `defimpl` defines, so none for
     * `for: []`.
     */
    @RequiresReadLock
    fun namesOf(moduleOrQuote: SyntacticCall): Set<String> =
        when {
            QuoteMacro.`is`(moduleOrQuote) -> setOf(NO_VALUE)
            Implementation.`is`(moduleOrQuote) ->
                Implementation.nameCollection(moduleOrQuote)?.toSet() ?: moduleOrQuote.canonicalNameSet()

            else -> moduleOrQuote.canonicalNameSet()
        }
}
