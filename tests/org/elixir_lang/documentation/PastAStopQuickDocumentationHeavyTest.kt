package org.elixir_lang.documentation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.code_insight.parameterInfoSignaturesAtCaret
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.expander.ExpanderSourceRows.candidates
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.model.psi.config.caret
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import kotlin.time.Duration.Companion.seconds

/**
 * Past a stop in a module body, the expander answers from the name index. An `import` it didn't see may bring in any
 * module's function, so a local call finds each in the index, a compiled module's at its `.beam`. Quick Documentation
 * reads such a find at the heads the source its library or SDK attaches writes.
 */
class PastAStopQuickDocumentationHeavyTest : LegSdkFixtureTestCase() {
    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        Quoter.compile(":ok", 60.seconds)
        super.setUp()
    }

    override fun setUpSdk(sdk: Sdk) {
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                ElixirSdkPathConfigurator.addSourcePaths(this)
                commitChanges()
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testPastAStopAnSdkFunctionIsDocumentedAtTheHeadsItsSourceWrites() {
        val file = pastAStop("import String\n  def f(s), do: pad_leading(s, 3)")
        val documentation = documentation("pad_<caret>leading(s, 3)")

        // The candidates are read only on failure, as reading them resolves again.
        if ("def pad_leading(string, count, padding \\\\ [\" \"])" !in documentation) {
            fail("Expected string.ex's head, got: $documentation\nfound: ${withExpanderSwitch { candidates(file, "pad_leading(s, 3)") }}")
        }
    }

    /** A head with defaults, found at its source past a stop, is the one clause the use's find is: its `@spec` shows once. */
    fun testPastAStopADefaultsHeadsSpecIsListedOnce() {
        pastAStop("import String\n  def f(s), do: pad_leading(s, 3)")
        val documentation = documentation("pad_<caret>leading(s, 3)")

        assertOnce(documentation, "@spec pad_leading(")
    }

    /** A deprecated head with defaults, found at its library's source past a stop, keeps its deprecation. */
    fun testPastAStopADeprecatedDefaultsHeadKeepsItsDeprecation() {
        myFixture.addCompiledLibrary(
            "defmodule Compiled do\n  @doc \"Ds.\"\n  @deprecated \"Use e.\"\n  @spec located_d(integer, integer) :: integer\n  def located_d(a, b \\\\ 1), do: a + b\nend\n",
            CompiledBuild("LocatedDeprecated"),
            sources = true,
        )
        pastAStop("import LocatedDeprecated\n  def f(x), do: located_d(x)")
        val documentation = documentation("located_<caret>d(x)")

        assertTrue(documentation, "Use e." in documentation)
        assertOnce(documentation, "@spec located_d(")
    }

    /** Past a stop, a library `.beam`'s function of a module the project's own source defines is not found: Elixir loads the project's copy. */
    fun testPastAStopALibraryBeamsFunctionOfAModuleTheProjectDefinesIsNotDocumented() {
        myFixture.addCompiledLibrary(
            "defmodule Compiled do\n  @doc \"Older.\"\n  def only_older(a), do: a\nend\n",
            CompiledBuild("ProjectOwned"),
            sources = true,
        )
        myFixture.addFileToProject("lib/project_owned.ex", "defmodule ProjectOwned do\n  def other(a), do: a\nend\n")
        pastAStop("import ProjectOwned\n  def f(x), do: only_older(x)")
        val documentation = documentation("only_<caret>older(x)")

        assertFalse("Expected nothing of the library's `only_older/1`, got: $documentation", "Older." in documentation)
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

    /** A module named by an atom, not an alias, is found at its source as an alias is. */
    fun testPastAStopAnAtomNamedLibraryModuleIsDocumentedFromItsSource() {
        myFixture.addCompiledLibrary(
            "defmodule Compiled do\n  @doc \"Written.\"\n  def located_f(written), do: written\nend\n",
            CompiledBuild(":located_atom", debugInfo = false, docs = false),
            sources = true,
        )
        val file = pastAStop("import :located_atom\n  def f(x), do: located_f(x)")
        val documentation = documentation("located_<caret>f(x)")

        if ("Written." !in documentation || "def located_f(written)" !in documentation) {
            fail("Expected the source's docs, got: $documentation\nfound: ${withExpanderSwitch { candidates(file, "located_f(x)") }}")
        }
    }

    /** A remote call past a stop finds the module's definitions at its source already, and still documents them there. */
    fun testPastAStopARemoteCallIsDocumentedAtTheHeadsItsSourceWrites() {
        pastAStop("def f(s), do: String.split(s, \",\")")

        assertTrue("def split(string, pattern, options \\\\ [])" in documentation("String.sp<caret>lit(s, \",\")"))
    }

    /**
     * A head in a `quote` past a stop is its own module's, so documenting it locates none of the definitions of its name
     * other modules hold, which the name index finds for a call of it.
     */
    fun testPastAStopAQuotedHeadLocatesNoOtherModulesDefinitionsOfItsName() {
        pastAStop("defmacro __using__(_) do\n    quote do\n      def split(s), do: s\n    end\n  end")
        ExpanderSource.Probe.reset()
        val documentation = ExpanderSource.Probe.counting { documentation("def sp<caret>lit(s)") }

        assertTrue("Expected the head's title, got: $documentation", documentation.startsWith("split(s)\n  def split(s)"))
        assertEquals(
            "Expected no `.beam` find located, documenting: $documentation",
            0L,
            ExpanderSource.Probe.snapshot().getValue(ExpanderSource.Probe.Counter.LOCATED)
        )
    }

    /** Parameter Info reads no location: past a stop it shows what the `.beam` records, as it did. */
    fun testPastAStopParameterInfoShowsTheBeamsSignatures() {
        pastAStop("import String\n  def f(s), do: pad_leading(s, 3)")
        myFixture.caret(CALLER, "pad_leading(<caret>s, 3)")

        assertEquals(listOf("string, count, padding \\\\ [\" \"]"), withExpanderSwitch { myFixture.parameterInfoSignaturesAtCaret() })
    }

    /**
     * [definitions] in a module whose body stops before them, after [before], at a macro that expands to what the expander
     * can't follow.
     */
    private fun pastAStop(definitions: String, before: String? = null): PsiFile {
        myFixture.addFileToProject("lib/m.ex", "defmodule M do\n  defmacro m, do: (:erlang.send(:erlang.self(), :decline); receive(do: (:decline -> :ok; x -> x), after: (0 -> :ok)))\nend\n")
        val written = before?.let { "  $it\n" }.orEmpty()

        return caller("defmodule Caller do\n  require M\n$written  M.m()\n  $definitions\nend")
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
