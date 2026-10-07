package org.elixir_lang.psi.scope

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.isAncestor
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.MacroRole
import org.elixir_lang.declaration.Reach
import org.elixir_lang.ecto.query.WindowAPI
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.blockChildCalls
import org.elixir_lang.psi.CallDefinitionClause.macroRole
import org.elixir_lang.psi.CallDefinitionClause.modularChildCalls
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.psi.ex_unit.Case
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.ancestorSequence
import org.elixir_lang.psi.impl.selfOrEnclosingMacroCall
import org.elixir_lang.psi.impl.enclosingMacroCall
import org.elixir_lang.psi.impl.call.*
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.resolver.narrowedScope
import org.jetbrains.annotations.TestOnly

abstract class CallDefinitionClause : PsiScopeProcessor {
    /*
     * Public Instance Methods
     */

    /**
     * @param element candidate element.
     * @param state   current state of resolver.
     * @return false to stop processing.
     */
    @RequiresReadLock
    override fun execute(element: PsiElement, state: ResolveState): Boolean =
        when (element) {
            is Call -> execute(element, state)
            is BeamModule -> execute(element, state)
            is BeamCallDefinition -> execute(element, state)
            is ElixirFile -> execute(element, state)
            else -> true
        }

    override fun <T> getHint(hintKey: Key<T>): T? = null
    override fun handleEvent(event: PsiScopeProcessor.Event, associated: Any?) {}

    /*
     * Protected Instance Methods
     */

    /**
     * Called on every [Call] where [org.elixir_lang.structure_view.element.CallDefinitionClause. is] is
     * `true` when checking tree with [.execute]
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean

    /**
     * Called on every [Call] where [org.elixir_lang.structure_view.element.Callback. is] is `true`
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean

    /**
     * Called on every [Call] where [org.elixir_lang.structure_view.element.Delegation. is] is `true` when checking tree
     * with [.execute]].
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnDelegation(element: Call, state: ResolveState): Boolean

    /**
     * Called on every [Call] where [org.elixir_lang.EEx.isFunctionFrom] is `true`.
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean

    /**
     * Called on every [Call] where [org.elixir_lang.psi.Exception. is] is `true`.
     *
     * @return true to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnException(element: Call, state: ResolveState): Boolean

    /**
     * Called on every [Call] where [org.elixir_lang.psi.mix.Generator.isEmbed] is `true`.
     *
     * @return `true` to keep searching up tree; `false` to stop searching.
     */
    protected abstract fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean

    /**
     * Whether to continue searching after each Module's children have been searched.
     *
     * @return `true` to keep searching up the PSI tree; `false` to stop searching.
     */
    protected abstract fun keepProcessing(): Boolean

    /** The prefix of every atom this processor can reach, or `null` when it needs every declaration. */
    protected open fun targetName(): String? = null

    /*
     * Private Instance Methods
     */

    // Every loop of the walk reaches here, but for a .beam module's definitions.
    @RequiresReadLock
    private fun execute(element: Call, state: ResolveState): Boolean {
        ProgressManager.checkCanceled()

        return (DeclaringForm.syntacticForm(element) ?: DeclaringForm.resolvingForm(element, state))
            ?.let { form -> executeOnDeclaring(form, element, state) }
            ?: executeOnNonDeclaring(element, state)
    }

    private fun executeOnDeclaring(form: Form, element: Call, state: ResolveState): Boolean =
        when (form) {
            Form.CLAUSE -> {
                WalkProbe.count(WalkProbe.Counter.CLAUSE_HANDLER_MODULE)
                executeOnCallDefinitionClause(element, state)
            }
            Form.CALLBACK -> executeOnCallback(element as AtUnqualifiedNoParenthesesCall<*>, state)
            Form.DELEGATION -> executeOnDelegation(element, state)
            Form.EXCEPTION -> executeOnException(element, state)
            Form.EEX_FUNCTION_FROM -> executeOnEExFunctionFrom(element, state)
            Form.GENERATOR_EMBED -> executeOnMixGeneratorEmbed(element, state)
        }

