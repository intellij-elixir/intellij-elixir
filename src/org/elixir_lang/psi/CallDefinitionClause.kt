package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.MacroRole
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.declaration.SourceOrigin
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.structure_view.element.CallDefinitionHead

object CallDefinitionClause {
    /**
     * The enclosing macro call that acts as the modular scope of `call`: the nearest one whose `do` block is a
     * [MacroRole.Block.BOUNDARY], or a `Module.create/3`.
     *
     * A macro whose block depends on its expansion, such as ExUnit's `describe` or a library's DSL, is looked through
     * as if it ran its block in place. Outside every module the nearest such macro is the answer instead, since only
     * its expansion can define the module that a definition there needs.
     */
    @RequiresReadLock
    @JvmStatic
    fun enclosingModularMacroCall(call: Call): Call? =
        enclosingModularMacroCall(SyntacticCall.of(call))?.let(SyntacticCall::call)

    @RequiresReadLock
    @JvmStatic
    fun enclosingModularMacroCall(call: SyntacticCall): SyntacticCall? {
        ThreadingAssertions.assertReadAccess()

        var enclosedCall = call
        var expansionDependent: SyntacticCall? = null

        while (true) {
            ProgressManager.checkCanceled()
            val enclosingMacroCall = enclosedCall.enclosingMacroCall() ?: return expansionDependent
            val block = macroRole(enclosingMacroCall).block

            if (startsNewScope(enclosingMacroCall, block)) {
                return enclosingMacroCall
            }

            if (block == MacroRole.Block.EXPANSION_DEPENDENT && expansionDependent == null) {
                expansionDependent = enclosingMacroCall
            }

            enclosedCall = enclosingMacroCall
        }
    }

    /**
     * The statements whose [enclosingModularMacroCall] is [modular], in source order, so the walk down agrees with the
     * walk up by construction. An operand, such as a type in a `@spec`, is not one.
     */
    @RequiresReadLock
    @JvmStatic
    fun modularChildCalls(modular: Call): List<Call> {
        ThreadingAssertions.assertReadAccess()

        return CachedValuesManager.getCachedValue(modular) {
            val accumulator = mutableListOf<Call>()
            collectModularChildCalls(modular, nearestCalls(modular, mutableListOf()), accumulator)

            CachedValueProvider.Result.create(accumulator.toList(), modular.containingFile)
        }
    }

    /**
     * The calls of [modularCalls] for [call]'s module that are written inside [call], in source order: none inside a
     * block that is not part of the module's body.
     */
    @RequiresReadLock
    @JvmStatic
    fun blockChildCalls(call: Call, modularCalls: (Call) -> List<Call> = ::modularChildCalls): List<Call> {
        ThreadingAssertions.assertReadAccess()

        val modular = enclosingModularMacroCall(call) ?: return emptyList()
        val calls = modularCalls(modular)
        val range = call.textRange
        // Source order is start-offset order, so the calls inside are the run starting after [call] and before its end.
        val first = -calls.binarySearch { if (it.textRange.startOffset <= range.startOffset) -1 else 1 } - 1

        return calls.subList(first, calls.size).takeWhile { it.textRange.startOffset < range.endOffset }
    }

    /** No call under one that starts a new scope can have [modular] as its module, so those are not descended. */
    private fun collectModularChildCalls(modular: Call, calls: List<Call>, accumulator: MutableList<Call>) {
        for (call in calls) {
            ProgressManager.checkCanceled()

            if (isStatement(call) && enclosingModularMacroCall(call) == modular) {
                accumulator.add(call)
            }

            if (!startsNewScope(SyntacticCall.of(call))) {
                collectModularChildCalls(modular, nearestCalls(call, mutableListOf()), accumulator)
            }
        }
    }

    /** Written as one of a block's expressions, or as an element of a list that is one. */
    private fun isStatement(element: PsiElement): Boolean {
        var parent = element.parent

        while (parent is ElixirAccessExpression) {
            parent = parent.parent
        }

        return when (parent) {
            is ElixirStabBody -> true
            is ElixirList -> isStatement(parent)
            // Whether the call is the module's own, not another call's, is the caller's `enclosingModularMacroCall` test.
            is QuotableKeywordPair -> parent.blockKeyword() != null
            else -> false
        }
    }

