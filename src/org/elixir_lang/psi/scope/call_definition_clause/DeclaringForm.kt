package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.EEx
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.Exception
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.isInterpolatedWithin
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.mix.Generator
import org.elixir_lang.psi.scope.WalkProbe
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.Delegation

/** Which [Form] a call declares with: `syntacticForm(call) ?: resolvingForm(call, state)`. */
object DeclaringForm {
    /** The forms the call's own syntax decides. */
    @RequiresReadLock
    fun syntacticForm(call: Call): Form? {
        WalkProbe.count(WalkProbe.Counter.CLASSIFY)

        return syntacticForm(SyntacticCall.of(call))
    }

    @RequiresReadLock
    fun syntacticForm(call: SyntacticCall): Form? {
        ThreadingAssertions.assertReadAccess()

        return when {
            CallDefinitionClause.`is`(call) -> Form.CLAUSE
            Callback.`is`(call) -> Form.CALLBACK
            Delegation.`is`(call) -> Form.DELEGATION
            Exception.`is`(call) -> Form.EXCEPTION
            else -> null
        }
    }

    /** The forms whose definer is known only by resolving the call, which needs the walk's [state]. */
    @RequiresReadLock
    fun resolvingForm(call: Call, state: ResolveState): Form? {
        ThreadingAssertions.assertReadAccess()
        WalkProbe.count(WalkProbe.Counter.CLASSIFY)

        return when {
            EEx.isFunctionFrom(call, state) -> Form.EEX_FUNCTION_FROM
            Generator.isEmbed(call, state) -> Form.GENERATOR_EMBED
            else -> null
        }
    }

    /**
     * [syntacticForm], or the shape of a [resolvingForm] without resolving whose call it is. A head a clause or
     * delegation writes declares nothing, even when named like a definer, as Kernel's `defmacro defdelegate(funs, opts)`.
     */
    @RequiresReadLock
    fun shapedForm(call: Call): Form? = shapedForm(SyntacticCall.of(call))

    @RequiresReadLock
    fun shapedForm(call: SyntacticCall): Form? =
        when {
            isWrittenHead(call) -> null
            else -> syntacticForm(call) ?: when {
                EEx.isFunctionFromShaped(call) -> Form.EEX_FUNCTION_FROM
                Generator.isEmbedShaped(call) -> Form.GENERATOR_EMBED
                else -> null
            }
        }

    private fun isWrittenHead(call: SyntacticCall): Boolean =
        listOfNotNull(call, call.guardOf()).any { head ->
            head.argumentOf()?.let { syntacticForm(it) == Form.CLAUSE } == true ||
                head.headListOf()?.let { syntacticForm(it) == Form.DELEGATION } == true
        }

    /** A call that declares, and the form it declares with. */
    data class Declaring(val call: Call, val form: Form)

    /** The nearest call around [element], itself included, that declares. */
    @RequiresReadLock
    fun enclosing(element: PsiElement): Declaring? = shaped(element).firstOrNull(::declares)

    /**
     * The call shaped like a declaring one when [element] is a name it writes, or is inside or around one, below the
     * call: the call itself is not, as its keyword is a use of the macro that declares, and nor is code in a name's
     * interpolation, which runs. The call is not resolved, as references are made from this.
     */
    @RequiresReadLock
    fun named(element: PsiElement): Declaring? =
        shaped(element).firstOrNull()?.takeIf { (call, form) ->
            element != call && names(call, form).any { name ->
                PsiTreeUtil.isAncestor(element, name, false) ||
                    PsiTreeUtil.isAncestor(name, element, false) && !element.isInterpolatedWithin(name)
            }
        }

    /** Whether [element] is a declaring call or a name one declares, as what a reference resolves to can be. */
    @RequiresReadLock
    fun isDeclaration(element: PsiElement): Boolean = (element as? Call)?.let(::shapedForm) != null || named(element) != null

    /** The calls around [element], itself included, that have a [shapedForm], nearest first. */
    private fun shaped(element: PsiElement): Sequence<Declaring> =
        generateSequence(element) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<Call>()
            .mapNotNull { call -> shapedForm(call)?.let { Declaring(call, it) } }

    /** Whether [declaring] declares: a call shaped like a [resolvingForm] only when it is that form's macro. */
    private fun declares(declaring: Declaring): Boolean =
        when (declaring.form) {
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED ->
                resolvingForm(declaring.call, ResolveState.initial()) == declaring.form
            Form.CLAUSE, Form.CALLBACK, Form.DELEGATION, Form.EXCEPTION -> true
        }

    /** The names [call] writes the functions it declares with [form] under: an EEx's or an embed's only as an atom. */
    @RequiresReadLock
    fun names(call: Call, form: Form): List<PsiElement> =
        when (form) {
            Form.CLAUSE -> listOfNotNull(CallDefinitionClause.nameIdentifier(call))
            Form.DELEGATION -> listOfNotNull(Delegation.nameIdentifier(call))
            Form.EEX_FUNCTION_FROM -> listOfNotNull(call.finalArguments()?.getOrNull(1)?.takeIf(::isAtom))
            Form.GENERATOR_EMBED -> listOfNotNull(call.finalArguments()?.firstOrNull()?.takeIf(::isAtom))
            Form.CALLBACK, Form.EXCEPTION -> emptyList()
        }

    private fun isAtom(name: PsiElement): Boolean = name.stripAccessExpression() is ElixirAtom

    /** The `defdelegate` whose head list holds [head]. */
    @RequiresReadLock
    fun delegationOf(head: SyntacticCall): SyntacticCall? =
        head.headListOf()?.takeIf { shapedForm(it) == Form.DELEGATION }
}
