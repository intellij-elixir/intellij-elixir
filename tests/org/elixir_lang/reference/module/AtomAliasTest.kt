package org.elixir_lang.reference.module

import org.elixir_lang.declaration.ExpanderSettingTestCase
import com.intellij.psi.PsiPolyVariantReference

/** An alias of an atom-named module reaches that module, however the atom is written. */
class AtomAliasTest : ExpanderSettingTestCase() {
    fun testAliasOfQuotedAtomReachesModule() = assertResolvesToF("alias :\"a.b\", as: AB", "AB", ":quoted")

    fun testAliasOfAtomReachesModuleWrittenQuoted() = assertResolvesToF("alias :plain, as: P", "P", ":plain")

    private fun assertResolvesToF(alias: String, qualifier: String, body: String) {
        myFixture.configureByText(
            "alias.ex",
            """
            defmodule :"a.b" do
              def f, do: :quoted
            end

            defmodule :"plain" do
              def f, do: :plain
            end

            defmodule User do
              $alias

              def g, do: $qualifier.f<caret>()
            end
            """.trimIndent()
        )

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val resolved = reference.multiResolve(false).mapNotNull { it.element?.text }

        assertTrue("$qualifier.f() resolved to $resolved", resolved.size == 1 && resolved.single().contains(body))
    }
}
