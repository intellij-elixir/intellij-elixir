package org.elixir_lang.psi.impl

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ecto.Query
import org.elixir_lang.ecto.Schema
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.ex_unit.Assertions
import org.elixir_lang.psi.ex_unit.Case
import org.elixir_lang.psi.impl.call.CallImpl.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.declarations.UseScopeImpl
import org.elixir_lang.psi.impl.declarations.UseScopeImpl.selector
import org.elixir_lang.psi.operation.*
import org.elixir_lang.psi.operation.infix.Position
import org.elixir_lang.psi.operation.infix.Triple
import org.elixir_lang.psi.scope.Variable
import org.elixir_lang.psi.scope.WhileIn.whileIn

object ProcessDeclarationsImpl {
    @JvmField
    val DECLARING_SCOPE = Key<Boolean>("DECLARING_SCOPE")

    /**
     * `{:ok, value} = func() && value == literal`
     */
    @JvmStatic
    fun processDeclarations(
        and: And,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement?,
        @Suppress("UNUSED_PARAMETER") place: PsiElement
    ): Boolean {
        var keepProcessing = true

        if (Normalized.operator(and).text == "&&") {
            val leftOperand = org.elixir_lang.psi.operation.infix.Normalized.leftOperand(and)

            if (leftOperand != null && !PsiTreeUtil.isAncestor(leftOperand, lastParent!!, false)) {
                // the left operand is not inherently declaring, it should only be if a match is in the left operand
                keepProcessing = processor.execute(leftOperand, state.put(DECLARING_SCOPE, false))
            }
        }

        return keepProcessing
    }

    @RequiresReadLock
    @JvmStatic
    fun processDeclarations(
        atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>,
        processor: PsiScopeProcessor,
        state: ResolveState,
        @Suppress("UNUSED_PARAMETER") lastParent: PsiElement?,
        @Suppress("UNUSED_PARAMETER") place: PsiElement
    ): Boolean {
        val identifierName = atUnqualifiedNoParenthesesCall.atIdentifier.identifierName()

        return if (ModuleAttribute.isTypeSpecName(identifierName)) {
            processor.execute(atUnqualifiedNoParenthesesCall, state) &&
                    atUnqualifiedNoParenthesesCall.finalArguments()?.singleOrNull()?.let { argument ->
                        processDeclarationInTypeSpecArgument(argument, processor, state)
                    } ?: true
        } else {
            processor.execute(atUnqualifiedNoParenthesesCall, state)
        }
    }

    private fun processDeclarationInTypeSpecArgument(
        argument: PsiElement,
        processor: PsiScopeProcessor,
        state: ResolveState
    ): Boolean =
        when (argument) {
            // `Type` are gotten going up, don't need to go down here
            is Type -> true
            is When -> processDeclarationInTypeSpecArgument(argument, processor, state)
            else -> true
        }

    private fun processDeclarationInTypeSpecArgument(
        argument: When,
        processor: PsiScopeProcessor,
        state: ResolveState
    ): Boolean =
        argument.rightOperand()?.stripAccessExpression()?.let {
            processDeclarationInTypeSpecRestrictions(it, processor, state)
        } ?: true

    private fun processDeclarationInTypeSpecRestrictions(
        restrictions: PsiElement,
        processor: PsiScopeProcessor,
        state: ResolveState
    ): Boolean =
        when (restrictions) {
            is ElixirList -> restrictions.whileInChildExpressions {
                processDeclarationInTypeSpecRestrictions(it, processor, state)
            }
            is QuotableKeywordList -> whileIn(restrictions.quotableKeywordPairList()) {
                processDeclarationInTypeSpecRestrictions(it, processor, state)
            }
            is QuotableKeywordPair -> processor.execute(restrictions.keywordKey, state)
            is Type -> restrictions.leftOperand()?.let {
                processor.execute(it, state)
            } ?: true
            // The `when` isn't finished being type, so its argument is the `def` like:
            // `@spec foo(bar) :: term() when\ndef foo(`
            is Call,
                // Fixes #2577
            is ElixirTuple -> true
            // Anything else restricts no type variable; keep walking
            else -> true
        }

