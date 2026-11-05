package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.EXPANDER
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.psi.call.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.Callable

/** How the expander's tests write what [ExpanderSource] finds, and run a feature with it answering. */
internal object ExpanderSourceRows {
    /**
     * [found], one a line, as `<declaring module>.<name>/<arity> <reach> <applicability> via [<calls>]`, then ` line <n>`
     * for a source declaration and its uncertainty where it has one.
     */
    fun render(found: List<Found>): String =
        read {
            found.joinToString("\n") { found ->
                val declaration = found.candidate.declaration
                val via = found.via.joinToString(" | ") { it.text.lines().first() }
                val line = when (declaration.declared) {
                    is Declared.Source -> " line ${lineOf(found.element)}"
                    is Declared.Compiled -> ""
                }

                "${found.declaringModuleName()}.${declaration.name}/${arity(declaration.arity)} ${found.candidate.reach} " +
                    "${found.candidate.applicability} via [$via]$line${found.uncertain?.let { " $it" }.orEmpty()}"
            }
        }

    /** [visible], one per line in name order, each with its arity and the line that declares it. */
    fun renderVisible(visible: List<Visible>): String =
        read {
            visible
                .map { visible -> "${visible.lookupName}/${arity(visible.declaration!!.arity)} line ${lineOf(visible.element)}" }
                .sorted()
                .joinToString("\n")
        }

    fun arity(arity: ArityKnowledge): String =
        when (arity) {
            is ArityKnowledge.Exact -> "${arity.arity}"
            is ArityKnowledge.Range -> "${arity.minimum}..${arity.maximum}"
            is ArityKnowledge.Open -> "${arity.minimum}+"
            ArityKnowledge.Unknown -> "?"
        }

    /** The 1-based line of [element] in its own file. */
    fun lineOf(element: PsiElement): Int = StringUtil.offsetToLineNumber(element.containingFile.text, element.textRange.startOffset) + 1

    /** The use that is the call [call], found in the only occurrence of [context], or its last with [last]. */
    fun use(file: PsiFile, context: String, call: String, last: Boolean = false): Use.OfCall =
        read { Use.of(call(file, context, call, last))!! }

    /** The call [call], found in the only occurrence of [context], or its last with [last]. */
    fun call(file: PsiFile, context: String, call: String, last: Boolean = false): Call {
        val text = file.text
        val at = if (last) text.lastIndexOf(context) else text.indexOf(context)
        assertTrue("`$context` in the file", at >= 0)
        if (!last) assertEquals("`$context` once in the file", at, text.lastIndexOf(context))
        val offset = text.indexOf(call, at)

        return generateSequence(file.findElementAt(offset)) { it.parent }
            .filterIsInstance<Call>()
            .first { it.textRange.startOffset == offset && it.text == call }
    }

    fun <T> read(block: () -> T): T = ReadAction.nonBlocking(Callable { block() }).executeSynchronously()

    /** [block] with the features answered by [ExpanderSource]. */
    fun <T> withExpanderSwitch(block: () -> T): T {
        val switch = System.setProperty(SOURCE_PROPERTY, EXPANDER)

        return try {
            block()
        } finally {
            if (switch == null) System.clearProperty(SOURCE_PROPERTY) else System.setProperty(SOURCE_PROPERTY, switch)
        }
    }
}
