package org.elixir_lang.psi.scope.module

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.Module.concat
import org.elixir_lang.Module.split
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.scope.Module
import org.elixir_lang.psi.scope.ResolveResultOrderedSet
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.maxScope
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.reference.resolver.Module as ModuleResolver

class MultiResolve internal constructor(private val name: String, private val incompleteCode: Boolean) : Module() {
    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.execute] methods
     * eventually end here.
     *
     * @return `true` to keep processing; `false` to stop processing.
     */
    override fun executeOnAliasedName(match: PsiNamedElement,
                                      aliasedName: String,
                                      state: ResolveState): Boolean {
        if (aliasedName == name) {
            // `require Foo, as: M` serves `M` as `alias Foo, as: M` does, as well as the `require` call itself
            state.get(REQUIRE_CALL)?.let {
                resolveResultOrderedSet.add(match, it.text, true, state.visitedElementSet())
            }

            // adds `defmodule Foo.SSH` for `alias Foo.SSH`, and `Foo.SSH` and `defmodule Foo.SSH` for
            // `alias Foo.SSH, as: FSSH`
            addUnaliased(match, state, emptyList())
        } else {
            val namePartList = split(name)

            // alias Foo.SSH, then SSH.Key is name; `require Foo, as: M` serves `M.Key` as `alias Foo, as: M` does
            if (aliasedName == namePartList[0]) {
                addUnaliased(match, state, namePartList.drop(1))
            } else if (aliasedName.startsWith(name)) {
                resolveResultOrderedSet.add(match, "alias ${match.text}", false, state.visitedElementSet())
            }
        }

        return resolveResultOrderedSet.keepProcessing(incompleteCode)
    }

    override fun executeOnModularName(modular: Named, modularName: String, state: ResolveState): Boolean {
        if (modularName.startsWith(name)) {
            val validResult = modularName == name
            resolveResultOrderedSet.add(modular, modularName, validResult, state.visitedElementSet())
        }

        return resolveResultOrderedSet.keepProcessing(incompleteCode)
    }

    fun resolveResults(): List<VisitedElementSetResolveResult> = resolveResultOrderedSet.toList()

    private val resolveResultOrderedSet = ResolveResultOrderedSet()

    /**
     * Adds the modules [match] stands for, followed by [tail], the segments written after its name: an `as:` directive's
     * first argument where [state] carries the directive, else `match` itself.
     */
    private fun addUnaliased(match: PsiNamedElement, state: ResolveState, tail: List<String>) {
        if (DumbService.isDumb(match.project)) return

        // only an `as:` directive sets ALIAS_CALL or REQUIRE_CALL
        val (head, unaliased) = when (val directive = state.get(ALIAS_CALL) ?: state.get(REQUIRE_CALL)) {
            null -> UnaliasedName.unaliasedName(match) to match
            else -> Alias.aliasTargetName(directive) to (Alias.firstArgument(directive) as? PsiNamedElement ?: match)
        }
        // An alias of something that is not a module resolves to nothing
        head ?: return

        val visitedElementSet = state.putVisitedElement(match).putVisitedElement(unaliased).visitedElementSet()
        val unaliasedName = concat(listOf(head) + tail)

        // A name is read through the aliases before it, as Elixir reads it: `alias NS.Foo` then `require Foo, as: M`
        // gives `NS.Foo`, though a `Foo` is defined too. (e.g. `alias MyNamespace.Referenced; alias Referenced, as:
        // Refd` - resolving `Refd` follows Referenced -> MyNamespace.Referenced.) An `Elixir.`-rooted name is the
        // module it says, and a first argument that is no alias is not looked up among them.
        val inScope = unaliased is QualifiableAlias && moduleName(unaliased)?.absolute != true

        // the name looked for holds the segments after the alias's own: `Target.Missing` is not `Target`
        RECURSION_GUARD.doPreventingRecursion(unaliasedName, false) {
            for (result in ModuleResolver.resolveAll(unaliased, unaliasedName, incompleteCode, inScope)) {
                val resolvedElement = result.element

                resolveResultOrderedSet.add(
                    resolvedElement,
                    (resolvedElement as? NamedElement)?.name ?: unaliasedName,
                    result.isValidResult,
                    visitedElementSet + result.visitedElementSet
                )
            }
        }
    }

    companion object {
        private val RECURSION_GUARD =
                RecursionManager.createGuard<String>("org.elixir_lang.psi.scope.module.MultiResolve")

        fun resolveResults(name: String,
                           incompleteCode: Boolean,
                           entrance: PsiElement): List<VisitedElementSetResolveResult> =
                resolveResults(name, incompleteCode, entrance, ResolveState.initial())

        private fun resolveResults(name: String,
                                   incompleteCode: Boolean,
                                   entrance: PsiElement,
                                   state: ResolveState): List<VisitedElementSetResolveResult> {
            val multiResolve = MultiResolve(name, incompleteCode)
            val maxScope = maxScope(entrance)

            PsiTreeUtil.treeWalkUp(
                    multiResolve,
                    entrance,
                    maxScope,
                    state.put(ENTRANCE, entrance).putInitialVisitedElement(entrance)
            )

            return multiResolve.resolveResults()
        }
    }
}
