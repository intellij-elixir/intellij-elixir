package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.macroChildCallList
import org.elixir_lang.psi.impl.call.stabBodyChildExpressions
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.structure_view.element.CallDefinitionHead

object CallDefinitionClause {
    /**
     * The modular [call] belongs to, or else the boundary that stops module scope around it: the nearest enclosing
     * call that is a modular or a [moduleScopeBoundary]. Everything between - arguments, pipes, `fn`, `if`, `case` -
     * is looked through, as the compiler runs it all in the module body.
     *
     * @param call a def(macro)?p?
     */
    @JvmStatic
    fun enclosingModularMacroCall(call: Call): Call? =
        generateSequence(call.parent) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<Call>()
            .firstOrNull { ancestor ->
                ProgressManager.checkCanceled()
                isModular(ancestor) || moduleScopeBoundary(ancestor)
            }

    /**
     * Whether [call] stops module scope for what is inside it: a definition's body, a `quote`, or a `do` block that is
     * not one of Elixir's own [isModuleScopeConstruct]s, as a macro may put it in a `def` as ExUnit's `test` does.
     * Decided without resolving, so stub building may ask; telling macros apart would resolve while listing the module.
     */
    @JvmStatic
    fun moduleScopeBoundary(call: Call): Boolean =
        `is`(call) || QuoteMacro.`is`(call) ||
            (call.hasDoBlockOrKeyword() && !isModular(call) && !isModuleScopeConstruct(call))

    /**
     * Whether [call] is one of Elixir's own `do`-block forms that run their block where they are written. Every other
     * `do`-block form of `Kernel` and `Kernel.SpecialForms` defines, is a module or quotes; `ModuleScopeConstructTest`
     * checks that against the SDK's docs.
     */
    @JvmStatic
    fun isModuleScopeConstruct(call: Call): Boolean =
        call.functionName() in BLOCK_IN_PLACE_FORMS && call.resolvedModuleName() == KERNEL

    /** Unqualified, as a module body writes them, they read as `Kernel`'s whether `Kernel` or `Kernel.SpecialForms` defines them. */
    val BLOCK_IN_PLACE_FORMS = setOf("case", "cond", "for", "if", "receive", "try", "unless", "with")

    /**
     * The `unquote(block)` a macro definition's `quote` puts directly in the module body the macro is called in, where
     * `block` is the macro's `do` argument; `null` when it puts the block anywhere else, or is not such a macro.
     */
    @RequiresReadLock
    @JvmStatic
    fun moduleBodyUnquoteOfBlock(macroDefinition: Call): Call? =
        head(macroDefinition)?.let { it as? Call }?.finalArguments()
            ?.lastOrNull()?.let { it as? QuotableKeywordList }?.keywordValue("do")?.let { block ->
                macroDefinition.stabBodyChildExpressions(forward = false)?.filterIsInstance<Call>()?.firstOrNull()
                    ?.takeIf { QuoteMacro.`is`(it) }
                    ?.stabBodyChildExpressions()?.filterIsInstance<Call>()?.filter { Unquote.`is`(it) }
                    ?.singleOrNull { unquote -> unquote.textMatches("unquote(${block.text})") }
            }

    /**
     * The calls inside [call] in the module scope around it: every call its subtree holds outside a
     * [moduleScopeBoundary] or a modular, in document order; `null` when [call] is itself one of those.
     */
    @RequiresReadLock
    @JvmStatic
    fun moduleScopeCalls(call: Call): List<Call>? =
        if (isModular(call) || moduleScopeBoundary(call)) null else inModuleScope(childCalls(call))

