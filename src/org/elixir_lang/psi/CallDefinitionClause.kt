package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.enclosingMacroCall
import org.elixir_lang.structure_view.element.CallDefinitionHead

object CallDefinitionClause {
    /**
     * The enclosing macro call that acts as the modular scope of `call`.  Ignores enclosing `for` calls that
     * [enclosingMacroCall] doesn't.
     *
     * @param call a def(macro)?p?
     */
    @RequiresReadLock
    @JvmStatic
    fun enclosingModularMacroCall(call: Call): Call? {
        var enclosedCall = call
        var enclosingMacroCall: Call?

        while (true) {
            ProgressManager.checkCanceled()
            enclosingMacroCall = enclosedCall.enclosingMacroCall()

            if (enclosingMacroCall != null &&
                    (enclosingMacroCall.isCalling(KERNEL, ALIAS) ||
                            enclosingMacroCall.isCalling(KERNEL, REQUIRE) ||
                            For.`is`(enclosingMacroCall))) {
                enclosedCall = enclosingMacroCall
            } else {
                break
            }
        }

        return enclosingMacroCall
    }

    /**
     * Description of element used in find-usages and element-description presentation.
     *
     * @param call a [Call] that has already been checked with [.is]
     * @param location where the description will be used
     * @return
     */
    @RequiresReadLock
    fun elementDescription(call: Call, location: ElementDescriptionLocation): String? =
            when {
                isFunction(call) -> functionElementDescription(call, location)
                isMacro(call) -> macroElementDescription(location)
                else -> null
            }

    /**
     * The head of the call definition.
     *
     * @param call a call that [.is].
     * @return element for `name(arg, ...) when ...` in `def* name(arg, ...) when ...`
     */
    @RequiresReadLock
    @JvmStatic
    fun head(call: Call): PsiElement? = call.primaryArguments()?.firstOrNull()

    @RequiresReadLock
    @JvmStatic
    fun `is`(call: Call): Boolean = CallableDeclaration.definerOf(call) != null

    /**
     * Returns `true` if [element] is at or within the name/head of any call-definition clause -
     * i.e. inside the `foo(args)` head in `def foo(args)`. Used to suppress reference providers
     * that must not fire on declaration names.
     *
     * Mirrors [org.elixir_lang.psi.Protocol.isHead] but applies to all `CallDefinitionClause`
     * contexts, not just `defprotocol` bodies.
     */
    @RequiresReadLock
    fun isHead(element: PsiElement): Boolean {
        val defClause = generateSequence(element) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { `is`(it) }
            ?: return false
        val nameIdentifier = nameIdentifier(defClause) ?: return false
        return PsiTreeUtil.isAncestor(nameIdentifier, element, false) ||
               PsiTreeUtil.isAncestor(element, nameIdentifier, false)
    }

    @RequiresReadLock
    @JvmStatic
    fun isFunction(call: Call): Boolean = isPrivateFunction(call) || isPublicFunction(call)
    @RequiresReadLock
    @JvmStatic
    fun isPublicFunction(call: Call): Boolean =
            isCallingKernelMacroOrHead(call, DEF) || isCallingKernelMacroOrHead(call, DEFMEMO)
    @RequiresReadLock
    fun isPrivateFunction(call: Call): Boolean =
            isCallingKernelMacroOrHead(call, DEFP) || isCallingKernelMacroOrHead(call, DEFMEMOP)

    @RequiresReadLock
    @JvmStatic
    fun isMacro(call: Call): Boolean = isPrivateMacro(call) || isPublicMacro(call)
    @RequiresReadLock
    @JvmStatic
    fun isPublicMacro(call: Call): Boolean = isCallingKernelMacroOrHead(call, DEFMACRO)
    @RequiresReadLock
    fun isPrivateMacro(call: Call): Boolean = isCallingKernelMacroOrHead(call, DEFMACROP)

    @RequiresReadLock
    fun isGuard(call: Call): Boolean = isPrivateGuard(call) || isPublicGuard(call)
    @RequiresReadLock
    fun isPublicGuard(call: Call): Boolean = isCallingKernelMacroOrHead(call, DEFGUARD)
    @RequiresReadLock
    fun isPrivateGuard(call: Call): Boolean = isCallingKernelMacroOrHead(call, DEFGUARDP)

    @RequiresReadLock
    fun isPublic(call: Call): Boolean = isPublicFunction(call) || isPublicMacro(call) || isPublicGuard(call)

    /**
     * The name and arity range of the call definition this clause belongs to.
     *
     * @param call
     * @return The name and arities of the [org.elixir_lang.structure_view.element.CallDefinition] this clause belongs.  Multiple arities occur when
     * default arguments are used, which produces an arity for each default argument that is turned on and off.
     * @see Call.resolvedFinalArityInterval
     */
    @RequiresReadLock
    @JvmStatic
    fun nameArityInterval(call: Call, state: ResolveState): NameArityInterval? =
            head(call)?.let { CallDefinitionHead.nameArityInterval(it, state) }

    /** A [putNameArityInterval] `write` policy that keeps the first clause seen for a repeated `(name, arity)`. */
    val firstWins: (byArity: MutableMap<Int, Call>, arity: Int, call: Call) -> Unit =
            { byArity, arity, call -> byArity.putIfAbsent(arity, call) }

    /**
     * Adds [call] to [byArityByName] under its name, once per arity in its arity interval, via [write] - so a
     * caller building a name/arity lookup across many clauses picks once whether a repeated (name, arity) keeps
     * the first clause seen or the last, instead of every call site reimplementing this walk.
     */
    @RequiresReadLock
    fun putNameArityInterval(
            call: Call,
            state: ResolveState,
            byArityByName: MutableMap<String, MutableMap<Int, Call>>,
            write: (byArity: MutableMap<Int, Call>, arity: Int, call: Call) -> Unit
    ) {
        nameArityInterval(call, state)?.let { nameArityInterval ->
            val byArity = byArityByName.getOrPut(nameArityInterval.name) { mutableMapOf() }
            nameArityInterval.arityInterval.closed().forEach { arity ->
                ProgressManager.checkCanceled()
                write(byArity, arity, call)
            }
        }
    }

    @RequiresReadLock
    fun nameIdentifier(call: Call): PsiElement? = head(call)?.let { CallDefinitionHead.nameIdentifier(it) }

    private fun functionElementDescription(
            @Suppress("UNUSED_PARAMETER") call: Call,
            location: ElementDescriptionLocation
    ): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "function"
            } else {
                null
            }

    private fun macroElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "macro"
            } else {
                null
            }

    /** Whether [call] is a clause written with the `def*` named [keyword], or that `def*`'s bodiless head. */
    @RequiresReadLock
    fun isCallingDefiner(call: Call, keyword: String): Boolean = isCallingKernelMacroOrHead(call, keyword)

    private fun isCallingKernelMacroOrHead(call: Call, resolvedName: String): Boolean =
            call.isCallingMacro(KERNEL, resolvedName, 2) ||
                    call.isCalling(KERNEL, resolvedName, 1)
}
