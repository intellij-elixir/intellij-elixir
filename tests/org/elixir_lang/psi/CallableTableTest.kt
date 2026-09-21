package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * `CallableTable.of` builds once per module scope and reuses the same instance across resolves - the
 * module-size independence #4123 exists for, in observable form - and its entries carry enough of the
 * walker's own state (`visitedElements`, `wrappers`) to reproduce the entrance-relative guards
 * `Import`/`Use`/`if`/`unless` apply live, instead of losing the resolve path a `use`-sourced declaration
 * needs or over-offering a declaration an entrance already sits inside.
 *
 * This is deliberately not a wall-clock test: the module-size guard that matters is the platform's own
 * missed-cache assertion (`RecursionManager`), which any test resolving through a re-entrant table build
 * would already fail without one line of timing code here.
 */
class CallableTableTest : PlatformTestCase() {
    fun testTableIsTheSameInstanceAcrossResolves() {
        myFixture.configureByText(
            "cached.ex",
            """
            defmodule Cached do
              def one, do: 1
              def two, do: 2
            end
            """.trimIndent()
        )

        val modular = modularCall("Cached")

        assertSame(CallableTable.of(modular), CallableTable.of(modular))
    }

    fun testUseSourcedEntryCarriesTheUseCallInItsWrappers() {
        myFixture.configureByFiles("through_use.ex")

        val modular = modularCall("ThroughUse")
        val entry = CallableTable.of(modular).declaring("clause").single()
        val useCall = PsiTreeUtil.findChildrenOfType(modular, Call::class.java).single { Use.`is`(it) }

        assertTrue(
            "the `use Using` call that declared `clause/1` must be recorded in its entry's wrappers",
            entry.wrappers.any { it.isEquivalentTo(useCall) }
        )
        assertTrue(
            "resolving from outside the `use` must still offer the entry",
            entry.reachableFrom(modular)
        )
    }

    fun testEntryBehindAnIfIsNotReachableFromAnEntranceInsideIt() {
        myFixture.configureByText(
            "if_wrapped.ex",
            """
            defmodule IfWrapped do
              if true do
                def conditional, do: :ok
              end

              def caller, do: conditional()
            end
            """.trimIndent()
        )

        val modular = modularCall("IfWrapped")
        val entry = CallableTable.of(modular).declaring("conditional").single()

        assertFalse(
            "an entrance compile-time-reachable from the declaration's own `if` must not be offered again " +
                "(`ElixirStabBody`'s own declarations already cover it - see `containsCompileTimeAncestorOrSelf`)",
            entry.reachableFrom(entry.call)
        )
        assertTrue(
            "an entrance outside the `if` must still be offered",
            entry.reachableFrom(modular)
        )
    }

    private fun modularCall(name: String): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { isModular(it) && it.text.startsWith("defmodule $name ") }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"
}
