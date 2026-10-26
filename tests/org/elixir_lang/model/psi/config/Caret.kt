package org.elixir_lang.model.psi.config

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private const val CARET = "<caret>"

/** Opens [path] with the caret where `<caret>` is in [marked], which occurs once in it. */
internal fun CodeInsightTestFixture.caret(path: String, marked: String) {
    configureFromTempProjectFile(path)
    val text = editor.document.text
    val written = marked.replace(CARET, "")
    val at = text.indexOf(written)
    assertTrue("`$written` in $path", at >= 0)
    assertEquals("`$written` once in $path", at, text.lastIndexOf(written))
    editor.caretModel.moveToOffset(at + marked.indexOf(CARET))
}
