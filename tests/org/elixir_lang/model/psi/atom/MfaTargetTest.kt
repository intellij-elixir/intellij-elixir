package org.elixir_lang.model.psi.atom

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAtom

/**
 * An MFA tuple's function atom targets what `apply/3` can call: a public runtime function, whichever form defines
 * it. A private function, a macro and a guard are not callable through `apply/3`; an EEx kind the plugin cannot read
 * could be public, so it is targeted.
 */
class MfaTargetTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testAnMfaAtomTargetsTheRemotelyCallableDefinitions() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "mfa_targets.ex",
            """
            defmodule Targets do
              require EEx

              def public_function(a), do: a
              defp private_function(a), do: a
              defmacro public_macro(a), do: a
              defguard public_guard(a) when a > 0
              defdelegate delegated(a), to: Kernel, as: :is_atom
              defexception [:message]
              EEx.function_from_string(:def, :rendered, "", [:a])
              @kind :def
              EEx.function_from_string(@kind, :unknown_kind, "", [:a])
            end

            defmodule Caller do
              def mfas do
                [
                  {Targets, :public_function, 1},
                  {Targets, :private_function, 1},
                  {Targets, :public_macro, 1},
                  {Targets, :public_guard, 1},
                  {Targets, :delegated, 1},
                  {Targets, :exception, 1},
                  {Targets, :rendered, 1},
                  {Targets, :unknown_kind, 1}
                ]
              end
            end
            """.trimIndent()
        )

        val caller = myFixture.file.text.indexOf("defmodule Caller")
        val actual = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java)
            .filter { it.textOffset > caller }
            .joinToString("\n") { atom ->
                val targets = (atom.reference as? PsiPolyVariantReference)
                    ?.multiResolve(false)
                    ?.filter { it.isValidResult }
                    ?.mapNotNull { it.element?.text?.lineSequence()?.first() }
                    .orEmpty()

                "${atom.text} -> ${targets.ifEmpty { listOf("nothing") }.joinToString(" | ")}"
            }

        assertEquals(
            """
            :public_function -> def public_function(a), do: a
            :private_function -> nothing
            :public_macro -> nothing
            :public_guard -> nothing
            :delegated -> defdelegate delegated(a), to: Kernel, as: :is_atom
            :exception -> defexception [:message]
            :rendered -> EEx.function_from_string(:def, :rendered, "", [:a])
            :unknown_kind -> EEx.function_from_string(@kind, :unknown_kind, "", [:a])
            """.trimIndent(),
            actual
        )

        val symbols = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java)
            .filter { it.textOffset > caller && it.text in listOf(":public_function", ":delegated", ":rendered") }
            .joinToString("\n") { atom ->
                val found = (atom.reference as com.intellij.model.psi.PsiSymbolReference).resolveReference()
                    .filterIsInstance<AtomSymbol>()
                    .map { "${it.name}/${it.arity}" }

                "${atom.text} -> $found"
            }

        assertEquals(
            ":public_function -> [public_function/1]\n:delegated -> [delegated/1]\n:rendered -> [rendered/1]",
            symbols
        )
    }
}
