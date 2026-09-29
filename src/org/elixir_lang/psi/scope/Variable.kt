package org.elixir_lang.psi.scope

import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.*
import org.elixir_lang.psi.CallDefinitionClause.head
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.ex_unit.Assertions
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.ProcessDeclarationsImpl.DECLARING_SCOPE
import org.elixir_lang.psi.impl.ProcessDeclarationsImpl.isDeclaringScope
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.*
import org.elixir_lang.psi.operation.Normalized.operatorIndex
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.operation.infix.Normalized
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.scope.variable.BindingPattern
import org.elixir_lang.resolvesToMacro
import org.elixir_lang.structure_view.element.CallDefinitionHead.Companion.strip
import org.elixir_lang.structure_view.element.Delegation.Companion.callDefinitionHeadCallList

abstract class Variable : PsiScopeProcessor {
    /**
     * @param element candidate element.
     * @param state   current state of resolver.
     * @return false to stop processing.
     */
    override fun execute(element: PsiElement, state: ResolveState): Boolean =
            // compiled elements don't have variables
            if (element is PsiCompiledElement) {
                false
            } else {
                when (VariableDescent.classify(element)) {
                    VariableDescent.Bucket.NON_DECLARING_INFIX -> executeNonDeclaringScopeInfix(element as Infix, state)
                    VariableDescent.Bucket.CHILDREN -> execute(readingOrder(element.children, element), state)
                    /* A bare identifier inside a string is a read even when the string is a macro argument, so the
                       pass stops declaring; a match inside starts it again for its own operands. A part with no `#`
                       cannot hold an interpolation, so a long heredoc costs one visit. */
                    VariableDescent.Bucket.CHILDREN_READING ->
                        !element.textContains('#') ||
                            execute(element.children.reversedArray(), state.put(DECLARING_SCOPE, false))
                    VariableDescent.Bucket.BRACKET -> execute((element as BracketOperation).bracketArguments, state)
                    VariableDescent.Bucket.AT_BRACKET ->
                        execute((element as AtUnqualifiedBracketOperation).bracketArguments, state)
                    VariableDescent.Bucket.CONTAINER_ASSOCIATION ->
                        execute(element as ElixirContainerAssociationOperation, state)
                    VariableDescent.Bucket.MAP_ARGUMENTS -> execute(element as ElixirMapArguments, state)
                    VariableDescent.Bucket.MAP_OPERATION -> execute(element as ElixirMapOperation, state)
                    VariableDescent.Bucket.WHEN -> execute(element as ElixirMatchedWhenOperation, state)
                    VariableDescent.Bucket.STAB_OPERATION -> execute(element as ElixirStabOperation, state)
                    VariableDescent.Bucket.STAB_NO_PARENTHESES_SIGNATURE ->
                        execute(element as ElixirStabNoParenthesesSignature, state)
                    VariableDescent.Bucket.STAB_PARENTHESES_SIGNATURE ->
                        execute(element as ElixirStabParenthesesSignature, state)
                    VariableDescent.Bucket.STRUCT_OPERATION -> execute(element as ElixirStructOperation, state)
                    VariableDescent.Bucket.VARIABLE -> executeOnVariable(element as PsiNamedElement, state)
                    VariableDescent.Bucket.IN -> execute(element as In, state)
                    VariableDescent.Bucket.IN_MATCH -> execute(element as InMatch, state)
                    VariableDescent.Bucket.MATCH -> execute(element as Match, state)
                    VariableDescent.Bucket.INFIX -> execute(element as Infix, state)
                    VariableDescent.Bucket.TYPE -> execute(element as Type, state)
                    VariableDescent.Bucket.UNARY -> execute(element as UnaryOperation, state)
                    VariableDescent.Bucket.MAYBE_VARIABLE ->
                        executeOnMaybeVariable(element as UnqualifiedNoArgumentsCall<*>, state)
                    VariableDescent.Bucket.CALL -> execute(element as Call, state)
                    VariableDescent.Bucket.QUALIFIED_MULTIPLE_ALIASES ->
                        execute(element as QualifiedMultipleAliases, state)
                    VariableDescent.Bucket.KEYWORD_LIST -> execute(element as QuotableKeywordList, state)
                    // stop at file.  No reason to look in directories
                    VariableDescent.Bucket.FILE -> false
                    // declares no variable; keep walking
                    VariableDescent.Bucket.STOP, VariableDescent.Bucket.LEAF -> true
                }
            }

