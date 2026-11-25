package org.elixir_lang.model.psi.type

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.elixir_lang.code_insight.enclosingCall
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderWarmUp
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.TypeQualifierShapes.ACTUAL
import org.elixir_lang.expander.TypeQualifierShapes.ALIASED
import org.elixir_lang.expander.TypeQualifierShapes.CURRENT_MODULE_NESTED
import org.elixir_lang.expander.TypeQualifierShapes.DUPLICATED
import org.elixir_lang.expander.TypeQualifierShapes.ERLANG_QUEUE
import org.elixir_lang.expander.TypeQualifierShapes.FALLBACK
import org.elixir_lang.expander.TypeQualifierShapes.MISSING
import org.elixir_lang.expander.TypeQualifierShapes.MISSING_UNDER_IF
import org.elixir_lang.expander.TypeQualifierShapes.NESTED_BEFORE_AND_AFTER
import org.elixir_lang.expander.TypeQualifierShapes.OF_DUPLICATED
import org.elixir_lang.expander.TypeQualifierShapes.PAST_A_STOP
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_TARGETS
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_USER
import org.elixir_lang.expander.TypeQualifierShapes.QUOTED_ALIAS_USING
import org.elixir_lang.expander.TypeQualifierShapes.STOPS
import org.elixir_lang.expander.TypeQualifierShapes.STRING_TYPES
import org.elixir_lang.expander.addOtpStdlib
import org.elixir_lang.expander.attachSdkSources
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.model.psi.config.caret
import org.elixir_lang.psi.call.qualification.Qualified
import kotlin.time.Duration.Companion.seconds
import org.elixir_lang.structure_view.element.Type as TypeElement

/**
 * With Run the expander on, Go to Declaration at a qualified type lands on the type in the module the expander finds
 * the qualifier names, where that module is held.
 */