    /**
     * `def(macro)?p?`, `for`, or `with` can declare variables
     */
    @RequiresReadLock
    @JvmStatic
    fun processDeclarations(
        call: Call,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement?,
        place: PsiElement
    ): Boolean =
        // need to check if call is place because lastParent is set to place at start of treeWalkUp
        if (!call.isEquivalentTo(lastParent) || call.isEquivalentTo(place)) {
            when {
                // Cheapest first: `declares` can resolve a reference.
                continuesWalk(call) || bindsNames(call, state) || declares(call, processor, state)
                -> processor.execute(call, state)
                Schema.isChild(call, state) -> {
                    processor.execute(call, state)
                }
                hasDoBlockOrKeyword(call) ->
                    // unknown macros that take do blocks often allow variables to be declared in their arguments
                    processor.execute(call, state)
                Query.isChild(call, state) -> {
                    processor.execute(call, state)
                }
                /* Any other call's arguments are values, so what they hold is read, but a match inside one binds a
                   variable for the code after the call, `IO.puts(x = 1)` then `x`, and for the arguments after it,
                   which Elixir evaluates left to right. A read inside an argument therefore sees only the arguments
                   before its own, the last of them to bind a name wins, and a call met as an ancestor is not walked
                   past that point. Only the variable resolver reads arguments; a type or module walk finds nothing in
                   a value. */
                processor is Variable -> call.finalArguments()?.let { arguments ->
                    val reading = state.put(DECLARING_SCOPE, false)
                    val before = arguments.takeWhile { !PsiTreeUtil.isAncestor(it, place, false) }

                    whileIn(before.asReversed()) { processor.execute(it, reading) }
                } ?: true
                else -> true
            }
        } else {
            true
        }

    /**
     * Only a clause's or a delegation's head binds variables, so the variable walk asks for those alone; every other
     * declaring form's arguments are values, read in order by the `processor is Variable` arm.
     */
    private fun declares(call: Call, processor: PsiScopeProcessor, state: ResolveState): Boolean =
        if (processor is Variable) {
            CallableDeclaration.headBindingFormOf(call) != null
        } else {
            CallableDeclaration.declares(call, state)
        }

    /** A bare name in the call's arguments, or in a clause it owns, may bind a variable. */
    private fun bindsNames(call: Call, state: ResolveState): Boolean =
        Case.isChild(call, state) ||
            call.isCalling(KERNEL, DESTRUCTURE) || // left operand
            call.isCallingMacro(KERNEL, IF) || // match in condition
            call.isCallingMacro(KERNEL, FOR) || // comprehension match variable
            call.isCalling(KERNEL, MATCH_QUESTION_MARK) ||
            call.isCallingMacro(KERNEL, UNLESS) || // match in condition
            call.isCallingMacro(KERNEL, "with") || // <- or = variable
            Assertions.isChild(call, state)

    /** The walk continues through what the call names or injects. */
    private fun continuesWalk(call: Call): Boolean =
        call.isCalling(KERNEL, ALIAS) ||
            call.isCalling(KERNEL, REQUIRE) ||
            Implementation.`is`(call) ||
            Import.`is`(call) ||
            Module.`is`(call) ||
            Protocol.`is`(call) ||
            Use.`is`(call) ||
            QuoteMacro.`is`(call) // quote :bind_quoted keys for Variable resolver OR call definitions for Callable resolver