    private fun executeOnNonDeclaring(element: Call, state: ResolveState): Boolean =
        when {
            For.`is`(element) -> For.treeWalkDown(element, state, ::execute)
            If.`is`(element) || Unless.`is`(element) -> {
                if (walksBranches(element, state)) {
                    // Every child is walked whatever the others answered, and the arm answers `true`.
                    val ifState =
                        Recording.enter(state, "IF", element, stops = false, absorbs = true, gate = { walksBranches(element, it) })

                    for (childCall in blockChildCalls(element, ::modularCallsToExecute)) {
                        ProgressManager.checkCanceled()
                        execute(childCall, ifState)
                    }
                }

                true
            }
            Import.`is`(element) -> {
                try {
                    Import.treeWalkUp(element, state) { call, accResolveState ->
                        execute(call, accResolveState)
                    }
                } catch (stackOverflowError: StackOverflowError) {
                    Logger.error(
                        CallDefinitionClause::class.java,
                        "StackOverflowError while processing import",
                        element,
                        stackOverflowError
                    )
                }

                true
            }

            walksModular(element, state) -> {
                val childCalls = if (isModular(element)) {
                    modularCallsToExecute(element).asSequence()
                } else {
                    element.macroChildCallSequence()
                }
                // Each child's answer is ignored. At replay, a body whose gate is closed is re-dispatched live here.
                val bodyState = Recording.enter(
                    state, "MODULAR_BODY", element, stops = false, absorbs = true,
                    gate = { walksModular(element, it) && !isAtCompileTimeLevel(element, it.get(ENTRANCE)) },
                    fallThrough = true
                )

                // If the entrance is at compile time level of the body, then only previous siblings could possibly define
                // this call and those will be handled by ElixirStabBody's processDeclarations.
                if (!isAtCompileTimeLevel(element, state.get(ENTRANCE))) {
                    val table = tableFor(element, state)

                    if (table != null) {
                        replay(table, state)
                    } else {
                        for (childCall in childCalls) {
                            ProgressManager.checkCanceled()
                            execute(childCall, bodyState)
                        }
                    }
                }

                Recording.live(bodyState, element, Recording.LiveKind.IMPLICIT_IMPORTS) { call, implicitState ->
                    afterModularBody(call, implicitState)
                } || afterModularBody(element, state)
            }
            QuoteMacro.`is`(element) -> QuoteMacro.treeWalkUp(element, state, ::execute, unvisited = true)
            Use.`is`(element) -> {
                Use.treeWalkUp(element, Recording.enter(state, "USE_ARM", element, stops = false, absorbs = true), ::execute)

                true
            }
            element.isCalling(KERNEL, TRY) -> {
                val tryState = Recording.enter(state, "TRY", element, stops = true, absorbs = false)

                element.whileInStabBodyChildExpressions { childExpression ->
                    execute(childExpression, tryState)
                }
            }
            org.elixir_lang.ecto.Schema.isChild(element, state) -> {
                org.elixir_lang.ecto.Schema.walkChild(element, state, ::execute)
            }
            // doesn't declare calls, but if this is the scope, then `Ecto.Query.API` is resolvable
            org.elixir_lang.ecto.Query.isChild(element, state) -> {
                Recording.live(state, element, Recording.LiveKind.QUERY) { call, liveState -> execute(call, liveState) } ||
                    org.elixir_lang.ecto.Query.walkChild(element, state, ::execute)
            }
            org.elixir_lang.ecto.query.API.`is`(element, state) -> {
                org.elixir_lang.ecto.query.API.treeWalkUp(element, state, ::execute)
            }
            WindowAPI.`is`(element, state) -> {
                WindowAPI.treeWalkUp(element, state, ::execute)
            }
            hasDoBlockOrKeyword(element) ->
                Recording.live(state, element, Recording.LiveKind.UNKNOWN_MACRO) { call, liveState ->
                    execute(call, liveState)
                } || executeOnUnknownMacroCall(element, state)
            else -> true
        }

    /** The modular arm's own test, which decides whether it is the arm for [element] at all. */
    private fun walksModular(element: Call, state: ResolveState): Boolean =
        (isModular(element) || Case.isChild(element, state)) && modularContainsEntrance(element, state)

    /** [keepProcessing] is asked only after the whole body, so every arity is collected; then the implicit imports. */
    private fun afterModularBody(element: PsiElement, state: ResolveState): Boolean =
        keepProcessing() && implicitImports(element, state)

    /** The table to replay for [modular]'s body, or `null` to walk it, as while recording. */
    private fun tableFor(modular: Call, state: ResolveState): CallableTable? =
        if (isModular(modular) && !Recording.isRecording(state)) {
            CallableTable.ofOrNull(modular, foreign = isForeignEntrance(modular, state.get(ENTRANCE)))
        } else {
            null
        }

