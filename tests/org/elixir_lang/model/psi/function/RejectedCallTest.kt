package org.elixir_lang.model.psi.function

import com.intellij.find.usages.impl.searchTargets
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.inspection.References

/**
 * A call at an arity nothing declares resolves only to invalid results. The editor says so, naming the arities there are, and offers
 * those declarations - a delegation's own head - to search for and label, but not to rename.
 */
@Suppress("UnstableApiUsage")
class RejectedCallTest : PlatformTestCase() {
    private val source = """
        defmodule Target do
          def snoc(q, x), do: {q, x}
        end

        defmodule Delegator do
          defdelegate snoc(q, x), to: Target
        end

        defmodule Caller do
          def calls(a), do: Delegator.snoc(a)
        end
    """.trimIndent()

    private fun configure() = myFixture.configureByText("rejected.ex", source.replace("Delegator.snoc(a)", "Delegator.sn<caret>oc(a)"))

    fun testTheSearchTargetsAreTheDelegationsDeclaredArities() {
        configure()

        assertEquals(
            listOf("defdelegate snoc(q, x)"),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
        )
    }

    /** A delegation with defaults is one declaration, offered once whichever of its arities the call could mean. */
    fun testADelegationWithDefaultsIsOfferedOnce() {
        myFixture.configureByText(
            "defaults.ex",
            """
            defmodule Target do
              def snoc(a, b), do: {a, b}
            end

            defmodule Delegator do
              defdelegate snoc(a \\ nil, b \\ nil), to: Target
            end

            defmodule Caller do
              import Delegator, except: [snoc: 1]

              def calls(a), do: sn<caret>oc(a)
            end
            """.trimIndent()
        )

        assertEquals(
            listOf("defdelegate snoc(a \\\\ nil, b \\\\ nil)"),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
        )
    }

    /** A delegation whose name only starts with the call's, or which the call only starts, is not what it meant. */
    fun testADelegationOfAnotherNameIsNotACandidate() {
        val lookalikes = source.replace(
            "  defdelegate snoc(q, x), to: Target\n",
            "  defdelegate snoc(q, x), to: Target\n  defdelegate snoc_x(q, x), to: Target\n  defdelegate snoc?(q, x), to: Target\n"
        )

        myFixture.configureByText("lookalikes.ex", lookalikes.replace("Delegator.snoc(a)", "Delegator.sn<caret>oc(a)"))

        assertEquals(
            listOf("defdelegate snoc(q, x)"),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
        )
    }

    /** A misspelling that is only the start of a delegation's name reaches nothing, even where its `to:` does not resolve. */
    fun testAMisspelledCallOffersNothing() {
        myFixture.configureByText(
            "misspelled.ex",
            source.replace("to: Target", "to: Missing").replace("Delegator.snoc(a)", "Delegator.sn<caret>o(a)")
        )

        assertEquals(
            emptyList<String>(),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
        )
    }

    /**
     * A misspelling that is the start of a delegation's name, at the arity the delegation declares, does not reach what
     * the delegation delegates to: renaming it would edit a function it does not call.
     */
    fun testAMisspelledCallOfTheDeclaredArityOffersNothingToRename() {
        myFixture.configureByText("misspelled_arity.ex", source.replace("Delegator.snoc(a)", "Delegator.sn<caret>o(a, a)"))

        assertEquals(emptyList<String>(), myFixture.renameTargetsAtCaret().map { it.presentation().presentableText })
        assertEquals(source.replace("Delegator.snoc(a)", "Delegator.sno(a, a)"), myFixture.editor.document.text)
    }

    /**
     * A misspelling that no arity of anything is called says nothing was found, not that an arity is wrong, and names
     * what the module exports near it, as the compiler does: every arity, public only, and not what a delegation in
     * it delegates to.
     */
    fun testAMisspelledCallIsSaidToResolveToNothingAndOffersWhatItMayHaveMeant() {
        val withPrivate = source
            .replace(
                "  defdelegate snoc(q, x), to: Target\n",
                "  defdelegate snoc(q, x), to: Target\n  def snoc(q, x, y), do: {q, x, y}\n  defp snob(q), do: q\n" +
                    "  defdelegate push(q), to: Target, as: :snop\n"
            )
            .replace("  def snoc(q, x), do: {q, x}\n", "  def snoc(q, x), do: {q, x}\n  def snop(q), do: q\n")

        assertEquals(
            listOf("Does not resolve to anything. Did you mean: snoc/2, snoc/3?"),
            saidAbout("Delegator.sno", withPrivate.replace("Delegator.snoc(a)", "Delegator.sno(a, a)"))
        )
    }

