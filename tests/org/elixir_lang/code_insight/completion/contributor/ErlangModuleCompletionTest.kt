package org.elixir_lang.code_insight.completion.contributor

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completeSoleCandidateAtCaret
import org.elixir_lang.code_insight.completionStringsAtCaret
import java.io.File

/**
 * Completion against a BEAM-decompiled Erlang module - the OTP stdlib case
 * [#615](https://github.com/intellij-elixir/intellij-elixir/issues/615) asks for. Resolution against this
 * same `math.beam` is pinned by `ErlangAtomQualifierTest` and `ErlangMfaTupleReferenceTest`; these
 * pin the popup.
 *
 * Three references reach a BEAM module and each gets a test: the module atom
 * (`GeneralAtomReference` -> `psi.scope.atom.Variants`, an `AllName` prefix search over
 * `:`-prefixed keys), functions after an atom qualifier (`provider.CallDefinitionClause` ->
 * `qualifiedLookupElements`, which appends `()`), and the function atom of an MFA
 * (`AtomReference`, the same lookup elements without parentheses).
 *
 * `sqrt/1` and `sin/1` are both stable OTP exports, so the `s` prefix keeps two candidates alive and
 * opens a popup. `:math` is the lone `:ma` match, so that test asserts the auto-insert with
 * [completeSoleCandidateAtCaret] rather than a popup.
 */
class ErlangModuleCompletionTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = ERLANG_STDLIB_EBIN

    /**
     * `:ma<caret>` auto-inserts the decompiled Erlang module `:math` - the entry point for #615,
     * since nothing downstream is reachable by typing until the module atom completes.
     *
     * The lookup string carries the colon (`:math`, not `math`) because `GeneralAtomReference`'s
     * range spans the whole atom, so the insertion replaces the colon rather than doubling it.
     */
    fun testErlangModuleAtomCompletesToDecompiledModule() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    :ma<caret>
                  end
                end
            """.trimIndent()
        )

        assertEquals(
            """
                defmodule Test do
                  def run do
                    :math
                  end
                end
            """.trimIndent(),
            myFixture.completeSoleCandidateAtCaret()
        )
    }

    /**
     * `:math.s<caret>` offers `sqrt` - the exported functions of a decompiled Erlang module, reached
     * through the atom qualifier.
     *
     * Only the `BeamModule` branch of `callDefinitionClauseVisible` makes BEAM functions
     * offerable; a `Call`-only path discards the decompiled module and offers nothing.
     */
    fun testErlangModuleFunctionsAreOfferedAfterAtomQualifier() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    :math.s<caret>
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("No completion popup after the Erlang atom qualifier `:math.`", strings)
        assertTrue(
            "Expected `sqrt` among the completions after `:math.s`, got: ${strings!!.sorted()}",
            strings.contains("sqrt")
        )
    }

    /**
     * `:math.<caret>` with nothing typed after the dot offers `sqrt` too.
     *
     * Distinct from the prefixed case above: the completion copy inserts an alias-shaped dummy
     * identifier, and an atom followed by an alias is a syntax error in Elixir - `Code.string_to_quoted`
     * rejects it with "atom cannot be followed by an alias". This grammar is more permissive than the
     * language: `matchedQualifiedAlias ::= matchedExpression dotInfixOperator alias`
     * and an atom is a `matchedExpression`, so `:math.<dummy>` still reduces to a qualified *alias* and is
     * answered by the same branch as `Mod.<caret>`. `:math.s<caret>` reaches the qualified-call branch
     * instead, because a lowercase dummy continues a call.
     */
    fun testErlangModuleFunctionsAreOfferedAfterABareAtomQualifier() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    :math.<caret>
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("No completion popup after the bare Erlang atom qualifier `:math.`", strings)
        assertTrue(
            "Expected `sqrt` among the completions after `:math.`, got: ${strings!!.sorted()}",
            strings.contains("sqrt")
        )
    }

    /**
     * `{:math, :s<caret>, 1}` offers `sqrt` - the MFA-tuple function atom against a BEAM module.
     * `ErlangMfaTupleReferenceTest` pins that this resolves; this pins that it completes.
     */
    fun testErlangMfaTupleFunctionAtomOffersModuleFunctions() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    {:math, :s<caret>, 1}
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("No completion popup for the MFA-tuple function atom `{:math, :s}`", strings)
        assertTrue(
            "Expected `sqrt` among the MFA-tuple function-atom completions, got: ${strings!!.sorted()}",
            strings.contains("sqrt")
        )
    }

    /**
     * `apply(:math, :s<caret>, [1])` offers `sqrt` - the `apply/3` shape of the same MFA path.
     *
     * `AtomCompletionTest` covers this for an Elixir source module; a BEAM module reaches
     * `callDefinitionClauseVisible` through the `BeamModule` branch instead.
     */
    fun testErlangApplyFunctionAtomOffersModuleFunctions() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    apply(:math, :s<caret>, [1])
                  end
                end
            """.trimIndent()
        )

        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("No completion popup for the `apply/3` function atom `apply(:math, :s, [1])`", strings)
        assertTrue(
            "Expected `sqrt` among the apply/3 function-atom completions, got: ${strings!!.sorted()}",
            strings.contains("sqrt")
        )
    }
}
