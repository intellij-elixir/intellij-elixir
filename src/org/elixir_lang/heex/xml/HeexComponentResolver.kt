package org.elixir_lang.heex.xml

import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlTag
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.impl.call.stabBodyChildExpressions
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import org.elixir_lang.reference.resolver.Module as ModuleResolver

object HeexComponentResolver {
    /** Go To Declaration target: the component's name, as a call's Go To lands on. */
    @RequiresReadLock
    fun resolveDeclaration(tag: XmlTag): PsiElement? = resolveCall(tag)?.let(::declarationTarget)

    /** The declaration [tag] names, for Find Usages, Rename and Quick Docs. */
    @RequiresReadLock
    fun resolveCall(tag: XmlTag): Call? =
        CachedValuesManager.getCachedValue(tag) {
            CachedValueProvider.Result.create(doResolveCall(tag), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** The [FunctionSymbol]s of the resolved declaration - one per arity a clause with defaults declares. */
    @RequiresReadLock
    fun resolveFunctionSymbols(tag: XmlTag): List<FunctionSymbol> =
        resolveCall(tag)?.let(FunctionSymbol::fromDeclaration).orEmpty()

    /** A `<.name>` candidate: the name it is completed as and the call that defines it. */
    data class LocalComponent(val name: String, val definition: Call)

    /** The module's local-component candidates for tag-name completion. */
    @RequiresReadLock
    fun localComponents(tag: XmlTag): List<LocalComponent> {
        val module = elixirRoot(tag)?.viewFile()?.modulars()?.singleOrNull() as? Call ?: return emptyList()
        val state = ResolveState.initial()

        return module.stabBodyChildExpressions()
            ?.filterIsInstance<Call>()
            ?.filter(::isComponent)
            ?.flatMap { call ->
                CallableDeclaration.declaredOf(call, state)?.definitions(state).orEmpty()
                    .filter { it.accepts(1) }
                    .map { LocalComponent(it.name, call) }
            }
            ?.toList()
            ?: emptyList()
    }

    /** `<.name>` and `<Module.name>` compile to a call of `name(assigns)`, so only a runtime function is a component. */
    @RequiresReadLock
    private fun isComponent(element: PsiElement): Boolean =
        CallableDeclaration.capabilitiesOf(element, ResolveState.initial())?.runtimeFunction == true

    @RequiresReadLock
    private fun doResolveCall(tag: XmlTag): Call? {
        val component = ComponentTagName.parse(tag.name) ?: return null
        val elixirRoot = elixirRoot(tag) ?: return null

        return when (component) {
            is ComponentTagName.Local -> component(component.functionName, elixirRoot)
            is ComponentTagName.Remote -> resolveRemoteCall(component.aliasChain, component.functionName, elixirRoot)
            // A slot is declared by the `slot` macro, not a def/defp.
            is ComponentTagName.Slot -> null
        }
    }
    // `.originalFile` recovers the real, on-disk file (with a real parent directory to search) when
    // `tag` comes from completion's throwaway dummy-identifier copy of the file; it is a no-op
    // otherwise.
    private fun elixirRoot(tag: XmlTag): ElixirFile? =
        tag.containingFile.originalFile.viewProvider.getPsi(ElixirLanguage) as? ElixirFile

    private fun resolveRemoteCall(aliasChain: String, functionName: String, entrance: PsiElement): Call? =
        ModuleResolver
            .resolve(entrance, aliasChain, false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }
            .filter { it is Call && Stub.isModular(it) }
            .firstNotNullOfOrNull { modular -> component(functionName, modular) }

    private fun component(functionName: String, entrance: PsiElement): Call? =
        MultiResolve
            .resolveResults(functionName, 1, false, entrance)
            .filter { it.isValidResult }
            .mapNotNull { it.element as? Call }
            .firstOrNull(::isComponent)

    @RequiresReadLock
    private fun declarationTarget(call: Call): PsiElement = CallableDeclaration.nameElement(call) ?: call
}
