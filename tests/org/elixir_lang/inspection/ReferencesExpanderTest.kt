package org.elixir_lang.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.legLevel
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.psi.QualifiableAlias

/**
 * With Run the expander on, the Elixir references inspection reports a call only where the expander is certain nothing
 * defines what it calls, and then as the compiler does; anywhere the expander can't be sure, it reports nothing.
 */
class ReferencesExpanderTest : LegSdkFixtureTestCase() {
    override fun setUpSdk(sdk: Sdk) {
        enableExpander(myFixture.testRootDisposable)
        myFixture.enableInspections(References::class.java)
        myFixture.addFileToProject("lib/known.ex", "defmodule Known do\n  def f(a), do: a\nend\n")
    }

    fun testAnUndefinedRemoteCallIsReported() =
        assertEquals(
            listOf("Known.missing(1): $UNRESOLVED"),
            reports("defmodule Caller do\n  def run, do: Known.missing(1)\nend"),
        )

    fun testAWrongArityNamesTheAritiesItsNameIsDefinedAt() =
        assertEquals(
            listOf("Known.f(): $INVALID; defined as f/1"),
            reports("defmodule Caller do\n  def run, do: Known.f()\nend"),
        )

    /** Before 1.15 an undefined local call raises, which ends its module where the expander can't say what it defines. */
    fun testAnUndefinedLocalCallIsReportedFromElixir115() =
        assertEquals(
            if (FUNCTION_ERRORS_CONTINUE.isSufficient(legLevel())) listOf("missing(1): $UNRESOLVED") else emptyList(),
            reports("defmodule Caller do\n  def run, do: missing(1)\nend"),
        )

    /** `import Foo, only: [Foo, g: 1]` raises, as `only:` takes a keyword list, so the expansion stops there. */
    fun testACallPastAStopIsNotReported() {
        myFixture.addFileToProject("lib/foo.ex", "defmodule Foo do\n  def g(x), do: x\nend\n")

        assertEquals(
            emptyList<String>(),
            reports("defmodule Stopped do\n  import Foo, only: [Foo, g: 1]\n\n  def run, do: nowhere(1)\nend"),
        )
    }

