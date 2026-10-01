package org.elixir_lang.lowering

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.headAtomQuotable
import org.elixir_lang.structure_view.element.Callback

/** The kinds of [quotedElements] whose atom production code reads. */
val ATOM_KINDS = setOf("atom", "functionName", "head")

/**
 * Each element of [file] that production code lowers on its own, with its kind: keyword keys and values, atoms, tuples,
 * and each call's function name and definition head.
 */
fun quotedElements(file: PsiFile): List<Pair<String, Quotable>> =
    PsiTreeUtil.findChildrenOfAnyType(
        file, QuotableKeywordPair::class.java, ElixirAtom::class.java, ElixirTuple::class.java, Call::class.java
    ).flatMap { element: PsiElement ->
        when (element) {
            is QuotableKeywordPair -> listOf("keywordKey" to element.keywordKey, "keywordValue" to element.keywordValue)
            is ElixirAtom -> listOf("atom" to element)
            is ElixirTuple -> listOf("tuple" to element)
            is Call -> callElements(element)
            else -> emptyList()
        }
    }

/** What `functionNameAtomValue` and `headAtomValue` read for [call]. */
private fun callElements(call: Call): List<Pair<String, Quotable>> =
    listOfNotNull(
        (call.functionNameElement() as? Quotable)?.let { "functionName" to it },
        heads(call).firstNotNullOfOrNull { headAtomQuotable(it) }?.let { "head" to it },
    )

/** The definition heads `Declarations` passes to `headAtomValue`. */
private fun heads(call: Call): List<PsiElement> =
    when {
        CallDefinitionClause.`is`(call) -> listOfNotNull(CallDefinitionClause.head(call))
        call is AtUnqualifiedNoParenthesesCall<*> && Callback.`is`(call) -> listOfNotNull(Callback.headCall(call))
        call.functionName() == "defdelegate" -> listOfNotNull(call.finalArguments()?.takeIf { it.size == 2 }?.get(0))
        else -> emptyList()
    }
