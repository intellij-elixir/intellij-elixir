package org.elixir_lang.code_insight.completion.code_fragment

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** How [kind] reads an item's PSI element. */
class ObservationTest : PlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    fun testKindNamesANestedModuleInFull() {
        myFixture.configureByText("nested.ex", "defmodule Outer do\n  defmodule Inner do\n    def f, do: 1\n  end\nend\n")
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, Call::class.java)
        val inner = calls.single { Stub.isModular(it) && it.text.startsWith("defmodule Inner") }
        val f = calls.single { DefinitionDeclaration.`is`(it) }

        assertEquals(Kind.MODULE to "Outer.Inner", kind(inner, Expr))
        assertEquals(Kind.CALL to "Outer.Inner", kind(f, Expr))
    }
}