    fun testAFunctionAnUnquotedNameDefinesIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reports("defmodule Unquoted do\n  for n <- [:a, :b], do: def unquote(n)(), do: 1\n\n  def run, do: a()\nend"),
        )

    /** `lib/` is compiled as `:dev`, but a module may define a function only under the env `test/` runs in. */
    fun testAFunctionDefinedUnderAnotherMixEnvIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reportsFromTest("defmodule InLib do\n  if Mix.env() == :test, do: def(test_only, do: 1)\nend\n", "InLib.test_only()"),
        )

    /** A module body reads what the code before its `defmodule` bound, which may hold an env the expander assumed. */
    fun testAFunctionDefinedUnderAnEnvReadBeforeItsModuleIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reportsFromTest(
                "env = Mix.env()\n\ndefmodule InLib do\n  if env == :test, do: def(test_only, do: 1)\nend\n",
                "InLib.test_only()",
            ),
        )

    /** A nested module's body reads what its outer module's body bound before it. */
    fun testAFunctionDefinedUnderAnEnvItsOuterModuleReadIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reportsFromTest(
                "defmodule Outer do\n  env = Mix.env()\n\n  defmodule InLib do\n    if env == :test, do: def(test_only, do: 1)\n  end\nend\n",
                "Outer.InLib.test_only()",
            ),
        )

    /** Which of two `defmodule`s runs may turn on the env. */
    fun testAFunctionOnlyTheOtherEnvsModuleDefinesIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reportsFromTest(
                "if Mix.env() == :test do\n  defmodule InLib do\n    def test_only, do: 1\n  end\nelse\n  defmodule InLib do\n  end\nend\n",
                "InLib.test_only()",
            ),
        )

    /** A module body may call a module defined before it, whose definitions turned on the env. */
    fun testAFunctionDefinedOnWhatAnEarlierModuleDefinesUnderAnEnvIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reportsFromTest(
                "defmodule Env do\n  if Mix.env() == :test, do: def(test?, do: true), else: def(test?, do: false)\nend\n\n" +
                    "defmodule InLib do\n  if Env.test?(), do: def(test_only, do: 1)\nend\n",
                "InLib.test_only()",
            ),
        )

    /** `lib/` is compiled as `:dev`, but a file may define a module only under the env `test/` runs in. */
    fun testAModuleDefinedUnderAnotherMixEnvIsNotReported() =
        assertEquals(emptyList<String>(), reportsFromTest(TEST_ONLY, "TestOnly.f()"))

    /** The file's expansion assumed the env, so what it leaves undefined isn't known from `lib/` either. */
    fun testAModuleDefinedUnderAnAssumedMixEnvIsNotReportedFromLib() {
        myFixture.addFileToProject("lib/in_lib.ex", TEST_ONLY)

        assertEquals(emptyList<String>(), reports("defmodule Caller do\n  def run, do: TestOnly.f()\nend"))
    }

    /** A file that assumed nothing defines the same in every env, so a module it leaves undefined is missing from `test/`. */
    fun testAModuleAFileThatAssumedNothingLeavesUndefinedIsReportedFromTest() =
        assertEquals(
            listOf("InLib.NeverDefined.f(): $UNRESOLVED"),
            reportsFromTest("defmodule InLib do\nend\n", "InLib.NeverDefined.f()"),
        )

    fun testWhatTheCompilersAddIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reports("defmodule Caller do\n  def all, do: Known.module_info()\n  def functions, do: Known.__info__(:functions)\nend"),
        )

    /** A call a `quote` returns runs in the module the code is injected into, which may define it. */
    fun testAQuotedCallIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reports(
                """
                defmodule Using do
                  defmacro __using__(_) do
                    quote do
                      def k(x), do: x
                      def f(y), do: k(y)
                      def g(y), do: __MODULE__.k(y)
                    end
                  end
                end
                """
            ),
        )

    /** A `@spec` a `quote` returns specifies a function of the module the code is injected into, or one it defines. */
    fun testAQuotedSpecificationIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reports(
                """
                defmodule Using do
                  defmacro __using__(_) do
                    quote do
                      @spec k(term) :: term
                      def k(x), do: x
                    end
                  end

                  defmacro generate do
                    quote do
                      defmodule Generated do
                        @spec f(integer) :: integer
                        def f(x), do: x
                      end
                    end
                  end
                end
                """
            ),
        )

    /** A private function is no function a remote call reaches, so the compiler names the public ones near it instead. */
    fun testAPrivateFunctionCalledRemotelyNamesTheNearPublicOnes() {
        myFixture.addFileToProject("lib/p.ex", "defmodule P do\n  def prim(a), do: a\n  defp priv(a, b), do: {a, b}\nend\n")

        assertEquals(
            listOf("P.priv(1, 2): $INVALID. Did you mean: prim/1?"),
            reports("defmodule Caller do\n  def run, do: P.priv(1, 2)\nend"),
        )
    }

    /** A `@callback` is no function, so the compiler names the functions near it instead. */
    fun testANameOnlyACallbackDeclaresNamesTheNearFunctions() {
        myFixture.addFileToProject("lib/b.ex", "defmodule B do\n  @callback cb(term) :: term\n  def cbx(a), do: a\nend\n")

        assertEquals(
            listOf("B.cb(1): $UNRESOLVED. Did you mean: cbx/1?"),
            reports("defmodule Caller do\n  def run, do: B.cb(1)\nend"),
        )
    }

    /** `alias` is a special form at 1 and 2 arguments, though its declaration writes only its second. */
    fun testASpecialFormIsNamedAtEveryArityItTakes() =
        assertEquals(
            if (FUNCTION_ERRORS_CONTINUE.isSufficient(legLevel())) {
                listOf("alias(A, B, C): $INVALID; defined as alias/1, alias/2")
            } else {
                emptyList()
            },
            reports("defmodule U do\n  def run, do: alias(A, B, C)\nend"),
        )

    /** The inspection's reports on [call], made from `test/`, where `lib/in_lib.ex` holds [lib]. */
    private fun reportsFromTest(lib: String, call: String): List<String> {
        myFixture.addFileToProject("lib/in_lib.ex", lib)
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir("test"), true)

        return reports("defmodule Caller do\n  def run, do: $call\nend", "test/support/caller.ex")
    }

    /** The inspection's reports on calls in [text], written at [path], each as `<reported text>: <message>`. */
    private fun reports(text: String, path: String = CALLER): List<String> {
        caller(text, path)
        myFixture.configureFromTempProjectFile(path)

        return myFixture.doHighlighting()
            // An alias's report reads no call's answer.
            .filter { it.inspectionToolId == "ElixirReferences" && !isAlias(it) }
            .map { "${it.text}: ${it.description}" }
    }

    private fun isAlias(report: HighlightInfo): Boolean =
        PsiTreeUtil.findElementOfClassAtRange(myFixture.file, report.startOffset, report.endOffset, QualifiableAlias::class.java)
            ?.textRange == TextRange(report.startOffset, report.endOffset)

    private companion object {
        const val TEST_ONLY = "if Mix.env() == :test do\n  defmodule TestOnly do\n    def f, do: 1\n  end\nend\n"
        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
