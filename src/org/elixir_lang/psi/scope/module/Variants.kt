package org.elixir_lang.psi.scope.module

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.*
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.contextOfType
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.Normalized
import org.elixir_lang.psi.scope.LookupElementByLookupName
import org.elixir_lang.psi.scope.Module
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.reference.resolver.Module as ModuleResolver

class Variants(private val entrance: PsiElement) : Module() {
    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.execute] methods
     * eventually end here.
     *
     * @param match
     * @param aliasedName
     * @param state
     * @return `true` to keep processing; `false` to stop processing.
     */
    override fun executeOnAliasedName(match: PsiNamedElement, aliasedName: String, state: ResolveState): Boolean {
        lookupElementByLookupName.put(aliasedName, match)

        val splitPrefix = org.elixir_lang.Module.split(aliasedName)
        putNestedAliased(lookupElementByLookupName, entrance, splitPrefix, match)

        return true
    }

    override fun executeOnModularName(modular: Named, modularName: String, state: ResolveState): Boolean =
        executeOnAliasedName(modular, modularName, state)

    private val lookupElementByLookupName: LookupElementByLookupName = LookupElementByLookupName()

    /**
     * Puts all `aliases` in scope from `entrance` and any modules nested under those modules in
     * `lookupElementByLookupName`.
     */
    private fun putAliases(): Variants {
        PsiTreeUtil.treeWalkUp(
                this,
                entrance,
                entrance.containingFile,
                ResolveState.initial().put(ElixirPsiImplUtil.ENTRANCE, entrance).putInitialVisitedElement(entrance)
        )

        return this
    }

    /**
     * Puts all project `Alias`es.
     */
    private fun putProject(): Variants {
        for (name in modularNames(entrance)) {
            if (!lookupElementByLookupName.contains(name)) {
                preferredNamedElement(entrance, name)?.let { lookupElementByLookupName.put(name, it) }
            }
        }

        return this
    }

    private fun lookupElements(): Collection<LookupElement> = lookupElementByLookupName.lookupElements()

    companion object {
        fun lookupElements(entrance: QualifiableAlias): Collection<LookupElement> =
                entrance
                        .contextOfType<ElixirMultipleAliases>()
                        ?.let { multipleAliases ->
                            multipleAliases.parent.let { it as QualifiedMultipleAliases }.qualifier()?.let { it as? QualifiableAlias }?.let { qualifier ->
                                relativeLookupElements(qualifier)
                            }
                        }
                        ?:
                        entrance
                                .qualifier()
                                // if there is a qualifier, then it is only modules nested under `qualifier`'s alias or it's fully
                                // qualified name if unaliased that are valid variants because the completions are after a `.`
                                ?.let { qualifier -> filteredLookupElements(qualifier) }
                        ?:
                        // if there is no qualifier then all aliases in the file and all project names are valid
                        unfilteredLookupElements(entrance)

        /**
         * Any modules nested under `qualifier` with `qualifier` stripped off the final names.
         */
        private fun relativeLookupElements(qualifier: QualifiableAlias): Collection<LookupElement> =
                qualifier
                        .maybeModularNameToModulars(qualifier.containingFile, useCall = null, incompleteCode = false)
                        .takeIf { it.isNotEmpty() }
                        ?.let { modularsRelativeLookupElements(qualifier, it) }
                        ?:
                        // The qualifier is an Alias to namespace that is shared, but never declared in an explicit modular
                        namespacesRelativeLookupElements(
                            qualifier,
                            setOf(moduleName(qualifier)?.takeIf { it.absolute }?.name ?: qualifier.fullyQualifiedName())
                        )

        /**
         * Any modules under `modulars` with each `modular` stripped off the final names for the respective nested one
         */
        private fun modularsRelativeLookupElements(entrance: PsiElement, modulars: Set<PsiNamedElement>): Collection<LookupElement> =
                modulars
                        .asSequence()
                        .filterIsInstance<CanonicallyNamed>()
                        .flatMap { it.canonicalNameSet().asSequence() }
                        .toSet()
                        .let { namespacesRelativeLookupElements(entrance, it) }

        /**
         * Any modules under the `namespace`, with the namespace stripped of the final names.
         */
        private fun namespacesRelativeLookupElements(entrance: PsiElement, namespaces: Set<String>): Collection<LookupElement> =
            relativeLookupElements(entrance, namespaces.map { namespace -> org.elixir_lang.Module.split(namespace) })

        private fun relativeLookupElements(entrance: PsiElement, splitNamespaces: List<List<String>>): Collection<LookupElement> {
            val lookupElementByLookupName = LookupElementByLookupName()

            for (name in modularNames(entrance)) {
                val splitName = org.elixir_lang.Module.split(name)

                for (splitNamespace in splitNamespaces) {
                    val splitRelativeName = org.elixir_lang.Module.relative(
                            ancestors = splitNamespace,
                            descendants = splitName
                    )

                    if (splitRelativeName.isNotEmpty()) {
                        val aliasedNestedName = org.elixir_lang.Module.concat(splitRelativeName)

                        if (!lookupElementByLookupName.contains(aliasedNestedName)) {
                            preferredNamedElement(entrance, name)?.let { lookupElementByLookupName.put(aliasedNestedName, it) }
                        }
                    }
                }
            }

            return lookupElementByLookupName.lookupElements()
        }

        /**
         * Any modules nested under `qualifier`
         */
        private fun filteredLookupElements(qualifier: PsiElement): Collection<LookupElement> =
            qualifier
                .reference
                ?.let { it as? PsiPolyVariantReference }
                ?.let { qualifierReference ->
                    val lookupElementByLookupName = LookupElementByLookupName()

                    val resolvedElements = qualifierReference
                        .multiResolve(false)
                        .filter(ResolveResult::isValidResult)
                        .mapNotNull(ResolveResult::getElement)

                    val resolvedModulars =
                        resolvedElements.filterIsInstance<Call>().filter { Stub.isModular(it) }

                    val resolveds = resolvedModulars.ifEmpty { resolvedElements }

                    for (resolved in resolveds) {
                        putNestedAliased(
                            lookupElementByLookupName,
                            qualifier,
                            emptyList(),
                            resolved as PsiNamedElement
                        )
                    }

                    lookupElementByLookupName.lookupElements()
                }
                ?:
                // if the qualifier can't be resolved to an `alias` or a modular, then we can't find the nested
                // modulars.
                emptyList()

        /**
         * The names of the project's modulars, read out whole because [preferredNamedElement] reads the index for each
         * and a stub index read must not nest under another.
         */
        private fun modularNames(entrance: PsiElement): Collection<String> =
            StubIndex.getInstance().getAllKeys(ModularName.KEY, entrance.project)

        /**
         * The first of the modulars named `name` that [ModuleResolver.resolvePreferred] returns from `entrance`.
         */
        private fun preferredNamedElement(entrance: PsiElement, name: String): PsiNamedElement? =
            ModuleResolver
                .resolvePreferred(entrance, name, incompleteCode = false, inScope = false)
                .firstNotNullOfOrNull { it.element as? PsiNamedElement }

        private fun putNestedAliased(lookupElementByLookupName: LookupElementByLookupName, entrance: PsiElement, splitPrefix: List<String>, aliasedElement: PsiNamedElement) {
            UnaliasedName.unaliasedName(aliasedElement)?.let { unaliasedName ->
                val splitUnaliasedName = org.elixir_lang.Module.split(unaliasedName)

                for (name in modularNames(entrance)) {
                    val splitRelativeName = org.elixir_lang.Module.relative(ancestors = splitUnaliasedName, descendant = name)

                    if (splitRelativeName.isNotEmpty()) {
                        val aliasedNestedName = org.elixir_lang.Module.concat(splitPrefix + splitRelativeName)

                        if (!lookupElementByLookupName.contains(aliasedNestedName)) {
                            preferredNamedElement(entrance, name)?.let { lookupElementByLookupName.put(aliasedNestedName, it) }
                        }
                    }
                }
            }
        }

        /**
         * * All `alias`es in scope from `entrance` and any modules nested under those modules.
         * * All project `Alias`es.
         */
        private fun unfilteredLookupElements(entrance: PsiElement): Collection<LookupElement> =
                Variants(entrance)
                        .putAliases()
                        .putProject()
                        .lookupElements()
    }
}

private fun QualifiedMultipleAliases.qualifier(): PsiElement? {
    val children = this.children
    val operatorIndex = Normalized.operatorIndex(children)

    return org.elixir_lang.psi.operation.infix.Normalized.leftOperand(children, operatorIndex)?.stripAccessExpression()
}
