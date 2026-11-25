package org.elixir_lang.code_insight

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.elixir_lang.psi.call.Call

/**
 * Elixir-PSI-aware companions to the platform gestures in BehaviouralGestures.kt: small helpers for
 * locating the enclosing [Call] at the caret and asserting where Go To Declaration landed in terms of
 * the Elixir call it navigated into.
 *
 * Kept in a separate file from the platform-only gestures so each keeps a single responsibility - the
 * gestures there wrap generic IDE actions and know nothing about Elixir; the assertions here reason
 * about Elixir PSI shape.
 */

/**
 * The nearest enclosing [Call] (ancestor-or-self) of the leaf at the caret that satisfies [predicate]
 * - the innermost [Call] by default - or `null` if there is none.
 */
fun CodeInsightTestFixture.enclosingCallAtCaret(predicate: (Call) -> Boolean = { true }): Call? =
    file.findElementAt(caretOffset)?.enclosingCall(predicate)

/**
 * Runs the real Go To Declaration action and asserts the caret landed on an element whose text is
 * [expectedText], nested inside an enclosing [Call] satisfying [enclosingPredicate] (named by
 * [enclosingDescription] in the failure message). Returns that enclosing [Call] so callers can make
 * further assertions on it.
 */
fun CodeInsightTestFixture.assertGotoDeclarationLandsIn(
    expectedText: String,
    enclosingDescription: String,
    enclosingPredicate: (Call) -> Boolean
): Call {
    val target = gotoDeclarationDestinationAtCaret()
    assertNotNull("Go To Declaration should navigate somewhere", target)
    assertEquals(expectedText, target!!.text)
    val enclosingCall = target.enclosingCall(enclosingPredicate)
    assertNotNull("Expected the caret to land inside $enclosingDescription", enclosingCall)
    return enclosingCall!!
}

/** The nearest ancestor-or-self [Call] of this element satisfying [predicate], or `null`. */
internal fun PsiElement.enclosingCall(predicate: (Call) -> Boolean): Call? =
    generateSequence(this) { it.parent }
        .filterIsInstance<Call>()
        .firstOrNull(predicate)