    /** What a `use` injects is exported, as `use GenServer` exports `child_spec/1`; what an `import` brings in is not. */
    fun testAMisspelledCallIsOfferedWhatAUseInjectsButNotWhatAnImportBringsIn() =
        assertEquals(
            listOf("Does not resolve to anything. Did you mean: child_spec/1?"),
            saidAbout(
                "Server.child_spe",
                """
                defmodule Injector do
                  defmacro __using__(_) do
                    quote do
                      def child_spec(arg), do: arg
                    end
                  end
                end

                defmodule Helpers do
                  def child_specs(arg), do: arg
                end

                defmodule Server do
                  use Injector
                  import Helpers
                end

                defmodule Caller do
                  def calls(a), do: Server.child_spe(a)
                end
                """.trimIndent()
            )
        )

    fun testANameNothingIsNearOffersNothing() =
        assertEquals(
            listOf("Does not resolve to anything"),
            saidAbout("Delegator.unrelated", source.replace("Delegator.snoc(a)", "Delegator.unrelated(a)"))
        )

    /** The compiler suggests nothing for a local call, whatever is near it. */
    fun testAnUnqualifiedMisspellingOffersNothing() =
        assertEquals(
            listOf("Does not resolve to anything"),
            saidAbout(
                "sno(q, q)",
                source.replace("  defdelegate snoc(q, x), to: Target\n", "  defdelegate snoc(q, x), to: Target\n  def calls(q), do: sno(q, q)\n")
            )
        )

    /** The arities named are the called module's: `Delegator` declares `snoc/2`, whatever else `Target` defines. */
    fun testTheAritiesNamedAreTheCalledModulesNotItsDelegationsTarget() =
        assertEquals(
            listOf("Only resolves to invalid results; defined as snoc/2"),
            saidAbout(
                "Delegator.snoc",
                source.replace("  def snoc(q, x), do: {q, x}\n", "  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\n")
            )
        )

    /**
     * A call at an arity a `def` or `defmacro` does not declare, whether an `import only:` excludes it or it is just wrong,
     * offers the arities there are to Go To Declaration and labels, as a delegation does; only a rename is refused.
     */
    fun testAWrongArityCallToADefOrDefmacroOffersItsDeclaredArity() {
        val callers = listOf(
            "an import only: excluding the arity" to "import DEFINER, only: [snoc: 1]\n\n  def calls(a, b), do: sn<caret>oc(a, b)",
            "a qualified call" to "def calls(a), do: DEFINER.sn<caret>oc(a, a, a)"
        )

        for (definer in listOf("def", "defmacro")) {
            for ((description, caller) in callers) {
                // Each form its own module: the project keeps the file of the one before.
                val module = "Definer${definer.replaceFirstChar { it.uppercase() }}"
                val declaration = "$definer snoc(q, x \\\\ nil), do: {q, x}"
                myFixture.configureByText(
                    "offered_$definer.ex",
                    "defmodule $module do\n  $declaration\nend\n\ndefmodule Caller$module do\n  ${caller.replace("DEFINER", module)}\nend\n"
                )

                assertEquals(
                    "$definer, $description: label",
                    listOf("$definer snoc(q, x \\\\ nil)"),
                    searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
                )
                assertEquals(
                    "$definer, $description: Go To Declaration",
                    listOf(declaration),
                    listOfNotNull(myFixture.gotoDeclarationDestinationAtCaret()).map { destination ->
                        val document = myFixture.editor.document
                        val line = document.getLineNumber(destination.textOffset)
                        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
                    }
                )
                val refusal = runCatching { myFixture.renameTargetAtCaret("renamed") }.exceptionOrNull()
                assertTrue("$definer, $description: rename is refused, got $refusal", refusal is IllegalArgumentException)
            }
        }
    }

    /** A function's clauses are one function, offered once to a call at an arity it does not declare. */
    fun testAWrongArityCallOffersAMultiClauseFunctionOnce() {
        myFixture.configureByText(
            "multi_clause.ex",
            """
            defmodule Clauses do
              def snoc(q, nil), do: q
              def snoc(q, x), do: {q, x}
            end

            defmodule Caller do
              def calls(a), do: Clauses.sn<caret>oc(a)
            end
            """.trimIndent()
        )

        assertEquals(1, searchTargets(myFixture.file, myFixture.caretOffset).size)
    }