    /**
     * The modular arm's child loop, answered from [table]: in the walk's own order, the nodes of this processor's
     * name, the live nodes the entrance needs and the first implicit import, each under its sites' own gates.
     */
    private fun replay(table: CallableTable, state: ResolveState) = replaying {
        val entrance = state.get(ENTRANCE)
        val leaves = targetName()?.let(table::leavesStartingWith) ?: table.leaves
        val gates = HashMap<Recording.Group, Boolean>()
        val opens = { group: Recording.Group ->
            gates.getOrPut(group) {
                (group.gate?.invoke(state) ?: true) && (group.parent?.childGate?.invoke(state, group.call) ?: true)
            }
        }
        // Every implicit import after the first that runs adds only what the first added, so only that one is replayed.
        val implicitImport = table.implicitImports.firstOrNull { live ->
            WalkProbe.count(WalkProbe.Counter.LIVE_EXAMINED)
            // A closed group that falls through is re-dispatched live, implicit imports and all, from this node.
            live.group?.chain.orEmpty().firstOrNull { !opens(it) }.let { closed -> closed == null || closed.fallThrough }
        }
        val nodes = buildList {
            addAll(leaves)
            addAll(table.livesFor(entrance))
            implicitImport?.let(::add)
        }.sortedBy { it.order }
        // Each group's answer so far, and the groups a `false` has ended. A group answers its parent when the replay
        // leaves it, as its site's loop returns after its last child.
        val answers = HashMap<Recording.Group, Boolean>()
        // The last child group that answered each group: a later child with no node here answered `true` live, as a
        // declaration of another name does.
        val lastChild = HashMap<Recording.Group, Int>()
        val ended = HashSet<Recording.Group>()
        val fellThrough = HashSet<Recording.Group>()
        val closedAnswered = HashSet<Recording.Group>()
        // Only the groups a node ran in answer their parents, as only those ran live.
        val entered = HashSet<Recording.Group>()
        var open: List<Recording.Group> = emptyList()

        fun answer(group: Recording.Group?, answer: Boolean, child: Recording.Group? = null) {
            group ?: return
            if (child != null) lastChild[group] = child.ordinal
            if (!answer && group.stops) ended.add(group)
            answers[group] = if (group.answersLast) answer else answers[group] != false && answer
        }

        fun leave(chain: List<Recording.Group>) {
            for (group in open.asReversed()) {
                if (group in chain) break
                if (group in entered) {
                    val answer = group.absorbs ||
                        (group.answersLast && lastChild[group] != group.children - 1) ||
                        answers[group] != false
                    answer(group.parent, answer, group)
                }
            }
            open = chain
        }

        for (node in nodes) {
            ProgressManager.checkCanceled()
            replayStep()
            val nodeChain = node.group?.chain.orEmpty()

            leave(nodeChain)
            if (nodeChain.any { it in ended }) continue

            val closed = nodeChain.firstOrNull { !opens(it) }

            if (closed != null) {
                val result = if (closed.fallThrough) {
                    if (!fellThrough.add(closed)) continue
                    execute(closed.call, reached(state.putVisitedElements(closed.visited), closed.parent?.chain.orEmpty()))
                } else {
                    // The site answers `true` when its gate fails.
                    if (!closedAnswered.add(closed)) continue
                    true
                }

                entered.addAll(closed.parent?.chain.orEmpty())
                answer(closed.parent, result, closed)
                continue
            }

            val element = when (node) {
                is CallableTable.Leaf -> node.element
                is CallableTable.Live -> node.call
            }
            if (node.group?.childGate?.invoke(state, element) == false) continue

            entered.addAll(nodeChain)
            val nodeState = replayState(node, nodeChain, state)
            val result = when (node) {
                is CallableTable.Leaf -> execute(node.element, nodeState)
                is CallableTable.Live -> {
                    WalkProbe.count(WalkProbe.Counter.LIVE_REDISPATCH)
                    node.rerun(this, node.call, nodeState)
                }
            }

            answer(node.group, result)
        }
    }

    private fun replayState(node: CallableTable.Node, chain: List<Recording.Group>, state: ResolveState): ResolveState {
        val nodeState = reached(state.putVisitedElements(node.visited), chain)

        return node.canonical?.let { nodeState.put(MODULAR_CANONICAL_NAME, it) } ?: nodeState
    }

    /** Each site's own reach step, outermost first, on the replaying state. */
    private fun reached(state: ResolveState, chain: List<Recording.Group>): ResolveState =
        chain.fold(state) { acc, group -> group.reach?.invoke(acc) ?: acc }