    override fun <T> getHint(hintKey: Key<T>): T? = null
    override fun handleEvent(event: PsiScopeProcessor.Event, associated: Any?) {}

    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.execute] methods
     * eventually end here.
     *
     * @return `true` to keep processing; `false` to stop processing.
     */
    protected abstract fun executeOnVariable(match: PsiNamedElement, state: ResolveState): Boolean

    protected fun isInDeclaringScope(call: Call, state: ResolveState): Boolean =
            state.get(DECLARING_SCOPE)
                    ?: PsiTreeUtil
                            .getContextOfType(
                                    call,
                                    false,
                                    ElixirStabOperation::class.java,
                                    InMatch::class.java
                            )
                            ?.let { maybeDeclaringScopeContext ->
                                if (maybeDeclaringScopeContext is ElixirStabOperation) {
                                    val stabOperation = maybeDeclaringScopeContext
                                    val signature: PsiElement? = stabOperation.leftOperand()
                                    if (PsiTreeUtil.isAncestor(signature, call, false)) {
                                        isDeclaringScope(stabOperation)
                                    } else {
                                        false
                                    }
                                } else if (maybeDeclaringScopeContext is InMatch) {
                                    if (PsiTreeUtil.isAncestor(maybeDeclaringScopeContext.leftOperand(), call, false)) {
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                    ?: false


    private fun execute(match: Call, state: ResolveState): Boolean =
            when (CallableDeclaration.headBindingFormOf(match, state)) {
                CallableDeclaration.Form.CLAUSE -> executeOnCallDefinitionClause(match, state)
                CallableDeclaration.Form.DELEGATION -> whileIn(callDefinitionHeadCallList(match)) {
                    executeStrippedCallDefinitionHead(it, state)
                }
                // only a clause's or a delegation's head binds parameters
                else -> executeOnNonDeclaration(match, state)
            }

    private fun executeOnCallDefinitionClause(match: Call, state: ResolveState): Boolean =
            head(match)?.let { head ->
                when (val stripped = strip(head)) {
                    is AtOperation -> stripped.operand().let { it as? ElixirAccessExpression }?.let { execute(it, state) }
                    is Call -> executeStrippedCallDefinitionHead(stripped, state)
                    else -> null
                }
            } ?: true

    private fun executeOnNonDeclaration(match: Call, state: ResolveState): Boolean =
            when {
                match.isCalling(Module.KERNEL, Function.DESTRUCTURE, 2) -> {
                    match.finalArguments()?.first()?.let { pattern ->
                        execute(pattern, state.put(DECLARING_SCOPE, true))
                    }
                }
                match.isCallingMacro(Module.KERNEL, Function.FOR) ||
                        match.isCallingMacro(Module.KERNEL, "with") -> {
                    val entrance = state.get(ElixirPsiImplUtil.ENTRANCE)

                    // what `for` and `with` bind does not leave them, so from outside they declare nothing
                    if (entrance != null && !PsiTreeUtil.isAncestor(match, entrance, false)) {
                        true
                    } else match.finalArguments()?.let { finalArguments ->
                        /* if the entrance isn't in the arguments, then it is part of the block and so search should start from
                       from the last argument */
                        var entranceArgumentIndex = finalArguments.size - 1

                        // have to check in reverse order as a variable can be rebound in the `<-` and `=` of a `for` or `with`
                        for (i in finalArguments.indices.reversed()) {
                            if (PsiTreeUtil.isAncestor(finalArguments[i], entrance, false)) {
                                entranceArgumentIndex = i
                                break
                            }
                        }

                        var keepProcessing = true

                        for (i in entranceArgumentIndex downTo 0) {
                            val finalArgument = finalArguments[i]

                            /* force to be non-declaring scope, so that variable {@code do} body ({@code a} in
                           {@code for a <- b, do: a}) will look in early arguments for declaration */
                            keepProcessing = execute(finalArgument, state.put(DECLARING_SCOPE, false))
                            if (!keepProcessing) {
                                break
                            }
                        }

                        keepProcessing
                    }
                }
                match.isCallingMacro(Module.KERNEL, Function.CASE) || match.isCallingMacro(Module.KERNEL, Function.COND) || match.isCallingMacro(Module.KERNEL, Function.IF) || match.isCallingMacro(Module.KERNEL, Function.UNLESS) -> {
                    match.finalArguments()?.firstOrNull()?.let {
                        execute(
                                it,
                                /* prevents variable condition (`if foo do .. end`) from counting as declaration of that
                               variable (`foo`) */
                                state.put(DECLARING_SCOPE, false)
                        )
                    }
                }
                match.isCalling(Module.KERNEL, Function.MATCH_QUESTION_MARK, 2) -> {
                    match.finalArguments()?.first()?.let { pattern ->
                        val declaringScope = when (pattern) {
                            is When -> {
                                pattern.leftOperand()
                            }
                            else -> pattern
                        }

                        if (declaringScope != null) {
                            execute(declaringScope, state.put(DECLARING_SCOPE, true))
                        } else {
                            true
                        }
                    }
                }
                QuoteMacro.`is`(match) -> executeOnBindQuoted(match, state) && executeOnOnlyChild(match, state)
                ElixirPsiImplUtil.hasDoBlockOrKeyword(match) -> {
                    match.finalArguments()?.let { finalArguments ->
                        val macroArgumentsState = state.put(DECLARING_SCOPE, true)

                        whileIn(finalArguments) {
                            execute(it, macroArgumentsState)
                        }
                    }
                }
                // The variable is used as compile-time in the body of a defmodule, but declared in a `use`, such
                // `if code_reloading? do` in `MyApp.Endpoint`, which is declared in `use Phoenix.Endpoint, ...` at
                // the top of `MyApp.Endpoint`
                Use.`is`(match) -> {
                    Use.treeWalkUp(match, state, ::execute)
                }
                org.elixir_lang.ecto.Query.isChild(match, state) -> {
                    org.elixir_lang.ecto.Query.walkChild(match, state, ::execute)
                }
                org.elixir_lang.ecto.Schema.isChild(match, state) -> {
                    org.elixir_lang.ecto.Schema.walkChild(match, state, ::execute)
                }
                Assertions.isChild(match, state) -> {
                    Assertions.walkChild(match, state, ::execute)
                }
                else -> {
                    // unquote(var) can't declare var, only use it
                    if (!match.isCalling(Module.KERNEL, Function.UNQUOTE, 1)) {
                        val resolvedFinalArity = match.resolvedFinalArity()

                        // UnqualifiedNorArgumentsCall prevents `foo()` from being treated as a variable.
                        // resolvedFinalArity prevents `|> foo` from being counted as 0-arity
                        when {
                            match is UnqualifiedNoArgumentsCall<*> && resolvedFinalArity == 0 -> {
                                executeOnVariable(match as PsiNamedElement, state)
                            }
                            maybeMacro(match, state) || macroInMatchPattern(match) -> {
                                /* macros uses in stab signatures see
                                           @see https://github.com/elixir-lang/elixir/blob/0c9e72c8d7be3ee502c43762e0ccbbf244198aeb/lib/elixir/lib/stream/reducers.ex#L7 */
                                match.finalArguments()?.let { execute(it, state) }
                            }
                            // a function's arguments are values, but a match inside one, `f(x = 1)`, binds after it
                            else -> match.finalArguments()?.let { execute(it, state.put(DECLARING_SCOPE, false)) }
                        }
                    } else {
                        null
                    }
                }
            } ?: true

    /**
     * Only checks the right operand of the container association operation because the left operand is either a literal
     * or a pinned variable, which means the variable is being used and was declared elsewhere.
     */
    private fun execute(match: ElixirContainerAssociationOperation, state: ResolveState): Boolean {
        val children = match.children

        return if (children.size > 1) {
            execute(children[1], state)
        } else {
            true
        }
    }

    /**
     * A construction may be a pattern, so what it holds declares. An update is a value, so what it holds reads, but a
     * match inside it still binds for the code after the map, the last to bind a name winning.
     */
    private fun execute(match: ElixirMapArguments, state: ResolveState): Boolean =
            (match.mapConstructionArguments?.let { execute(it, state) } ?: true) &&
                    (match.mapUpdateArguments?.let {
                        execute(it.children.reversedArray(), state.put(DECLARING_SCOPE, false))
                    } ?: true)

    private fun execute(match: ElixirMapOperation, state: ResolveState): Boolean =
            execute(match.mapArguments, state)

    private fun execute(match: ElixirMatchedWhenOperation, state: ResolveState): Boolean =
            executeLeftOperand(match, state)

    private fun execute(match: ElixirStabNoParenthesesSignature, state: ResolveState): Boolean =
            execute(match.noParenthesesArguments, state)

    private fun execute(match: ElixirStabOperation, state: ResolveState): Boolean {
        val children = match.children
        val operatorIndex = operatorIndex(children)
        val leftOperandKeepProcessing = Normalized.leftOperand(children, operatorIndex)?.let { execute(it, state) }
                ?: true

        return leftOperandKeepProcessing &&
                Normalized.rightOperand(children, operatorIndex)?.let { execute(it, state) } ?: true
    }

    private fun execute(match: ElixirStabParenthesesSignature, state: ResolveState): Boolean =
            execute(match.parenthesesArguments, state)

    private fun execute(match: ElixirStructOperation, state: ResolveState): Boolean =
            (match.variable?.let { execute(it, state) } ?: true) &&
                    execute(match.mapArguments, state)

    /**
     * `in` can declare variable for `rescue` clauses like `rescue e in RuntimeException ->`
     */
    private fun execute(match: In, state: ResolveState): Boolean =
            executeLeftOperand(match, state)

    /**
     * Infix operations where either side can declare a variable in a match
     */
    private fun execute(match: Infix, state: ResolveState): Boolean =
            state.hasBeenVisited(match) ||
                    state.putVisitedElement(match).let { matchState ->
                        executeLeftOperand(match, matchState) &&
                                match.rightOperand()?.let { execute(it, matchState) } ?: true
                    }

    private fun execute(match: InMatch, state: ResolveState): Boolean {
        val operator = match.operator()
        val operatorText = operator.text
        var keepProcessing = true
        if (operatorText == ElixirPsiImplUtil.DEFAULT_OPERATOR) {
            executeLeftOperand(match, state)
        } else if (operatorText == "<-") {
            val entrance = state.get(ElixirPsiImplUtil.ENTRANCE)
            val rightOperand: PsiElement? = match.rightOperand()

            // variable on right of <- can't be declared on left because right expression generates left expression
            if (!PsiTreeUtil.isAncestor(rightOperand, entrance, false)) {
                // counter {@code state.put(DECLARING_SCOPE, false)} in #boolean(Call, Resolve) for {@code for}
                keepProcessing = executeLeftOperand(match, state.put(DECLARING_SCOPE, true))
            }
        }
        return keepProcessing
    }

    private fun execute(match: Match, state: ResolveState): Boolean {
        /* ensure DECLARING_SCOPE is `true` to counter `DECLARING_SCOPE` being `false` for -> signature under
           `after` or `cond` */
        val matchState = state.put(DECLARING_SCOPE, true)
        return execute((match as Infix), matchState)
    }

    private fun execute(parameters: Array<PsiElement>, state: ResolveState): Boolean =
            whileIn(parameters) {
                execute(it, state)
            }

    private fun execute(match: QualifiedMultipleAliases, state: ResolveState): Boolean {
        val children = match.children
        assert(children.size == 3)

        // MultipleAliases assumed to be a tuple on next line
        return execute(children[children.size - 1], state)
    }

    private fun execute(match: QuotableKeywordList, state: ResolveState): Boolean {
        val keywordPairList = match.quotableKeywordPairList()

        return whileIn(readingOrder(keywordPairList, match)) {
            execute(it, state)
        }
    }

    /**
     * The order the resolver reads [container]'s [parts] in. Elixir evaluates a value's parts left to right, so the
     * last to bind a name is the one in scope after the value, and they are read last first. A pattern binds a name
     * once however often it writes it, and its first writing is the binding, so a pattern's parts keep their order.
     * One part has no order, so the container is not asked which it is.
     */
    private fun readingOrder(parts: Array<PsiElement>, container: PsiElement): Array<PsiElement> =
        if (parts.size < 2 || BindingPattern.binds(container)) parts else parts.reversedArray()

    private fun <T> readingOrder(parts: List<T>, container: PsiElement): List<T> =
        if (parts.size < 2 || BindingPattern.binds(container)) parts else parts.asReversed()

    // a quoted key can hold an interpolation, so it is visited like the value
    private fun execute(match: QuotableKeywordPair, state: ResolveState): Boolean =
            execute(match.keywordKey, state) && execute(match.keywordValue, state)

    private fun execute(match: Type, state: ResolveState): Boolean =
            executeLeftOperand(match, state)

    private fun execute(match: UnaryOperation, state: ResolveState): Boolean {
        val operator = match.operator()
        val operatorText = operator.text

        // pinned expressions cannot be declared at pin site
        return operatorText == "^" || execute(match as Call, state)
    }

    private fun executeLeftOperand(match: Infix, state: ResolveState): Boolean =
            match.leftOperand()?.let { execute(it, state) } ?: true

    /**
     * Turns off any DECLARING_SCOPE because the [Infix] subclass can never be used to declare a variable in a
     * match.
     *
     * The rule is, if a lone variable by itself as an operand would declare that variable in a match then call,
     * [.execute]; otherwise, call this method.
     */
    private fun executeNonDeclaringScopeInfix(match: Infix, state: ResolveState): Boolean =
            execute(match, state.put(DECLARING_SCOPE, false))

    private fun executeOnMaybeVariable(match: UnqualifiedNoArgumentsCall<*>, state: ResolveState): Boolean =
            if (match.resolvedFinalArity() == 0) {
                executeOnVariable(match, state)
            } else {
                // ignore piped no argument calls that really have arity-1
                execute(match, state)
            }

    private fun executeStrippedCallDefinitionHead(strippedCallDefinitionHead: Call, state: ResolveState): Boolean =
            strippedCallDefinitionHead.finalArguments()?.let { finalArguments ->
                // set scope to declaring so that calls inside the arguments are treated as maybe macros
                execute(finalArguments, state.put(DECLARING_SCOPE, true))
            } ?: true

    private fun executeOnBindQuoted(quote: Call, state: ResolveState): Boolean =
            quote
                    .keywordArgument("bind_quoted")?.let { it as? ElixirAccessExpression }
                    ?.stripAccessExpression()
                    ?.let { it as? ElixirList }
                    ?.children?.singleOrNull()
                    ?.let { it as? ElixirKeywords }
                    ?.keywordPairList?.let { keywordPairList ->
                        whileIn(keywordPairList) { keywordPair ->
                            executeOnVariable(keywordPair.keywordKey, state)
                        }
                    } ?: true

    private fun executeOnOnlyChild(quote: Call, state: ResolveState): Boolean =
            quote
                    .keywordArgument("do")
                    ?.let { it as? PsiNamedElement}
                    ?.takeIf { org.elixir_lang.psi.Variable.isDeclaration(it) }
                    ?.let { executeOnVariable(it, state) }
                    ?: true

    private fun maybeMacro(call: Call, state: ResolveState): Boolean =
            !ElixirPsiImplUtil.hasDoBlockOrKeyword(call) && isInDeclaringScope(call, state)

    /**
     * Whether [call] is a macro call in the pattern of a match, so its arguments are declarations.
     *
     * [maybeMacro] cannot answer this. A match hands its left operand straight to the processor
     * rather than passing itself, so the `DECLARING_SCOPE` that [execute] sets for a [Match] is never
     * put in the state, and [isInDeclaringScope] recognises only a stab signature or an [InMatch].
     *
     * Resolving is what separates the two cases: a macro's arguments are quoted and may be bound,
     * while a function's are values and bind nothing. An unresolved call answers `false`, leaving a
     * macro from a `.beam` dependency, or one generated by `Record.defrecord`, exactly as it was.
     */
    private fun macroInMatchPattern(call: Call): Boolean =
            call is UnqualifiedParenthesesCall<*> &&
                    !ElixirPsiImplUtil.hasDoBlockOrKeyword(call) &&
                    PsiTreeUtil
                            .getContextOfType(call, Match::class.java)
                            ?.let { match -> PsiTreeUtil.isAncestor(match.leftOperand(), call, false) } == true &&
                    // resolve last: it is the only expensive check here
                    resolvesToMacro(call)
}