    /** A private function is not exported, so a remote call names none of its arities, as the compiler's "undefined or private" does. */
    fun testARemoteCallNamesNoPrivateArity() =
        assertEquals(
            listOf("Does not resolve to anything"),
            saidAbout(
                "Definer.priv",
                """
                defmodule Definer do
                  defp priv(a), do: a
                  def pub(a), do: priv(a)
                end

                defmodule Caller do
                  def calls, do: Definer.priv(1, 2)
                end
                """.trimIndent()
            )
        )

    /** An `import only:` brings in the arities it lists, so those are the ones there are. */
    fun testTheAritiesNamedAreTheOnesTheImportBringsIn() =
        assertEquals(
            listOf("Only resolves to invalid results; defined as pad/2"),
            saidAbout(
                "pad(1)",
                """
                defmodule Padding do
                  def pad(a, b \\ 1), do: {a, b}
                end

                defmodule Caller do
                  import Padding, only: [pad: 2]

                  def calls, do: pad(1)
                end
                """.trimIndent()
            )
        )

    private val kernelAndNesting = """
        defmodule Kernel do
          def is_nil(term), do: term == nil
          def is_atom(term), do: term
        end

        defmodule Outer do
          def outer, do: :ok

          defmodule Inner do
            def inner, do: :ok
          end
        end

        defmodule Mod do
          def own, do: :ok
        end
    """.trimIndent()

    /**
     * What a module exports is what it declares or a `use` injects: not `Kernel`'s, which it imports implicitly, nor
     * the module it is nested in. So a remote call suggests and names neither.
     */
    fun testARemoteCallIsOfferedOnlyWhatItsModuleExports() {
        val caller = "\n\ndefmodule Caller do\n  def calls(x), do: {Mod.is_nill(x), Mod.is_atom(1, 2), Outer.Inner.outr()}\nend\n"

        assertEquals(
            listOf(
                "Mod.is_nill -> Does not resolve to anything",
                "Mod.is_atom -> Does not resolve to anything",
                "Outer.Inner.outr -> Does not resolve to anything"
            ),
            listOf("Mod.is_nill", "Mod.is_atom", "Outer.Inner.outr").map { called ->
                "$called -> ${saidAbout(called, kernelAndNesting + caller).joinToString(" | ")}"
            }
        )
    }

    /** A name the call only starts is not a valid result, even when the walk follows the delegation it starts. */
    fun testAPrefixOfADelegationsNameIsNeverAValidResult() {
        myFixture.configureByText(
            "prefix.ex",
            source.replace("  defdelegate snoc(q, x), to: Target\n", "  defdelegate snoc(q, x), to: Target\n  def calls(a), do: sno(a, a)\n")
        )

        assertFalse(validAt("sno(a, a)", incompleteCode = true))
    }

    private fun validAt(fragment: String, incompleteCode: Boolean): Boolean {
        val offset = myFixture.file.text.indexOf(fragment)
        assertTrue("`$fragment` not found", offset >= 0)

        return generateSequence(myFixture.file.findElementAt(offset)) { it.parent }
            .mapNotNull { it.reference as? com.intellij.psi.PsiPolyVariantReference }
            .first()
            .multiResolve(incompleteCode)
            .any { it.isValidResult }
    }

    private fun saidAbout(called: String, text: String): List<String> {
        myFixture.configureByText("said.ex", text)
        myFixture.enableInspections(References())

        return myFixture.doHighlighting(HighlightSeverity.ERROR)
            .filter { it.text.startsWith(called) }
            .mapNotNull { it.description }
            .distinct()
    }

    fun testRenamingFromARejectedCallIsRefused() {
        configure()

        val refusal = runCatching { myFixture.renameTargetAtCaret("renamed") }.exceptionOrNull()

        assertTrue("Expected a refusal, got $refusal", refusal is IllegalArgumentException)
        assertTrue("Expected the refusal to say why, got ${refusal?.message}", refusal?.message?.contains("cannot be renamed") == true)
        assertEquals(source, myFixture.editor.document.text)
    }

    fun testTheEditorSaysTheCallResolvesOnlyToInvalidResultsAndNamesTheArities() {
        configure()
        myFixture.enableInspections(References())

        // The fixture has no SDK, so what Kernel defines does not resolve either.
        val said = myFixture.doHighlighting(HighlightSeverity.ERROR)
            .mapNotNull { it.description }
            .filterNot { it == "Does not resolve to anything" }

        assertEquals(listOf("Only resolves to invalid results; defined as snoc/2"), said)
    }
}