    private fun execute(element: ElixirFile, state: ResolveState): Boolean =
        if (element.viewFile() == null) {
            implicitImports(element, state)
        }
        // if there is a view file then it will have implicit imports, not this template
        else {
            true
        }

    private fun execute(element: BeamModule, state: ResolveState): Boolean =
        whileIn(element.callDefinitions()) {
            ProgressManager.checkCanceled()
            execute(it, state)
        }

    protected abstract fun execute(element: BeamCallDefinition, state: ResolveState): Boolean

    private fun executeOnUnknownMacroCall(macroCall: Call, state: ResolveState): Boolean =
        if (macroCall.isAncestor(state.get(ENTRANCE), strict = true)) {
            macroCall
                .reference?.let { it as PsiPolyVariantReference }
                ?.multiResolve(false)?.asSequence()
                ?.filter(ResolveResult::isValidResult)
                ?.mapNotNull(ResolveResult::getElement)
                ?.filterIsInstance<Call>()
                ?.filter { org.elixir_lang.psi.CallDefinitionClause.capabilities(it)?.quotesArguments == true }
                ?.let { macroDefinitions ->
                    val injectedState = state.reachedThroughInjection(macroCall)

                    whileIn(macroDefinitions) { macroDefinition ->
                        executeOnUnknownMacroDefinition(macroDefinition, injectedState)
                    }
                }
                ?: true
        } else {
            true
        }

    private fun executeOnUnknownMacroDefinition(macroDefinition: Call, state: ResolveState): Boolean =
        org.elixir_lang.psi.CallDefinitionClause.head(macroDefinition)?.let { it as? Call }?.finalArguments()
            ?.lastOrNull()?.let { it as? QuotableKeywordList }?.let { keywords ->
                keywords.keywordValue("do")?.let { block ->
                    macroDefinition.stabBodyChildExpressions(forward = false)?.filterIsInstance<Call>()?.firstOrNull()
                        ?.takeIf { QuoteMacro.`is`(it) }?.let { quote ->
                            quote.stabBodyChildExpressions()?.filterIsInstance<Call>()?.filter { Unquote.`is`(it) }
                                ?.singleOrNull { unquote -> unquote.textMatches("unquote(${block.text})") }
                                ?.let { unquoteBlock ->
                                    unquoteBlock
                                        .siblingExpressions(forward = false, withSelf = false)
                                        .filterIsInstance<Call>()
                                        .let { QuoteMacro.treeWalkUp(it, state, ::execute) }
                                }
                        }
                }
            } ?: true

    private fun modularContainsEntrance(call: Call, state: ResolveState): Boolean =
        state.get(ENTRANCE)?.let { entrance ->
            if (!isForeignEntrance(call, entrance)) {
                /* Only allow scanning back down in outer nested modules for siblings.  Prevents scanning in sibling
                   nested modules in https://github.com/intellij-elixir/intellij-elixir/issues/1270 */
                modularContains(call, entrance)
            } else {
                // done by injection or viewFile
                true
            }
        } ?: false

    private fun modularContains(modular: Call, contained: PsiElement): Boolean =
        contained.ancestorSequence().filterIsInstance<Call>().firstOrNull { isModular(it) } == modular

    private fun implicitImports(element: PsiElement, state: ResolveState): Boolean {
        WalkProbe.count(WalkProbe.Counter.IMPLICIT_IMPORTS)
        val project = element.project
        // Use the entrance element (the call being resolved) for narrowedScope so the search
        // is limited to the SDK / libraries attached to the module that contains the reference.
        // Falling back to `element` covers the ElixirFile case where ENTRANCE may be absent.
        val entrance = state.get(ENTRANCE) ?: element
        val scope = narrowedScope(entrance, project)

        val implicitState = state.reachedThrough(Reach.IMPLICIT_IMPORT)
        val keepProcessing = implicitImport(project, scope, KERNEL, implicitState)

        return if (keepProcessing) {
            val modularCanonicalNameState = implicitState.put(MODULAR_CANONICAL_NAME, KERNEL_SPECIAL_FORMS)

            implicitImport(project, scope, KERNEL_SPECIAL_FORMS, modularCanonicalNameState)
        } else {
            false
        }
    }

    private fun implicitImport(project: Project, scope: GlobalSearchScope, moduleName: String, state: ResolveState): Boolean =
        if (DumbService.isDumb(project)) {
            true
        } else {
            whileIn(sourceFirstNamedElements(project, scope, moduleName)) { namedElement ->
                when (namedElement) {
                    is Call -> implicitImport(namedElement, state.putVisitedElement(namedElement))
                    is BeamModule -> implicitImport(namedElement, state)
                    else -> true
                }
            }
        }