class TypeGotoDeclarationExpanderTest : LegSdkFixtureTestCase() {
    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        Quoter.compile(":ok", 60.seconds)
        super.setUp()
    }

    override fun setUpSdk(sdk: Sdk) {
        enableExpander(myFixture.testRootDisposable)
    }

    /** Elixir's answer: `Inner` names no module before `defmodule Inner` aliases it. */
    fun testANestedModuleNamedBeforeItIsDefinedHasNoTarget() {
        caller(NESTED_BEFORE_AND_AFTER)

        assertEquals("none", typeTargets("@type before :: Inner.<caret>t()"))
    }

    /** The only copy of the module is under an `if` the expansion assumed. */
    fun testAModuleDefinedUnderAnAssumedConditionHasNoTarget() {
        caller(MISSING_UNDER_IF, "lib/other.ex")
        caller(MISSING)

        assertEquals("none", typeTargets("@type t :: Missing.<caret>t()"))
    }

    /** What the `alias` in the same `quote` names isn't known until the `quote` is used. */
    fun testAModuleAnAliasInTheSameQuoteNamesHasNoTarget() {
        caller(QUOTED_ALIAS_TARGETS, "lib/targets12.ex")
        caller(QUOTED_ALIAS_USER, "lib/s12.ex")
        caller(QUOTED_ALIAS_USING)

        assertEquals("none", typeTargets("@spec qb() :: B.<caret>t()"))
    }

    /** The first ask finds the other file's expansion not held and starts its warm-up; the ask after it finds the type. */
    fun testAModuleInAnotherFileNotYetExpandedHasNoTargetUntilItsWarmUpHasRun() {
        caller(NOT_YET_EXPANDED, "lib/other15.ex")
        caller(OF_NOT_YET_EXPANDED)

        val first = withWarmUpsInTheBackground { firstAsk("@type t :: Other.<caret>t()") }
        val after = ExpanderWarmUp.getInstance(project).asynchronous { typeTargets("@type t :: Other.<caret>t()") }

        assertEquals("first: none\nafter: other15.ex: @type t :: integer()", "first: $first\nafter: $after")
    }

    /**
     * Past a stop, the env is the one at the stop, which misses the `alias` after it: `Fallback` names `Fallback`,
     * which has no `t`, not `Actual`.
     */
    fun testPastAStopATypeOfAModuleAnAliasAfterTheStopRenamesHasNoTarget() {
        caller(STOPS, "lib/m.ex")
        caller(ACTUAL, "lib/actual.ex")
        caller(FALLBACK, "lib/fallback.ex")
        caller(PAST_A_STOP)

        assertEquals("none", typeTargets("@type t :: Fallback.<caret>t()"))
    }

    fun testAnAliasedModulesTypeIsTheTarget() {
        caller(ALIASED)

        assertEquals("caller.ex: @type t :: integer()", typeTargets("@type t :: Bar.<caret>t()"))
    }

    fun testANestedModuleNamedAfterItIsDefinedHasItsTypeAsTheTarget() {
        caller(NESTED_BEFORE_AND_AFTER)

        assertEquals("caller.ex: @type t :: integer()", typeTargets("@type later :: Inner.<caret>t()"))
    }

    fun testAModuleUnderTheCurrentOneHasItsTypeAsTheTarget() {
        caller(CURRENT_MODULE_NESTED)

        assertEquals("caller.ex: @type t :: integer()", typeTargets("@type sub :: __MODULE__.Sub.<caret>t()"))
    }

    fun testACompiledModuleWithItsSourceAttachedHasTheSourcesTypeAsTheTarget() {
        attachSdkSources(module)
        caller(STRING_TYPES)

        assertEquals("string.ex: @type t :: binary", typeTargets("@type t :: String.<caret>t()"))
    }

    fun testAnErlangModuleWithNoSourceHasItsDecompiledTypeAsTheTarget() {
        addOtpStdlib(module, myFixture.testRootDisposable)
        caller(ERLANG_QUEUE)

        assertMatches(
            "queue\\.beam\\.decompiled\\.ex: @type queue :: queue\\(.*",
            typeTargets("@type t :: :queue.<caret>queue()"),
        )
    }

    /** Both copies declare the same type, so it is one target. */
    fun testAModuleInTwoSourcesHasOneTarget() {
        caller(DUPLICATED, "lib/dup_a.ex")
        caller(DUPLICATED, "lib/dup_b.ex")
        caller(OF_DUPLICATED)

        assertMatches("dup_[ab]\\.ex: @type t :: integer\\(\\)", typeTargets("@type t :: Dup13.<caret>t()"))
    }

    /** [actual] is one line [pattern] matches whole. */
    private fun assertMatches(pattern: String, actual: String) = assertTrue(actual, Regex(pattern).matches(actual))

    /** [marked]'s targets, each as [typeOf] renders it. */
    private fun typeTargets(marked: String): String = destinations(marked, ::typeOf)

    /**
     * What one ask of the type at [marked] resolves to, `none` for nothing. Go to Declaration asks more than once, and an
     * ask after the first may find the warm-up the first started has run.
     */
    private fun firstAsk(marked: String): String {
        myFixture.caret(CALLER, marked)
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!

        return onPooledThread {
            read {
                val call = leaf.enclosingCall { it is Qualified }!!
                TypeReference(call, TextRange.EMPTY_RANGE).resolveReference().joinToString("\n").ifEmpty { "none" }
            }
        }
    }

    /** [destination]'s file and the first line of the type that declares it. */
    private fun typeOf(destination: PsiElement): String {
        val type = destination.enclosingCall { TypeElement.`is`(it) }

        return "${destination.containingFile.name}: ${(type ?: destination).text.lineSequence().first()}"
    }

    private companion object {
        const val NOT_YET_EXPANDED = """defmodule Other do
  @type t :: integer()
end
"""

        const val OF_NOT_YET_EXPANDED = """defmodule Asker do
  @type t :: Other.t()
end
"""
    }
}
