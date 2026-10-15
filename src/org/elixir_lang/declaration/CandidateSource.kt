package org.elixir_lang.declaration

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.atom.mfaReferenceDecision
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.functionNameAtomValue
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.structure_view.element.CallDefinitionSpecification

/** What a use can reach, and what is visible at a position: the one place features ask. */
interface CandidateSource {
    /**
     * Every candidate at [use], by declaring element in the order the source first found each, and each element's
     * candidates in the order found. [incompleteCode] says the use is being typed, so candidates otherwise dropped may
     * be returned.
     */
    @RequiresReadLock
    fun candidates(use: Use, incompleteCode: Boolean): List<Found>

    /** What completion offers at [position]. */
    @RequiresReadLock
    fun visible(position: Call): List<Visible>
}

/**
 * A [candidate] at a use, the PSI [element] that declares it, and [via], the `import` and `use` calls and then the
 * `defdelegate` calls it was reached through.
 */
data class Found(val candidate: Candidate, val element: PsiElement, val via: List<Call>)

/**
 * The name of the module that declares [this], so two copies of one module are one. What a `use` injects is declared
 * by the module that holds the `use`, the first call [via] crossed.
 */
@RequiresReadLock
fun Found.declaringModuleName(): String? =
    when (val declared = candidate.declaration.declared) {
        is Declared.Compiled -> declared.origin.module
        is Declared.Source ->
            (via.firstOrNull().takeIf { candidate.reach == Reach.USE } ?: element as? Call)
                ?.let { CallDefinitionClause.enclosingModularMacroCall(it) }
                ?.let { SyntacticCall.of(it).canonicalName() }
    }

/**
 * What completion offers as [lookupName] at a position. [name] and [declaration] are `null` exactly when the name has
 * no atom value.
 */
data class Visible(val lookupName: String, val name: String?, val declaration: Declaration?, val element: PsiElement)

/** A use of a callable. */
sealed class Use {
    /** A call, used at [arity]: a capture's name is used at the capture's arity. */
    sealed class OfCall : Use() {
        abstract val entrance: Call
        abstract val arity: Int
    }

    /** A use that names [name], an atom value. */
    @ConsistentCopyVisibility
    data class Named internal constructor(override val entrance: Call, val name: String, override val arity: Int) : OfCall()

    /** A qualified `unquote`, which names no declaration but can reach any in the qualifier's module. */
    @ConsistentCopyVisibility
    data class AnyName internal constructor(override val entrance: Call, override val arity: Int) : OfCall()

    /** A `@spec` [head], which names only what its own [modular] declares. */
    @ConsistentCopyVisibility
    data class Specified internal constructor(val head: Call, val modular: Call, val name: String, val arity: Int) : Use()

    /**
     * An MFA [atom] naming [name] in the modules [module] names, from outside them. [arity] `null` is any arity: Elixir
     * checks only a literal one.
     */
    @ConsistentCopyVisibility
    data class Remote internal constructor(
        val atom: ElixirAtom,
        val module: PsiElement,
        val name: String,
        val arity: Int?
    ) : Use()

    companion object {
        /** `null` when [call] names nothing, as when its name has no atom value. */
        @RequiresReadLock
        fun of(call: Call): OfCall? = of(call, Unquote.arity(call))

        /** The use of a capture's [name], `f` or `Mod.f` in `&f/2` or `&Mod.f/2`, at the capture's [arity]. */
        @RequiresReadLock
        fun capture(name: Call, arity: Int): OfCall? = of(name, arity)

        /** The use of the function a `@spec` [head] specifies, `f(t)` in `@spec f(t) :: t`. */
        @RequiresReadLock
        fun specification(head: Call, moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): Specified? {
            ThreadingAssertions.assertReadAccess()

            val modular = CallDefinitionClause.enclosingModularMacroCall(moduleAttribute) ?: return null
            val (name, arity) = CallDefinitionSpecification.typeNameArity(head) ?: return null

            return Specified(head, modular, name, arity)
        }

        /** The use of the function an MFA [atom] names, `:f` in `{M, :f, 1}` or `apply(M, :f, [a])`. */
        @RequiresReadLock
        fun mfa(atom: ElixirAtom): Remote? {
            ThreadingAssertions.assertReadAccess()

            val context = atom.mfaReferenceDecision().context ?: return null
            val name = quotedAtomValue(atom) ?: return null

            return Remote(atom, context.moduleElement, name, context.arity)
        }

        private fun of(call: Call, arity: Int): OfCall? {
            ThreadingAssertions.assertReadAccess()

            val functionName = call.functionName() ?: return null

            return if (call is Qualified && Unquote.isQualified(call, functionName)) {
                AnyName(call, arity)
            } else {
                functionNameAtomValue(call)?.let { Named(call, it, arity) }
            }
        }
    }
}
