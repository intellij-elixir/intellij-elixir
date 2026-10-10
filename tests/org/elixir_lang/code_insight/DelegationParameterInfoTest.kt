package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/**
 * A call of a `defdelegate` names the delegation, so Parameter Info shows the delegation's own head, once. What the
 * delegation reaches is not a second signature.
 */
class DelegationParameterInfoTest : PlatformTestCase() {
    fun testAPlainDelegationShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target",
                target = "def snoc(q, x), do: {q, x}",
                call = "Delegator.snoc(a<caret>)"
            )
        )

    fun testAnAsDelegationShowsItsHeadNotTheTargetsName() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target, as: :other",
                target = "def other(q, x), do: {q, x}",
                call = "Delegator.snoc(a<caret>)"
            )
        )

    fun testADelegationToAMissingModuleShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Missing",
                target = "def snoc(q, x), do: {q, x}",
                call = "Delegator.snoc(a<caret>)"
            )
        )

    fun testADelegationWhoseTargetHasAnotherArityShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target",
                target = "def snoc(q), do: q\n  def snoc(q, x), do: {q, x}",
                call = "Delegator.snoc(a<caret>)"
            )
        )

    fun testADelegationWhoseOptionsAreAListShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), [to: Target, as: :other]",
                target = "def other(q, x), do: {q, x}",
                call = "Delegator.snoc(a<caret>)"
            )
        )

    /** `DELEGATION_TARGET`: the call is in the module that holds the delegation. */
    fun testACallInTheDelegatingModuleShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target\n  def calls(a), do: snoc(a<caret>)",
                target = "def snoc(q, x), do: {q, x}",
                call = "nil"
            )
        )

    /** `UNHELD_DELEGATION_TARGET`: the call imports the module that holds the delegation. */
    fun testACallThroughImportOfTheDelegatingModuleShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target",
                target = "def snoc(q, x), do: {q, x}",
                call = "snoc(a<caret>)",
                caller = "import Delegator"
            )
        )

    /**
     * Elixir rejects a call to a function imported from two modules ("conflicting snoc/1 import"), so the delegation
     * and its target are two functions of two modules, and both heads are shown, the later import's first.
     */
    fun testADelegationAndItsTargetBothImportedAreTwoFunctions() =
        assertEquals(
            listOf("first, second \\\\ nil", SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target",
                target = "def snoc(first, second \\\\ nil), do: {first, second}",
                call = "snoc(a<caret>)",
                caller = "import Delegator\n  import Target"
            )
        )

    fun testADelegationImportedOnlyAtALowerArityShowsItsHead() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                delegation = "defdelegate snoc(q, x \\\\ nil), to: Target",
                target = "def snoc(q, x), do: {q, x}",
                call = "snoc(a<caret>)",
                caller = "import Delegator, only: [snoc: 1]"
            )
        )

    /** A delegation whose name the called one only starts is not the call's, and hides nothing. */
    fun testADelegationOfALongerNameDoesNotHideTheFunctionCalled() {
        myFixture.configureByText(
            "prefix.ex",
            """
            defmodule Snocs do
              def snoc(q, x), do: {q, x}
              defdelegate snoc_many(list), to: Enum, as: :reverse

              def calls(a), do: snoc(a<caret>, a)
            end
            """.trimIndent()
        )

        assertEquals(listOf("q, x"), myFixture.parameterInfoSignaturesAtCaret())
    }

    /** A delegation at one arity and a function at another are both the name's signatures. */
    fun testADelegationAndAFunctionOfTheSameNameAtOtherAritiesAreBothShown() {
        myFixture.configureByText(
            "arities.ex",
            """
            defmodule Getter do
              defdelegate get(map, key), to: Map
              def get(key), do: key

              def calls(a), do: get(a<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("key", "map, key"), myFixture.parameterInfoSignaturesAtCaret().sorted())
    }

    private fun shown(delegation: String, target: String, call: String, caller: String = ""): List<String> {
        myFixture.configureByText(
            "delegation_parameter_info.ex",
            """
            defmodule Target do
              $target
            end

            defmodule Delegator do
              $delegation
            end

            defmodule Caller do
              $caller

              def calls(a), do: $call
            end
            """.trimIndent()
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private companion object {
        const val SNOC_HEAD = "q, x \\\\ nil"
    }
}
