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
