package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.Processor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module.NO_VALUE
import org.elixir_lang.Module.concat
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.indexName
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.reference.resolver.Module as ModuleResolver

object Implementation {
    @RequiresReadLock
    @JvmStatic
    fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun `is`(call: SyntacticCall): Boolean =
        call.isCallingMacro(org.elixir_lang.psi.call.name.Module.KERNEL, Function.DEFIMPL, 2) ||
                call.isCallingMacro(org.elixir_lang.psi.call.name.Module.KERNEL, Function.DEFIMPL, 3)

    /**
     * @return `null` if protocol or module for the implementation cannot be derived or if the `for` argument is a
     * list.
     */
    @RequiresReadLock
    fun name(call: Call): String? = name(SyntacticCall.of(call))

    @RequiresReadLock
    fun name(call: SyntacticCall): String? = nameCollection(call)?.singleOrNull()

    /** A `PROTOCOL.FOR` name for each module the implementation is for, as [forNames] gives them. */
    @RequiresReadLock
    fun nameCollection(call: SyntacticCall): Collection<String>? {
        val protocolName = protocolName(call) ?: return null

        return forNames(call)?.map { forName -> name(protocolName, forName) }
    }

    /** The `PROTOCOL.FOR` name of [protocolName]'s implementation for [forName], one of its [forNames]. */
    fun name(protocolName: String, forName: String): String = concat(protocolName, forName)

    @RequiresReadLock
    fun forNames(call: Call): Collection<String>? = forNames(SyntacticCall.of(call))

    /**
     * The modules the implementation is for: empty for `for: []`, but `null` when there is no module to name, as for
     * a top-level `defimpl` without `for:`. Without `for:`, Elixir implements the protocol for `__MODULE__`.
     */
    @RequiresReadLock
    fun forNames(call: SyntacticCall): Collection<String>? {
        val written = call.forNames() ?: listOf(Function.__MODULE__)

        return if (written.isEmpty()) {
            written
        } else {
            written
                .flatMap { forName -> CanonicallyNamedImpl.expandModuleNames(forName, call) }
                .filterNotNull()
                .takeIf { it.isNotEmpty() }
        }
    }

    @RequiresReadLock
    fun forText(call: Call): String? = forText(SyntacticCall.of(call))

    @RequiresReadLock
    fun forText(call: SyntacticCall): String? = forNames(call)?.let(::forText)

    /** [forNames] as one module, or as a list when there are more or fewer. */
    fun forText(forNames: Collection<String>): String = forNames.singleOrNull() ?: forNames.joinToString(", ", "[", "]")

    fun elementDescription(location: ElementDescriptionLocation): String? =
        if (location === UsageViewTypeLocation.INSTANCE) {
            "implementation"
        } else {
            null
        }

    private fun forNameCollection(forNameElement: ElixirAccessExpression): Collection<String> =
        forNameCollection(forNameElement.children)

    private fun forNameCollection(forNameElement: ElixirList): Collection<String> =
        forNameCollection(forNameElement.children)

    @RequiresReadLock
    fun forNameCollection(forNameElement: PsiElement): Collection<String>? = when (forNameElement) {
        is ElixirAccessExpression -> forNameCollection(forNameElement)
        is ElixirList -> forNameCollection(forNameElement)
        is QualifiableAlias -> forNameCollection(forNameElement)
        is ElixirAtom -> forNameCollection(forNameElement)
        // `__MODULE__`, which `forNames` expands; any other name, such as a variable's, has no value here.
        is PsiNamedElement -> listOf(forNameElement.name.takeIf { it == Function.__MODULE__ } ?: NO_VALUE)
        else -> listOf(NO_VALUE)
    }

    private fun forNameCollection(children: Array<PsiElement>): Collection<String> =
        children.flatMap { child -> forNameCollection(child) ?: emptyList() }

    /** The module the atom names: `:"Elixir.String"` is `String`, and `:foo` is `:foo`. */
    private fun forNameCollection(forNameElement: ElixirAtom): Collection<String>? =
        forNameElement.indexName()?.let { listOf(it) }

    private fun forNameCollection(forNameElement: QualifiableAlias): Collection<String>? =
        (moduleName(forNameElement)?.name ?: forNameElement.name)?.let { listOf(it) }

    @RequiresReadLock
    fun forNameElement(call: Call): PsiElement? =
        call.finalArguments()?.lastOrNull()?.let { it as? QuotableKeywordList }?.keywordValue(Function.FOR)

    @RequiresReadLock
    fun processProtocols(defimpl: Call, consumer: Processor<in PsiElement>) {
        val protocolName = protocolName(defimpl) ?: return

        for (protocol in ModuleResolver.resolvePreferred(defimpl, protocolName, incompleteCode = false, inScope = false)) {
            ProgressManager.checkCanceled()

            if (!consumer.process(protocol.element)) return
        }
    }

    @RequiresReadLock
    @JvmStatic
    fun protocolName(call: Call): String? = protocolName(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun protocolName(call: SyntacticCall): String? =
        call.protocolAliasText()?.let { CanonicallyNamedImpl.expandModule(it, call) }

    @RequiresReadLock
    @JvmStatic
    fun implementedProtocolName(call: SyntacticCall): String? = if (`is`(call)) protocolName(call) else null
}
