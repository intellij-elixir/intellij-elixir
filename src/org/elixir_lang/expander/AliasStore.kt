package org.elixir_lang.expander

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Module as DefModule
import org.elixir_lang.psi.Protocol as DefProtocol
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Require
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.CASE
import org.elixir_lang.psi.call.name.Function.COND
import org.elixir_lang.psi.call.name.Function.FOR
import org.elixir_lang.psi.call.name.Function.IF
import org.elixir_lang.psi.call.name.Function.RECEIVE
import org.elixir_lang.psi.call.name.Function.TRY
import org.elixir_lang.psi.call.name.Function.WITH
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.qualification.Unqualified

/** What a call does to the aliases of the scope around it. */
enum class AliasStore {
    /** The call stores an alias: an `alias`, a `require` with `as:`, or a `defmodule` or `defprotocol`. */
    STORES,

    /** The call may store an alias, as a `use` may: what it stores is not read. */
    MAY_STORE,

    /** The call stores no alias in the scope around it. */
    NONE
}

/**
 * What [call], a statement in a scope, does to the aliases of that scope, as Elixir reads it from what the call resolves to.
 * It reads the statements written before [call] in the scopes that hold it, and no expander state; [defaultRequires] is
 * [Env.defaultRequires] at the language level.
 *
 * - An `alias`, a `require` with `as:`, `defmodule` and `defprotocol` store an alias.
 * - A `use` may store one. A plain `require`, an `import` and a `defimpl` (whose module has a computed name) store none.
 * - A form that opens a scope of its own (`for`, `if`, `case`, `cond`, `try`, `with`, `receive`) adds nothing outside it.
 * - A call with no qualifier resolves to `Kernel` and stores none, unless a `use` or an `import` is written before it in
 *   scope, which may bring a macro of that name; then it may store one.
 * - A call to a module [defaultRequires] holds is read as a `Kernel` call. A call to any other module is a function call
 *   and stores none, unless a `require`, `import` or `use` written before it names that module, when it may store one.
 */
@RequiresReadLock
fun aliasStore(call: Call, defaultRequires: List<String>): AliasStore =
    when {
        storesAlias(call) -> AliasStore.STORES
        Use.`is`(call) -> AliasStore.MAY_STORE
        Import.`is`(call) || Require.`is`(call) || Implementation.`is`(call) -> AliasStore.NONE
        call.functionName() in SCOPE_OPENING_FORMS -> AliasStore.NONE
        call is Qualified ->
            if (Module.atom(call.resolvedModuleName()) !in defaultRequires && isNamedBefore(call)) {
                AliasStore.MAY_STORE
            } else {
                AliasStore.NONE
            }
        call is Unqualified ->
            if (earlierInScope(call).any { Import.`is`(it) || Use.`is`(it) }) AliasStore.MAY_STORE else AliasStore.NONE
        else -> AliasStore.NONE
    }

@RequiresReadLock
private fun storesAlias(call: Call): Boolean =
    Alias.`is`(call) || Require.`is`(call) && Alias.asValue(call) != null || aliasesFirstSegment(call)

/** Whether [call] is a `defmodule` or `defprotocol`, which store an alias of their name's first segment. */
@RequiresReadLock
internal fun aliasesFirstSegment(call: Call): Boolean {
    val syntactic = SyntacticCall.of(call)

    return DefModule.isDefmodule(syntactic) || DefProtocol.`is`(syntactic)
}

/**
 * Whether a `require`, `import` or `use` written before [call] in scope names its module, or the `as:` of a `require`
 * does. Aliases are not expanded, so a module is named when the last segment of its name is.
 */
@RequiresReadLock
private fun isNamedBefore(call: Qualified): Boolean {
    val written = Module.split(call.moduleName()).last()

    return earlierInScope(call)
        .filter { Require.`is`(it) || Import.`is`(it) || Use.`is`(it) }
        .any { directive ->
            listOfNotNull(Alias.firstArgument(directive), Alias.asValue(directive))
                .any { Module.split(it.text).last() == written }
        }
}

/** The calls written before [element] in the scopes that hold it, below [until] or else its file, the nearest first. */
internal fun earlierInScope(element: PsiElement, until: PsiElement? = null): Sequence<Call> =
    generateSequence(element) { it.parent }
        .takeWhile { it !is PsiFile && it !== until }
        .flatMap { it.siblingExpressions(forward = false, withSelf = false) }
        .filterIsInstance<Call>()

/** The forms that run their body in a scope of their own. */
private val SCOPE_OPENING_FORMS = setOf(FOR, IF, CASE, COND, TRY, WITH, RECEIVE)