    /** The calls under [element] that no other call under it holds. */
    private fun nearestCalls(element: PsiElement, accumulator: MutableList<Call>): List<Call> {
        for (child in element.children) {
            ProgressManager.checkCanceled()

            if (child is Call) accumulator.add(child) else nearestCalls(child, accumulator)
        }

        return accumulator
    }

    /** Whether nothing written in [call]'s block belongs to the module [call] is in. */
    @RequiresReadLock
    @JvmStatic
    fun startsNewScope(call: Call): Boolean {
        ThreadingAssertions.assertReadAccess()

        return startsNewScope(SyntacticCall.of(call))
    }

    private fun startsNewScope(call: SyntacticCall, block: MacroRole.Block = macroRole(call).block): Boolean =
        block == MacroRole.Block.BOUNDARY || Module.`is`(call)

    /** The [MacroRole] of [call], or that of a macro the table does not list when [call] is not `Kernel`'s. */
    @RequiresReadLock
    @JvmStatic
    fun macroRole(call: Call): MacroRole {
        ThreadingAssertions.assertReadAccess()

        return macroRole(SyntacticCall.of(call))
    }

    private fun macroRole(call: SyntacticCall): MacroRole {
        val name = call.functionName()

        return if (name != null && call.resolvedModuleName() in KERNEL_MODULES) {
            MacroRole.of(name, call.hasDoBlockOrKeyword())
        } else {
            MacroRole.unlisted(call.hasDoBlockOrKeyword())
        }
    }

    private val KERNEL_MODULES = setOf(KERNEL, KERNEL_SPECIAL_FORMS)

    /**
     * Description of element used in find-usages and element-description presentation.
     *
     * @param call a [Call] that has already been checked with [.is]
     * @param location where the description will be used
     * @return
     */
    @RequiresReadLock
    fun elementDescription(call: Call, location: ElementDescriptionLocation): String? =
            when (capabilities(call)?.presentation) {
                Presentation.FUNCTION -> functionElementDescription(call, location)
                Presentation.MACRO -> macroElementDescription(location)
                Presentation.GUARD, null -> null
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
    fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun `is`(call: SyntacticCall): Boolean = definer(call) != null

    /** The `def*` [call] is written with, `null` when it is no clause. */
    @RequiresReadLock
    @JvmStatic
    fun definer(call: Call): Definer? = definer(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun definer(call: SyntacticCall): Definer? =
        call.functionName()?.let { Definer.of(it) }?.takeIf { isCallingKernelMacroOrHead(call, it.keyword) }

    @RequiresReadLock
    @JvmStatic
    fun capabilities(call: Call): Capabilities? = capabilities(SyntacticCall.of(call))

    @RequiresReadLock
    @JvmStatic
    fun capabilities(call: SyntacticCall): Capabilities? = definer(call)?.capabilities

    @RequiresReadLock
    fun declaration(call: Call, state: ResolveState): Declaration? {
        val capabilities = capabilities(call) ?: return null
        val (name, arityInterval) = nameArityInterval(call, state) ?: return null

        return Declaration(name, arityInterval.arityKnowledge(), capabilities, Declared.Source(Form.CLAUSE, sourceOrigin(call)))
    }

    /** Where [call] declares, in the compiled file when it is in that file's decompiled text. */
    @RequiresReadLock
    fun sourceOrigin(call: Call): SourceOrigin {
        ThreadingAssertions.assertReadAccess()

        val file = call.containingFile
        val viewProvider =
            (file.originalFile as? PsiCompiledFile)?.takeIf { it.decompiledPsiFile == file }?.viewProvider
                ?: file.viewProvider

        return SourceOrigin(viewProvider.virtualFile, viewProvider.modificationStamp, call.textRange)
    }

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
        return head(defClause)?.let { CallDefinitionHead.isOnName(it, element) } == true
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

    /** The element [call]'s name is written as: in `def unquote(:name)(...)`, the atom. */
    @RequiresReadLock
    fun nameElement(call: Call): PsiElement? = head(call)?.let(CallDefinitionHead::nameElement)

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

    private fun isCallingKernelMacroOrHead(call: SyntacticCall, resolvedName: String): Boolean =
            call.isCallingMacro(KERNEL, resolvedName, 2) ||
                    call.isCalling(KERNEL, resolvedName, 1)
}
