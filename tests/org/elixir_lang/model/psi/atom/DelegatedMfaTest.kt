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

    /** A delegation at another arity does not hide the definition at the arity an MFA names. */
    fun testAnApplyReachesTheDefinitionBesideADelegationAtAnotherArity() {
        myFixture.configureByText(
            "other_arity.ex",
            """
            defmodule Target do
              def snoc(q), do: q
            end

            defmodule OtherArity do
              defdelegate snoc(q), to: Target
              def snoc(q, x), do: {q, x}
            end

            defmodule Caller do
              def applies(a, b), do: apply(OtherArity, :snoc, [a, b])
            end
            """.trimIndent()
        )

        val caller = myFixture.file.text.indexOf("defmodule Caller")
        val atom = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).single { it.textOffset > caller }
        val symbols = (atom.reference as PsiSymbolReference).resolveReference()
            .filterIsInstance<AtomSymbol>()
            .map { "${it.name}/${it.arity}" }

        assertEquals(listOf("snoc/2"), symbols)
    }

    /** Renaming the definition renames the MFA atom naming it, beside a delegation at another arity it leaves alone. */
    fun testRenamingTheDefinitionBesideADelegationAtAnotherArityRenamesTheAtom() {
        myFixture.configureByText(
            "other_arity_rename.ex",
            """
            defmodule Target do
              def snoc(q), do: q
            end

            defmodule OtherArity do
              defdelegate snoc(q), to: Target
              def sn<caret>oc(q, x), do: {q, x}
            end

            defmodule Caller do
              def applies(a, b), do: apply(OtherArity, :snoc, [a, b])
            end
            """.trimIndent()
        )

        myFixture.renameTargetAtCaret("renamed")

        val text = myFixture.editor.document.text
        assertTrue(text, "apply(OtherArity, :renamed, [a, b])" in text)
        assertTrue(text, "defdelegate snoc(q), to: Target\n" in text)
    }

    /** A delegation at another arity makes the MFA's arity undefined, not a name for what it delegates to. */
    fun testAnApplyAtAnArityOnlyTheTargetDefinesNamesNothing() {
        for (options in listOf("to: Target", "to: Target, as: :add")) {
            myFixture.configureByText(
                "target_arity.ex",
                """
                defmodule Target do
                  def snoc(q, x), do: {q, x}
                  def add(q, x), do: {q, x}
                end

                defmodule OnlyOne do
                  defdelegate snoc(q), $options
                end

                defmodule Caller do
                  def applies(a, b), do: apply(OnlyOne, :snoc, [a, b])
                end
                """.trimIndent()
            )

            val caller = myFixture.file.text.indexOf("defmodule Caller")
            val atom = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).single { it.textOffset > caller }
            val symbols = (atom.reference as PsiSymbolReference).resolveReference()
                .filterIsInstance<AtomSymbol>()
                .map { "${it.name}/${it.arity}" }

            assertEquals(options, emptyList<String>(), symbols)
        }
    }

    /** An MFA atom naming a delegation with `as:` reaches what it delegates to, as a call of it does. */
    fun testAnApplyOfARenamedDelegationReachesItsTargetAsACallDoes() {
        myFixture.configureByText(
            "renamed_delegation.ex",
            """
            defmodule Target do
              def snoc(q), do: q
            end

            defmodule Renaming do
              defdelegate renamed(q), to: Target, as: :snoc
            end

            defmodule Caller do
              def applies(q), do: apply(Renaming, :renamed, [q])
              def calls(q), do: Renaming.renamed(q)
            end
            """.trimIndent()
        )

        fun targets(element: com.intellij.psi.PsiElement): List<String> =
            generateSequence(element) { it.parent }
                .mapNotNull { it.reference as? PsiPolyVariantReference }
                .first()
                .multiResolve(false)
                .filter { it.isValidResult }
                .mapNotNull { it.element?.text?.trim() }

        val text = myFixture.file.text
        val atom = myFixture.file.findElementAt(text.indexOf(":renamed, [q]") + 1)!!
        val call = myFixture.file.findElementAt(text.indexOf("renamed(q)", text.indexOf("def calls")))!!

        assertEquals(targets(call).sorted(), targets(atom).sorted())
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
