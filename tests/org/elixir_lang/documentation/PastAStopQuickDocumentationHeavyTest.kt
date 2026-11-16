package org.elixir_lang.documentation

import com.intellij.openapi.util.text.StringUtil
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.model.psi.config.caret
import kotlin.time.Duration.Companion.seconds

/** Past a stop in a module body, the expander answers from the name index. */
class PastAStopQuickDocumentationHeavyTest : LegSdkFixtureTestCase() {
    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        Quoter.compile(":ok", 60.seconds)
        super.setUp()
    }

    /** Past a stop, a later clause's head is documented as its first is: the module's definitions are one per head. */
    fun testPastAStopALaterClausesHeadIsDocumented() {
        pastAStop("@doc \"Ones.\"\n  def one(1), do: 1\n  def one(2), do: 2")
        val documentation = documentation("def o<caret>ne(2)")

        assertTrue("Expected the function's @doc, got: $documentation", "Ones." in documentation)
    }

    /** A function with an arity written before a stop and another after it lists both at either head. */
    fun testPastAStopAnArityWrittenBeforeItListsTheArityWrittenAfter() {
        pastAStop(
            "def one(a, b), do: {a, b}",
            before = "@doc \"One.\"\n  @spec one(term) :: term\n  def one(a), do: a\n  @doc \"Two.\"",
        )
        val documentation = documentation("def o<caret>ne(a)")

        assertTrue("Expected both arities' @doc, got: $documentation", "One." in documentation && "Two." in documentation)
        assertOnce(documentation, "@spec one(")
    }

    /** A function whose clauses a stop splits is documented at its later clause's head as at its first. */
    fun testPastAStopAClauseWrittenAfterTheFunctionsFirstIsDocumented() {
        pastAStop("def one(2), do: 2", before = "@doc \"Ones.\"\n  def one(1), do: 1")
        val documentation = documentation("def o<caret>ne(2)")

        assertTrue("Expected the function's @doc, got: $documentation", "Ones." in documentation)
        assertTrue("Expected both clauses, got: $documentation", "def one(1)" in documentation && "def one(2)" in documentation)
    }

    /** As above, for private clauses: a link from the module that defines them reads its private ones too. */
    fun testPastAStopPrivateClausesWrittenOnBothSidesOfItAreListedAtEitherHead() {
        pastAStop("defp one(a, b), do: {a, b}", before = "defp one(a), do: a")

        for (marked in listOf("defp o<caret>ne(a)", "defp o<caret>ne(a, b)")) {
            val documentation = documentation(marked)

            assertTrue(
                "Expected both clauses at $marked, got: $documentation",
                "defp one(a)" in documentation && "defp one(a, b)" in documentation,
            )
        }
    }

    /** A clause before a stop, in a branch the module body doesn't take, is not one the module defines. */
    fun testPastAStopAClauseInABranchNotTakenBeforeItIsNotListed() {
        pastAStop(
            "def two(a), do: a",
            before = "if Code.ensure_loaded?(Kernel) do\n    @doc \"Loaded.\"\n    def one(a), do: a\n  else\n" +
                "    @doc \"Fallback.\"\n    def one(b), do: b\n  end",
        )
        val documentation = documentation("def o<caret>ne(a)")

        assertTrue("Expected the branch taken, got: $documentation", "Loaded." in documentation)
        assertFalse("Expected no branch not taken, got: $documentation", "Fallback." in documentation || "def one(b)" in documentation)
    }

    /** A clause before a stop, under `if false`, is not one the module defines. */
    fun testPastAStopAClauseUnderIfFalseBeforeItIsNotListed() {
        pastAStop(
            "def two(a), do: a",
            before = "@doc \"One.\"\n  def one(a), do: a\n  if false do\n    def one(a, b), do: {a, b}\n  end",
        )
        val documentation = documentation("def o<caret>ne(a)")

        assertTrue("Expected the clause defined, got: $documentation", "One." in documentation)
        assertFalse("Expected no clause under if false, got: $documentation", "def one(a, b)" in documentation)
    }

    /** A clause whose own expansion stops, at an `unquote` in its head, is one the module defines. */
    fun testPastAStopAClauseWhoseHeadStopsIsListedAtEitherHead() {
        pastAStop("def two(a), do: a", before = "@doc \"One.\"\n  def one(a), do: a\n  def one(unquote(M.m())), do: :ok")

        for (marked in listOf("def o<caret>ne(a)", "def o<caret>ne(unquote")) {
            val documentation = documentation(marked)

            assertOnce(documentation, "def one(a)")
            assertOnce(documentation, "def one(unquote(M.m()))")
        }
    }

    /** A clause the module defines, whose body stops in a `try` its `rescue` makes, is listed once. */
    fun testPastAStopAClauseWhoseRescuedBodyStopsIsListedOnce() {
        assertListedOnce("rescue\n    _ -> a")
    }

    /** As above, for a body with an `after`. */
    fun testPastAStopAClauseWhoseBodyWithAnAfterStopsIsListedOnce() {
        assertListedOnce("after\n    a")
    }

    /** Past a stop, `one/1`, whose body stops and has [option] after its `do`, is listed with its `@spec` once. */
    private fun assertListedOnce(option: String) {
        pastAStop("def two(a), do: a", before = "@doc \"One.\"\n  @spec one(term) :: term\n  def one(a) do\n    M.m()\n  $option\n  end")
        val documentation = documentation("def o<caret>ne(a)")

        assertOnce(documentation, "@spec one(")
        assertOnce(documentation, "def one(a)")
    }

    /**
     * [definitions] in a module whose body stops before them, after [before], at a macro that expands to what the expander
     * can't follow.
     */
    private fun pastAStop(definitions: String, before: String? = null) {
        myFixture.addFileToProject("lib/m.ex", "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        val written = before?.let { "  $it\n" }.orEmpty()

        caller("defmodule Caller do\n  require M\n$written  M.m()\n  $definitions\nend")
    }

    private fun assertOnce(documentation: String, text: String) {
        assertEquals(documentation, 1, StringUtil.getOccurrenceCount(documentation, text))
    }

    /** Quick Documentation at [marked] in the caller, with the expander answering, as text. */
    private fun documentation(marked: String): String {
        myFixture.caret(CALLER, marked)

        return withExpanderSwitch {
            StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(myFixture.quickDocumentationAtCaret(project).orEmpty()))
        }
    }
}
