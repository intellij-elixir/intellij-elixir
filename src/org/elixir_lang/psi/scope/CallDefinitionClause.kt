package org.elixir_lang.psi.scope

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
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.name.Module.KERNEL_SPECIAL_FORMS
import org.elixir_lang.psi.ex_unit.Case
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.ancestorSequence
import org.elixir_lang.psi.impl.call.*
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.siblingExpressions
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

    /**
     * The one name this processor can ever match, or `null` if it needs every declaration in a scope
     * (completion's [org.elixir_lang.psi.scope.call_definition_clause.Variants] has no single target).
     * [org.elixir_lang.psi.scope.call_definition_clause.MultiResolve] overrides this with its own `name` -
     * knowing it lets the modular branch below look a name up in [CallableTable] instead of walking every
     * entry in the scope for a resolve that can only ever match one of them.
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

    private fun executeOnNonDeclaration(element: Call, state: ResolveState): Boolean =
        when {
            For.`is`(element) -> For.treeWalkDown(element, state, ::execute)
            If.`is`(element) || Unless.`is`(element) -> {
                // If the entrance os at compile time level of `childCalls`, then only previous siblings could
                // possibly define this call and those will be handled by ElixirStabBody's processDeclarations
                val branches = Branches(element)

                val primaryChildCalls = branches.primaryChildExpressions.filterIsInstance<Call>()
                val walkPrimary = !containsCompileTimeEntranceAncestorOrSelf(primaryChildCalls, state)

                val alternativeChildCalls = branches.alternativeChildExpressions.filterIsInstance<Call>()
                val walkAlternative = !containsCompileTimeEntranceAncestorOrSelf(alternativeChildCalls, state)

                if (walkPrimary && walkAlternative) {
                    val childCalls = primaryChildCalls + alternativeChildCalls

                    for (childCall in childCalls) {
                        execute(childCall, state)
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

            (isModular(element) ||
                    Case.isChild(element, state))
                    && modularContainsEntrance(element, state) -> {
                // If the entrance is at compile time level of the module's own children, then only previous
                // siblings could possibly define this call and those will be handled by ElixirStabBody's
                // processDeclarations. Walks up from the entrance instead of scanning down from `element`'s
                // children (`element.macroChildCallSequence()`, previously called here unconditionally) -
                // materializing that list on every resolve reaching a modular scope was itself the majority
                // of the cost profiled live against a large decompiled file (#4123's own example,
                // `elixir_parser.beam`); this is bounded by nesting depth instead, typically tiny.
                if (!modularContainsEntranceAtCompileTimeLevel(element, state.get(ENTRANCE))) {
                    // `CallableTable` answers, for the whole scope at once and cached against PSI changes,
                    // which calls declare a callable and what - see its class doc for why the handful of
                    // forms that decide by resolving a call's own reference are excluded and walked below
                    // exactly as before this table existed. `reachableFrom` reproduces the entrance-relative
                    // guards the live walk applies to an `import`/`use`/`if`/`unless` wrapper crossed getting
                    // to an entry; `putVisitedElements` restores the path that wrapper would have recorded.
                    // `of`, not `ofOrNull`: this walk never reaches `element`'s own build recursively, only
                    // `CallableTable.ofOrNull`'s DSL-membership callers do (see its doc) - if that ever
                    // stopped holding, `ofOrNull` would need to replace this too.
                    val table = CallableTable.of(element)
                    val entrance = state.get(ENTRANCE)

                    // A caller that knows the one name it can ever match (`MultiResolve`) only needs that
                    // name's own entries, an O(1) lookup instead of a walk of every entry in the module -
                    // `Variants` (completion) has no single target and still needs `entries` in full.
                    val candidateEntries = targetName()?.let { table.declaring(it) } ?: table.entries

                    for (entry in candidateEntries) {
                        if (entry.reachableFrom(entrance)) {
                            executeOnDeclaration(entry.call, entry.form, state.putVisitedElements(entry.path.visitedElements))
                        }
                    }

                    for (candidate in table.liveCandidates) {
                        if (candidate.reachableFrom(entrance)) {
                            execute(candidate.call, state.putVisitedElements(candidate.path.visitedElements))
                        }
                    }

                    for (beamCallDefinition in table.beamCallDefinitions) {
                        if (beamCallDefinition.reachableFrom(entrance)) {
                            execute(beamCallDefinition.callDefinition, state.putVisitedElements(beamCallDefinition.path.visitedElements))
                        }
                    }
                }

                // Only check MultiResolve.keepProcessing at the end of a Module to all multiple arities
                keepProcessing() &&
                        // the implicit `import Kernel` and `import Kernel.SpecialForms`
                        implicitImports(element, state)
            }
            QuoteMacro.`is`(element) -> if (!state.hasBeenVisited(element)) {
                QuoteMacro.treeWalkUp(element, state, ::execute)
            } else {
                true
            }
            Use.`is`(element) -> {
                Use.treeWalkUp(element, state, ::execute)

                true
            }
            element.isCalling(KERNEL, TRY) -> {
                element.whileInStabBodyChildExpressions { childExpression ->
                    execute(childExpression, state)
                }
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
                ?.filter { org.elixir_lang.psi.CallDefinitionClause.isMacro(it) }
                ?.let { macroDefinitions ->
                    whileIn(macroDefinitions) { macroDefinition ->
                        executeOnUnknownMacroDefinition(macroDefinition, state)
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
        contained.ancestorSequence().filterIsInstance<Call>().firstOrNull { isModular(it) } == modular

    private fun implicitImports(element: PsiElement, state: ResolveState): Boolean {
        val project = element.project
        // Use the entrance element (the call being resolved) for narrowedScope so the search
        // is limited to the SDK / libraries attached to the module that contains the reference.
        // Falling back to `element` covers the ElixirFile case where ENTRANCE may be absent.
        val entrance = state.get(ENTRANCE) ?: element
        val scope = narrowedScope(entrance, project)
        val entranceFile = entrance.containingFile

        val keepProcessing = implicitImport(entranceFile, scope, KERNEL, KERNEL_NAMED_ELEMENTS_KEY, state)

        return if (keepProcessing) {
            val modularCanonicalNameState = state.put(MODULAR_CANONICAL_NAME, KERNEL_SPECIAL_FORMS)

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
        if (DumbService.isDumb(entranceFile.project)) {
            true
        } else {
            whileIn(cachedSourceFirstNamedElements(entranceFile, scope, moduleName, cacheKey)) { namedElement ->
                when (namedElement) {
                    is Call -> implicitImportModular(namedElement, state)
                    is BeamModule -> whileIn(namedElement.callDefinitions()) {
                        execute(it, state)
                    }
                    else -> true
                }
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
            val entrance = state.get(ENTRANCE)
            // A caller that knows the one name it can ever match (`MultiResolve`) only needs that name's own
            // entries; `Variants` (completion) has no single target and still needs every one.
            val candidateEntries = targetName()?.let { table.declaring(it) } ?: table.entries

            whileIn(candidateEntries) { entry ->
                if (entry.exportedByItsModular() &&
                    entry.reachableFrom(entrance) &&
                    !modularResolveState.hasBeenVisited(entry.call)
                ) {
                    executeOnDeclaration(
                        entry.call,
                        entry.form,
                        modularResolveState
                            .putVisitedElements(entry.path.visitedElements)
                            .putVisitedElement(entry.call)
                    )
                } else {
                    true
                }
            }
        } else {
            Modular.callDefinitionClauseCallWhile(modular, modularResolveState) { callDefinitionClause, accResolveState ->
                executeOnCallDefinitionClause(callDefinitionClause, accResolveState)
            }
        }
    }

    /**
     * Whether an entry is something the modular that owns the table *exports*, rather than merely something
     * that modular can *call*. [CallableTable] answers the second, larger question - it descends through
     * `import` collecting the imported module's declarations, because they are callable from inside the
     * importing module - and only an `import`'s own results have to be dropped when the question is what a
     * third party gets by importing that modular in turn: `import` is not transitive in Elixir.
     *
     * Every other way the table reaches a declaration keeps it. A `use` injects its `__using__` body into
     * the using module, so the declaration is that module's own and is re-exported (which is why
     * [CallableTable.Entry.declaringModuleName] is the wrong test here - the injected `def` sits physically
     * in the *using* module's `quote`, so it names that module, not this one). An `if`/`unless` only decides
     * *whether* a declaration exists, not whose it is. `for`/`quote`/`try` are not recorded as wrappers at
     * all and need no test.
     *
     * Each of those three is what `elixir` itself does, not a reading of the docs: compiling `import M`
     * against an `M` that only `import`ed the name fails with "expected C1 to define such a function or for
     * it to be imported, but none are available", while the same import of a `use`-injected `def` and of an
     * `if`-guarded `def` both compile.
     *
     * Only ever consulted for the implicit `import Kernel`/`import Kernel.SpecialForms`
     * ([implicitImportModular]) - the modular branch of [execute] is asking the callable question, about the
     * module the entrance is inside, and must not filter. Nothing about `Kernel` makes the rule specific to
     * it; `Kernel` is just the only module whose [CallableTable] is read to answer what a *different* file
     * may call unqualified. An explicit `import` resolves through `Import.treeWalkUp`, which is handed only
     * declaration calls from the imported module's own children and so cannot follow a nested `import` -
     * pinned by `reference/callable/ImportIsNotTransitiveTest`.
     */
    private fun CallableTable.Entry.exportedByItsModular(): Boolean = path.wrappers.none { Import.`is`(it) }

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
                when {
                    ancestor is ElixirDoBlock || ancestor is ElixirBlockList || ancestor is ElixirBlockItem ||
                            ancestor is ElixirStab || ancestor is ElixirStabBody ->
                        ancestor = ancestor.parent
                    ancestor is Call && (If.`is`(ancestor) || Unless.`is`(ancestor)) -> {
                        if (childScope.containsAsMacroChild(ancestor)) return true

                        ancestor = ancestor.parent
                    }
                    else -> return false
                }
            }

            return false
        }

        /**
         * The single scope [org.elixir_lang.psi.impl.call.macroChildCallList] would collect [element]'s
         * direct macro children from - the `ElixirStabBody` of a `do...end` block, or the one [Call] at a
         * one-liner `do:` keyword. Mirrors that function's own two shapes without building the list; `null`
         * if [element] has neither.
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
            state.get(ENTRANCE).let { entrance ->
                containsCompileTimeAncestorOrSelf(childCalls, entrance)
            }

        /** Shared with [org.elixir_lang.psi.CallableTable.Entry.reachableFrom], which needs the same
         *  entrance-relative check for an `if`/`unless` wrapper crossed while the table was built. */
        internal fun containsCompileTimeAncestorOrSelf(childCalls: Sequence<Call>, entrance: PsiElement): Boolean =
            childCalls.any { isCompileTimeAncestorOrSelf(it, entrance) }

        private fun isCompileTimeAncestorOrSelf(call: Call, entrance: PsiElement): Boolean =
            call.isEquivalentTo(entrance) || isCompileTimeAncestor(call, entrance.parent)

        private fun isCompileTimeAncestor(stop: Call, ancestor: PsiElement?): Boolean =
            when (ancestor) {
                is ElixirDoBlock,
                is ElixirBlockList, is ElixirBlockItem,
                is ElixirStab, is ElixirStabBody ->
                    isCompileTimeAncestor(stop, ancestor.parent)
                is Call -> when {
                    If.`is`(ancestor) || Unless.`is`(ancestor) ->
                        // the `stop` is an `if` or `unless`
                        ancestor.isEquivalentTo(stop) ||
                                // there is an `if` or `unless` wrapping the original `ancestor`, but need to
                                // confirm all levels above are also `if` or `unless` until `stop`.
                                isCompileTimeAncestor(stop, ancestor.parent)
                    else -> false
                }
                else -> false
            }

    }
}
