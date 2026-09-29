package org.elixir_lang.leex.reference.resolver

import com.intellij.openapi.roots.impl.LibraryScopeCache
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.stubs.StubIndex
import org.elixir_lang.leex.reference.Assign
import org.elixir_lang.psi.*
import org.elixir_lang.psi.Modular.callDefinitionClauseCallFoldWhile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.childExpressionsFoldWhile
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.*
import org.elixir_lang.psi.stub.index.AllName
import org.elixir_lang.util.AccumulatorContinue
import org.elixir_lang.util.foldWhile
import org.elixir_lang.leex.reference.Assign as ReferenceAssign
import org.elixir_lang.psi.scope.NameMatch

object Assign : ResolveCache.PolyVariantResolver<ReferenceAssign> {

    /** How [declaredName], declared at [declaration], matches the name [assign] reads. */
    private fun nameMatch(assign: Assign, declaredName: String, declaration: PsiElement): NameMatch =
        NameMatch.of(NameMatch.query(assign.name, assign.element), declaredName, declaration)
    override fun resolve(assign: Assign, incompleteCode: Boolean): Array<ResolveResult> {
        val containingFile = assign.element.containingFile

        val viewModule = containingFile.context as? Call

        val resolveResultList = if (viewModule != null) {
            val viewModuleResolveResultList = resolveInViewModule(assign, incompleteCode, viewModule)

            if (viewModuleResolveResultList.any(ResolveResult::isValidResult)) {
                viewModuleResolveResultList
            } else {
                val liveComponentCallResolveResultList = resolveInLiveComponentCalls(assign, incompleteCode, viewModule)

                if (liveComponentCallResolveResultList.any(ResolveResult::isValidResult)) {
                    viewModuleResolveResultList + liveComponentCallResolveResultList
                } else {
                    val phoenixLiveViewAccumulatorContinue = resolveInPhoenixLiveView(assign, incompleteCode)

                    viewModuleResolveResultList + liveComponentCallResolveResultList + phoenixLiveViewAccumulatorContinue.accumulator
                }
            }
        } else if (containingFile.containingDirectory.name == "layout") {
            resolveInPhoenixLiveView(assign, incompleteCode).accumulator
        } else {
            emptyList()
        }

        return resolveResultList.toTypedArray()
    }

    private fun resolveInViewModule(assign: Assign, incompleteCode: Boolean, viewModule: Call): List<ResolveResult> =
        callDefinitionClauseCallFoldWhile(viewModule, emptyList<ResolveResult>()) { callDefinitionClauseCall, acc ->
            // Always continue because there is no way to determine which definition is called last if the view module has enough helper functions.
            resolveInCallDefinitionClause(assign, incompleteCode, callDefinitionClauseCall, acc)
        }.accumulator

    private fun resolveInCallDefinitionClause(
        assign: Assign,
        incompleteCode: Boolean,
        callDefinitionClause: Call,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        resolveInCallDefinitionClause(
            assign,
            incompleteCode,
            callDefinitionClause,
            initial,
            ::resolveInCallDefinitionClauseExpression
        )

    private const val FLASH = "flash"
    private const val SOCKET = "socket"

    private fun resolveInCallDefinitionClauseExpression(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        when (expression) {
            is ElixirAccessExpression, is ElixirStabBody, is ElixirTuple -> {
                expression.childExpressionsFoldWhile(
                    forward = false,
                    initial = initial
                ) { child, accumulator ->
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, child, accumulator)
                }
            }

            is Arrow -> {
                val leftAccumulatorContinue = expression.leftOperand()?.let { leftOperand ->
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, leftOperand, initial)
                } ?: AccumulatorContinue(initial, true)

                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveInCallDefinitionClauseExpression(
                        assign,
                        incompleteCode,
                        rightOperand,
                        leftAccumulatorContinue.accumulator
                    )
                } else {
                    leftAccumulatorContinue
                }
            }

