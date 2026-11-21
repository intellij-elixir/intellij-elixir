package org.elixir_lang.psi

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.__module__.Reference
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module.KERNEL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

object __MODULE__ {
    private val KEY: Key<CachedValue<ConcurrentHashMap<Call, PsiReference>>> = Key.create("__MODULE__REFERENCE")

    /** Whether [call] is `__MODULE__`, `Kernel.__MODULE__/0`. */
    fun `is`(call: Call): Boolean = call.isCalling(KERNEL, Function.__MODULE__, 0)

    /** Whether [element] is a `__MODULE__`: the call, or the variable a struct's name `%__MODULE__{}` is written as. */
    @RequiresReadLock
    fun isUse(element: PsiElement): Boolean = when (element) {
        is Call -> `is`(element)
        is ElixirVariable -> element.name == Function.__MODULE__ && element.parent is ElixirStructOperation
        else -> false
    }

    /**
     * The reference for `__MODULE__Call`, resolved through the scope `useCall` injects when one is given.
     *
     * The reference is cached per `useCall`, not per `__MODULE__Call` alone.  A `ParameterizedCachedValue`
     * holds one value per (data holder, key) and never consults the parameter when looking a value up, so
     * a single slot handed the reference built for one `use` call back to callers that asked for a
     * different one - and let the platform compare two computations whose dependency arrays named
     * different elements, which it reports as a non-idempotent computation.
     *
     * The map must stay a [ConcurrentMap]: `IdempotenceChecker` exempts one from the value comparison
     * it applies to every other cached collection, because a cache filled lazily after it is stored
     * would otherwise be reported as non-idempotent itself.
     */
    @RequiresReadLock
    fun reference(__MODULE__Call: Call, useCall: Call? = null): PsiReference =
        CachedValuesManager
            .getCachedValue(__MODULE__Call, KEY) {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<Call, PsiReference>(),
                    PsiModificationTracker.MODIFICATION_COUNT
                )
            }
            // `__MODULE__Call` stands in for a null `useCall`, which a `ConcurrentHashMap` cannot key on.
            // It cannot collide with a real one: a `use` call is never a `__MODULE__` call.
            .computeIfAbsent(useCall ?: __MODULE__Call) { Reference(call = __MODULE__Call, useCall = useCall) }
}
