package org.elixir_lang.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderSourceRows.withBeamLibrary
import org.elixir_lang.expander.LegSdkFixtureTestCase
import org.elixir_lang.expander.legBeam
import org.elixir_lang.expander.legLevel
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDEFINED_FUNCTION_HINT_READS_MODULE_EXPORTS
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

    fun testAnUndefinedLocalCallIsReported() =
        assertEquals(
            listOf("missing(1): $UNRESOLVED"),
            reports("defmodule Caller do\n  def run, do: missing(1)\nend"),
        )

    /**
     * Before 1.15 a bodiless head raises while the module's definitions are stored, which ends it where the expander
     * can't say what it defines, before Elixir checks its local calls.
     */
    fun testAnUndefinedLocalCallBesideABodilessHeadIsReportedFromElixir115() =
        assertEquals(
            if (FUNCTION_ERRORS_CONTINUE.isSufficient(legLevel())) listOf("missing(1): $UNRESOLVED") else emptyList(),
            reports("defmodule Caller do\n  def head(x)\n  def run, do: missing(1)\nend"),
        )

    /** A raise in the module body ends it before its definitions are stored. */
    fun testAnUndefinedLocalCallInAModuleWhoseBodyRaisesIsNotReported() =
        assertEquals(
            emptyList<String>(),
            reports("defmodule Caller do\n  def run, do: missing(1)\n  defoverridable nope: 0\nend"),
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

    fun testADeprecatedFunctionIsNotNamed() {
        myFixture.addFileToProject(
            "lib/mr.ex",
            "defmodule Mr do\n  @deprecated \"use new2\"\n  def new(), do: 1\n  def new2(), do: 2\nend\n",
        )

        assertEquals(
            listOf("Mr.neww(): $UNRESOLVED. Did you mean: new2/0?"),
            reports("defmodule Caller do\n  def run, do: Mr.neww()\nend"),
        )
    }

    fun testEachArityOfANearNameIsNamed() {
        myFixture.addFileToProject(
            "lib/mr2.ex",
            "defmodule Mr2 do\n  def get(m, k), do: {m, k}\n  def get(m, k, d), do: {m, k, d}\nend\n",
        )

        assertEquals(
            listOf("Mr2.gett(1, 2): $UNRESOLVED. Did you mean: get/2, get/3?"),
            reports("defmodule Caller do\n  def run, do: Mr2.gett(1, 2)\nend"),
        )
    }

    fun testWhatTheCompilerAddsToAnElixirModuleIsNotNamed() =
        assertEquals(
            listOf("String.module_inf(): $UNRESOLVED"),
            reports("defmodule Caller do\n  def run, do: String.module_inf()\nend"),
        )

    /** A configured project reaches OTP's `.beam`s through its Elixir SDK's Erlang code paths, which the leg's SDK lacks. */
    fun testAnErlangModulesModuleInfoIsNamed() {
        assertEquals(
            listOf(":lists.module_inf(): $UNRESOLVED. Did you mean: module_info/0, module_info/1?"),
            withBeamLibrary(project, module, myFixture.testRootDisposable, "stdlib", legBeam("lists")!!) {
                reports("defmodule Caller do\n  def run, do: :lists.module_inf()\nend")
            },
        )
    }

    /** Before 1.18 the compiler lists a module's exports by name then arity, macros among the functions. */
    fun testAMacroAndFunctionsOfOneNameAreOrderedAsTheReleaseOrdersThem() {
        myFixture.addFileToProject(
            "lib/m.ex",
            """
            defmodule M do
              def m(_), do: 1
              def m(_, _), do: 2
              def m(_, _, _), do: 3
              defmacro m(_, _, _, _), do: 4
              defmacro m(_, _, _, _, _), do: 5
              defmacro m(_, _, _, _, _, _), do: 6
            end
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "M.m(): $INVALID; defined as " +
                    if (UNDEFINED_FUNCTION_HINT_READS_MODULE_EXPORTS.isSufficient(legLevel())) {
                        "m/4, m/5, m/6, m/1, m/2"
                    } else {
                        "m/1, m/2, m/3, m/4, m/5"
                    },
            ),
            reports("defmodule Caller do\n  def run, do: M.m()\nend"),
        )
    }

    fun testInfoAndBehaviourInfoAreNamedBefore118() {
        myFixture.addFileToProject("lib/beh.ex", "defmodule Beh do\n  @callback cb(integer) :: term\n  def real(a), do: a\nend\n")
        val before118 = !UNDEFINED_FUNCTION_HINT_READS_MODULE_EXPORTS.isSufficient(legLevel())

        assertEquals(
            listOf(
                "Beh.__infoo__(1): $UNRESOLVED" + if (before118) ". Did you mean: __info__/1?" else "",
                "Beh.behaviour_infoo(1): $UNRESOLVED" + if (before118) ". Did you mean: behaviour_info/1?" else "",
            ),
            reports("defmodule Caller do\n  def info, do: Beh.__infoo__(1)\n  def behaviour, do: Beh.behaviour_infoo(1)\nend"),
        )
    }

    /** Before 1.14 the compiler wrote a name as `Code.Identifier.inspect_as_function/1` did. */
    fun testAnOperatorIsNamedAsTheReleaseWritesIt() {
        myFixture.addFileToProject(
            "lib/q.ex",
            "defmodule Q do\n  def unquote(:\"**\")(a, b), do: {a, b}\n  def unquote(:\"//\")(a, b), do: {a, b}\nend\n",
        )
        val (power, step) =
            when (legLevel().elixir.minor) {
                11 -> "\"**\"" to "\"//\""
                12 -> "\"**\"" to "//"
                13 -> "**" to "//"
                else -> "**" to "\"//\""
            }

        assertEquals(
            listOf(
                "Q.*(1, 2): $UNRESOLVED. Did you mean: $power/2?",
                "Q./(1, 2): $UNRESOLVED. Did you mean: $step/2?",
            ),
            reports("defmodule Caller do\n  def power, do: Q.*(1, 2)\n  def step, do: Q./(1, 2)\nend"),
        )
    }

    /** `alias` is a special form at 1 and 2 arguments, though its declaration writes only its second. */
    fun testASpecialFormIsNamedAtEveryArityItTakes() =
        assertEquals(
            listOf("alias(A, B, C): $INVALID; defined as alias/1, alias/2"),
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
