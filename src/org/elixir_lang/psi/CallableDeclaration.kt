package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.EEx
import org.elixir_lang.Name
import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.mix.Generator
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.Delegation

/**
 * Whether a call puts function or macro names in scope, and which names at which arities. Every walker that needs
 * the answer asks here; `CallableDeclarationGuardTest` fails a file that lists the forms itself.
 *
 * Three entry points, nesting `headBindingFormOf ⊆ syntacticFormOf ⊆ formOf`, each answering as much as its caller
 * can afford to ask:
 *
 * - [formOf] - every form, for a caller that may resolve a reference.
 * - [syntacticFormOf] - the forms recognised without resolving, for a caller that may not, stub building above all.
 * - [headBindingFormOf] - the two forms whose head binds parameters, for a caller that needs only those.
 */
object CallableDeclaration {
    enum class Form { CLAUSE, CALLBACK, DELEGATION, EXCEPTION, EEX_FUNCTION_FROM, GENERATOR_EMBED }

    /** @property arityInterval `null` when the call does not say, as for `EEx.function_from_string` given `@args`. */
    data class Declaration(val name: Name, val arityInterval: ArityInterval?)

    @RequiresReadLock
    fun formOf(call: Call, state: ResolveState): Form? =
        syntacticFormOf(call)
            ?: when {
                EEx.isFunctionFrom(call, state) -> Form.EEX_FUNCTION_FROM
                Generator.isEmbed(call, state) -> Form.GENERATOR_EMBED
                else -> null
            }

    /** [formOf], restricted to the forms recognised without resolving a reference, so safe during stub building. */
    @RequiresReadLock
    fun syntacticFormOf(call: Call): Form? =
        headBindingFormOf(call)
            ?: when {
                Callback.`is`(call) -> Form.CALLBACK
                Exception.`is`(call) -> Form.EXCEPTION
                else -> null
            }

    /**
     * The two forms whose head binds parameters. Not a filter over [syntacticFormOf]: the variable walk asks it for
     * every call it visits.
     */
    @RequiresReadLock
    fun headBindingFormOf(call: Call): Form? =
        when {
            CallDefinitionClause.`is`(call) -> Form.CLAUSE
            Delegation.`is`(call) -> Form.DELEGATION
            else -> null
        }

    @RequiresReadLock
    fun declares(call: Call, state: ResolveState): Boolean = formOf(call, state) != null

    /** [form] must be [formOf]'s answer for [call]; callers already have it from dispatching on it. */
    @RequiresReadLock
    fun declarations(call: Call, form: Form, state: ResolveState): List<Declaration> =
        when (form) {
            Form.CLAUSE -> listOfNotNull(CallDefinitionClause.nameArityInterval(call, state)?.let(::declaration))
            Form.CALLBACK -> listOfNotNull(
                (call as? AtUnqualifiedNoParenthesesCall<*>)
                    ?.let { Callback.headCall(it) }
                    ?.let { CallDefinitionHead.nameArityInterval(it, state) }
                    ?.let(::declaration)
            )
            Form.DELEGATION -> listOfNotNull(
                delegationHead(call)?.let { CallDefinitionHead.nameArityInterval(it, state) }?.let(::declaration)
            )
            Form.EXCEPTION -> Exception.NAME_ARITY_LIST.map { Declaration(it.name, ArityInterval(it.arity, it.arity)) }
            Form.EEX_FUNCTION_FROM -> listOfNotNull(eexFunctionFrom(call))
            Form.GENERATOR_EMBED -> listOfNotNull(generatorEmbed(call))
        }

    /** The one head of a `defdelegate`; a list of heads declares nothing here (unhandled, not a `null` result). */
    @RequiresReadLock
    fun delegationHead(call: Call): PsiElement? = call.finalArguments()?.takeIf { it.size == 2 }?.first()

    private fun declaration(nameArityInterval: NameArityInterval): Declaration =
        Declaration(nameArityInterval.name, nameArityInterval.arityInterval)

    private fun eexFunctionFrom(call: Call): Declaration? =
        EEx.declaredNameArity(call).let { (name, arity) ->
            name?.let { Declaration(it, arity?.let { a -> ArityInterval(a, a) }) }
        }

    private fun generatorEmbed(call: Call): Declaration? =
        call.finalArguments()?.firstOrNull()?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()?.let { prefix ->
            when (val suffix = call.functionName()?.removePrefix("embed_")) {
                // `name_template(assigns)`
                "template" -> Declaration("${prefix}_$suffix", ArityInterval(1, 1))
                "text" -> Declaration("${prefix}_$suffix", ArityInterval(0, 0))
                else -> null
            }
        }
}