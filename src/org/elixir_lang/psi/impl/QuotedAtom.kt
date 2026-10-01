package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Macro
import org.elixir_lang.Module.NO_VALUE
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.UNQUOTE
import org.elixir_lang.psi.call.name.Function.__MODULE__
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * The value of the atom [quotable] quotes to; `null` when it quotes to something else, as an interpolated atom does.
 */
@RequiresReadLock
fun quotedAtomValue(quotable: Quotable): String? {
    ThreadingAssertions.assertReadAccess()

    return ElementLowering.atomName(quotable)
}

/**
 * The module [element] names, read without expansion. An atom, or an alias headed by `Elixir`, names its module outright
 * ([ModuleName.absolute]) by its [org.elixir_lang.Module.indexName]. Any other alias joins its segments, with a
 * `__MODULE__` head kept for expansion and any other head that is not an alias as [NO_VALUE]. `null` when [element]
 * quotes to anything else.
 */
@RequiresReadLock
fun moduleName(element: PsiElement): ModuleName? {
    ThreadingAssertions.assertReadAccess()

    return when (val quoted = (element as? Quotable)?.quote()) {
        is OtpErlangAtom -> ModuleName(org.elixir_lang.Module.indexName(quoted.atomValue()), absolute = true)
        is OtpErlangTuple ->
            if (Macro.isAliases(quoted)) aliasesModuleName(Macro.callArguments(quoted).elements()) else null
        else -> null
    }
}

data class ModuleName(val name: String, val absolute: Boolean)

private fun aliasesModuleName(segments: Array<OtpErlangObject>): ModuleName? {
    val head = segments.firstOrNull() ?: return null
    val tail = segments.drop(1).map { (it as? OtpErlangAtom)?.atomValue() ?: return null }

    return when {
        head is OtpErlangAtom && head.atomValue() == ELIXIR && tail.isNotEmpty() ->
            ModuleName(org.elixir_lang.Module.indexName("$ELIXIR.${tail.joinToString(".")}"), absolute = true)
        head is OtpErlangAtom -> ModuleName((listOf(head.atomValue()) + tail).joinToString("."), absolute = false)
        isModuleVariable(head) -> ModuleName((listOf(__MODULE__) + tail).joinToString("."), absolute = false)
        else -> ModuleName((listOf(NO_VALUE) + tail).joinToString("."), absolute = false)
    }
}

private const val ELIXIR = "Elixir"

private fun isModuleVariable(quoted: OtpErlangObject): Boolean =
    quoted is OtpErlangTuple && quoted.arity() == 3 && (quoted.elementAt(0) as? OtpErlangAtom)?.atomValue() == __MODULE__

/** The atom value of the name [call] uses. */
@RequiresReadLock
fun functionNameAtomValue(call: Call): String? {
    ThreadingAssertions.assertReadAccess()

    return (call.functionNameElement() as? Quotable)?.let(::quotedAtomValue)
}

/** The atom value a definition [head] names: its name, or for an `unquote(atom)` head, that atom. */
@RequiresReadLock
fun headAtomValue(head: PsiElement): String? {
    ThreadingAssertions.assertReadAccess()

    return headAtomQuotable(head)?.let(::quotedAtomValue)
}

/** The element [headAtomValue] reads for [head]. */
@RequiresReadLock
internal fun headAtomQuotable(head: PsiElement): Quotable? =
    (CallDefinitionHead.strip(head) as? Call)?.let { stripped ->
        if (stripped.functionName() == UNQUOTE) {
            stripped.primaryArguments()?.singleOrNull()?.stripAccessExpression()
                ?.takeIf { it is ElixirAtom || it is ElixirAtomKeyword }
        } else {
            stripped.functionNameElement()
        }
    } as? Quotable