    /** What an implicit `import` of [module] brings in. */
    private fun implicitImport(module: BeamModule, state: ResolveState): Boolean =
        whileIn(module.callDefinitions()) {
            ProgressManager.checkCanceled()
            Import.importedCapabilities(it) == null || execute(it, state)
        }

    /** [modular]'s own clauses that this processor can reach, as an implicit `import` of it brings them in. */
    private fun implicitImport(modular: Call, state: ResolveState): Boolean {
        val index = ImplicitImportIndex.of(modular)

        for (clause in targetName()?.let(index::startingWith) ?: index.clauses) {
            ProgressManager.checkCanceled()

            if (Import.importedCapabilities(clause) != null && !state.hasBeenVisited(clause)) {
                WalkProbe.count(WalkProbe.Counter.CLAUSE_HANDLER_KERNEL)

                if (!executeOnCallDefinitionClause(clause, state.putVisitedElement(clause))) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Returns the [NamedElement]s for [moduleName] within [scope] to walk, preferring source [Call]s
     * over decompiled [BeamModule] beam stubs: when the module is available as source, the beam stubs
     * are dropped entirely so a module present in both forms is not visited twice.
     *
     * Dropping the beam stubs matters for Variants-style completion, where [keepProcessing] is always
     * `true`, so a source [Call] and its [BeamModule] beam counterpart would otherwise both be visited
     * and offer every definition twice (e.g. each `Kernel.SpecialForms` macro). For resolution, where
     * [keepProcessing] stops after the first result, the surviving source [Call]s are still ordered
     * first so the walk short-circuits on source before any beam stub (which are now only present when
     * no source exists) is reached.
     */
    private fun sourceFirstNamedElements(project: Project, scope: GlobalSearchScope, moduleName: String): List<NamedElement> {
        val namedElements = buildList {
            StubIndex.getInstance().processElements(
                org.elixir_lang.psi.stub.index.ModularName.KEY,
                moduleName,
                project,
                scope,
                NamedElement::class.java
            ) { add(it); true }
        }

        return if (namedElements.any { it is Call }) {
            namedElements.filter { it is Call }
        } else {
            namedElements
        }
    }

    companion object {
        val MODULAR_CANONICAL_NAME = Key<String>("MODULAR_CANONICAL_NAME")

        private val replayDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

        /** [block] as one replay; only an outermost replay's steps are cancellation points. */
        private fun <T> replaying(block: () -> T): T {
            replayDepth.set(replayDepth.get() + 1)

            try {
                return block()
            } finally {
                replayDepth.set(replayDepth.get() - 1)
            }
        }

        private fun replayStep() {
            if (replayDepth.get() == 1) WalkProbe.cancelPoint(WalkProbe.CancelPoint.REPLAY)
        }

        @TestOnly
        fun replayDepth(): Int = replayDepth.get()

        /**
         * The `if`/`unless` arm's test: if the entrance is at compile time level of a branch, then only previous
         * siblings could possibly define this call and those will be handled by ElixirStabBody's processDeclarations.
         */
        private fun walksBranches(element: Call, state: ResolveState): Boolean {
            val branches = Branches(element)

            return !containsCompileTimeEntranceAncestorOrSelf(branches.primaryChildExpressions, state) &&
                !containsCompileTimeEntranceAncestorOrSelf(branches.alternativeChildExpressions, state)
        }

        /** The entrance is in another file than [call] (an injection or a view file), so [call]'s arm walks it whole. */
        private fun isForeignEntrance(call: Call, entrance: PsiElement): Boolean = call.containingFile != entrance.containingFile

        /**
         * The calls of [modular] to hand to [execute]. A block that runs in place is left out, as its calls are among
         * these. An `import` inside a block is not taken, as in Elixir it reaches no further than that block. From a
         * block that only its expansion runs, such as a `test` body, only the calls
         * [isTakenFromExpansionDependentBlock] names are taken.
         */
        @RequiresReadLock
        fun modularCallsToExecute(modular: Call): List<Call> {
            ThreadingAssertions.assertReadAccess()

            return CachedValuesManager.getCachedValue(modular) {
                val calls = modularChildCalls(modular).filter { call ->
                    macroRole(call).block != MacroRole.Block.IN_PLACE &&
                            (isTakenFromExpansionDependentBlock(call) ||
                                    !isInExpansionDependentBlock(call, modular) && !isNestedImport(call, modular))
                }

                CachedValueProvider.Result.create(calls, modular.containingFile)
            }
        }

        private fun isInExpansionDependentBlock(call: Call, modular: Call): Boolean =
            generateSequence(call.enclosingMacroCall()) { it.enclosingMacroCall() }
                .takeWhile { it != modular }
                .any { macroRole(it).block == MacroRole.Block.EXPANSION_DEPENDENT }

        private fun isNestedImport(call: Call, modular: Call): Boolean =
            call.enclosingMacroCall() != modular && Import.`is`(call)

        /**
         * The calls taken from a block that only its expansion runs: those that define something, and a `use` for what
         * it injects. The `EEx` and `Mix.Generator` definers are matched by shape, as resolving whose they are needs
         * the [ResolveState] that [execute] has.
         */
        private fun isTakenFromExpansionDependentBlock(call: Call): Boolean =
            DeclaringForm.shapedForm(call) != null || Use.`is`(call)

        /**
         * The `state.get(ENTRANCE)` is one of the [children], is inside one, or any calls in the way are compile-time
         * conditional logic like `if`s.
         */
        private fun containsCompileTimeEntranceAncestorOrSelf(
            children: Sequence<PsiElement>,
            state: ResolveState
        ): Boolean =
            state.get(ENTRANCE).let { entrance ->
                val ancestors = compileTimeAncestors(entrance).toList()

                children.any { it.isEquivalentTo(entrance) || it in ancestors || it.isAncestor(entrance) }
            }

        /**
         * Whether [entrance] or one of its [compileTimeAncestors] is among [modular]'s `macroChildCallList`, found by
         * walking up from [entrance], so the cost is its depth rather than the size of [modular]'s body.
         */
        private fun isAtCompileTimeLevel(modular: Call, entrance: PsiElement): Boolean =
            (sequenceOf(entrance) + compileTimeAncestors(entrance)).any {
                WalkProbe.count(WalkProbe.Counter.GATE)
                isMacroChild(modular, it)
            }

        /** The `if`s and `unless`es that [entrance] is directly in, innermost first, through blocks and containers between. */
        private fun compileTimeAncestors(entrance: PsiElement): Sequence<Call> = sequence {
            var ancestor: PsiElement? = entrance.parent

            while (ancestor != null) {
                ancestor = when (ancestor) {
                    is ElixirDoBlock,
                    is ElixirBlockList, is ElixirBlockItem,
                    is ElixirStab, is ElixirStabBody,
                    is ElixirAccessExpression, is ElixirParentheticalStab,
                    is ElixirList, is ElixirTuple,
                    is ElixirMapOperation, is ElixirStructOperation,
                    is ElixirMapArguments, is ElixirMapConstructionArguments, is ElixirMapUpdateArguments,
                    is ElixirKeywords, is ElixirNoParenthesesKeywords,
                    is ElixirAssociations, is ElixirAssociationsBase,
                    is ElixirContainerAssociationOperation -> ancestor.parent
                    is QuotableKeywordPair -> ancestor.selfOrEnclosingMacroCall() ?: ancestor.parent
                    is Call ->
                        if (If.`is`(ancestor) || Unless.`is`(ancestor)) {
                            yield(ancestor)
                            ancestor.parent
                        } else {
                            null
                        }
                    else -> null
                }
            }
        }

        /** Whether `modular.macroChildCallList()` holds [candidate]. */
        private fun isMacroChild(modular: Call, candidate: PsiElement): Boolean {
            if (candidate !is Call) return false

            val doBlock = modular.doBlock

            return if (doBlock != null) {
                (doBlock.stab?.children?.singleOrNull() as? ElixirStabBody)
                    ?.let { stabBody -> isTraversed(candidate.parent, stabBody) }
                    ?: false
            } else {
                modular.oneLinerDoValue() == candidate
            }
        }

        /**
         * Whether `macroChildCallList` takes the calls directly in [container] when it reads [stabBody]: the stab body
         * itself, or a list that is the first child of access expressions in a container it takes.
         */
        private fun isTraversed(container: PsiElement?, stabBody: ElixirStabBody): Boolean {
            if (container == stabBody) return true
            if (container !is ElixirList) return false

            var child: PsiElement = container
            var parent = container.parent

            while (parent is ElixirAccessExpression && parent.firstChild == child) {
                child = parent
                parent = parent.parent
            }

            return child != container && isTraversed(parent, stabBody)
        }
    }
}
