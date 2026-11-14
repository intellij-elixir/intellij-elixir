package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.declaringModuleCall
import org.elixir_lang.psi.impl.isInterpolatedWithin
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.reference.Resolver
import org.elixir_lang.reference.resolver.Callable

/** The one read of what Quick Documentation documents at a caret, which both documentation providers ask. */
internal object DocumentedAt {
    /** The use written at a caret, and whether a valid variable binds its name. */
    class UseAt(val use: Use, val bound: Boolean) {
        /** What it documents. */
        @RequiresReadLock
        fun choice(): Documented.Choice = Documented.of(use, bound)
    }

    /**
     * What [original] documents: what the use written there documents, else the module its name names; `null` where
     * neither documents anything.
     */
    @RequiresReadLock
    fun of(original: PsiElement): PsiElement? {
        choiceAt(original)?.let { return it.element }
        atomAt(original)?.let { return moduleOf(it) }
        return aliasAt(original)?.let(::moduleOf)
    }

    /**
     * What the target provider documents at the use written at [leaf]: its choice where the expansion answers
     * documentation; `null` elsewhere.
     */
    @RequiresReadLock
    fun answeredAt(leaf: PsiElement): Documented.Choice? =
        if (sourceFor(Feature.DOCUMENTATION) === ExpanderSource) choiceAt(leaf) else null

    /** What the use written at [original] documents; `null` where no use is written there. */
    @RequiresReadLock
    private fun choiceAt(original: PsiElement?): Documented.Choice? = useAt(original)?.choice()

    /** The use written at [original]: an MFA's atom, else the call whose name is there. */
    @RequiresReadLock
    fun useAt(original: PsiElement?): UseAt? =
        atomAt(original)?.let(Use::mfa)?.let { UseAt(it, bound = false) }
            ?: callAt(original)?.let { call ->
                val capture = captureOf(call)

                useOf(call, capture)?.let { UseAt(it, bound = capture == null && isBound(call, it)) }
            }

    private fun atomAt(element: PsiElement?): ElixirAtom? = enclosing(element)

    /** The call whose name holds [element], a quoted name included. */
    private fun callAt(element: PsiElement?): Call? = namedCall(enclosing<ElixirRelativeIdentifier>(element) ?: element)

    // A caret in an interpolation belongs to the code there, not to the quote around it.
    private inline fun <reified T : PsiElement> enclosing(element: PsiElement?): T? =
        PsiTreeUtil.getParentOfType(element, T::class.java, false)?.takeUnless { element?.isInterpolatedWithin(it) == true }

    private tailrec fun namedCall(element: PsiElement?): Call? = when (element) {
        is LeafPsiElement, is ElixirIdentifier, is ElixirRelativeIdentifier -> namedCall(element.parent)
        is Call -> element
        else -> null
    }

    /** Whether a valid variable binds [call]'s name, which a use of it then names, where the name is no capture's. */
    private fun isBound(call: Call, use: Use): Boolean = use is Use.OfCall && Callable.bound(call, false)

    /**
     * Inside `&Mod.name/arity` the name carries no reference of its own: it lives on the capture operator, which alone
     * spans the `/arity`.
     */
    private fun captureOf(call: Call): CaptureNameArity? =
        PsiTreeUtil.getParentOfType(call, NonNumeric::class.java)
            ?.reference
            ?.let { it as? CaptureNameArity }
            ?.takeIf { it.nameElement == call }

    /**
     * The use [call] is, read from its reference's kind, or from [capture]'s where [call] is that capture's name;
     * `null` where it has no reference to document through.
     */
    private fun useOf(call: Call, capture: CaptureNameArity?): Use? {
        return if (capture != null) {
            Use.capture(call, capture.arity)
        } else {
            when (val reference = call.reference) {
                is org.elixir_lang.reference.Callable,
                is org.elixir_lang.psi.__module__.Reference -> Use.of(call)

                is org.elixir_lang.reference.CallDefinitionClause ->
                    Use.specification(call, reference.moduleAttribute)

                else -> null
            }
        }
    }

    /** The module an atom no MFA holds names, as `:lists` in `:lists.map(...)`: its first valid result, source first. */
    private fun moduleOf(atom: ElixirAtom): PsiElement? =
        Resolver.preferSource(validTargets(atom.reference).filterIsInstance<PsiNamedElement>()).firstOrNull()

    /** The elements [reference] validly resolves to; none where it is not poly-variant. */
    private fun validTargets(reference: PsiReference?): List<PsiElement> =
        (reference as? PsiPolyVariantReference)
            ?.multiResolve(false)
            ?.filter(ResolveResult::isValidResult)
            ?.mapNotNull(ResolveResult::getElement)
            .orEmpty()

    /** The alias whose name holds [element]: past an alias with no reference, the one it is part of. */
    private fun aliasAt(element: PsiElement): QualifiableAlias? {
        var alias = PsiTreeUtil.getParentOfType(element, QualifiableAlias::class.java, false) ?: return null

        while (declaringModuleCall(alias) == null && alias.reference == null) {
            alias = alias.parent as? QualifiableAlias ?: return null
        }

        return alias
    }

    /** The modular [alias] declares, else the one modular its reference resolves to. */
    private fun moduleOf(alias: QualifiableAlias): PsiElement? =
        declaringModuleCall(alias) ?: validTargets(alias.reference).filterIsInstance<Call>().singleOrNull { isModular(it) }
}
