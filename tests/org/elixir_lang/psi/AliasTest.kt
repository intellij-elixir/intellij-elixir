package org.elixir_lang.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.Alias.aliasNameIntroducedBy
import org.elixir_lang.psi.Alias.aliasTargetName
import org.elixir_lang.psi.call.Call

/**
 * The module an `alias` call names is read through parentheses, since Elixir quotes `(Foo.Bar)` as `Foo.Bar`. The
 * inspection and the mix project's aliases read the module through [aliasTargetName].
 */
class AliasTest : PlatformTestCase() {
    fun testTargetNameOfAnAlias() = assertEquals("Foo.Bar", targetName("alias Foo.Bar"))

    fun testTargetNameOfAParenthesizedAlias() = assertEquals("Foo.Bar", targetName("alias (Foo.Bar)"))

    fun testTargetNameOfADoublyParenthesizedAlias() = assertEquals("Foo.Bar", targetName("alias ((Foo.Bar))"))

    fun testNameIntroducedByAParenthesizedAlias() = assertEquals("Bar", introducedName("alias (Foo.Bar)"))

    fun testNameIntroducedByAParenthesizedAliasWithAs() = assertEquals("B", introducedName("alias (Foo.Bar), as: B"))

    fun testUsageTypeOfAnAliasedModule() = assertEquals("alias", usageType("alias <caret>Target"))

    fun testUsageTypeOfAParenthesizedAliasedModule() = assertEquals("alias", usageType("alias (<caret>Target)"))

    fun testUsageTypeOfAParenthesizedAliasedModuleWithAs() =
        assertEquals("alias", usageType("alias (<caret>Target), as: First"))

    /** Elixir quotes `(__MODULE__)` as `__MODULE__`, so the enclosing module is presented as the alias it is either way. */
    fun testPresentationOfTheEnclosingModuleAsAlias() = assertEquals("alias Foo", moduleCallPresentation("alias __MODULE__"))

    fun testPresentationOfAParenthesizedEnclosingModuleAsAlias() =
        assertEquals("alias Foo", moduleCallPresentation("alias (__MODULE__)"))

    private fun moduleCallPresentation(code: String): String? {
        val text = "defmodule Foo do\n  $code\nend\n"
        myFixture.configureByText("alias.ex", text)
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("__MODULE__")), Call::class.java)!!

        return call.presentation?.presentableText
    }

    private fun usageType(code: String): String {
        myFixture.configureByText("alias.ex", "$code\n")

        return ElementDescriptionUtil.getElementDescription(myFixture.caretAlias(), UsageViewTypeLocation.INSTANCE)
    }

    private fun targetName(code: String): String? = readAliasCall(code, ::aliasTargetName)

    private fun introducedName(code: String): String? = readAliasCall(code, ::aliasNameIntroducedBy)

    private fun readAliasCall(code: String, read: (Call) -> String?): String? = aliasCall(code).let { call ->
        ReadAction.computeBlocking<String?, Throwable> { read(call) }
    }

    private fun aliasCall(code: String): Call {
        myFixture.configureByText("alias.ex", "$code\n")

        return PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { Alias.`is`(it) }
    }
}
