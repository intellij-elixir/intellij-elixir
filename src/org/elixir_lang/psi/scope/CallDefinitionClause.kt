package org.elixir_lang.psi.scope

import org.elixir_lang.psi.scope.Reach.Companion.reachedThrough
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.isAncestor
import org.elixir_lang.Name
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.ecto.query.WindowAPI
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.psi.ex_unit.Case
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.call.*
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.scope.WhileIn.throughout
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.resolver.narrowedScope

abstract class CallDefinitionClause : PsiScopeProcessor {
    /*
     * Public Instance Methods
     */

    /**
     * @param element candidate element.
     * @param state   current state of resolver.
     * @return false to stop processing.
     */
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

    /** Whether the walk wants what an `import`, explicit or the implicit `import Kernel`, brings in. */
    protected open val followsImports: Boolean = true

    /**
     * The name that starts every name this processor can ever reach, or `null` if it needs every declaration in a
     * scope (completion's [org.elixir_lang.psi.scope.call_definition_clause.Variants] has no single target).
     * [org.elixir_lang.psi.scope.call_definition_clause.MultiResolve] overrides this with its own `name` -
     * knowing it lets the modular branch below look those names up in [CallableTable] instead of walking every
     * entry in the scope for a resolve that can only ever reach a few of them.
     */
    protected open fun targetName(): Name? = null

    /*
     * Private Instance Methods
     */

    private fun execute(element: Call, state: ResolveState): Boolean =
        CallableDeclaration.formOf(element, state)
            ?.let { form -> executeOnDeclaration(element, form, state) }
            ?: executeOnNonDeclaration(element, state)

    private fun executeOnDeclaration(element: Call, form: CallableDeclaration.Form, state: ResolveState): Boolean =
        when (form) {
            CallableDeclaration.Form.CLAUSE -> executeOnCallDefinitionClause(element, state)
            CallableDeclaration.Form.CALLBACK ->
                executeOnCallback(element as AtUnqualifiedNoParenthesesCall<*>, state)
            CallableDeclaration.Form.DELEGATION -> executeOnDelegation(element, state)
            CallableDeclaration.Form.EXCEPTION -> executeOnException(element, state)
            CallableDeclaration.Form.EEX_FUNCTION_FROM -> executeOnEExFunctionFrom(element, state)
            CallableDeclaration.Form.GENERATOR_EMBED -> executeOnMixGeneratorEmbed(element, state)
        }

