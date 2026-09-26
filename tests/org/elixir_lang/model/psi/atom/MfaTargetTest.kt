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

    private val targets = """
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
    """.trimIndent()

    /** Completing an MFA's function offers exactly what it can target. */
    fun testCompletingAnMfaFunctionOffersWhatItCanTarget() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText("mfa_completion.ex", "$targets\n\ndefmodule Caller do\n  def mfa, do: {Targets, :x, 1}\nend\n")

        val atom = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).single { it.text == ":x" }

        assertEquals(
            listOf("delegated", "exception", "message", "public_function", "rendered", "unknown_kind"),
            atom.reference!!.variants.map { (it as com.intellij.codeInsight.lookup.LookupElement).lookupString }.sorted()
        )
    }

    /** Completing a capture offers only the functions declared at its arity, whichever form declares them. */
    fun testCompletingACaptureOffersOnlyWhatIsDeclaredAtItsArity() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "capture_completion.ex",
            targets.replace("  def public_function(a), do: a\n", "  def public_function(a), do: a\n  def pair(a, b), do: {a, b}\n") +
                "\n\ndefmodule Caller do\n  def capture, do: &Targets.x/2\nend\n"
        )

        val capture = myFixture.file.findElementAt(myFixture.file.text.indexOf("x/2"))!!
        val reference = generateSequence(capture) { it.parent }.firstNotNullOf { it.reference as? org.elixir_lang.reference.CaptureNameArity }

        assertEquals(
            listOf("pair"),
            reference.variants.map { (it as com.intellij.codeInsight.lookup.LookupElement).lookupString }.sorted()
        )
    }

    /** Completing a remote call offers what the module exports, macros included, and what a `use` injects. */
    fun testCompletingARemoteCallOffersWhatTheModuleExports() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "remote_completion.ex",
            targets.replace("  require EEx\n", "  require EEx\n  use Injector\n  import Helpers\n") +
                "\n\ndefmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      def child_spec(a), do: a\n    end\n  end\nend\n\n" +
                "defmodule Helpers do\n  def helper(a), do: a\nend\n\n" +
                "defmodule Caller do\n  def calls, do: Targets.<caret>\nend\n"
        )
        myFixture.completeBasic()

        assertEquals(
            listOf(
                "child_spec", "delegated", "exception", "message", "public_function", "public_guard", "public_macro",
                "rendered", "unknown_kind"
            ),
            myFixture.lookupElementStrings.orEmpty().sorted()
        )
    }

    fun testAnMfaAtomTargetsTheRemotelyCallableDefinitions() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "mfa_targets.ex",
            """
            $targets

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
