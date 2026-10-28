package org.elixir_lang.declaration

import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.atom.mfaReferenceDecision
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
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
 * A [candidate] at a use, the PSI [element] that declares it, [via], the `import` and `use` calls and then the
 * `defdelegate` calls it was reached through, the [head] that declares it, and for a delegation target the head of the
 * delegation the use reached it through, [delegatedThrough], as that delegation's own [head] is.
 */
data class Found(
    val candidate: Candidate,
    val element: PsiElement,
    val via: List<Call>,
    val head: Head,
    val delegatedThrough: Head?,
)

/**
 * One head of a definition: the PSI [written] that writes it, the [module] that declares it, as the source that found
 * it knows it, and its [name] and [arity]. [written] is a clause's or a delegation's head, one of a list of
 * `defdelegate` heads, the call of a form that writes no head, or a compiled definition; it is `null` where nothing
 * written declares the head, as where debug info places it at the macro clause that injected it.
 */
data class Head(val written: PsiElement?, val module: String?, val name: String, val arity: ArityKnowledge)

/** The name of the module that declares [this], so two copies of one module are one. */
fun Found.declaringModuleName(): String? = head.module

/**
 * What completion offers as [lookupName] at a position. [name] and [declaration] are `null` exactly when the name has
 * no atom value. [importedArities] are the arities of [declaration]'s closed interval that the `import` it was reached
 * through brings in, `null` when no `import` reached it, that `import` brings in every arity of the interval, or the
 * interval is not closed; [declaration]'s arity stays the whole interval.
 */
data class Visible(
    val lookupName: String,
    val name: String?,
    val declaration: Declaration?,
    val element: PsiElement,
    val importedArities: Set<Int>?,
)

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

    /**
     * A HEEx component [tag] named [name], used as a call of one argument from [entrance]: the view's Elixir root, or a
     * module a remote tag's alias names.
     */
    @ConsistentCopyVisibility
    data class Tag internal constructor(val tag: XmlTag, val entrance: PsiElement, val name: String) : Use() {
        val arity: Int get() = 1
    }

    companion object {
        /** The use of the component a HEEx [tag] named [name] names, from [entrance]. */
        @RequiresReadLock
        fun tag(tag: XmlTag, entrance: PsiElement, name: String): Tag = Tag(tag, entrance, name)

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
