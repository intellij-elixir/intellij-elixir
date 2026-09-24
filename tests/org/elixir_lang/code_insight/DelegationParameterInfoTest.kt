package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/** A call of a `defdelegate` names the delegation, so parameter info shows the delegation's own head. */
class DelegationParameterInfoTest : PlatformTestCase() {
    /** The target only imports the function, so nothing the delegation reaches has a head to show but its own. */
    fun testACallOfADelegationWhoseTargetLacksTheFunctionShowsTheDelegationsHead() =
        assertEquals(listOf("q, x"), shown(target = "import Source"))

    /** Where the target defines it, the call still names the delegation: its head, not the target's parameters. */
    fun testACallOfADelegationWhoseTargetDefinesTheFunctionShowsTheDelegationsHead() =
        assertEquals(listOf("q, x"), shown(target = "def snoc(a, b), do: {a, b}"))

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

    private fun shown(target: String): List<String> {
        myFixture.configureByText(
            "delegation_parameter_info.ex",
            """
            defmodule Source do
              def snoc(q, x), do: {q, x}
            end

            defmodule Target do
              $target
            end

            defmodule Delegator do
              defdelegate snoc(q, x), to: Target
            end

            defmodule Caller do
              def calls(a), do: Delegator.snoc(a<caret>, a)
            end
            """.trimIndent()
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }
}