    @JvmStatic
    fun processDeclarations(
        alias: ElixirAlias,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        processDeclarationsRecursively(
            alias,
            processor,
            state,
            lastParent,
            place
        )

    @JvmStatic
    fun processDeclarations(
        file: ElixirFile,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean {
        val keepProcessing = processDeclarationsInPreviousSibling(file, processor, state, lastParent, place)

        if (keepProcessing) {
            processor.execute(file, state)
        }

        return keepProcessing
    }

    @JvmStatic
    fun processDeclarations(
        multipleAliases: ElixirMultipleAliases,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        entrance: PsiElement
    ): Boolean {
        return processDeclarationsRecursively(
            multipleAliases,
            processor,
            state,
            lastParent,
            entrance
        )
    }

    @JvmStatic
    fun processDeclarations(
        scope: ElixirEex,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        processDeclarationsInPreviousSibling(scope, processor, state, lastParent, place)

    @JvmStatic
    fun processDeclarations(
        scope: ElixirEexTag,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        if (scope.isEquivalentTo(lastParent.parent)) {
            processDeclarationsInPreviousSibling(scope, processor, state, lastParent, place)
        } else {
            scope
                .childExpressions(forward = false)
                .let { processDeclarations(it, processor, state, lastParent, place) }
        }

    @JvmStatic
    fun processDeclarations(
        scope: ElixirStabBody,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        processDeclarationsInPreviousSibling(scope, processor, state, lastParent, place)

    @JvmStatic
    fun processDeclarations(
        stabOperation: ElixirStabOperation,
        processor: PsiScopeProcessor,
        state: ResolveState,
        @Suppress("UNUSED_PARAMETER") lastParent: PsiElement,
        @Suppress("UNUSED_PARAMETER") place: PsiElement
    ): Boolean {
        var keepProcessing = true
        val signature = stabOperation.leftOperand()

        if (signature != null) {
            val declaringScope = isDeclaringScope(stabOperation)

            keepProcessing = processor.execute(signature, state.put(DECLARING_SCOPE, declaringScope))
        }

        return keepProcessing
    }

    @JvmStatic
    fun processDeclarations(
        match: Match,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        @Suppress("UNUSED_PARAMETER") place: PsiElement
    ): Boolean {
        val rightOperand = match.rightOperand()
        val leftOperand = match.leftOperand()
        var checkRight: Boolean
        var checkLeft: Boolean

        val triple = Triple(match.children)
        val position = triple.ancestorPosition(lastParent)

        if (position != null) {
            when (position) {
                Position.LEFT -> {
                    checkLeft = true
                    checkRight = false
                }
                Position.OPERATOR -> {
                    checkLeft = true
                    checkRight = true
                }
                Position.RIGHT -> {
                    checkLeft = false
                    checkRight = true
                }
            }
        } else {
            checkLeft = true
            checkRight = true
        }

        var keepProcessing = true

        if (checkRight || checkLeft) {
            // check right-operand first if both sides need to be checked because only left-side can do rebinding
            if (checkRight && rightOperand != null) {
                keepProcessing = processor.execute(rightOperand, state)
            }

            if (checkLeft && leftOperand != null && keepProcessing) {
                keepProcessing = processor.execute(leftOperand, state)
            }
        }

        return keepProcessing
    }

    @JvmStatic
    fun processDeclarations(
        type: Type,
        processor: PsiScopeProcessor,
        state: ResolveState
    ): Boolean =
        type
            .leftOperand()
            ?.let { processor.execute(it, state) }
            ?: true

    @JvmStatic
    fun processDeclarations(
        qualifiedAlias: QualifiedAlias,
        processor: PsiScopeProcessor,
        state: ResolveState,
        @Suppress("UNUSED_PARAMETER") lastParent: PsiElement,
        @Suppress("UNUSED_PARAMETER") place: PsiElement
    ): Boolean {
        return processor.execute(qualifiedAlias, state)
    }

    // Private Functions

    /**
     * @see [Elixir Scoping](https://elixir-lang.readthedocs.io/en/latest/technical/scoping.html)
     *
     * @see [](https://github.com/alco/elixir/wiki/Scoping-Rules-in-Elixir-
    ) */
    private fun createsNewScope(element: PsiElement): Boolean {
        return selector(element) == UseScopeImpl.UseScopeSelector.SELF
    }

    @JvmStatic
    fun isDeclaringScope(stabOperation: ElixirStabOperation): Boolean {
        var declaringScope = true
        val parent = stabOperation.parent

        if (parent is ElixirStab) {
            val grandParent = parent.parent

            if (grandParent is ElixirBlockItem) {
                val blockIdentifier = grandParent.blockIdentifier

                val blockIdentifierText = blockIdentifier.text

                /* `after` is not a declaring scope because the timeout value does not need to be pinned even though it
                    must be a literal or declared in an outer scope */
                if (blockIdentifierText == "after") {
                    declaringScope = false
                }
            } else if (grandParent is ElixirDoBlock) {
                val call = grandParent.parent as Call

                if (call.isCalling(KERNEL, COND)) {
                    declaringScope = false
                }
            }
        }

        return declaringScope
    }

    /**
     * Processes declarations in siblings of `lastParent` backwards from `lastParent`.
     *
     * @param scope an [ElixirStabBody] or [ElixirFile] that has a sequence of expressions as children
     */
    private fun processDeclarationsInPreviousSibling(
        scope: PsiElement,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        if (scope.isEquivalentTo(lastParent.parent)) {
            lastParent
                .siblingExpressions(forward = false, withSelf = false)
                .let { processDeclarations(it, processor, state, lastParent, place) }
        } else {
            if (lastParent !is ElixirFile) {
                Logger.error(
                    PsiElement::class.java,
                    "Scope is not lastParent's parent\nlastParent:\n" + lastParent.text,
                    scope
                )
            }

            true
        }

    fun processDeclarations(
        sequence: Sequence<PsiElement>,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean =
        sequence
            .filter { !createsNewScope(it) }
            .map {
                /* A call decides what it declares through its own `processDeclarations`, and so do a template's
                   tags and the alias shapes. A container that is a statement on its own, `[x = 1]` or
                   `"#{x = 1}"`, has none, so the variable resolver reads its children instead; every other walk keeps
                   the shape's own answer. */
                if (answersForItself(it) || processor !is Variable) {
                    it.processDeclarations(processor, state, lastParent, place)
                } else {
                    processor.execute(it, state)
                }
            }
            .takeWhile { it }
            .lastOrNull()
            ?: true

    /** The shapes with a `processDeclarations` of their own, the overloads above. */
    private fun answersForItself(element: PsiElement): Boolean =
        element is Call || element is ElixirEex || element is ElixirEexTag || element is ElixirStabBody ||
            element is ElixirStabOperation || element is ElixirAlias || element is QualifiedAlias ||
            element is ElixirMultipleAliases

    private fun processDeclarationsRecursively(
        psiElement: PsiElement,
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement,
        place: PsiElement
    ): Boolean {
        var keepProcessing = processor.execute(psiElement, state)

        if (keepProcessing) {
            var child: PsiElement? = psiElement.firstChild

            while (child !== null && child !== lastParent) {
                if (!child.processDeclarations(processor, state, lastParent, place)) {
                    keepProcessing = false

                    break
                }

                child = child.nextSibling
            }
        }

        return keepProcessing
    }
}
