package org.elixir_lang.psi.scope

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.isAncestor
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
                val childCalls = element.macroChildCallSequence()

                // If the entrance is at compile time level of `childCalls`, then only previous siblings could possibly define
                // this call and those will be handled by ElixirStabBody's processDeclarations.
                if (!containsCompileTimeEntranceAncestorOrSelf(childCalls, state)) {
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

                    for (entry in table.entries) {
                        if (entry.reachableFrom(entrance)) {
                            executeOnDeclaration(entry.call, entry.form, state.putVisitedElements(entry.visitedElements))
                        }
                    }

                    for (candidate in table.liveCandidates) {
                        if (candidate.reachableFrom(entrance)) {
                            execute(candidate.call, state.putVisitedElements(candidate.visitedElements))
                        }
                    }

                    for (beamCallDefinition in table.beamCallDefinitions) {
                        if (beamCallDefinition.reachableFrom(entrance)) {
                            execute(beamCallDefinition.callDefinition, state.putVisitedElements(beamCallDefinition.visitedElements))
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

        val keepProcessing = implicitImport(project, scope, KERNEL, state)

        return if (keepProcessing) {
            val modularCanonicalNameState = state.put(MODULAR_CANONICAL_NAME, KERNEL_SPECIAL_FORMS)

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
                    is Call -> {
                        val namedElementResolveState = state.putVisitedElement(namedElement)

                        Modular.callDefinitionClauseCallWhile(
                            namedElement, namedElementResolveState
                        ) { callDefinitionClause, accResolveState ->
                            executeOnCallDefinitionClause(
                                callDefinitionClause,
                                accResolveState
                            )
                        }
                    }
                    is BeamModule -> whileIn(namedElement.callDefinitions()) {
                        execute(it, state)
                    }
                    else -> true
                }
            }
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
