package org.elixir_lang.psi

import com.intellij.testFramework.UsefulTestCase.assertInstanceOf
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertNotNull

/** The alias whose token the caret is in. */
internal fun CodeInsightTestFixture.caretAlias(): ElixirAlias {
    val element = file.findElementAt(caretOffset)
    assertNotNull("Expected an element at the caret", element)

    return assertInstanceOf(element!!.parent, ElixirAlias::class.java)
}
