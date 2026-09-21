package org.elixir_lang.ecto

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.Arity
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.prevSiblingSequence
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.whileInChildExpressions
import org.elixir_lang.psi.operation.Arrow
import org.elixir_lang.psi.operation.In
import org.elixir_lang.psi.operation.Normalized.operator
import org.elixir_lang.psi.operation.Normalized.operatorIndex
import org.elixir_lang.psi.operation.infix.Normalized.leftOperand
import org.elixir_lang.psi.operation.infix.Normalized.rightOperand
import org.elixir_lang.psi.scope.WhileIn.whileIn

private abstract class QueryBindingExpr(name: String) : NameArityRangeWalker(name, 2..3) {
    override fun walk(
        call: Call,
        arguments: Array<PsiElement>,
        resolvedFinalArity: Arity,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        // ${name}(query, binding \\ [], expr)
        when (resolvedFinalArity) {
            // `${name}(query, expr)` or `|> ${name}(expr)`
            2 ->
                // Check for Ecto.Query.API when resolving calls
                Query.executeOnSelectExpression(
                    arguments[arguments.lastIndex],
                    state.put(Query.CALL, call),
                    keepProcessing
                )
            // `${name}(query, binding, expr)` or `|> ${name}t(binding, expr)`
            3 -> Query.executeOnBinding(arguments[arguments.lastIndex - 1], state, keepProcessing) &&
                    // Check for Ecto.Query.API when resolving calls
                    Query.executeOnSelectExpression(
                        arguments[arguments.lastIndex],
                        state.put(Query.CALL, call),
                        keepProcessing
                    )

            else -> true
        }
}

private object Distinct : QueryBindingExpr("distinct")

private object Dynamic : NameArityRangeWalker("dynamic", 1..2) {
    override fun walk(
        call: Call,
        arguments: Array<PsiElement>,
        resolvedFinalArity: Arity,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        // dynamic(binding \\ [], expr)
        when (resolvedFinalArity) {
            // `dynamic(expr)`
            1 -> Query.executeOnSelectExpression(
                arguments[arguments.lastIndex],
                state.put(Query.CALL, call),
                keepProcessing
            )
            // `dynamic(binding, expr)
            2 -> Query.executeOnBinding(arguments[arguments.lastIndex - 1], state, keepProcessing) &&
                    Query.executeOnSelectExpression(
                        arguments[arguments.lastIndex],
                        state.put(Query.CALL, call),
                        keepProcessing
                    )

            else -> true
        }
}

private object From : NameArityRangeWalker("from", 1..2) {
    override fun walk(
        call: Call,
        arguments: Array<PsiElement>,
        resolvedFinalArity: Arity,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        Query.executeOnIn(arguments[0], state, keepProcessing) &&
                ((arguments.size < 2) ||
                        executeOnFromKeywords(arguments[1], state.put(Query.CALL, call), keepProcessing))

    fun executeOnFromKeywords(
        fromKeywords: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (fromKeywords) {
            is ElixirAccessExpression -> executeOnFromKeywords(
                fromKeywords.stripAccessExpression(),
                state,
                keepProcessing
            )

            is ElixirList -> {
                whileIn(fromKeywords.childExpressions()) { child ->
                    executeOnFromKeywords(child, state, keepProcessing)
                }
            }

            is QuotableKeywordList -> {
                whileIn(fromKeywords.quotableKeywordPairList()) { quotableKeywordPair ->
                    executeOnFromKeywords(quotableKeywordPair, state, keepProcessing)
                }
            }

            is QuotableKeywordPair -> {
                when (val keywordKeyText = fromKeywords.keywordKey.text) {
                    "cross_join", "full_join", "inner_join", "inner_lateral_join", "join", "left_join",
                    "left_lateral_join", "right_join" -> Query.executeOnIn(
                        fromKeywords.keywordValue,
                        state,
                        keepProcessing
                    )
                    // Can call Ecto.Query.API
                    "select", "select_merge", "order_by" ->
                        Query.executeOnSelectExpression(fromKeywords.keywordValue, state, keepProcessing)
                    // Can call Ecto.Query.API
                    "having", "or_having", "or_where", "where", "on" ->
                        Query.executeOnHavingOrOnOrWhere(fromKeywords.keywordValue, state, keepProcessing)
                    // Cannot declare a reference variable
                    "as", "distinct", "except", "except_all", "hints", "group_by", "intersect", "intersect_all",
                    "limit", "lock", "offset", "prefix", "preload", "union", "union_all", "update",
                    "windows" -> true

                    // Ecto's `from/2` keywords are a finite, documented set and a new one may declare
                    // bindings, so an unlisted key is reported rather than passed over.
                    else -> {
                        // https://github.com/intellij-elixir/intellij-elixir/issues/3171
                        // Missing list around preload arguments
                        if (!fromKeywords.prevSiblingSequence().filterIsInstance<QuotableKeywordPair>().drop(1).any {
                                it.keywordKey.text in arrayOf("preload")
                            }) {
                            Logger.error(
                                logger,
                                "Don't know how to find reference variables for keyword key $keywordKeyText",
                                fromKeywords
                            )
                        }

                        true
                    }
                }
            }
            // middle of typing like in Issue #2915
            is UnqualifiedNoArgumentsCall<*> -> true
            // Pinned or unquoted options, or an argument still being typed, declare no binding
            else -> true
        }
}

private object GroupBy : QueryBindingExpr("group_by")
private object Having : QueryBindingExpr("having")

private object Join : NameArityRangeWalker("join", 3..5) {
    override fun walk(
        call: Call,
        arguments: Array<PsiElement>,
        resolvedFinalArity: Arity,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        // `join(query, qual, binding \\ [], expr, opts \\ [])`
        when (resolvedFinalArity) {
            // `join(query, qual, expr)` or `|> join(qual, expr)`
            3 -> Query.executeOnIn(arguments[arguments.lastIndex], state.put(Query.CALL, call), keepProcessing)
            // `join(query, qual, binding, expr)` or `|> join(qual, binding, expr)`
            4 -> Query.executeOnBinding(arguments[arguments.lastIndex - 1], state, keepProcessing) &&
                    Query.executeOnIn(arguments[arguments.lastIndex], state.put(Query.CALL, call), keepProcessing)
            // `join(query, qual, binding, expr, opts)` or `|> join(qual, binding, expr, opts)`
            5 -> Query.executeOnBinding(arguments[arguments.lastIndex - 2], state, keepProcessing) &&
                    Query.executeOnIn(
                        arguments[arguments.lastIndex - 1],
                        state.put(Query.CALL, call),
                        keepProcessing
                    ) &&
                    executeOnJoinOptions(arguments[arguments.lastIndex], state.put(Query.CALL, call), keepProcessing)

            else -> true
        }

    private fun executeOnJoinOptions(
        options: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (options) {
            is QuotableKeywordList -> whileIn(options.quotableKeywordPairList()) {
                executeOnJoinOption(it, state, keepProcessing)
            }

            else -> true
        }

    private fun executeOnJoinOption(
        option: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (option) {
            is QuotableKeywordPair -> when (option.keywordKey.text) {
                "on" -> Query.executeOnHavingOrOnOrWhere(option.keywordValue, state, keepProcessing)
                else -> true
            }

            else -> true
        }
}

private object Lock : QueryBindingExpr("lock")
private object OrderBy : QueryBindingExpr("order_by")
private object Preload : QueryBindingExpr("preload")
private object Select : QueryBindingExpr("select")
private object SelectMerge : QueryBindingExpr("select_merge")
private object Update : QueryBindingExpr("update")
private object Where : QueryBindingExpr("where")
private object Windows : QueryBindingExpr("windows")

private object WithCTE : NameArityRangeWalker("with_cte", 3..3) {
    override fun walk(
        call: Call,
        arguments: Array<PsiElement>,
        resolvedFinalArity: Arity,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        // with_cte(query, name, list)
        when (call.resolvedFinalArity()) {
            // `with_cte(query, name, list)` or `|> with_cte(name, list)`
            3 -> executeOnWithCTEList(arguments[arguments.lastIndex], state.put(Query.CALL, call), keepProcessing)
            else -> true
        }

    private fun executeOnWithCTEList(
        list: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (list) {
            is QuotableKeywordList -> whileIn(list.quotableKeywordPairList()) {
                executeOnWithCTEList(it, state, keepProcessing)
            }

            is QuotableKeywordPair -> when (list.keywordKey.text) {
                "as" -> keepProcessing(list.keywordValue, state)
                else -> true
            }

            // A bracketed or pinned option list declares no binding
            else -> true
        }
}

object Query : ModuleWalker(
    "Ecto.Query",
    Distinct,
    Dynamic,
    From,
    GroupBy,
    Having,
    Join,
    Lock,
    OrderBy,
    Preload,
    Select,
    SelectMerge,
    Update,
    Where,
    Windows,
    WithCTE
) {
    val CALL: Key<Call> = Key.create("Ecto.Query")

    internal fun executeOnIn(
        fromIn: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
    //  If the query needs a reference to the data source in any other part of the expression, then an in must be
        //  used to create a reference variable.
        fromIn.let { it as? In }?.let { operation ->
            val children = operation.children
            val operatorIndex = operatorIndex(children)

            (leftOperand(children, operatorIndex)?.let { executeOnInReference(it, state, keepProcessing) } == true) &&
                    (rightOperand(children, operatorIndex)?.let { keepProcessing(it, state) } == true)
        } ?: true

    private fun executeOnInReference(
        inReference: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (inReference) {
            is ElixirAccessExpression -> executeOnInReference(inReference.children.single(), state, keepProcessing)
            is ElixirList -> whileIn(inReference.childExpressions()) {
                executeOnInReference(it, state, keepProcessing)
            }

            is QuotableKeywordList -> whileIn(inReference.quotableKeywordPairList()) { keywordPair ->
                executeOnInReference(keywordPair.keywordValue, state, keepProcessing)
            }

            is UnqualifiedNoArgumentsCall<*> ->
                inReference.resolvedFinalArity() != 0 || keepProcessing(inReference, state)

            // A pinned source or a left operand still being typed declares no binding
            else -> true
        }

    internal fun executeOnBinding(
        element: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (element) {
            is ElixirAccessExpression -> executeOnBinding(element.children.single(), state, keepProcessing)
            // Invalid binding test `:foo`
            // test "plan: raises on invalid binding index in join" do
            //   query =
            //     from(p in Post, as: :posts)
            //     |> join(:left, [{p, :foo}], assoc(p, :comments))
            //
            //   assert_raise ArgumentError, ~r/invalid binding index/, fn ->
            //     plan(query)
            //   end
            // end
            is ElixirAtom,
                // `{p, 1}`
            is ElixirDecimalWholeNumber -> true

            is ElixirList -> {
                element.whileInChildExpressions(forward = false) {
                    executeOnBinding(it, state, keepProcessing)
                }
            }
            // `left in right` like `left in subquery(...)`
            is In -> {
                val leftOperand = element.leftOperand()

                if (leftOperand != null) {
                    executeOnBinding(leftOperand, state, keepProcessing)
                } else {
                    true
                }
            }

            is QuotableKeywordList -> {
                val keywordValues = element.quotableKeywordPairList().mapNotNull(QuotableKeywordPair::getKeywordValue)

                whileIn(keywordValues) {
                    executeOnBinding(it, state, keepProcessing)
                }
            }
            // `{^assoc, a}` inside of `[{^assoc, a}]`
            is ElixirTuple -> {
                val elements = element.children

                if (elements.size == 2) {
                    // `src` in `{src, counter}`
                    executeOnBinding(elements[0], state, keepProcessing)
                            // `a` in `{^assoc, a}`
                            && executeOnBinding(elements[1], state, keepProcessing)
                } else {
                    // Ecto rejects any other tuple, and one is typed part-way through; it binds nothing
                    true
                }
            }

            is UnqualifiedNoArgumentsCall<*> -> element.resolvedFinalArity() != 0 || keepProcessing(element, state)
            // A pinned variable is a use, not a binding, and no other prefix operator binds either
            is ElixirUnmatchedUnaryOperation -> true
            // Anything else in a binding position binds nothing
            else -> true
        }


    internal fun executeOnSelectExpression(
        element: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (element) {
            is ElixirAccessExpression ->
                executeOnSelectExpression(element.stripAccessExpression(), state, keepProcessing)

            is ElixirList, is ElixirTuple -> element.whileInChildExpressions { childExpression ->
                executeOnSelectExpression(childExpression, state, keepProcessing)
            }

            is ElixirMapOperation -> executeOnSelectExpression(element.mapArguments, state, keepProcessing)
            is ElixirMapArguments -> {
                val arguments = element.mapUpdateArguments ?: element.mapConstructionArguments

                if (arguments != null) {
                    executeOnSelectExpression(arguments, state, keepProcessing)
                } else {
                    true
                }
            }

            is ElixirMapConstructionArguments, is ElixirMapUpdateArguments -> element.whileInChildExpressions {
                executeOnSelectExpression(it, state, keepProcessing)
            }

            is QuotableKeywordList -> whileIn(element.quotableKeywordPairList()) {
                executeOnSelectExpression(it, state, keepProcessing)
            }

            is ElixirKeywordPair -> executeOnSelectExpression(element.keywordValue, state, keepProcessing)
            is Arrow -> {
                val children = element.children
                val operatorIndex = operatorIndex(children)
                val operator = operator(children, operatorIndex)

                if (operator.text == "|>") {
                    (leftOperand(children, operatorIndex)?.let { leftOperand ->
                        executeOnSelectExpression(leftOperand, state, keepProcessing)
                    } ?: true)
                            &&
                            (rightOperand(children, operatorIndex)?.let { rightOperand ->
                                executeOnSelectExpression(rightOperand, state, keepProcessing)
                            } ?: true)
                } else {
                    keepProcessing(element, state)
                }
            }

            is Call -> keepProcessing(element, state)
            else -> true
        }

    internal fun executeOnHavingOrOnOrWhere(
        element: PsiElement,
        state: ResolveState,
        keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean
    ): Boolean =
        when (element) {
            is Call -> keepProcessing(element, state)
            else -> true
        }

    /**
     * Whether `call` is an `assoc/2` call inside of a `join` statement
     */
    fun isAssoc(call: Call): Boolean =
        call is UnqualifiedParenthesesCall<*> && call.functionName() == "assoc" && call.resolvedFinalArity() == 2 &&
                call.parent?.let { it as? In }
                    ?.let { isJoin(it.parent, ResolveState().put(ENTRANCE, call).putInitialVisitedElement(call)) }
                ?: true

    /**
     * Whether the `parent` is a `join(...)` macro call or `join: ...` keyword in a from
     */
    private tailrec fun isJoin(ancestor: PsiElement, state: ResolveState): Boolean =
        when (ancestor) {
            is QuotableKeywordPair -> {
                when (ancestor.keywordKey.text) {
                    "cross_join", "full_join", "inner_join", "inner_lateral_join", "join", "left_join",
                    "left_lateral_join", "right_join" -> true

                    else -> false
                }
            }

            is ElixirParenthesesArguments,
            is ElixirMatchedParenthesesArguments -> isJoin(ancestor.parent, state)

            is Call -> isJoin(ancestor, state)
            else -> false
        }

    /**
     * Whether the `call` is a `join(...)` macro call
     */
    private fun isJoin(call: Call, state: ResolveState): Boolean = Join.hasNameArity(call) && matches(call, state)

    private val logger by lazy { com.intellij.openapi.diagnostic.Logger.getInstance(Query::class.java) }
}
