package org.elixir_lang.psi.scope

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * Regression for the walk-up rewrite of the modular branch's compile-time gate ([CallDefinitionClause]'s
 * `modularContainsEntranceAtCompileTimeLevel`, replacing a scan of every one of the module's children with
 * a walk up from the entrance - see #4123's sandbox-profiling follow-up). Two shapes the walk-up must still
 * get right: an entrance nested inside the *same* top-level `if`/`unless` as its own previous sibling
 * declaration (the gate fires, skipping the table - `ElixirStabBody`'s own sibling processing resolves it
 * instead), and an ordinary entrance with no `if`/`unless` anywhere above it (the gate never fires, the
 * table is always consulted).
 */
class CallDefinitionClauseCompileTimeGateTest : PlatformTestCase() {
    fun testEntranceInsideTheSameIfAsAPreviousSiblingStillResolves() {
        myFixture.configureByText(
            "gated.ex",
            """
            defmodule Gated do
              if true do
                def conditional, do: :ok
                def caller, do: conditional()
              end
            end
            """.trimIndent()
        )

        assertResolvesTo(entranceText = "conditional()", targetHeadName = "conditional")
    }

    fun testEntranceNestedTwoIfLevelsDeepStillResolvesItsOwnPreviousSibling() {
        myFixture.configureByText(
            "nested_gated.ex",
            """
            defmodule NestedGated do
              if true do
                if true do
                  def conditional, do: :ok
                  def caller, do: conditional()
                end
              end
            end
            """.trimIndent()
        )

        assertResolvesTo(entranceText = "conditional()", targetHeadName = "conditional")
    }

    fun testEntranceWithNoIfAboveItStillResolvesAForwardReferenceViaTheTable() {
        myFixture.configureByText(
            "ordinary.ex",
            """
            defmodule Ordinary do
              def caller, do: target()
              def target, do: :ok
            end
            """.trimIndent()
        )

        assertResolvesTo(entranceText = "target()", targetHeadName = "target")
    }

    /** [entranceText] is the call site to resolve (e.g. `"conditional()"`); [targetHeadName] is the
     *  declared name a valid result must resolve to. */
    private fun assertResolvesTo(entranceText: String, targetHeadName: String) {
        val entranceCall = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.text == entranceText }

        val reference = entranceCall.reference
        assertNotNull("`$entranceText` must have a reference", reference)

        val resolved = (reference as PsiPolyVariantReference).multiResolve(false)

        assertTrue(
            "resolving `$entranceText` must find a valid result declaring `$targetHeadName`",
            resolved.any { result ->
                result.isValidResult && (result.element as? Call)?.functionName() == "def" &&
                    result.element?.text?.contains(targetHeadName) == true
            }
        )
    }
}
