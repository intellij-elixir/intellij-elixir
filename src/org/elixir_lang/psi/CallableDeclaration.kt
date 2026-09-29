package org.elixir_lang.psi

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
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
 * - [formOf] - every form, for a caller that may resolve a reference. Its four static forms
 *   ([Form.CLAUSE]/[Form.CALLBACK]/[Form.DELEGATION]/[Form.EXCEPTION]) go through [deterministicFormOf], cached
 *   per call, rather than through [syntacticFormOf] itself - the cost
 *   [#4123](https://github.com/intellij-elixir/intellij-elixir/issues/4123) reports is this classification
 *   recomputing on every one of a module's resolves.
 * - [syntacticFormOf] - the forms recognised without resolving, for a caller that may not, stub building above all -
 *   deliberately uncached, since caching is unsafe or unwanted in that context.
 * - [headBindingFormOf] - the two forms whose head binds parameters, for a caller that needs only those.
 *
 * [Form.EEX_FUNCTION_FROM] and [Form.GENERATOR_EMBED] stay live even from [formOf]: their answer is a function of
 * the call *and* the `ResolveState` - [org.elixir_lang.resolvesToModularName]'s `isBeingResolved` guard answers
 * differently while the call is the resolution's own entrance - so there is no per-call value to cache in the first
 * place, independent of any recursion concern.
 */
object CallableDeclaration {
    enum class Form { CLAUSE, CALLBACK, DELEGATION, EXCEPTION, EEX_FUNCTION_FROM, GENERATOR_EMBED }

    /** @property arityInterval `null` when the call does not say, as for `EEx.function_from_string` given `@args`. */
    data class Declaration(val name: Name, val arityInterval: ArityInterval?)

    @RequiresReadLock
    fun formOf(call: Call, state: ResolveState): Form? =
        deterministicFormOf(call) ?: liveFormOf(call, state)

    private fun liveFormOf(call: Call, state: ResolveState): Form? =
        when {
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

    /**
     * [formOf] without [liveFormOf]'s two forms, for callers - [org.elixir_lang.psi.CallableTable]'s
     * collection - that must never risk resolving [call]'s own reference while [call] is mid-resolution
     * (see the class doc for why [Form.EEX_FUNCTION_FROM]/[Form.GENERATOR_EMBED] cannot be cached).
     */
    @RequiresReadLock
    internal fun deterministicFormOf(call: Call): Form? =
        CachedValuesManager.getCachedValue(call, DETERMINISTIC_FORM_KEY) {
            val form = when {
                CallDefinitionClause.`is`(call) -> Form.CLAUSE
                Callback.`is`(call) -> Form.CALLBACK
                Delegation.`is`(call) -> Form.DELEGATION
                Exception.`is`(call) -> Form.EXCEPTION
                else -> null
            }

            CachedValueProvider.Result(form, PsiModificationTracker.MODIFICATION_COUNT)
        }

    @RequiresReadLock
    fun declares(call: Call, state: ResolveState): Boolean = formOf(call, state) != null

    /** [form] must be [formOf]'s answer for [call]; callers already have it from dispatching on it. */
    @RequiresReadLock
    fun declarations(call: Call, form: Form, state: ResolveState): List<Declaration> =
        when (form) {
            Form.CLAUSE ->
                listOfNotNull(cachedNameArityInterval(call) { CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) }
                    ?.adjusted(state)
                    ?.let(::declaration))
            Form.CALLBACK -> listOfNotNull(
                (call as? AtUnqualifiedNoParenthesesCall<*>)
                    ?.let { Callback.headCall(it) }
                    ?.let { head -> cachedNameArityInterval(call) { CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) } }
                    ?.adjusted(state)
                    ?.let(::declaration)
            )
            Form.DELEGATION -> listOfNotNull(
                delegationHead(call)
                    ?.let { head -> cachedNameArityInterval(call) { CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) } }
                    ?.adjusted(state)
                    ?.let(::declaration)
            )
            Form.EXCEPTION -> Exception.NAME_ARITY_LIST.map { Declaration(it.name, ArityInterval(it.arity, it.arity)) }
            Form.EEX_FUNCTION_FROM -> listOfNotNull(eexFunctionFrom(call))
            Form.GENERATOR_EMBED -> listOfNotNull(generatorEmbed(call))
        }

    /**
     * The unadjusted (no `Kernel.SpecialForms`/`Ecto.Query.(Window)API` arity override) name/arity, cached per
     * [call] so [compute] - [CallDefinitionClause.nameArityInterval]'s `resolvedFinalArityInterval` walk - runs
     * once regardless of how many times the enclosing module gets resolved into. [NameArityInterval.adjusted]
     * is cheap and applied by the caller against the real [ResolveState] afterward.
     */
    private fun cachedNameArityInterval(call: Call, compute: () -> NameArityInterval?): NameArityInterval? =
        CachedValuesManager.getCachedValue(call, NAME_ARITY_INTERVAL_KEY) {
            CachedValueProvider.Result(compute(), PsiModificationTracker.MODIFICATION_COUNT)
        }

    private val DETERMINISTIC_FORM_KEY: Key<CachedValue<Form?>> =
        Key.create("org.elixir_lang.psi.CallableDeclaration.DETERMINISTIC_FORM")
    private val NAME_ARITY_INTERVAL_KEY: Key<CachedValue<NameArityInterval?>> =
        Key.create("org.elixir_lang.psi.CallableDeclaration.NAME_ARITY_INTERVAL")

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