    private fun executeOnNonDeclaration(element: Call, state: ResolveState): Boolean {
        // `for` tracks what it has visited, so it keeps its own walk.
        if (For.`is`(element)) return For.treeWalkDown(element, state, ::execute)

        element
            .takeIf { org.elixir_lang.psi.CallDefinitionClause.isModuleScopeConstruct(it) }
            ?.let { org.elixir_lang.psi.CallDefinitionClause.moduleScopeCalls(it) }
            ?.let { moduleScopeCalls ->
            // If the entrance is at compile time level of these calls, only previous siblings could define this call,
            // and ElixirStabBody's processDeclarations handles those.
            if (!containsCompileTimeEntranceAncestorOrSelf(moduleScopeCalls.asSequence(), state)) {
                val listed = listedState(element, state)

                throughout(moduleScopeCalls) { execute(it, listed) }
            }

            return true
        }

        return when {
            Import.`is`(element) -> {
                if (followsImports) try {
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

            (isModular(element) ||
                    Case.isChild(element, state))
                    && modularContainsEntrance(element, state) -> {
                // If the entrance is at compile time level of the module's own scope, then only previous siblings
                // could possibly define this call and those will be handled by ElixirStabBody's
                // processDeclarations. Walks up from the entrance instead of scanning down the scope's calls: that
                // scan was itself the majority of the cost profiled live against a large decompiled file (#4123's
                // own example, `elixir_parser.beam`); this is bounded by nesting depth instead, typically tiny.
                if (!atCompileTimeLevel(element, state.get(ENTRANCE))) {
                    // `CallableTable` answers, for the whole scope at once and cached against PSI changes,
                    // which calls declare a callable and what - see its class doc for why the handful of
                    // forms that decide by resolving a call's own reference are excluded and walked below
                    // exactly as before this table existed. `reachableFrom` reproduces the entrance-relative
                    // guards the live walk applies to an `import`/`use`/`if`/`unless` wrapper crossed getting
                    // to an entry; `Path.onto` restores the state that wrapper would have recorded.
                    // `of`, not `ofOrNull`: this walk never reaches `element`'s own build recursively, only
                    // `CallableTable.ofOrNull`'s DSL-membership callers do (see its doc) - if that ever
                    // stopped holding, `ofOrNull` would need to replace this too.
                    val table = CallableTable.of(element)
                    val entrance = state.get(ENTRANCE)
                    val listed = listedState(element, state)
                    val walksImports = followsImports && Import.followsImports(listed)

                    // A caller that knows the one name it can ever match (`MultiResolve`) only needs that
                    // name's own entries, an O(1) lookup instead of a walk of every entry in the module -
                    // `Variants` (completion) has no single target and still needs `entries` in full.
                    val candidateEntries = targetName()?.let { table.declaringStartingWith(it) } ?: table.entries

                    for (entry in candidateEntries) {
                        if (entry.reachableFrom(entrance, walksImports)) {
                            executeOnDeclaration(entry.call, entry.form, entry.path.onto(listed))
                        }
                    }

                    for (candidate in table.liveCandidates) {
                        if (candidate.reachableFrom(entrance, walksImports)) {
                            execute(candidate.call, candidate.path.onto(listed))
                        }
                    }

                    for (beamCallDefinition in table.beamCallDefinitions) {
                        if (beamCallDefinition.reachableFrom(entrance, walksImports)) {
                            execute(beamCallDefinition.callDefinition, beamCallDefinition.path.onto(listed))
                        }
                    }
                }

                // Only check MultiResolve.keepProcessing at the end of a Module to all multiple arities
                keepProcessing() &&
                        // the implicit `import Kernel` and `import Kernel.SpecialForms`
                        (!followsImports || implicitImports(element, state))
            }
            QuoteMacro.`is`(element) -> if (!state.hasBeenVisited(element)) {
                QuoteMacro.treeWalkUp(element, state, ::execute)
            } else {
                true
            }
            Use.`is`(element) -> {
                // Finishes the quote, as a module's own children are.
                Use.treeWalkUpThroughout(element, state, ::execute)

                true
            }
            org.elixir_lang.ecto.Schema.isChild(element, state) -> {
                org.elixir_lang.ecto.Schema.walkChild(element, state, ::execute)
            }
            // doesn't declare calls, but if this is the scope, then `Ecto.Query.API` is resolvable
            org.elixir_lang.ecto.Query.isChild(element, state) -> {
                org.elixir_lang.ecto.Query.walkChild(element, state, ::execute)
            }
            org.elixir_lang.ecto.query.API.`is`(element, state) -> {
                org.elixir_lang.ecto.query.API.treeWalkUp(element, state, ::execute)
            }
            WindowAPI.`is`(element, state) -> {
                WindowAPI.treeWalkUp(element, state, ::execute)
            }
            hasDoBlockOrKeyword(element) -> executeOnUnknownMacroCall(element, state)
            else -> true
        }
    }

    private fun execute(element: ElixirFile, state: ResolveState): Boolean =
        if (element.viewFile() == null) {
            !followsImports || implicitImports(element, state)
        }
        // if there is a view file then it will have implicit imports, not this template
        else {
            true
        }

    private fun execute(element: BeamModule, state: ResolveState): Boolean =
        whileIn(element.callDefinitions()) {
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
                ?.filter { CallableDeclaration.syntacticCapabilitiesOf(it)?.quotesArguments == true }
                ?.let { macroDefinitions ->
                    whileIn(macroDefinitions) { macroDefinition ->
                        executeOnUnknownMacroDefinition(macroDefinition, state)
                    }
                }
                ?: true
        } else {
            true
        }

    /** What the macro's `quote` defines ahead of its `do` block, which a call in the block sees. */
    private fun executeOnUnknownMacroDefinition(macroDefinition: Call, state: ResolveState): Boolean =
        org.elixir_lang.psi.CallDefinitionClause.moduleBodyUnquoteOfBlock(macroDefinition)
            ?.siblingExpressions(forward = false, withSelf = false)
            ?.filterIsInstance<Call>()
            ?.let { QuoteMacro.treeWalkUp(it, state, ::execute) }
            ?: true

    /**
     * [state] for walking [element]'s listing: for what it defines, as an `import` is lexical and the walk up from an
     * entrance in the same file has passed every one that precedes it. An entrance in another file, a view template or
     * an injection, is not passed through the module's body, so the listing is what brings its imports.
     */
    private fun listedState(element: Call, state: ResolveState): ResolveState =
        if (state.get(ENTRANCE)?.containingFile == element.containingFile) Import.definitionsOnly(state) else state

    private fun modularContainsEntrance(call: Call, state: ResolveState): Boolean =
        state.get(ENTRANCE)?.let { entrance ->
            val callFile = call.containingFile

            if (callFile == entrance.containingFile) {
                /* Only allow scanning back down in outer nested modules for siblings.  Prevents scanning in sibling
                   nested modules in https://github.com/intellij-elixir/intellij-elixir/issues/1270 */
                modularContains(call, entrance)
            } else {
                // done by injection or viewFile
                true
            }
        } ?: false

    private fun modularContains(modular: Call, contained: PsiElement): Boolean =
        org.elixir_lang.psi.CallDefinitionClause.enclosingModular(contained) == modular

    private fun implicitImports(element: PsiElement, state: ResolveState): Boolean {
        val project = element.project
        // Use the entrance element (the call being resolved) for narrowedScope so the search
        // is limited to the SDK / libraries attached to the module that contains the reference.
        // Falling back to `element` covers the ElixirFile case where ENTRANCE may be absent.
        val entrance = state.get(ENTRANCE) ?: element
        val scope = narrowedScope(entrance, project)
        val entranceFile = entrance.containingFile

        val implicitState = state.reachedThrough(Reach.IMPLICIT_IMPORT)
        val keepProcessing = implicitImport(entranceFile, scope, KERNEL, KERNEL_NAMED_ELEMENTS_KEY, implicitState)

        return if (keepProcessing) {
            val modularCanonicalNameState = implicitState.put(MODULAR_CANONICAL_NAME, KERNEL_SPECIAL_FORMS)

            implicitImport(
                entranceFile, scope, KERNEL_SPECIAL_FORMS, KERNEL_SPECIAL_FORMS_NAMED_ELEMENTS_KEY,
                modularCanonicalNameState
            )
        } else {
            false
        }
    }

    private fun implicitImport(
        entranceFile: PsiFile,
        scope: GlobalSearchScope,
        moduleName: String,
        cacheKey: Key<CachedValue<List<NamedElement>>>,
        state: ResolveState
    ): Boolean =
        DumbService.isDumb(entranceFile.project) ||
            whileIn(cachedSourceFirstNamedElements(entranceFile, scope, moduleName, cacheKey)) { namedElement ->
                when (namedElement) {
                    is Call -> implicitImportModular(namedElement, state)
                    else -> Import.treeWalkUpImplicitly(namedElement, state.putVisitedElement(namedElement), ::execute)
                }
            }

    /**
     * `Kernel`/`Kernel.SpecialForms`'s own declarations, through the same [CallableTable] the modular branch
     * of [execute] uses for the module the entrance is actually in. Previously
     * [Modular.callDefinitionClauseCallWhile], which materializes `macroChildCalls()` and runs
     * [org.elixir_lang.psi.CallDefinitionClause.is] over every one of them before any name is consulted -
     * the name only filters later, in
     * [org.elixir_lang.psi.scope.call_definition_clause.MultiResolve]'s own `addIfNameOrArityToResolveResults`.
     * That is the O(module size) walk [#4123](https://github.com/intellij-elixir/intellij-elixir/issues/4123)
     * removed from the entrance's own module, still being paid here on every resolve that falls through to the
     * implicit import - twice, once per module - and it dominated a live thread-dump sample of
     * `elixir_parser.beam` (152 of 300) after the earlier fixes landed. [CallableTable.declaring] answers the
     * same question as a map lookup against a table cached per modular, so `Kernel`'s is built once per PSI
     * change for the whole project rather than per walk.
     *
     * [CallableTable.ofOrNull], not [CallableTable.of]: a DSL-membership check inside a table build resolves a
     * candidate call's own reference, which can reach this implicit import while that same modular's table is
     * mid-build on this thread - see `ofOrNull`'s own doc. The full walk is still the fallback for that case.
     */
    private fun implicitImportModular(modular: Call, state: ResolveState): Boolean {
        val modularResolveState = state.putVisitedElement(modular)
        val table = CallableTable.ofOrNull(modular)

        return if (table != null) {
            val listed = moduleScopeCallSet(modular)
            // A caller that knows the one name it can ever match (`MultiResolve`) only needs that name's own
            // entries; `Variants` (completion) has no single target and still needs every one.
            val candidateEntries = targetName()?.let { table.declaringStartingWith(it) } ?: table.entries

            whileIn(candidateEntries) { entry ->
                if (entry.call in listed &&
                    !modularResolveState.hasBeenVisited(entry.call) &&
                    Import.bringsInImplicitly(CallableDeclaration.Declared.Source(entry.call, entry.form), modularResolveState)
                ) {
                    executeOnDeclaration(
                        entry.call,
                        entry.form,
                        entry.path.onto(modularResolveState).putVisitedElement(entry.call)
                    )
                } else {
                    true
                }
            }
        } else {
            Import.treeWalkUpImplicitly(modular, modularResolveState, ::execute)
        }
    }

    /**
     * [sourceFirstNamedElements], cached per [entranceFile] - `Kernel`/`Kernel.SpecialForms`'s own
     * declarations don't change from one resolve to the next within the same file, but
     * [sourceFirstNamedElements] ran this project-scoped stub-index search fresh on every single resolve
     * that reached a modular scope, unconditionally. Profiling `elixir_parser.beam` live (#4123's own
     * example) attributed 14.5% of the resolve cost to `implicitImport`/`implicitImports` for exactly
     * this reason. `PsiModificationTracker.MODIFICATION_COUNT`, not a narrower per-file stamp, matches
     * [org.elixir_lang.psi.CallableTable.of]'s own dependency: [scope] can include other files (the SDK,
     * libraries) whose changes wouldn't touch [entranceFile] itself.
     */
    private fun cachedSourceFirstNamedElements(
        entranceFile: PsiFile,
        scope: GlobalSearchScope,
        moduleName: String,
        cacheKey: Key<CachedValue<List<NamedElement>>>
    ): List<NamedElement> =
        CachedValuesManager.getCachedValue(entranceFile, cacheKey) {
            CachedValueProvider.Result(
                sourceFirstNamedElements(entranceFile.project, scope, moduleName),
                PsiModificationTracker.MODIFICATION_COUNT
            )
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

        // Created once here, not per-resolve where they're used (`MultiResolve`/`Variants` are instantiated
        // fresh per resolve) - a `Key` created afresh each time would never find a previous resolve's cache
        // entry under it, silently defeating `cachedSourceFirstNamedElements`'s caching entirely.
        private val KERNEL_NAMED_ELEMENTS_KEY: Key<CachedValue<List<NamedElement>>> =
            Key.create("org.elixir_lang.psi.scope.CallDefinitionClause.KERNEL_NAMED_ELEMENTS")
        private val KERNEL_SPECIAL_FORMS_NAMED_ELEMENTS_KEY: Key<CachedValue<List<NamedElement>>> =
            Key.create("org.elixir_lang.psi.scope.CallDefinitionClause.KERNEL_SPECIAL_FORMS_NAMED_ELEMENTS")

        /**
         * [containsCompileTimeEntranceAncestorOrSelf] for [element]'s module scope, without scanning it: only
         * [entrance]'s own chain up (bounded by nesting depth) can land on one of the calls to compare. A module's scope
         * is its [org.elixir_lang.psi.CallDefinitionClause.modularChildCalls] listing, which looks through `if`, `case`
         * and the like, so the walk asks the listing's set at each call up to the first module-scope boundary.
         */
        private fun atCompileTimeLevel(element: Call, entrance: PsiElement): Boolean {
            if (!isModular(element)) return modularContainsEntranceAtCompileTimeLevel(element, entrance)

            val listed = moduleScopeCallSet(element)

            if (entrance is Call && entrance in listed) return true

            var ancestor: PsiElement? = entrance.parent

            while (ancestor != null && ancestor !is PsiFile) {
                if (ancestor is Call) {
                    if (isModular(ancestor) || org.elixir_lang.psi.CallDefinitionClause.moduleScopeBoundary(ancestor)) {
                        return false
                    }

                    if (ancestor in listed) return true
                }

                ancestor = ancestor.parent
            }

            return false
        }

        private val MODULE_SCOPE_CALL_SET: Key<CachedValue<Set<Call>>> = Key.create("CallDefinitionClause.MODULE_SCOPE_CALL_SET")

        /** [org.elixir_lang.psi.CallDefinitionClause.modularChildCalls] as a set, cached until the next change. */
        private fun moduleScopeCallSet(modular: Call): Set<Call> =
            CachedValuesManager.getCachedValue(modular, MODULE_SCOPE_CALL_SET) {
                CachedValueProvider.Result.create(
                    org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(modular).toHashSet(),
                    PsiModificationTracker.MODIFICATION_COUNT
                )
            }

        /**
         * [containsCompileTimeEntranceAncestorOrSelf], without materializing [element]'s children via
         * [org.elixir_lang.psi.impl.call.macroChildCallSequence]: walks up from [entrance] instead of
         * scanning down from [element] - only [entrance]'s own compile-time-ancestor chain (bounded by
         * nesting depth) can ever land on one of [element]'s direct macro children, so there is no need to
         * enumerate the rest of them to answer this.
         */
        private fun modularContainsEntranceAtCompileTimeLevel(element: Call, entrance: PsiElement): Boolean {
            val childScope = element.macroChildScope() ?: return false

            if (childScope.containsAsMacroChild(entrance)) return true

            var ancestor: PsiElement? = entrance.parent

            while (ancestor != null) {
                when (ancestor) {
                    is ElixirDoBlock, is ElixirBlockList, is ElixirBlockItem, is ElixirStab, is ElixirStabBody ->
                        ancestor = ancestor.parent
                    is Call -> {
                        if (!If.`is`(ancestor) && !Unless.`is`(ancestor)) return false
                        if (childScope.containsAsMacroChild(ancestor)) return true

                        ancestor = ancestor.parent
                    }
                    else -> return false
                }
            }

            return false
        }

        /**
         * The single scope [org.elixir_lang.psi.impl.call.macroChildCallList] would collect this call's
         * direct macro children from - the `ElixirStabBody` of a `do...end` block, or the one [Call] at a
         * one-liner `do:` keyword. Mirrors that function's own two shapes without building the list; `null`
         * if this call has neither.
         */
        private fun Call.macroChildScope(): PsiElement? {
            val doBlock = doBlock

            return if (doBlock != null) {
                doBlock.stab?.stabBody
            } else {
                val potentialKeywords = finalArguments()?.lastOrNull()

                (potentialKeywords as? QuotableKeywordList)
                    ?.quotableKeywordPairList()
                    ?.firstOrNull()
                    ?.takeIf { it.keywordKey.text == "do" }
                    ?.keywordValue as? Call
            }
        }

        /** Whether [candidate] is one of the macro children [this] scope (from [Call.macroChildScope])
         *  collects - for an `ElixirStabBody`, [candidate]'s own parent, unwrapped through any
         *  `ElixirAccessExpression` wrapper the same way [org.elixir_lang.psi.impl.macroChildCallList]'s own
         *  recursion does; for the one-liner `do:` shape, [this] scope *is* the single child, so direct
         *  equivalence is the whole check. */
        private fun PsiElement.containsAsMacroChild(candidate: PsiElement): Boolean =
            when (this) {
                is ElixirStabBody -> {
                    var parent: PsiElement? = candidate.parent

                    while (parent is ElixirAccessExpression) {
                        parent = parent.parent
                    }

                    parent != null && parent.isEquivalentTo(this)
                }
                else -> this.isEquivalentTo(candidate)
            }

        /**
         * The `state.get(ENTRANCE)` is one of the `childCalls` OR any calls in the way are compile-time conditional
         * logic like `if`s
         */
        private fun containsCompileTimeEntranceAncestorOrSelf(
            childCalls: Sequence<Call>,
            state: ResolveState
        ): Boolean =
            containsCompileTimeAncestorOrSelf(childCalls, state.get(ENTRANCE))

        /** Shared with [org.elixir_lang.psi.CallableTable.Entry.reachableFrom], which needs the same
         *  entrance-relative check for an `if`/`unless` wrapper crossed while the table was built. */
        internal fun containsCompileTimeAncestorOrSelf(childCalls: Sequence<Call>, entrance: PsiElement): Boolean =
            childCalls.any { isCompileTimeAncestorOrSelf(it, entrance) }

        private fun isCompileTimeAncestorOrSelf(call: Call, entrance: PsiElement): Boolean =
            call.isEquivalentTo(entrance) || isCompileTimeAncestor(call, entrance.parent)

        /** Whether [stop] is reached from [ancestor] up without crossing a modular or a module-scope boundary. */
        private tailrec fun isCompileTimeAncestor(stop: Call, ancestor: PsiElement?): Boolean =
            when {
                ancestor == null || ancestor is PsiFile -> false
                ancestor !is Call -> isCompileTimeAncestor(stop, ancestor.parent)
                isModular(ancestor) || org.elixir_lang.psi.CallDefinitionClause.moduleScopeBoundary(ancestor) -> false
                ancestor.isEquivalentTo(stop) -> true
                else -> isCompileTimeAncestor(stop, ancestor.parent)
            }

    }
}
