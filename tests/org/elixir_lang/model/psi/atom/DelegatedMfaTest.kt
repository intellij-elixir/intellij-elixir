package org.elixir_lang.model.psi.atom

import com.intellij.model.psi.PsiSymbolReference
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.psi.ElixirAtom

/**
 * A `defdelegate` reached as a Symbol keeps the rules a call already follows: an `apply/3` reaches what it delegates
 * to first, then the delegation; an MFA names one arity; and the `defdelegate` head is its declaration, never a usage
 * of itself.
 */
class DelegatedMfaTest : PlatformTestCase() {
    private val source = """
        defmodule Target do
          def snoc(q, x), do: {q, x}
        end

        defmodule Delegator do
          defdelegate snoc(q, x), to: Target
          defdelegate lost(q, x), to: Missing
          def spread(a, b \\ nil, c \\ nil), do: {a, b, c}
        end

        defmodule Caller do
          def applies(a, b) do
            {apply(Delegator, :snoc, [a, b]), apply(Delegator, :lost, [a, b]), apply(Delegator, :spread, [a, b])}
          end

          def calls(a, b), do: Delegator.lost(a, b)
        end
    """.trimIndent()

    fun testAnApplyReachesWhatADelegationDelegatesToAndOnlyItsOwnArity() {
        myFixture.configureByText("delegated_mfa.ex", source)

        val caller = myFixture.file.text.indexOf("defmodule Caller")
        val atoms = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).filter { it.textOffset > caller }
        val actual = atoms.joinToString("\n") { atom ->
            val targets = (atom.reference as PsiPolyVariantReference).multiResolve(false)
                .filter { it.isValidResult }
                .mapNotNull { it.element?.text?.lineSequence()?.first()?.trim() }
            val arities = (atom.reference as PsiSymbolReference).resolveReference()
                .filterIsInstance<AtomSymbol>()
                .map { it.arity }
                .distinct()

            "${atom.text} -> $targets $arities"
        }

        assertEquals(
            """
            :snoc -> [def snoc(q, x), do: {q, x}, defdelegate snoc(q, x), to: Target] [2]
            :lost -> [defdelegate lost(q, x), to: Missing] [2]
            :spread -> [def spread(a, b \\ nil, c \\ nil), do: {a, b, c}] [2]
            """.trimIndent(),
            actual
        )
    }

    fun testADelegationIsNotAUsageOfItself() {
        myFixture.configureByText("delegated_mfa.ex", source.replace("defdelegate lost(q", "defdelegate lo<caret>st(q"))

        val text = myFixture.file.text
        val usages = myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { usage -> text.substring(0, usage.range.startOffset).count { it == '\n' } + 1 }
            .sorted()
        val line = { fragment: String -> text.substring(0, text.indexOf(fragment)).count { it == '\n' } + 1 }

        assertEquals(listOf(line("apply(Delegator, :snoc"), line("Delegator.lost(a, b)")), usages)
    }

    fun testRenamingADelegationFromItsSpecRenamesItsApply() {
        for (name in listOf("snoc", "lost")) {
            val specced = source.replace("  defdelegate $name(q", "  @spec ${name.first()}<caret>${name.drop(1)}(term, term) :: term\n  defdelegate $name(q")
            myFixture.configureByText("delegated_mfa_spec_$name.ex", specced)

            myFixture.renameTargetAtCaret("renamed")

            val text = myFixture.file.text
            assertTrue("$name: $text", "defdelegate renamed(q, x)" in text && "apply(Delegator, :renamed, [a, b])" in text)
        }
    }
}
