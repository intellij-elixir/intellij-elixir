package org.elixir_lang.declaration

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.psi.Arguments
import org.elixir_lang.psi.call.Call

/** The call whose arguments hold the caret. */
internal fun CodeInsightTestFixture.callAtCaret(): Call {
    val arguments = PsiTreeUtil.getParentOfType(file.findElementAt(caretOffset), Arguments::class.java)

    return PsiTreeUtil.getParentOfType(arguments, Call::class.java)!!
}

/** Each entry as `line -> [lines of what it reaches]`, in the order given. */
internal fun List<Preferred>.entryLines(): List<String> =
    map { entry -> "${lineOf(entry.found.element)} -> ${entry.delegatedTo.map { lineOf(it.element) }}" }
