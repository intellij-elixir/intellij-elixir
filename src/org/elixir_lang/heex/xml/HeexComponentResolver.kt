package org.elixir_lang.heex.xml

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.reference.resolver.Module as ModuleResolver

/**
 * Resolves a `<.button>` / `<MyAppWeb.CoreComponents.button>` component tag to the `def`/`defp`
 * it names, as a call of one argument is resolved ([Use.Tag]) and through module resolution
 * (`reference.resolver.Module`). Local components enter through the view provider's Elixir root,
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

    /** The [FunctionSymbol]s of the resolved clause - one per arity a clause with defaults declares. */
    fun resolveFunctionSymbols(tag: XmlTag): List<FunctionSymbol> =
        resolveCall(tag)?.takeIf(CallDefinitionClause::`is`)?.let(FunctionSymbol::fromClause).orEmpty()
    /** The module's arity-1 call definitions, local-component candidates for tag-name completion. */
    @RequiresReadLock
    fun localComponentDefinitions(tag: XmlTag): List<Call> {
        val module = elixirRoot(tag)?.viewFile()?.modulars()?.singleOrNull() as? Call ?: return emptyList()

        return CallDefinitionClause.modularChildCalls(module)
            .filter { CallDefinitionClause.capabilities(it)?.runtimeFunction == true }
            .filter { call -> arity1(call) }
    }

    private fun arity1(call: Call): Boolean =
        CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.arityInterval?.let { 1 in it } == true

    private fun doResolveCall(tag: XmlTag): Call? {
        val component = ComponentTagName.parse(tag.name) ?: return null
        val elixirRoot = elixirRoot(tag) ?: return null

        return when (component) {
            is ComponentTagName.Local -> resolveLocalCall(tag, atomName(component.functionName, elixirRoot), elixirRoot)
            is ComponentTagName.Remote ->
                resolveRemoteCall(tag, component.aliasChain, atomName(component.functionName, elixirRoot), elixirRoot)
            // A slot is declared by the `slot` macro, not a def/defp.
            is ComponentTagName.Slot -> null
        }
    }
    // `.originalFile` recovers the real, on-disk file (with a real parent directory to search) when
    // `tag` comes from completion's throwaway dummy-identifier copy of the file; it is a no-op
    // otherwise.
    private fun elixirRoot(tag: XmlTag): ElixirFile? =
        tag.containingFile.originalFile.viewProvider.getPsi(ElixirLanguage) as? ElixirFile

    private fun atomName(functionName: String, elixirRoot: ElixirFile): String =
        identifierAtomName(functionName) { languageLevelFor(elixirRoot) }

    private fun resolveLocalCall(tag: XmlTag, functionName: String, entrance: PsiElement): Call? =
        target(Use.tag(tag, entrance, functionName))?.let(::source)

    private fun resolveRemoteCall(tag: XmlTag, aliasChain: String, functionName: String, entrance: PsiElement): Call? =
        ModuleResolver
            .resolve(entrance, aliasChain, false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }
            .filter { it is Call && Stub.isModular(it) }
            .firstNotNullOfOrNull { modular -> target(Use.tag(tag, modular, functionName)) }
            ?.let(::source)

    /** What [use] resolves to, as a call of one argument at the same place does. */
    private fun target(use: Use.Tag): Found? {
        val projection = Resolved.projection(use, false, feature = Feature.HEEX_COMPONENTS)
        val target = Resolved.target(Resolved.results(projection)) ?: return null

        return projection.first { it.element == target }
    }

    /** The call that declares [found], which a compiled definition is not. */
    private fun source(found: Found): Call? =
        when (found.candidate.declaration.declared) {
            is Declared.Source -> found.element as Call
            is Declared.Compiled -> null
        }

    private fun declarationTarget(element: PsiElement): PsiElement =
        (element as? Call)
            ?.takeIf { CallDefinitionClause.capabilities(it)?.runtimeFunction == true }
            ?.let { CallDefinitionClause.nameIdentifier(it) }
            ?: element
}
