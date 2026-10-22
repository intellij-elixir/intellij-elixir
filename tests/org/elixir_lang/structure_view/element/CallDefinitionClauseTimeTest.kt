package org.elixir_lang.structure_view.element

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.icons.RowIcon
import org.elixir_lang.Icons
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause as LookupRenderer
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Timed.Time

/** A definition has compile time when it is expanded at compile time, as a macro is: a guard is. */
class CallDefinitionClauseTimeTest : PlatformTestCase() {
    fun testDefguard() = assertTime(Time.COMPILE, "defguard g(q, x) when is_list(q)")

    fun testDefguardParenthesized() = assertTime(Time.COMPILE, "defguard(g(q, x) when is_list(q))")

    fun testDefguardp() = assertTime(Time.COMPILE, "defguardp g(q, x) when is_list(q)")

    fun testDefguardpParenthesized() = assertTime(Time.COMPILE, "defguardp(g(q, x) when is_list(q))")

    fun testDef() = assertTime(Time.RUN, "def f(q, x), do: q")

    fun testDefp() = assertTime(Time.RUN, "defp f(q, x), do: q")

    fun testDefmacro() = assertTime(Time.COMPILE, "defmacro m(q, x), do: q")

    fun testDefmacrop() = assertTime(Time.COMPILE, "defmacrop m(q, x), do: q")

    fun testGuardLookupIcon() = assertLookupIcon(Icons.Time.COMPILE, "defguard g(q, x) when is_list(q)")

    fun testDefLookupIcon() = assertLookupIcon(Icons.Time.RUN, "def g(q, x), do: q")

    fun testDefmacroLookupIcon() = assertLookupIcon(Icons.Time.COMPILE, "defmacro g(q, x), do: q")

    private fun assertTime(expected: Time, definition: String) {
        assertEquals(expected, CallDefinitionClause.time(definitionCall(definition)))
    }

    private fun assertLookupIcon(expected: javax.swing.Icon, definition: String) {
        val presentation = LookupElementPresentation()

        LookupElementBuilder.create(definitionCall(definition), "g").withRenderer(LookupRenderer("g")).renderElement(presentation)

        assertEquals(expected, (presentation.icon as RowIcon).getIcon(0))
    }

    private fun definitionCall(definition: String): Call {
        myFixture.configureByText("x.ex", "defmodule M do\n  $definition\nend\n")

        return PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.text == definition }
    }
}
