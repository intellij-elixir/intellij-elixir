package org.elixir_lang.psi.scope.variable

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call

/**
 * Elixir evaluates a call's arguments left to right, so a read sees a binding in an earlier argument and never one in
 * a later argument - an EEx `function_from_*` or `Mix.Generator` embed included, since their arguments are values.
 *
 * Each row prints the form [CallableDeclaration.formOf] gives the call, so an EEx or generator row that stops being
 * recognised as one - `EEx` or `Mix.Generator` not loaded - shows in the table rather than passing as an ordinary call.
 */
class ArgumentOrderTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testAReadSeesAnEarlierArgumentsBindingAndNeverALaterOnes() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")

        val actual = CASES.joinToString("\n") { (name, callee, code) ->
            myFixture.configureByText("$name.ex", code)

            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.functionName() == callee }
            val form = CallableDeclaration.formOf(call, ResolveState.initial()) ?: "-"
            val resolved = (myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference)
                ?.multiResolve(false)
                ?.mapNotNull { it.element?.text }
                .orEmpty()

            "$name $form -> ${resolved.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "-"}"
        }

        assertEquals(
            """
            preceding_expression - -> template
            earlier_argument - -> template
            later_argument - -> -
            eex_earlier_argument EEX_FUNCTION_FROM -> template
            eex_later_argument EEX_FUNCTION_FROM -> -
            generator_earlier_argument GENERATOR_EMBED -> template
            generator_later_argument GENERATOR_EMBED -> -
            """.trimIndent(),
            actual
        )
    }

    private companion object {
        fun inModule(body: String) = "defmodule Sample do\n  require EEx\n  require Mix.Generator\n\n$body\nend\n"

        val CASES = listOf(
            Triple("preceding_expression", "puts", inModule("  def run do\n    template = \"x\"\n    IO.puts(tem<caret>plate)\n  end")),
            Triple("earlier_argument", "puts", inModule("  def run do\n    IO.puts([template = \"x\"], tem<caret>plate)\n  end")),
            Triple("later_argument", "puts", inModule("  def run do\n    IO.puts(tem<caret>plate, [template = \"x\"])\n  end")),
            Triple(
                "eex_earlier_argument",
                "function_from_string",
                inModule("  EEx.function_from_string(:def, :f, [template = \"x\"], tem<caret>plate)")
            ),
            Triple(
                "eex_later_argument",
                "function_from_string",
                inModule("  EEx.function_from_string(:def, :f, tem<caret>plate, [template = \"x\"])")
            ),
            Triple(
                "generator_earlier_argument",
                "embed_text",
                inModule("  Mix.Generator.embed_text(template = :x, tem<caret>plate)")
            ),
            Triple(
                "generator_later_argument",
                "embed_text",
                inModule("  Mix.Generator.embed_text(tem<caret>plate, template = \"x\")")
            )
        )
    }
}
