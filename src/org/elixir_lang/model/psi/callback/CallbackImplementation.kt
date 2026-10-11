package org.elixir_lang.model.psi.callback

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Using
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.reference.resolver.Module as ModuleResolver
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Whether a definition clause implements a `@callback` or `@macrocallback`, decided here and nowhere else: Go To
 * Declaration from the clause ([implementedCallbacks]) and Find Usages from the callback ([implements]) agree because
 * both read it.
 *
 * Elixir's rule: a public clause whose name and arity match, a `@callback` implemented by a function (`def`) and a
 * `@macrocallback` by a macro (`defmacro`, `defguard`), in a module that implements the behaviour, or in the
 * behaviour's `__using__` quote.
 */
internal object CallbackImplementation {
    /** The callbacks of the behaviours [clause] belongs to that it implements. */
    @RequiresReadLock
    fun implementedCallbacks(clause: Call): List<Callback> {
        val signature = Signature.of(clause) ?: return emptyList()
        val callbacks = mutableListOf<Callback>()

        for (name in behaviourNames(clause)) {
            for (behaviourModule in ModuleResolver.resolvePreferred(clause, name, incompleteCode = false, inScope = false).map { it.element }) {
                ProgressManager.checkCanceled()
                if (behaviourModule !is Call) continue
                CallDefinitionClause.modularChildCalls(behaviourModule)
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .filter { CallbackElement.`is`(it) }
                    .forEach { attribute ->
                        ProgressManager.checkCanceled()
                        Callback.fromModuleAttribute(attribute).filterTo(callbacks, signature::matches)
                    }
            }
        }

        return callbacks
    }

    /** Whether [clause] implements [callback]. */
    @RequiresReadLock
    fun implements(clause: Call, callback: Callback): Boolean {
        val signature = Signature.of(clause) ?: return false

        return signature.matches(callback) && callback.moduleName in behaviourNames(clause)
    }

    /**
     * The behaviours whose callbacks [call], a definition clause or a `defoverridable`, may implement: those the
     * module or quote around it implements, and, inside a `__using__` quote, the behaviour that module defines and
     * those the quote injects.
     */
    @RequiresReadLock
    internal fun behaviourNames(call: Call): Set<String> {
        val names = linkedSetOf<String>()

        CallDefinitionClause.enclosingModularMacroCall(call)?.let { names += BehaviourMembership.namesImplementedBy(it) }
        enclosingUsingDefiner(call)?.let { definer ->
            CallDefinitionClause.enclosingModularMacroCall(definer)?.let { definingModule ->
                names += BehaviourMembership.namesInjectedByDefiner(definer, definingModule)
                BehaviourMembership.moduleName(definingModule)?.let { names += it }
            }
        }

        return names
    }

    /**
     * Nearest `use` definer around [call] within its own module: a module nested in the quote implements only the
     * behaviours it declares itself.
     */
    @RequiresReadLock
    private fun enclosingUsingDefiner(call: Call): Call? =
        generateSequence(call.parent) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<Call>()
            .takeWhile { !Stub.isModular(it) }
            .firstOrNull { Using.isDefiner(it) }

    /** What of a public clause a callback is matched on. */
    private class Signature(private val name: String, private val arityInterval: ArityInterval, private val capabilities: Capabilities) {
        fun matches(callback: Callback): Boolean =
            callback.name == name && callback.arity in arityInterval && callback.macro == capabilities.compileTime

        companion object {
            /** `null` for a clause that implements nothing: a private one, or one with no name and arity. */
            @RequiresReadLock
            fun of(clause: Call): Signature? {
                val capabilities = CallDefinitionClause.capabilities(clause)?.takeIf { it.public } ?: return null
                val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return null

                return Signature(nameArity.name, nameArity.arityInterval, capabilities)
            }
        }
    }
}
