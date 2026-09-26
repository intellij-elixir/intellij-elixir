package org.elixir_lang.heex.xml

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlTag
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import org.elixir_lang.reference.resolver.Module as ModuleResolver

/**
 * Resolves a `<.button>` / `<MyAppWeb.CoreComponents.button>` component tag to the `def`/`defp`
 * it names, through the existing call-definition and module resolution (`MultiResolve`,
 * `reference.resolver.Module`). Local components enter through the view provider's Elixir root,
 * whose `getContext()` is the surrounding view module ([ElixirFile.viewFile]) or, inside a `~H`
 * sigil, the injection host.
 */object HeexComponentResolver {
    /** Go To Declaration target: the def's name identifier, like a regular Elixir call site. */
    fun resolveDeclaration(tag: XmlTag): PsiElement? = resolveCall(tag)?.let(::declarationTarget)

    /** The resolved `def`/`defp` clause, for Find Usages, Rename and Quick Docs. */
    fun resolveCall(tag: XmlTag): Call? =
        CachedValuesManager.getCachedValue(tag) {
            CachedValueProvider.Result.create(doResolveCall(tag), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** The [FunctionSymbol]s of the resolved declaration - one per arity a clause with defaults declares. */
    fun resolveFunctionSymbols(tag: XmlTag): List<FunctionSymbol> =
        resolveCall(tag)?.let(FunctionSymbol::fromDeclaration).orEmpty()
    /** A `<.name>` candidate: the name it is completed as and the call that defines it. */
    data class LocalComponent(val name: String, val definition: Call)

    /**
     * The module's local-component candidates for tag-name completion. `<.name>` compiles to the local call
     * `name(assigns)`, so a candidate is any arity-1 runtime function, whichever form defines it.
     */
    fun localComponents(tag: XmlTag): List<LocalComponent> {
        val module = elixirRoot(tag)?.viewFile()?.modulars()?.singleOrNull() as? Call ?: return emptyList()
        val state = ResolveState.initial()

        return org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(module)
            .flatMap { call ->
                CallableDeclaration.declaredOf(call, state)
                    ?.takeIf { it.capabilities?.runtimeFunction == true }
                    ?.definitions(state)
                    ?.filter { it.accepts(1) }
                    ?.map { LocalComponent(it.name, call) }
                    .orEmpty()
            }
    }

    private fun doResolveCall(tag: XmlTag): Call? {
        val component = ComponentTagName.parse(tag.name) ?: return null
        val elixirRoot = elixirRoot(tag) ?: return null

        return when (component) {
            is ComponentTagName.Local -> resolveLocalCall(component.functionName, elixirRoot)
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

    private fun resolveLocalCall(functionName: String, entrance: PsiElement): Call? =
        MultiResolve
            .resolveResults(functionName, 1, false, entrance)
            .firstOrNull { it.isValidResult }
            ?.element as? Call

    private fun resolveRemoteCall(aliasChain: String, functionName: String, entrance: PsiElement): Call? =
        ModuleResolver
            .resolve(entrance, aliasChain, false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }
            .filter(::isModular)
            .firstNotNullOfOrNull { modular ->
                MultiResolve.resolveResults(functionName, 1, false, modular).firstOrNull { it.isValidResult }?.element
            } as? Call

    private fun isModular(element: PsiElement): Boolean =
        element is Call && (Module.`is`(element) || Protocol.`is`(element) || Implementation.`is`(element))

    private fun declarationTarget(element: PsiElement): PsiElement =
        (element as? Call)?.let { CallableDeclaration.nameElement(it) } ?: element
}