            is Match -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is ElixirAtom, is ElixirAtomKeyword, is ElixirEndOfExpression, is ElixirList, is ElixirMapOperation,
            is ElixirStructOperation, is QualifiableAlias, is QuotableKeywordList, is Quote -> AccumulatorContinue(
                initial,
                true
            )
            // After `None` as `assign/2` and `assign/3` have options
            // After `Match` because it is a more specific `Call`
            is Call ->
                when (expression.functionName()) {
                    "assign" ->
                        when (expression.resolvedFinalArity()) {
                            2 -> expression
                                .finalArguments()
                                ?.let { arguments ->
                                    val socketAccumulatorContinue = if (SOCKET.startsWith(assign.name)) {
                                        if (arguments.size == 2) {
                                            resolveSocket(assign, incompleteCode, arguments[0], initial)
                                        } else {
                                            // if a pipeline
                                            resolveSocket(assign, incompleteCode, expression.parent, initial)
                                        }
                                    } else {
                                        AccumulatorContinue(initial, true)
                                    }

                                    if (socketAccumulatorContinue.`continue`) {
                                        val assignsElement = arguments.last()
                                        val accumulator = resolveInAssign2Argument(
                                            assign,
                                            incompleteCode,
                                            assignsElement,
                                            socketAccumulatorContinue.accumulator
                                        )

                                        AccumulatorContinue(accumulator, true)
                                    } else {
                                        socketAccumulatorContinue
                                    }
                                }

                            3 -> expression
                                .finalArguments()
                                ?.let { arguments ->
                                    val socketAccumulatorContinue = if (SOCKET.startsWith(assign.name)) {
                                        if (arguments.size == 3) {
                                            resolveSocket(assign, incompleteCode, arguments[0], initial)
                                        } else {
                                            // if a pipeline
                                            resolveSocket(assign, incompleteCode, expression.parent, initial)
                                        }
                                    } else {
                                        AccumulatorContinue(initial, true)
                                    }

                                    if (socketAccumulatorContinue.`continue`) {
                                        val nameElement = arguments[arguments.size - 2]
                                        val accumulator = resolveInAtomArgument(
                                            assign,
                                            incompleteCode,
                                            nameElement,
                                            socketAccumulatorContinue.accumulator
                                        )

                                        AccumulatorContinue(accumulator, true)
                                    } else {
                                        socketAccumulatorContinue
                                    }
                                }

                            else -> null
                        } ?: AccumulatorContinue(initial, true)

                    "assign_new" ->
                        when (expression.resolvedFinalArity()) {
                            3 -> expression
                                .finalArguments()
                                ?.let { arguments ->
                                    val socketAccumulatorContinue = if (SOCKET.startsWith(assign.name)) {
                                        if (arguments.size == 3) {
                                            resolveSocket(assign, incompleteCode, arguments[0], initial)
                                        } else {
                                            // if a pipeline
                                            resolveSocket(assign, incompleteCode, expression.parent, initial)
                                        }
                                    } else {
                                        AccumulatorContinue(initial, true)
                                    }

                                    if (socketAccumulatorContinue.`continue`) {
                                        val nameElement = arguments[arguments.size - 2]
                                        val accumulator = resolveInAtomArgument(
                                            assign,
                                            incompleteCode,
                                            nameElement,
                                            socketAccumulatorContinue.accumulator
                                        )

                                        AccumulatorContinue(accumulator, true)
                                    } else {
                                        socketAccumulatorContinue
                                    }
                                }

                            else -> null
                        } ?: AccumulatorContinue(initial, true)

                    "put_flash" ->
                        if (expression.resolvedFinalArity() == 3 && FLASH.startsWith(assign.name)) {
                            val validResult = assign.name == FLASH
                            val accumulator = initial + listOf(PsiElementResolveResult(expression, validResult))

                            AccumulatorContinue(accumulator, !validResult)
                        } else {
                            AccumulatorContinue(initial, true)
                        }

                    else -> {
                        val doBlock = expression.doBlock

                        if (doBlock != null) {
                            val stab = doBlock.stab

                            val stabAccumulatorContinue = if (stab != null) {
                                resolveInCallDefinitionClauseExpression(assign, incompleteCode, stab, initial)
                            } else {
                                AccumulatorContinue(initial, true)
                            }

                            if (stabAccumulatorContinue.`continue`) {
                                val blockList = doBlock.blockList

                                if (blockList != null) {
                                    resolveInCallDefinitionClauseExpression(
                                        assign,
                                        incompleteCode,
                                        blockList,
                                        stabAccumulatorContinue.accumulator
                                    )
                                } else {
                                    stabAccumulatorContinue
                                }
                            } else {
                                stabAccumulatorContinue
                            }
                        } else {
                            AccumulatorContinue(initial, true)
                        }
                    }
                }

