package org.elixir_lang.psi

import com.intellij.openapi.util.Key
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
    data class Declaration(val name: Name, val arityInterval: ArityInterval?) {
        /** An arity the call does not say could be any, so it accepts every arity. */
        fun accepts(arity: Int): Boolean = arityInterval?.let { arity in it } ?: true

        fun nameArityInterval(): NameArityInterval = NameArityInterval(name, arityInterval ?: ArityInterval(0, null))
    }

    private val HEAD_BINDING = listOf(Form.CLAUSE, Form.DELEGATION)
    private val OTHER_SYNTACTIC = listOf(Form.CALLBACK, Form.EXCEPTION)
    private val RESOLVING = listOf(Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED)

    /** Whether [call] is [form], asking only [form]'s own predicate. The forms are disjoint, so this agrees with [formOf]. */
    @RequiresReadLock
    @JvmOverloads
    fun isForm(call: Call, form: Form, state: ResolveState = ResolveState.initial()): Boolean =
        when (form) {
            Form.CLAUSE -> CallDefinitionClause.`is`(call)
            Form.DELEGATION -> Delegation.`is`(call)
            Form.CALLBACK -> Callback.`is`(call)
            Form.EXCEPTION -> Exception.`is`(call)
            Form.EEX_FUNCTION_FROM -> EEx.isFunctionFrom(call, state)
            Form.GENERATOR_EMBED -> Generator.isEmbed(call, state)
        }

    /** [formOf]'s answer for [call] - `null` meaning it declares nothing - carried in a [ResolveState] as [CLASSIFIED]. */
    class Classified(val call: Call, val form: Form?)

    /** Lets a walker that already classified a call hand the answer to the processor, so it is not classified twice. */
    val CLASSIFIED: Key<Classified> = Key.create("CallableDeclaration.CLASSIFIED")

    private fun classified(call: Call, state: ResolveState): Classified? = state.get(CLASSIFIED)?.takeIf { it.call == call }

    @RequiresReadLock
    fun formOf(call: Call, state: ResolveState): Form? =
        when (val classified = classified(call, state)) {
            null -> syntacticFormOf(call) ?: RESOLVING.firstOrNull { isForm(call, it, state) }
            else -> classified.form
        }

    /** [formOf], restricted to the forms recognised without resolving a reference, so safe during stub building. */
    @RequiresReadLock
    fun syntacticFormOf(call: Call): Form? = headBindingFormOf(call) ?: OTHER_SYNTACTIC.firstOrNull { isForm(call, it) }

    /** The two forms whose head binds parameters. */
    @RequiresReadLock
    fun headBindingFormOf(call: Call): Form? = HEAD_BINDING.firstOrNull { isForm(call, it) }

    /** [headBindingFormOf], read from [CLASSIFIED] when the walk already classified [call]. */
    @RequiresReadLock
    fun headBindingFormOf(call: Call, state: ResolveState): Form? =
        when (val classified = classified(call, state)) {
            null -> headBindingFormOf(call)
            else -> classified.form?.takeIf { it in HEAD_BINDING }
        }

    @RequiresReadLock
    fun declares(call: Call, state: ResolveState): Boolean = formOf(call, state) != null

    /**
     * What [call] defines in its own module: every form's [declarations] but a `@callback`'s, which the implementing
     * module defines - so an `import` does not bring it in and a `@spec` does not name it.
     */
    @RequiresReadLock
    fun definitions(call: Call, state: ResolveState): List<Declaration> = definitions(call, formOf(call, state), state)

    /** [definitions], given [form], [formOf]'s answer for [call]. */
    @RequiresReadLock
    fun definitions(call: Call, form: Form?, state: ResolveState): List<Declaration> =
        when (form) {
            null, Form.CALLBACK -> emptyList()
            Form.CLAUSE, Form.DELEGATION, Form.EXCEPTION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED ->
                declarations(call, form, state)
        }

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

    /** The one head of a `defdelegate`; a list of heads declares nothing here yet (#4040). */
    @RequiresReadLock
    fun delegationHead(call: Call): PsiElement? = call.finalArguments()?.takeIf { it.size == 2 }?.first()

    private fun declaration(nameArityInterval: NameArityInterval): Declaration =
        Declaration(nameArityInterval.name, nameArityInterval.arityInterval)

    private fun eexFunctionFrom(call: Call): Declaration? =
        EEx.declaredName(call)?.let { name ->
            Declaration(name, EEx.argumentList(call)?.size?.let { arity -> ArityInterval(arity, arity) })
        }

    private fun generatorEmbed(call: Call): Declaration? =
        Generator.Embed.of(call)?.let { embed ->
            call.finalArguments()?.firstOrNull()?.stripAccessExpression()?.let { it as? ElixirAtom }?.literalName()?.let { prefix ->
                val arity = embed.parameters.size

                Declaration("${prefix}_${embed.suffix}", ArityInterval(arity, arity))
            }
        }
}
