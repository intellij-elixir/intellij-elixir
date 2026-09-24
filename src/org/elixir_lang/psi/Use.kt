package org.elixir_lang.psi

import org.elixir_lang.psi.scope.Reach.Companion.reachedThrough
import org.elixir_lang.psi.scope.Reach
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.isAncestor
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.Use.`is`
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.USE
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.maybeModularNameToModulars

/**
 * A `use` call
 */
object Use {
    /**
     * Calls `keepProcessing` on each element added to the scope from the `quote` block inside the `__using__`
     * macro called by `useCall` while `keepProcessing` returns `true`.  Stops the first time `keepProcessing`
     * returns `false`.
     */
    @RequiresReadLock
    fun treeWalkUp(
        useCall: Call,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        var accumulatedKeepProcessing = true

        // don't descend back into `use` when the entrance is the alias to the `use` like `MyAlias` in `use MyAlias`.
        if (!useCall.isAncestor(resolveState.get(ENTRANCE))) {
            val useCallResolveState = resolveState.putVisitedElement(useCall).reachedThrough(Reach.USE, useCall)

            outer@ for (modular in modulars(useCall)) {
                ProgressManager.checkCanceled()
                val directDefiners = Using.definers(modular).toList()

                // `Using.definers` looks for an explicit `defmacro __using__/1` in the source PSI of `modular`.
                // When `modular` was defined with `use ExUnit.CaseTemplate`, that macro is absent from source:
                // `ExUnit.CaseTemplate.__using__/1` generates the `defmacro __using__` at compile time so it
                // only exists in the compiled BEAM, not in the `.ex` file.  The plugin therefore cannot follow
                // the injected `__using__` -> `__proxy__` -> `use ExUnit.Case` chain statically (it breaks at
                // the `unquote(__MODULE__)` qualifier in `__proxy__`, which `resolvedModuleName()` cannot
                // evaluate).  As a stop-gap, when no direct definer is found and the module is a CaseTemplate,
                // skip the opaque generated layer and use `ExUnit.Case.__using__/1` directly - the net runtime
                // effect of `use <any CaseTemplate>` is always equivalent to `use ExUnit.Case`.
                val effectiveDefiners: Iterable<PsiElement> =
                    if (directDefiners.isEmpty() && Using.isCaseTemplate(modular, useCallResolveState))
                        Using.exUnitCaseDefiners(useCall).asIterable()
                    else
                        directDefiners

                for (definer in effectiveDefiners) {
                    ProgressManager.checkCanceled()
                    val childResolveState = useCallResolveState.putVisitedElement(definer)
                    accumulatedKeepProcessing = Using.treeWalkUp(
                        using = definer,
                        use = useCall,
                        resolveState = childResolveState,
                        keepProcessing = keepProcessing
                    )

                    if (!accumulatedKeepProcessing) {
                        break@outer
                    }
                }
            }
        }

        return accumulatedKeepProcessing
    }

    fun elementDescription(@Suppress("UNUSED_PARAMETER") call: Call, location: ElementDescriptionLocation): String? {
        var elementDescription: String? = null

        if (location === UsageViewTypeLocation.INSTANCE) {
            elementDescription = "use"
        }

        return elementDescription
    }

    /**
     * Whether `call` is a `use Module` or `use Module, opts` call.
     */
    @JvmStatic
    fun `is`(call: Call): Boolean =
        call.isCalling(KERNEL, USE, 1) || call.isCalling(KERNEL, USE, 2)

    /**
     * The modular that is used by `useCall`.
     *
     * @param useCall a [Call] where [is] is `true`.
     * @return `defmodule`, `defimpl`, or `defprotocol` used by `useCall`.  It can be `null` if Alias passed to
     *    `useCall` cannot be resolved.
     */
    @RequiresReadLock
    fun modulars(useCall: Call): Set<PsiElement> =
        useCall
            .finalArguments()
            ?.firstOrNull()
            ?.maybeModularNameToModulars(maxScope = useCall.parent, useCall = useCall, incompleteCode = false)
            ?: emptySet()
}