            is ElixirStab -> {
                val stabBody = expression.stabBody

                if (stabBody != null) {
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, stabBody, initial)
                } else {
                    expression.stabOperationList.foldWhile(initial) { stabOperation, accumulator ->
                        resolveInCallDefinitionClauseExpression(
                            assign,
                            incompleteCode,
                            stabOperation,
                            accumulator
                        )
                    }
                }
            }

            is ElixirStabOperation -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is ElixirBlockList ->
                expression.blockItemList.foldWhile(initial) { blockItem, accumulator ->
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, blockItem, accumulator)
                }

            is ElixirBlockItem -> {
                val stab = expression.stab

                if (stab != null) {
                    resolveInCallDefinitionClauseExpression(assign, incompleteCode, stab, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            // Anything else assigns nothing; keep looking
            else -> AccumulatorContinue(initial, true)
        }

    private fun resolveInAssign2Argument(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): List<ResolveResult> =
        when (expression) {
            is ElixirAccessExpression -> resolveInAssign2Argument(
                assign,
                incompleteCode,
                expression.stripAccessExpression(),
                initial
            )

            is ElixirKeywordKey -> {
                if (expression.line == null) {
                    val resolvedName = expression.text
                    val nameMatch = nameMatch(assign, resolvedName, expression)

                    if (nameMatch != NameMatch.NONE) {
                        val validResult = nameMatch == NameMatch.EXACT

                        initial + listOf(PsiElementResolveResult(expression, validResult))
                    } else {
                        null
                    }
                } else {
                    null
                } ?: initial
            }

            is ElixirMapOperation -> resolveInAssign2Argument(assign, incompleteCode, expression.mapArguments, initial)
            is ElixirMapArguments -> {
                val child = expression.mapConstructionArguments ?: expression.mapConstructionArguments

                if (child != null) {
                    resolveInAssign2Argument(assign, incompleteCode, child, initial)
                } else {
                    initial
                }
            }

            is ElixirMapConstructionArguments -> expression.arguments().fold(initial) { acc, argument ->
                resolveInAssign2Argument(assign, incompleteCode, argument, acc)
            }

            is QuotableKeywordList -> expression.quotableKeywordPairList().fold(initial) { acc, keywordPair ->
                resolveInAssign2Argument(assign, incompleteCode, keywordPair, acc)
            }

            is QuotableKeywordPair -> resolveInAssign2Argument(assign, incompleteCode, expression.keywordKey, initial)
            is ElixirUnmatchedUnqualifiedNoArgumentsCall -> initial
            // Anything else names no assign
            else -> initial
        }

    private fun resolveInAtomArgument(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): List<ResolveResult> =
        when (expression) {
            is ElixirAccessExpression -> resolveInAtomArgument(
                assign,
                incompleteCode,
                expression.stripAccessExpression(),
                initial
            )

            is ElixirAtom -> if (expression.line == null) {
                val resolvedName = expression.node.lastChildNode.text
                val nameMatch = nameMatch(assign, resolvedName, expression)

                if (nameMatch != NameMatch.NONE) {
                    val validResult = nameMatch == NameMatch.EXACT
                    initial + listOf(PsiElementResolveResult(expression, validResult))
                } else {
                    null
                }
            } else {
                null
            } ?: initial

            // A key that is not an atom names no assign
            else -> initial
        }

    private fun resolveInLiveComponentCalls(
        assign: Assign,
        incompleteCode: Boolean,
        viewModule: Call
    ): List<ResolveResult> {
        val searchScope = GlobalSearchScope.projectScope(assign.element.project)
            .let { GlobalSearchScope.getScopeRestrictedByFileTypes(it, org.elixir_lang.leex.file.Type.INSTANCE) }

        return ReferencesSearch
            .search(viewModule, searchScope)
            .mapNotNull { liveComponentCall(it) }
            .flatMap { resolveInLiveComponentCall(assign, incompleteCode, it) }
    }

    private fun liveComponentCall(psiReference: PsiReference): Call? = liveComponentCall(psiReference.element)

    private tailrec fun liveComponentCall(element: PsiElement): Call? =
        when (element) {
            is Arguments, is ElixirAccessExpression, is QualifiableAlias -> liveComponentCall(element.parent)
            is Call -> if ((element.functionName() == "live_component" && element.resolvedFinalArity() == 3) ||
                // `live_modal` is auto-generated in `MyAppWeb.LiveHelpers` by the `mix phx.gen.live` generator`
                // to turn a live component into modal dialog.  It calls `live_component`, so count it as a
                // `live_component` call.
                (element.functionName() == "live_modal" && element.resolvedFinalArity() == 3)
            ) {
                element
            } else {
                null
            }

            else -> null
        }

    private fun resolveInLiveComponentCall(
        assign: Assign,
        incompleteCode: Boolean,
        liveComponentCall: Call
    ): List<ResolveResult> =
        liveComponentCall
            .finalArguments()
            ?.last()
            ?.let { resolveInLiveComponentAssigns(assign, incompleteCode, it) }
            ?: emptyList()

    private fun resolveInLiveComponentAssigns(
        assign: Assign,
        incompleteCode: Boolean,
        assigns: PsiElement
    ): List<ResolveResult> =
        when (assigns) {
            is QuotableKeywordList ->
                assigns.quotableKeywordPairList().flatMap { keywordPair ->
                    resolveInLiveComponentAssigns(assign, incompleteCode, keywordPair)
                }

            is QuotableKeywordPair -> resolveInLiveComponentAssigns(assign, incompleteCode, assigns.keywordKey)
            is ElixirKeywordKey -> {
                if (assigns.line == null) {
                    val resolvedName = assigns.text
                    val nameMatch = nameMatch(assign, resolvedName, assigns)

                    if (nameMatch != NameMatch.NONE) {
                        val validResult = nameMatch == NameMatch.EXACT

                        listOf(PsiElementResolveResult(assigns, validResult))
                    } else {
                        null
                    }
                } else {
                    null
                } ?: emptyList()
            }

            else -> emptyList()
        }

    private tailrec fun resolveSocket(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        when (expression) {
            is Arrow -> {
                val leftOperand = expression.leftOperand()

                if (leftOperand != null) {
                    resolveSocket(assign, incompleteCode, leftOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is UnqualifiedNoArgumentsCall<*> -> if (expression.resolvedFinalArity() == 0) {
                // resolve as a variable
                expression.reference?.let { reference ->
                    if (reference is PsiPolyVariantReference) {
                        val referenceResolveResults = reference.multiResolve(incompleteCode)
                        val validResult = referenceResolveResults.any(ResolveResult::isValidResult)
                        val accumulator = initial + referenceResolveResults
                        val `continue` = incompleteCode || !validResult

                        AccumulatorContinue(accumulator, `continue`)
                    } else {
                        reference.resolve()?.let { resolved ->
                            resolveSocketAsOtherNamedElement(assign, incompleteCode, resolved, initial)
                        }
                    }
                } ?: AccumulatorContinue(initial, true)
            } else {
                // function calls are the source of the socket, don't resolve through the function call
                resolveSocketAsOtherNamedElement(assign, incompleteCode, expression, initial)
            }
            // function calls are the source of the socket, don't resolve through the function call
            is Call -> resolveSocketAsOtherNamedElement(assign, incompleteCode, expression, initial)
            // Anything else is not a socket; keep looking
            else -> AccumulatorContinue(initial, true)
        }

    private fun resolveSocketAsOtherNamedElement(
        assign: Assign,
        incompleteCode: Boolean,
        element: PsiElement,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> {
        // the name of the element doesn't matter as the assign is always `@socket` regardless of the variable name in
        // the function in the view module.
        val validResult = assign.name == SOCKET

        val accumulator = initial + listOf(PsiElementResolveResult(element, validResult))
        val `continue` = incompleteCode || !validResult

        return AccumulatorContinue(accumulator, `continue`)
    }

    private const val INNER_CONTENT = "inner_content"
    private const val LIVE_ACTION = "live_action"
    private const val MYSELF = "myself"

    private fun resolveInPhoenixLiveView(
        assign: Assign,
        incompleteCode: Boolean
    ): AccumulatorContinue<List<ResolveResult>> {
        val assignName = assign.name

        return when {
            INNER_CONTENT.startsWith(assignName) -> resolveInnerContent(assign, incompleteCode)
            LIVE_ACTION.startsWith(assignName) -> resolveLiveAction(assign, incompleteCode)
            MYSELF.startsWith(assignName) -> resolveMyself(assign, incompleteCode)
            else -> AccumulatorContinue(emptyList(), true)
        }
    }

    private fun resolveInnerContent(assign: Assign, incompleteCode: Boolean): AccumulatorContinue<List<ResolveResult>> {
        val element = assign.element
        val globalSearchScope = globalSearchScope(element)
        val resolveResultList = mutableListOf<ResolveResult>()

        val `continue` = StubIndex.getInstance().processElements(
            AllName.KEY,
            "to_rendered",
            element.project,
            globalSearchScope,
            NamedElement::class.java
        ) { namedElement ->
            // use `StubBased<*>` to ignore decompiled
            if (namedElement is StubBased<*>) {
                val accumulatorContinue = resolveInToRendered(assign, incompleteCode, namedElement, emptyList())
                resolveResultList.addAll(accumulatorContinue.accumulator)

                accumulatorContinue.`continue`
            } else {
                true
            }
        }

        return AccumulatorContinue(resolveResultList, `continue`)
    }

    private fun resolveInToRendered(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        when (expression) {
            is ElixirStabBody -> {
                expression.childExpressionsFoldWhile(forward = false, initial = initial) { child, accumulator ->
                    resolveInToRendered(assign, incompleteCode, child, accumulator)
                }
            }

            is Match -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveInToRendered(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is Call -> when (expression.functionName()) {
                "put_in" -> when (expression.resolvedFinalArity()) {
                    2 -> {
                        expression.finalArguments()?.let { arguments ->
                            val path = arguments[0]

                            if (path.textMatches("assigns[:${INNER_CONTENT}]")) {
                                val validResult = INNER_CONTENT == assign.name
                                val accumulator = initial + listOf(PsiElementResolveResult(path, validResult))

                                AccumulatorContinue(accumulator, !validResult)
                            } else {
                                null
                            }
                        }
                    }

                    else -> null
                } ?: AccumulatorContinue(initial, true)

                else -> {
                    val arguments = expression.finalArguments()

                    val argumentsAccumulatorContinue = if (arguments != null) {
                        arguments.foldWhile(initial) { argument, accumulator ->
                            resolveInToRendered(assign, incompleteCode, argument, accumulator)
                        }
                    } else {
                        AccumulatorContinue(initial, true)
                    }

                    if (argumentsAccumulatorContinue.`continue`) {
                        val stab = expression.doBlock?.stab

                        if (stab != null) {
                            resolveInToRendered(assign, incompleteCode, stab, argumentsAccumulatorContinue.accumulator)
                        } else {
                            argumentsAccumulatorContinue
                        }
                    } else {
                        argumentsAccumulatorContinue
                    }
                }
            }

            is ElixirStab -> {
                val stabBody = expression.stabBody

                if (stabBody != null) {
                    resolveInToRendered(assign, incompleteCode, stabBody, initial)
                } else {
                    expression.stabOperationList.foldWhile(initial) { stabOperation, accumulator ->
                        resolveInToRendered(assign, incompleteCode, stabOperation, accumulator)
                    }
                }
            }

            is ElixirStabOperation -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveInToRendered(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is ElixirEndOfExpression -> AccumulatorContinue(initial, true)
            // Anything else assigns nothing; keep looking
            else -> AccumulatorContinue(initial, true)
        }

    private fun resolveLiveAction(assign: Assign, incompleteCode: Boolean): AccumulatorContinue<List<ResolveResult>> {
        val element = assign.element
        val globalSearchScope = globalSearchScope(element)
        val resolveResultList = mutableListOf<ResolveResult>()

        val `continue` = StubIndex.getInstance().processElements(
            AllName.KEY,
            "assign_action",
            element.project,
            globalSearchScope,
            NamedElement::class.java
        ) { namedElement ->
            // use `StubBased<*>` to ignore decompiled
            if (namedElement is StubBased<*>) {
                val accumulatorContinue =
                    resolveInCallDefinitionClause(assign, incompleteCode, namedElement, emptyList())
                resolveResultList.addAll(accumulatorContinue.accumulator)

                accumulatorContinue.`continue`
            } else {
                true
            }
        }

        return AccumulatorContinue(resolveResultList, `continue`)
    }

    private fun resolveMyself(assign: Assign, incompleteCode: Boolean): AccumulatorContinue<List<ResolveResult>> {
        val element = assign.element
        val globalSearchScope = globalSearchScope(element)
        val resolveResultList = mutableListOf<ResolveResult>()

        val `continue` = StubIndex.getInstance().processElements(
            AllName.KEY,
            "render_pending_components",
            element.project,
            globalSearchScope,
            NamedElement::class.java
        ) { namedElement ->
            // use `StubBased<*>` to ignore decompiled
            if (namedElement is StubBased<*>) {
                val accumulatorContinue =
                    resolveInRenderPendingComponentsClause(assign, incompleteCode, namedElement, emptyList())
                resolveResultList.addAll(accumulatorContinue.accumulator)

                accumulatorContinue.`continue`
            } else {
                true
            }
        }

        return AccumulatorContinue(resolveResultList, `continue`)
    }

    private fun resolveInRenderPendingComponentsClause(
        assign: Assign,
        incompleteCode: Boolean,
        callDefinitionClause: Call,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        resolveInCallDefinitionClause(assign, incompleteCode, callDefinitionClause, initial, ::resolveMyself)

    private fun resolveMyself(
        assign: Assign,
        incompleteCode: Boolean,
        expression: PsiElement,
        initial: List<ResolveResult>
    ): AccumulatorContinue<List<ResolveResult>> =
        when (expression) {
            is ElixirAccessExpression, is ElixirList, is ElixirStabBody, is ElixirTuple -> {
                expression.childExpressionsFoldWhile(forward = false, initial = initial) { child, accumulator ->
                    resolveMyself(assign, incompleteCode, child, accumulator)
                }
            }

            is ElixirAnonymousFunction -> resolveMyself(assign, incompleteCode, expression.stab, initial)
            is Match -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveMyself(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            // No operation assigns
            is Operation -> AccumulatorContinue(initial, true)

            is Call -> {
                val arguments = expression.finalArguments()

                val argumentsAccumulatorContinue = if (arguments != null) {
                    arguments.foldWhile(initial) { argument, accumulator ->
                        resolveMyself(assign, incompleteCode, argument, accumulator)
                    }
                } else {
                    AccumulatorContinue(initial, true)
                }

                if (argumentsAccumulatorContinue.`continue`) {
                    val stab = expression.doBlock?.stab

                    if (stab != null) {
                        resolveMyself(assign, incompleteCode, stab, argumentsAccumulatorContinue.accumulator)
                    } else {
                        argumentsAccumulatorContinue
                    }
                } else {
                    argumentsAccumulatorContinue
                }
            }

            is ElixirMapOperation -> resolveMyself(assign, incompleteCode, expression.mapArguments, initial)
            is ElixirMapArguments -> {
                val child = expression.mapConstructionArguments ?: expression.mapConstructionArguments

                if (child != null) {
                    resolveMyself(assign, incompleteCode, child, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is ElixirMapConstructionArguments -> {
                expression.arguments().foldWhile(initial) { argument, accumulator ->
                    resolveMyself(assign, incompleteCode, argument, accumulator)
                }
            }

            is ElixirStab -> {
                val stabBody = expression.stabBody

                if (stabBody != null) {
                    resolveMyself(assign, incompleteCode, stabBody, initial)
                } else {
                    expression.stabOperationList.foldWhile(initial) { stabOperation, accumulator ->
                        resolveMyself(assign, incompleteCode, stabOperation, accumulator)
                    }
                }
            }

            is ElixirStabOperation -> {
                val rightOperand = expression.rightOperand()

                if (rightOperand != null) {
                    resolveMyself(assign, incompleteCode, rightOperand, initial)
                } else {
                    AccumulatorContinue(initial, true)
                }
            }

            is QuotableKeywordList ->
                expression.quotableKeywordPairList().foldWhile(initial) { keywordPair, accumulator ->
                    resolveMyself(assign, incompleteCode, keywordPair, accumulator)
                }

            is QuotableKeywordPair -> resolveMyself(assign, incompleteCode, expression.keywordKey, initial)
            is ElixirKeywordKey -> {
                if (expression.line == null) {
                    val resolvedName = expression.text
                    val nameMatch = nameMatch(assign, resolvedName, expression)

                    if (nameMatch != NameMatch.NONE) {
                        val validResult = nameMatch == NameMatch.EXACT

                        val accumulator = initial + listOf(PsiElementResolveResult(expression, validResult))

                        AccumulatorContinue(accumulator, !validResult)
                    } else {
                        null
                    }
                } else {
                    null
                } ?: AccumulatorContinue(initial, true)
            }

            // Anything else assigns nothing; keep looking
            else -> AccumulatorContinue(initial, true)
        }

    private fun resolveInCallDefinitionClause(
        assign: Assign,
        incompleteCode: Boolean,
        callDefinitionClause: Call,
        initial: List<ResolveResult>,
        resolveInExpression: (
            assign: Assign,
            incompleteCode: Boolean,
            expression: PsiElement,
            initial: List<ResolveResult>
        ) -> AccumulatorContinue<List<ResolveResult>>
    ): AccumulatorContinue<List<ResolveResult>> =
        callDefinitionClause
            .doBlock
            ?.stab
            ?.stabBody
            ?.let { resolveInExpression(assign, incompleteCode, it, initial) }
            ?: AccumulatorContinue(initial, true)

    private fun globalSearchScope(element: PsiElement): GlobalSearchScope {
        val project = element.project

        // MUST use `originalFile` to get the PsiFile with a VirtualFile for decompiled elements
        return element.containingFile.originalFile.virtualFile?.let { elementVirtualFile ->
            LibraryScopeCache.getInstance(project).getLibraryScope(elementVirtualFile)
        } ?: GlobalSearchScope.allScope(project)
    }
}
