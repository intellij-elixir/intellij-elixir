package org.elixir_lang.refactoring

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationLineAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * A use of a `defdelegate` names the delegation, which Go To follows to its target, wherever the delegation is written
 * (under a compile-time `if`) and whichever of several same-named delegations of different arities it names.
 */
class DelegationScopeTest : PlatformTestCase() {
    private val conditional = """
        defmodule Target do
          if Code.ensure_loaded?(Kernel) do
            def snoc(q, x), do: {q, x}
          end
        end

        defmodule Delegator do
          if Code.ensure_loaded?(Kernel) do
            defdelegate snoc(q, x), to: Target
          end
        end

        defmodule Caller do
          def calls(a, b), do: {Delegator.snoc(a, b), &Delegator.snoc/2, apply(Delegator, :snoc, [a, b])}
        end
    """.trimIndent()

    private val wrongArity = """
        defmodule Target do
          def snoc(q, x), do: {q, x}
        end

        defmodule Delegator do
          @doc "Delegated."
          defdelegate snoc(q, x), to: Target
        end

        defmodule Caller do
          def calls(a), do: Delegator.snoc(a)
        end
    """.trimIndent()

    private val multiArity = """
        defmodule Target do
          def snoc(), do: {}
          def snoc(a), do: {a}
          def snoc(a, b), do: {a, b}
        end

        defmodule Delegator do
          defdelegate snoc(), to: Target
          defdelegate snoc(a), to: Target
          defdelegate snoc(a, b), to: Target
        end

        defmodule Caller do
          import Delegator

          def unqualified(a, b), do: snoc(a, b)
          def piped(a, b), do: a |> snoc(b)
        end
    """.trimIndent()

    fun testGoToFromACallOfADelegationUnderAnIfFollowsIt() =
        assertFollows(conditional, "Delegator.sn<caret>oc(a, b)", "def snoc(q, x), do: {q, x}")

    fun testGoToFromACaptureOfADelegationUnderAnIfFollowsIt() =
        assertFollows(conditional, "&Delegator.sn<caret>oc/2", "def snoc(q, x), do: {q, x}")

    fun testGoToFromAnApplyOfADelegationUnderAnIfFollowsIt() =
        assertFollows(conditional, "apply(Delegator, :sn<caret>oc", "def snoc(q, x), do: {q, x}")

    fun testGoToFromAnImportedCallOfAMultiArityDelegationFollowsIt() =
        assertFollows(multiArity, "do: sn<caret>oc(a, b)", "def snoc(a, b), do: {a, b}")

    fun testGoToFromAPipedCallOfAMultiArityDelegationFollowsIt() =
        assertFollows(multiArity, "a |> sn<caret>oc(b)", "def snoc(a, b), do: {a, b}")

    /**
     * A `defdelegate` calls its target remotely, so it reaches only what the `to:` module exports: not what that module
     * imports, nor `Kernel`'s functions, nor a private one. Go To lands on the delegation, as for a `to:` that is missing.
     */
    fun testGoToFromACallOfADelegationFollowsOnlyWhatItsTargetExports() {
        val imports = """
            defmodule Source do
              def snoc(q, x), do: {q, x}
            end

            defmodule Kernel do
              def is_nil(term), do: term == nil
            end

            defmodule Target do
              import Source, only: [snoc: 2], warn: false

              defp secret(q), do: q
            end

            defmodule Delegator do
              defdelegate snoc(q, x), to: Target
              defdelegate is_nil(q), to: Target
              defdelegate secret(q), to: Target

              def calls(a, b), do: {snoc(a, b), is_nil(a), secret(a)}
            end
        """.trimIndent()

        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)

