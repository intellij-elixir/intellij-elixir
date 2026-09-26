package org.elixir_lang.psi.scope

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.Name
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putAncestorUnquote
import org.elixir_lang.psi.putInitialVisitedElement

/**
 * #4123's own issue text: resolving one call in a large module should cost work independent of the
 * module's size - a `CallableTable.declaring(name)` lookup, not a walk of every entry the module declares.
 * [CallDefinitionClause]'s modular branch only takes that shortcut for a caller that knows its one target
 * name ahead of time ([CallDefinitionClause.targetName]) - [MultiResolve] does;
 * [org.elixir_lang.psi.scope.call_definition_clause.Variants] (completion) has no single target and still
 * needs every entry, so it is untested here.
 *
 * Compares the visit count at two module sizes rather than asserting one fixed number: `caller`'s own
 * clause is legitimately visited too (its `Call` is an ancestor of the entrance, so the tree walk reaches
 * it directly, not through the table), so the true count is a small constant, not 1 - the number of
 * *unrelated* clauses (which the table walk must not touch) is what has to stay flat.
 */
class CallDefinitionClauseModuleSizeIndependenceTest : PlatformTestCase() {
    fun testResolvingOneNameVisitsTheSameEntryCountRegardlessOfModuleSize() {
        val smallCount = visitCountResolvingTarget(unrelatedDefCount = 10)
        val largeCount = visitCountResolvingTarget(unrelatedDefCount = 1000)

        assertEquals(
            "resolving `target/0` visited $smallCount clauses with 10 unrelated defs in the module but " +
                "$largeCount with 1000 - the modular branch must look `target` up in CallableTable " +
                "(O(1)), not walk every entry in the module (the O(n) cost #4123 removes)",
            smallCount,
            largeCount
        )
    }

    /**
     * `target` is defined after `caller` calls it - a forward reference only reachable through the modular
     * branch's table walk, not through the direct-preceding-sibling declaration path a defined-before-use
     * clause would also be offered through (a second, legitimate visit unrelated to what this measures).
     */
    private fun visitCountResolvingTarget(unrelatedDefCount: Int): Int {
        val unrelatedDefs = (0 until unrelatedDefCount).joinToString("\n") { "  def unrelated_$it, do: :ok" }

        myFixture.configureByText(
            "large_$unrelatedDefCount.ex",
            """
            defmodule Large$unrelatedDefCount do
              def caller, do: target()
            $unrelatedDefs

              def target, do: :ok
            end
            """.trimIndent()
        )

        val entrance = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.functionName() == "target" && it.text == "target()" }

        val collector = ClauseVisitCounter("target")
        val walkScope = maxScope(entrance)
        val state = ResolveState.initial()
            .put(ENTRANCE, entrance)
            .putInitialVisitedElement(entrance)
            .putAncestorUnquote(entrance)

        PsiTreeUtil.treeWalkUp(collector, entrance, walkScope, state)

        return collector.clauseVisitCount
    }

    /** Counts every clause the modular branch's `executeOnCallDefinitionClause` is called on - the rest of
     *  the abstract surface is a no-op, since this test only cares how many clauses get visited. */
    private class ClauseVisitCounter(private val name: Name) : CallDefinitionClause() {
        var clauseVisitCount = 0
            private set

        override fun targetName(): Name? = name

        override fun executeOnCallDefinitionClause(element: Call, state: ResolveState): Boolean {
            clauseVisitCount++
            return true
        }

        override fun execute(element: BeamCallDefinition, state: ResolveState): Boolean = true
        override fun executeOnCallback(element: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean = true
        override fun executeOnDelegation(element: Call, state: ResolveState): Boolean = true
        override fun executeOnException(element: Call, state: ResolveState): Boolean = true
        override fun executeOnEExFunctionFrom(element: Call, state: ResolveState): Boolean = true
        override fun executeOnMixGeneratorEmbed(element: Call, state: ResolveState): Boolean = true
        override fun keepProcessing(): Boolean = true
    }
}