    /**
     * The calls in [modular]'s module scope: its body, through everything that is not a [moduleScopeBoundary]. Listed
     * once per change, as each `@spec` and call in the module asks. It is what the module defines: `import`, `alias`,
     * `require` and variables are lexical and come only from the walk up from a use; a walk of this listing marks its
     * state [Import.definitionsOnly].
     */
    @RequiresReadLock
    @JvmStatic
    fun modularChildCalls(modular: Call): List<Call> =
        CachedValuesManager.getCachedValue(modular) {
            CachedValueProvider.Result.create(
                inModuleScope(modular.macroChildCallList()),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }

    /**
     * [calls] and what each holds in module scope. Elixir's own constructs stand for their contents alone; any other
     * call is listed and looked into.
     */
    @RequiresReadLock
    private fun inModuleScope(calls: List<Call>): List<Call> =
        calls.flatMap { call ->
            ProgressManager.checkCanceled()
            val inside = moduleScopeCalls(call)

            when {
                inside == null -> listOf(call)
                isModuleScopeConstruct(call) -> inside
                else -> listOf(call) + inside
            }
        }

    /** The nearest calls under [call] in its subtree, not looking inside them. */
    private fun childCalls(call: Call): List<Call> {
        val calls = mutableListOf<Call>()

        fun collect(element: PsiElement) {
            for (child in element.children) {
                if (child is Call) calls.add(child) else collect(child)
            }
        }

        collect(call)

        return calls
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
            CallableDeclaration.definerOf(call)?.let { definer ->
                if (definer.capabilities.compileTime) macroElementDescription(location)
                else functionElementDescription(call, location)
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

    /**
     * The name and arities of the function [call] is a clause of: those of the bodiless head it follows, whose defaults
     * its clauses share and which Elixir requires to come first in their group, with only module attributes between;
     * otherwise its own [nameArityInterval].
     *
     * Read from [enclosingModularMacroCall]'s [functions], so every clause of a module is worked out once, for every
     * [state]: the state only [NameArityInterval.adjusted]s a name's arities, the same for each of its clauses.
     */
    @RequiresReadLock
    fun functionNameArityInterval(call: Call, state: ResolveState): NameArityInterval? {
        val functions = enclosingModularMacroCall(call)?.let(::functions)
        val function = if (functions != null && call in functions) functions[call] else nameArityInterval(call, ResolveState.initial())

        return function?.adjusted(state)
    }

    /**
     * The clause that stands for each name at each arity in [modular]'s module scope: the one a compiled definition's
     * decompiled source is. Of a function's clauses, as [functions] groups them, that is the first whose own arities
     * are the function's - its head, or the clause carrying the defaults that a decompiled module's lower-arity
     * clauses fall under.
     */
    @RequiresReadLock
    fun firstClauseByArityByName(modular: Call): Map<String, Map<Int, Call>> {
        val representatives = LinkedHashMap<NameArityInterval, Call>()

        for ((clause, function) in functions(modular)) {
            function ?: continue
            val current = representatives[function]

            if (current == null ||
                (nameArityInterval(current, ResolveState.initial()) != function &&
                    nameArityInterval(clause, ResolveState.initial()) == function)
            ) {
                representatives[function] = clause
            }
        }

        val byArityByName = mutableMapOf<String, MutableMap<Int, Call>>()

        for ((function, clause) in representatives) {
            val byArity = byArityByName.getOrPut(function.name) { mutableMapOf() }

            function.arityInterval.closed().forEach { arity ->
                ProgressManager.checkCanceled()
                firstWins(byArity, arity, clause)
            }
        }

        return byArityByName
    }

    /**
     * Each clause in [modular]'s module scope, in document order, with the function it is a clause of, worked out in
     * one pass and cached: a clause with a body after a bodiless head of the same name and function arity, with only
     * module attributes between, is that head's; a clause at arities another same-named function's defaults cover, in
     * the same block, is that function's, as Elixir rejects the pair as two (a decompiled module whose docs give the
     * defaults only at the higher arity writes the lower one so), while in another branch it may be the one compiled;
     * any other clause is its own.
     */
    private fun functions(modular: Call): Map<Call, NameArityInterval?> =
        CachedValuesManager.getCachedValue(modular) {
            val functions = LinkedHashMap<Call, NameArityInterval?>()

            for (call in modularChildCalls(modular)) {
                ProgressManager.checkCanceled()
                if (!`is`(call)) continue

                functions[call] = nameArityInterval(call, ResolveState.initial())?.let { own ->
                    previousClause(call)
                        ?.takeIf { call.hasDoBlockOrKeyword() }
                        ?.let { functions[it] }
                        ?.takeIf { it.isFunctionOf(own) }
                        ?: own
                }
            }

            val clausesWithDefaults = functions.entries.filter { it.value?.hasDefaults() == true }

            for (clause in functions.keys.toList()) {
                val function = functions[clause]?.takeUnless { it.hasDefaults() } ?: continue
                clausesWithDefaults
                    .firstOrNull { (withDefaults, covering) -> withDefaults.parent == clause.parent && covering?.covers(function) == true }
                    ?.let { functions[clause] = it.value }
            }

            CachedValueProvider.Result.create(functions, PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun NameArityInterval.hasDefaults(): Boolean =
        arityInterval.maximum?.let { it > arityInterval.minimum } == true

    private fun NameArityInterval.covers(other: NameArityInterval): Boolean =
        name == other.name && other.arityInterval.maximum?.let { maximum ->
            other.arityInterval.minimum in arityInterval && maximum in arityInterval
        } == true

    /** The call before [call], past module attributes, if it is a clause. */
    private fun previousClause(call: Call): Call? =
        generateSequence(call.prevSibling) { it.prevSibling }
            .filterIsInstance<Call>()
            .firstOrNull { it !is AtUnqualifiedNoParenthesesCall<*> }
            ?.takeIf { `is`(it) }

    private fun NameArityInterval.isFunctionOf(clause: NameArityInterval): Boolean =
        name == clause.name && arityInterval.functionArity == clause.arityInterval.functionArity

    /** Keeps the first clause seen for a repeated `(name, arity)`. */
    val firstWins: (byArity: MutableMap<Int, Call>, arity: Int, call: Call) -> Unit =
            { byArity, arity, call -> byArity.putIfAbsent(arity, call) }

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
