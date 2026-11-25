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

    /** The file's own run created the module, so what its callback defined is offered there. */
    fun testANearNameOfAModuleABeforeCompileCallbackCreatesInTheCallersFileIsNamed() =
        assertEquals(
            listOf("Created.FromBeforeCompile.Helpers.helpr(): $UNRESOLVED. Did you mean: helper/0?"),
            reports(
                """
                defmodule Created.Definer do
                  defmacro __using__(_) do
                    quote do
                      @before_compile Created.Definer
                      Module.register_attribute(__MODULE__, :names, accumulate: true)
                    end
                  end

                  defmacro __before_compile__(env) do
                    names = Module.get_attribute(env.module, :names)
                    defs = for name <- names, do: quote(do: def(unquote(name)(), do: unquote(name)))
                    Module.create(Module.concat(env.module, Helpers), quote(do: (unquote_splicing(defs))), line: env.line, file: env.file)
                    :ok
                  end
                end

                defmodule Created.FromBeforeCompile do
                  use Created.Definer
                  @names :helper

                  def run, do: Created.FromBeforeCompile.Helpers.helpr()
                end
                """,
            ),
        )

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

    // An alias is asked for its whole module name, from the expander's one answer for it.

    fun testAMissingModuleUnderAKnownNamespaceIsReported() =
        assertEquals(
            listOf("Known.Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  def run, do: Known.Missing.f()\nend"),
        )

    fun testAMissingModuleUnderAnAliasedNamespaceIsReported() =
        assertEquals(
            listOf("Known.Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias Known\n\n  def run, do: Known.Missing.f()\nend"),
        )

    fun testAMissingModuleUnderANamespaceAliasedAsIsReported() =
        assertEquals(
            listOf("K.Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias Known, as: K\n\n  def run, do: K.Missing.f()\nend"),
        )

    /** Pins: the expander reads the whole name from the env, so `Refd` is `NS.Referenced` over the alias before it. */
    fun testAModuleUnderANamespaceAliasedAsOverAnAliasIsNotReported() {
        myFixture.addFileToProject(
            "lib/referenced.ex",
            "defmodule NS.Referenced do\nend\n\ndefmodule NS.Referenced.Sub do\n  def f, do: 1\nend\n"
        )

        assertEmpty(
            aliasReports("defmodule Caller do\n  alias NS.Referenced\n  alias Referenced, as: Refd\n\n  def run, do: Refd.Sub.f()\nend")
        )
    }

    fun testAMissingModuleUnderANamespaceAliasedAsOverAnAliasIsReported() {
        myFixture.addFileToProject("lib/referenced.ex", "defmodule NS.Referenced do\nend\n")

        assertEquals(
            listOf("Refd.Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias NS.Referenced\n  alias Referenced, as: Refd\n\n  def run, do: Refd.Missing.f()\nend")
        )
    }

    /** An alias a `quote` writes is in the env of the code after it in that `quote`, not of the code around the `quote`. */
    fun testAnAliasAQuoteWritesIsReadInTheQuote() {
        myFixture.addRepo()

        assertEmpty(aliasReports(inQuote("alias MyApp.Repo\nRepo")))
    }

    fun testACallOnAnAliasAQuoteWritesIsReadInTheQuote() {
        myFixture.addRepo()

        assertEmpty(reports(inQuote("alias MyApp.Repo\nRepo.insert!(1)")))
    }

    /** No env aliases `Missing`, and no `alias` in the `quote` names it, so it is the module `Missing`, which is absent. */
    fun testACallOnAnAliasNothingAliasesInAQuoteIsReported() {
        assertEquals(
            listOf("Missing.f(): $UNRESOLVED"),
            reports(inQuote("Missing.f()"))
        )
    }

    fun testAnAliasNothingAliasesInAQuoteIsReported() {
        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("Missing"))
        )
    }

    /** The `as:` name an `alias` in the `quote` gives is read in each injection, as the name it makes is. */
    fun testAnAliasAQuoteGivesWithAsIsReadInTheQuote() {
        myFixture.addRepo()

        assertEmpty(aliasReports(inQuote("alias MyApp.Repo, as: R\nR")))
    }

    /** A `require` with `as:` gives a name as an `alias` does, so `R` is read in each injection. */
    fun testAnAliasAQuoteGivesWithRequireAsIsReadInTheQuote() {
        myFixture.addRepo()

        assertEmpty(aliasReports(inQuote("require MyApp.Repo, as: R\nR")))
    }

    /** What an `alias` in the `quote` names through an `unquote` can't be read, so any alias after it may be its name. */
    fun testAnAliasAfterAnUnreadableAliasInAQuoteIsNotReported() {
        assertEmpty(aliasReports(inQuote("alias unquote(module)\nRepo", parameters = "(module)")))
    }

    /** An `alias` is read where its scope reaches: not in the next function of the `quote`. */
    fun testAnAliasInAnotherFunctionOfAQuoteIsNotRead() {
        myFixture.addRepo()

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("def first do\n  alias MyApp.Repo, as: Missing\n  :ok\nend\n\ndef second, do: Missing.f()"))
        )
    }

    /** An `alias` is read where its scope reaches: not after the `fn` it is in. */
    fun testAnAliasInAnAnonymousFunctionOfAQuoteIsNotReadAfterIt() {
        myFixture.addRepo()

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("f = fn -> alias MyApp.Repo, as: Missing end\nMissing.f()"))
        )
    }

    /** Pins: an `alias` in the scope around the name is read inside a function of it. */
    fun testAnAliasAnEnclosingScopeGivesIsReadInTheQuote() {
        myFixture.addRepo()

        assertEmpty(aliasReports(inQuote("alias MyApp.Repo, as: R\nf = fn -> R.f() end")))
    }

    /** A `defmodule` in the `quote` gives the name its first segment makes, as an `alias` does: `Child` is `Outer.Child`. */
    fun testAModuleAQuoteDefinesIsReadInTheQuote() {
        assertEmpty(aliasReports(inQuote("defmodule Child do\n  def f, do: 1\nend\nChild")))
    }

    fun testACallOnAModuleAQuoteDefinesIsNotReported() {
        assertEmpty(reports(inQuote("defmodule Child do\n  def f, do: 1\nend\nChild.f()")))
    }

    fun testAModuleAQuoteDefinesDoesNotGiveAnotherNameInTheQuote() {
        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("defmodule Child do\nend\nMissing"))
        )
    }

    /** A `defprotocol` in the `quote` gives the name its first segment makes, as a `defmodule` does. */
    fun testAProtocolAQuoteDefinesIsReadInTheQuote() {
        assertEmpty(aliasReports(inQuote("defprotocol Child do\n  def f(x)\nend\nChild")))
    }

    /** A `defimpl` defines a module whose name is computed, so it gives no name: `Missing` is still reported. */
    fun testAnImplementationAQuoteDefinesGivesNoName() {
        myFixture.addFileToProject("lib/proto.ex", "defprotocol Proto do\n  def f(x)\nend\n")

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("defimpl Proto, for: BitString do\n  def f(x), do: x\nend\nMissing"))
        )
    }

    /** `alias Foo.{A, B}` in the `quote` gives `A` and `B`, and only those: `Missing` is still reported. */
    fun testAnAliasOfMembersInAQuoteGivesTheMembersNames() {
        myFixture.addFileToProject("lib/foo.ex", "defmodule Foo.A do\nend\n\ndefmodule Foo.B do\nend\n")

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("alias Foo.{A, B}\nA\nB\nMissing"))
        )
    }

    /** A qualified member gives its last segment: `alias Foo.{A.C}` gives `C`. */
    fun testAnAliasOfAQualifiedMemberInAQuoteGivesItsLastSegment() {
        myFixture.addFileToProject("lib/foo.ex", "defmodule Foo.A.C do\nend\n")

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("alias Foo.{A.C}\nC\nMissing"))
        )
    }

    /** An `Elixir.`-rooted name is the module it says, whatever an `alias` that can't be read gave a name. */
    fun testAnElixirRootedAliasAfterAnUnreadableAliasInAQuoteIsReported() {
        assertEquals(
            listOf("Elixir.Missing: $UNRESOLVED"),
            aliasReports(inQuote("alias unquote(module)\nElixir.Missing.f()", parameters = "(module)"))
        )
    }

    /** Each member of `alias Foo.{Foo, Bar}` is `Foo` and then the member: the base is read before the first member's alias. */
    fun testAMemberAfterAMemberThatAliasesTheBaseIsNotReported() {
        myFixture.addFileToProject(
            "lib/foo.ex",
            "defmodule Foo.Foo do\nend\n\ndefmodule Foo.Bar do\nend\n"
        )

        assertEmpty(aliasReports("defmodule Caller do\n  alias Foo.{Foo, Bar}\nend"))
    }

    /** Pins: the module a directive names is read in the env the directive is entered in, before its `as:` name. */
    fun testTheModuleAnAliasAsNamesIsReadBeforeTheName() {
        myFixture.addFileToProject("lib/foo_bar.ex", "defmodule Foo.Bar do\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  alias Foo.Bar, as: Foo\nend"))
    }

    /** Pins: a `require` names its module in the env it is entered in, as an `alias` does. */
    fun testTheModuleARequireAsNamesIsReadBeforeTheName() {
        myFixture.addFileToProject("lib/foo_bar.ex", "defmodule Foo.Bar do\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  require Foo.Bar, as: Foo\nend"))
    }

    /** Elixir needs no module `Foo` for `Foo.Bar.f()` to run. */
    fun testAModuleUnderNoNamespaceModuleIsNotReported() {
        myFixture.addFileToProject("lib/foo_bar.ex", "defmodule Foo.Bar do\n  def f, do: 1\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  def run, do: Foo.Bar.f()\nend"))
    }

    fun testTheTargetOfAnAsAliasIsReportedAndItsNameIsNot() =
        assertEquals(
            listOf("Known.Gone: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias Known.Gone, as: G\nend"),
        )

    fun testAMissingModuleAliasIsReported() =
        assertEquals(listOf("Missing: $UNRESOLVED"), aliasReports("defmodule Caller do\n  alias Missing\nend"))

    fun testAMissingModuleStructIsReported() =
        assertEquals(listOf("Missing: $UNRESOLVED"), aliasReports("defmodule Caller do\n  def run, do: %Missing{}\nend"))

    /** What the type is is the expander's to say: it is recorded here as it stands, and never as held. */
    fun testAMissingModuleInATypeIsReported() =
        assertEquals(listOf("Missing: $UNRESOLVED"), aliasReports("defmodule Caller do\n  @type t :: Missing.t()\nend"))

    fun testTheBaseOfAMultiAliasOfAHeldModuleIsNotReported() {
        myFixture.addFileToProject("lib/nowhere_a.ex", "defmodule Nowhere.A do\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  alias Nowhere.{A}\nend"))
    }

    /** `Foo.BitString` and `Foo.Elixir` are what the members name, and `Foo` has neither. */
    fun testMembersOfAMultiAliasNamedBitStringAndElixirAreReportedWhenTheirModulesAreMissing() {
        myFixture.addFileToProject("lib/foo.ex", "defmodule Foo do\nend\n")

        assertEquals(
            listOf("BitString: $UNRESOLVED", "Elixir: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias Foo.{BitString, Elixir}\nend"),
        )
    }

    fun testMembersOfAMultiAliasNamedBitStringAndElixirAreNotReportedWhenTheirModulesAreHeld() {
        myFixture.addFileToProject("lib/foo_bit_string.ex", "defmodule Foo.BitString do\nend\n\ndefmodule Foo.Elixir do\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  alias Foo.{BitString, Elixir}\nend"))
    }

    /** `Foo` is `My.Foo` there, so the member names `My.Foo.Missing`, not `Foo.Missing`. */
    fun testAMemberOfAMultiAliasIsNamedUnderTheModuleItsQualifierAliases() {
        myFixture.addFileToProject("lib/my_foo.ex", "defmodule My.Foo do\nend\n")

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias My.Foo\n  alias Foo.{Missing}\nend"),
        )
    }

    fun testAMemberOfAMultiAliasWhoseModuleIsHeldUnderItsQualifiersAliasIsNotReported() {
        myFixture.addFileToProject("lib/my_foo.ex", "defmodule My.Foo do\nend\n\ndefmodule My.Foo.Held do\nend\n")

        assertEmpty(aliasReports("defmodule Caller do\n  alias My.Foo\n  alias Foo.{Held}\nend"))
    }

    /** A `quote`'s `__MODULE__` is its caller's, so the module `alias __MODULE__.{Sub}` names is not known. */
    fun testAMemberOfAMultiAliasUnderModuleInAQuoteIsNotReported() {
        assertEmpty(aliasReports(inQuote("alias __MODULE__.{Sub}", module = "L")))
    }

    fun testAQualifiedMemberOfAMultiAliasUnderModuleInAQuoteIsNotReported() {
        assertEmpty(
            aliasReports(inQuote("alias __MODULE__.{Sub.Deep}", module = "L"))
        )
    }

    /** `B.Missing` of `alias Foo.{B.Missing}` is `Foo.B.Missing`, which is absent whatever `Foo` and `Foo.B` hold. */
    fun testAQualifiedMemberOfAMultiAliasIsNamedUnderItsQualifier() {
        myFixture.addFileToProject("lib/foo.ex", "defmodule Foo do\nend\n\ndefmodule Foo.B do\nend\n")

        assertEquals(
            listOf("B.Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  alias Foo.{B.Missing}\nend"),
        )
    }

    fun testAnUndefinedModuleUnderTheCurrentModuleIsReported() =
        assertEquals(
            listOf("__MODULE__.Nope: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  def run, do: __MODULE__.Nope.f()\nend"),
        )

    /** The quote's code runs in the module it is injected into, so `__MODULE__` there may name a module that defines `Sub`. */
    fun testAModuleUnderTheCurrentModuleInADefmacrosQuoteIsNotReported() =
        assertEmpty(
            aliasReports(inQuote("def run, do: __MODULE__.Sub.helper(1)", module = "L")),
        )

    /** The expander reads what the `use` adds, so an undefined module after it is reported and the one it aliases is not. */
    fun testAnAliasAUseAddsIsNotReportedAndAnUndefinedModuleAfterItIs() {
        myFixture.addConnCase()

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(
                "defmodule MyApp.RepoTest do\n  use MyApp.ConnCase\n\n  def test do\n    Repo.insert!(%{})\n    Missing.f()\n  end\nend",
            ),
        )
    }

    /** A `use` in a `quote` is not expanded where the macro is, so the alias it adds where the macro is called may name the head. */
    fun testAnAliasAUseInAQuoteMayAddIsNotReported() {
        myFixture.addConnCase()

        assertEmpty(aliasReports(inQuote("use MyApp.ConnCase\nRepo.insert!(%{})")))
    }

    /** A macro of a module a `require` in the `quote` names may add an alias where the macro is called. */
    fun testAnAliasARequiredModulesMacroInAQuoteMayAddIsNotReported() =
        assertEmpty(aliasReports(inQuote("require Known\nKnown.m()\nRepo.insert!(%{})")))

    /** A remote call to a module no `require` names is a function call, which adds no alias. */
    fun testAnAliasAfterACallToAModuleNoRequireNamesInAQuoteIsReported() =
        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("Known.f(1)\nMissing.insert!(%{})")),
        )

    /** A call to a local macro after an `import` in the `quote` may be the imported macro, which may add an alias. */
    fun testAnAliasAfterALocalCallFollowingAnImportInAQuoteIsNotReported() =
        assertEmpty(aliasReports(inQuote("import Known\nf(1)\nRepo.insert!(%{})")))

    /** A call to a `Kernel` macro with no `import` in scope adds no alias. */
    fun testAnAliasAfterAKernelMacroInAQuoteIsReported() =
        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports(inQuote("if true, do: 1\nMissing.insert!(%{})")),
        )

    /** What an undefined module's `__using__` would have added is unknown, so so is what the alias after it names. */
    fun testAnAliasAfterAUseOfAnUndefinedModuleIsNotReported() =
        assertEmpty(aliasReports("defmodule Caller do\n  use Undefined.Case\n\n  def run, do: Repo.insert!(%{})\nend"))

    fun testACallThroughARequireAsNameIsNotReported() {
        val text = "defmodule Caller do\n  require Known, as: M\n\n  def run, do: M.f(1)\nend"

        assertEmpty(aliasReports(text))
        assertEmpty(reports(text))
    }

    fun testACallThroughARequireAsNameOfAModuleRelativeAliasIsNotReported() {
        myFixture.addFileToProject("lib/sub.ex", "defmodule Foo.Sub do\n  def f, do: 1\nend\n")
        val text = "defmodule Foo do\n  require __MODULE__.Sub, as: M\n\n  def run, do: M.f()\nend"

        assertEmpty(aliasReports(text))
        assertEmpty(reports(text))
    }

    /** `BitString` is the type of `<<...>>`, which no module declares. */
    fun testABitStringImplementationIsNotReported() =
        assertEmpty(
            aliasReports("defprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: BitString do\n  def f(x), do: x\nend"),
        )

    fun testAnElixirPrefixedBitStringImplementationIsNotReported() =
        assertEmpty(
            aliasReports("defprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: Elixir.BitString do\n  def f(x), do: x\nend"),
        )

    /** A module the expander is sure is missing is reported after an opaque call. */
    fun testAMissingModuleAfterAnOpaqueCallIsReported() {
        myFixture.addFileToProject("lib/opaque.ex", OPAQUE)

        assertEquals(
            listOf("Missing: $UNRESOLVED"),
            aliasReports("defmodule Caller do\n  require Opaque\n\n  def a, do: Opaque.m()\n  def run, do: Missing.f()\nend"),
        )
    }

    /** The inspection's reports on [call], made from `test/`, where `lib/in_lib.ex` holds [lib]. */
    private fun reportsFromTest(lib: String, call: String): List<String> {
        myFixture.addFileToProject("lib/in_lib.ex", lib)
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir("test"), true)

        return reports("defmodule Caller do\n  def run, do: $call\nend", "test/support/caller.ex")
    }

    /**
     * The inspection's reports on calls in [text], or on its aliases where [aliases], written at [path], each as
     * `<reported text>: <message>`.
     */
    private fun reports(text: String, path: String = CALLER, aliases: Boolean = false): List<String> {
        caller(text, path)
        myFixture.configureFromTempProjectFile(path)

        return myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" && isAlias(it) == aliases }
            .map { "${it.text}: ${it.description}" }
    }

    /** [reports] on the aliases in [text]. */
    private fun aliasReports(text: String): List<String> = reports(text, aliases = true)

    /** [body], one statement a line, in a `quote` that the macro `m` of [module], taking [parameters], returns. */
    private fun inQuote(body: String, module: String = "Caller", parameters: String = ""): String =
        "defmodule $module do\n  defmacro m$parameters do\n    quote do\n      ${body.replace("\n", "\n      ")}\n    end\n  end\nend"

    private fun isAlias(report: HighlightInfo): Boolean =
        PsiTreeUtil.findElementOfClassAtRange(myFixture.file, report.startOffset, report.endOffset, QualifiableAlias::class.java)
            ?.textRange == TextRange(report.startOffset, report.endOffset)

    private companion object {
        /** A macro whose output the expander can't tell, so a call of it is opaque. */
        const val OPAQUE = "defmodule Opaque do\n  defmacro m, do: Code.string_to_quoted!(System.get_env(\"OPAQUE\") || \"nil\")\nend\n"
        const val TEST_ONLY = "if Mix.env() == :test do\n  defmodule TestOnly do\n    def f, do: 1\n  end\nend\n"
        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