        assertEquals(
            listOf("defdelegate snoc(q, x), to: Target", "defdelegate is_nil(q), to: Target", "defdelegate secret(q), to: Target"),
            listOf("sn<caret>oc(a, b)", "is_n<caret>il(a)", "sec<caret>ret(a)").map { caretAt ->
                configure(imports, caretAt)
                myFixture.gotoDeclarationLineAtCaret()
            }
        )
    }

    fun testRenamingFromAnImportedCallOfAMultiArityDelegationRenamesTheDelegation() {
        configure(multiArity, "do: sn<caret>oc(a, b)")

        myFixture.renameTargetAtCaret("renamed")

        val expected = multiArity
            .replace("defdelegate snoc(a, b), to: Target", "defdelegate renamed(a, b), to: Target, as: :snoc")
            .replace("do: snoc(a, b)", "do: renamed(a, b)")
            .replace("a |> snoc(b)", "a |> renamed(b)")
        assertEquals(expected, myFixture.editor.document.text)
    }

    /**
     * A call at an arity no delegation declares does not compile, so it resolves to nothing validly. The delegation and
     * what it delegates to are still offered as candidates, to document it and suggest the arity that is declared.
     */
    fun testACallAtAnArityNoDelegationDeclaresIsOnlyOfferedCandidates() =
        assertCandidatesOnly(
            wrongArity,
            "Delegator.sn<caret>oc(a)",
            "defdelegate snoc(q, x), to: Target",
            "def snoc(q, x), do: {q, x}"
        )

    /** ... even where what it delegates to defines that arity, which the delegation does not pass on. */
    fun testACallAtAnArityOnlyTheTargetDefinesIsOnlyOfferedCandidates() =
        assertCandidatesOnly(
            wrongArity.replace("def snoc(q, x), do: {q, x}", "def snoc(q, x), do: {q, x}\n  def snoc(a), do: {a}"),
            "Delegator.sn<caret>oc(a)",
            "defdelegate snoc(q, x), to: Target",
            "def snoc(q, x), do: {q, x}",
            "def snoc(a), do: {a}"
        )

    private fun assertCandidatesOnly(source: String, caretAt: String, vararg expected: String) {
        configure(source, caretAt)
        val call = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            org.elixir_lang.psi.call.Call::class.java
        )!!

        val resolved = (call.reference as com.intellij.psi.PsiPolyVariantReference).multiResolve(false)

        assertEquals(expected.toSet(), resolved.mapNotNull { it.element?.text?.lineSequence()?.first() }.toSet())
        assertEquals(emptyList<String>(), resolved.filter { it.isValidResult }.mapNotNull { it.element?.text })
    }

    /** A definition under a compile-time `if` belongs to the module around the `if`, not to the `if`. */
    fun testADefinitionUnderAnIfBelongsToItsModule() {
        configure(conditional, "defdelegate sn<caret>oc(q, x)")
        val calls = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, org.elixir_lang.psi.call.Call::class.java)

        val modules = listOf("def snoc", "defdelegate snoc").map { start ->
            calls.first { it.text.startsWith(start) }.let(org.elixir_lang.model.psi.function.FunctionSymbol::fromDeclaration).single().moduleName
        }

        assertEquals(listOf("Target", "Delegator"), modules)
    }

    /** ... so the target's `def` is not another declaration of the delegation's function. */
    fun testFindUsagesOfADelegationUnderAnIfDoesNotListItsTarget() {
        configure(conditional, "defdelegate sn<caret>oc(q, x)")
        val text = myFixture.file.text

        val usages = myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { usage -> text.substring(0, usage.range.startOffset).count { it == '\n' } + 1 }
            .sorted()

        assertEquals(listOf(text.lines().indexOfFirst { "Delegator.snoc(a, b)" in it } + 1), usages.distinct())
    }

    private fun assertFollows(source: String, caretAt: String, targetLine: String) {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        configure(source, caretAt)

        val line = myFixture.gotoDeclarationLineAtCaret()

        assertNotNull("Go To Declaration from `$caretAt` went nowhere", line)
        assertEquals(targetLine, line)
    }

    private fun configure(source: String, caretAt: String) {
        val plain = caretAt.replace("<caret>", "")
        assertEquals("`$plain` must occur once", 1, source.split(plain).size - 1)
        myFixture.configureByText("delegation_scope.ex", source.replace(plain, caretAt))
    }
}
