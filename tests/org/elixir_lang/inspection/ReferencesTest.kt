package org.elixir_lang.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import org.elixir_lang.declaration.ExpanderSettingBeamLibraryTestCase
import org.elixir_lang.expander.ExpanderSourceRows
import java.io.File

/**
 * What the Elixir references inspection reports for a call, a capture and a `@spec` head: "Does not resolve to
 * anything" where nothing is a candidate, "Only resolves to invalid results" where candidates exist and none applies.
 */
class ReferencesTest : ExpanderSettingBeamLibraryTestCase() {
    override fun getTestDataPath(): String = EBIN.path

    override val ebinDirectory: File
        get() = EBIN

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
        // The fixture library holds `Kernel` only, so the special forms the fixtures use need declaring
        myFixture.addFileToProject(
            "special_forms.ex",
            """
            defmodule Kernel.SpecialForms do
              defmacro __MODULE__, do: nil
              defmacro alias(module, opts \\ []), do: nil
              defmacro import(module, opts \\ []), do: nil
              defmacro quote(opts \\ [], block), do: nil
              defmacro require(module, opts \\ []), do: nil
              defmacro unquote(:unquote)(expr), do: nil
            end
            """.trimIndent() + "\n"
        )
    }

    fun testAnUndeclaredLocalCallDoesNotResolve() {
        assertEquals(
            listOf("nope() => $UNRESOLVED"),
            reports("defmodule M do\n  def run, do: nope()\nend\n")
        )
    }

    fun testALocalCallAtAWrongArityOnlyResolvesToInvalidResults() {
        assertEquals(
            listOf("f() => $INVALID; defined as f/1"),
            reports("defmodule M do\n  def f(a), do: a\n\n  def run, do: f()\nend\n")
        )
    }

    fun testACorrectCallIsSilent() {
        assertEquals(emptyList<String>(), reports("defmodule M do\n  def f(a), do: a\n\n  def run, do: f(1)\nend\n"))
    }

    fun testACurrentModuleQualifierAtAnotherArityIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports("defmodule M do\n  def g(a, b), do: {a, b}\n\n  def run, do: __MODULE__.g(1)\nend\n")
        )
    }

    fun testAQualifiedUnquoteIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule Qualifier do\n  def f(a, b), do: {a, b}\nend\n\n" +
                    "defmodule U do\n  defmacro m(v) do\n    quote do\n      Qualifier.unquote(v)(1, 2, 3)\n    end\n  end\nend\n"
            )
        )
    }

    /** `Elixir.IcWeb.Router.Helpers` is `IcWeb.Router.Helpers`, whose router is defined, so the alias is not reported. */
    fun testAnElixirHeadedRouterHelpersAliasIsTreatedAsTheUnprefixedOne() {
        myFixture.addFileToProject("ic_web_router.ex", "defmodule IcWeb.Router do\nend\n")

        assertEquals(emptyList<String>(), walkReports(routerHelpersCaller("IcWeb.Router.Helpers")))
        assertEquals(emptyList<String>(), walkReports(routerHelpersCaller("Elixir.IcWeb.Router.Helpers")))
    }

    fun testACallOnAVariableDoesNotResolve() {
        assertEquals(
            listOf("v.f(1) => $UNRESOLVED"),
            reports("defmodule M do\n  def run(v), do: v.f(1)\nend\n")
        )
    }

    fun testACallWhoseOnlyCandidateIsACallbackOnlyResolvesToInvalidResults() {
        assertEquals(
            listOf("cb(1) => $INVALID"),
            reports("defmodule M do\n  @callback cb(term) :: term\n\n  def run, do: cb(1)\nend\n")
        )
    }

    fun testAnEexFunctionWithArgumentsIsSilent() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )

        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule M do\n  require EEx\n  @args [:a]\n  EEx.function_from_string(:def, :render, \"<%= a %>\", @args)\n\n" +
                    "  def run, do: render(1)\nend\n"
            )
        )
    }

    fun testACaptureAtAWrongArityOnlyResolvesToInvalidResults() {
        assertEquals(
            listOf("&f/2 => $INVALID; defined as f/1"),
            reports("defmodule M do\n  def f(a), do: a\n\n  def run, do: &f/2\nend\n")
        )
    }

    fun testACaptureAtItsArityIsSilent() {
        assertEquals(emptyList<String>(), reports("defmodule M do\n  def f(a), do: a\n\n  def run, do: &f/1\nend\n"))
    }

    fun testASpecHeadWithNoDefinitionDoesNotResolve() {
        assertEquals(
            listOf("f(integer) => $UNRESOLVED"),
            reports("defmodule M do\n  @spec f(integer) :: integer\n  def g(a), do: a\nend\n")
        )
    }

    fun testASpecHeadAtAnArityNothingDefinesOnlyResolvesToInvalidResults() {
        assertEquals(
            listOf("f(integer) => $INVALID; defined as f/2"),
            reports("defmodule M do\n  @spec f(integer) :: integer\n  def f(a, b), do: a + b\nend\n")
        )
    }

    fun testASpecHeadWithItsDefinitionIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports("defmodule M do\n  @spec f(integer) :: integer\n  def f(a), do: a\nend\n")
        )
    }

    fun testAnUnquotedModuleQualifierInAModuleBodyIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports("defmodule L do\n  def helper(x), do: x\n\n  def run, do: unquote(__MODULE__).helper(1)\nend\n")
        )
    }

    fun testAnUnquotedModuleQualifierInAMacrosQuoteIsSilent() {
        assertEquals(emptyList<String>(), reports(macroQuoting("def run, do: unquote(__MODULE__).helper(1)")))
    }

    fun testAnUnquotedModuleQualifierInAUsingQuoteIsSilent() {
        assertEquals(emptyList<String>(), reports(usingQuoting("def run, do: unquote(__MODULE__).helper(1)")))
    }

    fun testAnUnquotedModuleQualifierInAModuleAQuoteWritesIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(macroQuoting("defmodule Inner do\n        def run, do: unquote(__MODULE__).helper(1)\n      end"))
        )
    }

    fun testAFunctionImportedByAUsingQuotesUnquotedModuleIsSilent() {
        assertEquals(emptyList<String>(), reports(usingQuoting("import unquote(__MODULE__)", user = "def run, do: f()")))
    }

    fun testAFunctionImportedByAnUnquotedModuleInAModuleAQuoteWritesIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(macroQuoting("defmodule Inner do\n        import unquote(__MODULE__)\n        def run, do: f()\n      end"))
        )
    }

    /** The module a bare `__MODULE__` names is the one the `quote` runs in, which here defines `helper/1`. */
    fun testAModuleQualifierInAMacrosQuoteIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(quoting("m", "def run, do: __MODULE__.helper(1)", "require L\n  def helper(x), do: x\n  L.m()"))
        )
    }

    /** A `bind_quoted:` `quote` leaves `unquote(__MODULE__)` for `def` to unquote where it is injected, in `U`. */
    fun testAnUnquotedModuleQualifierInABindQuotedQuoteIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule L do\n  defmacro m do\n    quote bind_quoted: [name: :run] do\n" +
                    "      def unquote(name)(), do: unquote(__MODULE__).helper(1)\n    end\n  end\nend\n\n" +
                    "defmodule U do\n  require L\n  def helper(x), do: x\n  L.m()\nend\n"
            )
        )
    }

    /** `__MODULE__.Sub` in a `quote` names `Sub` in the module the `quote` is injected into, here `U.Sub`. */
    fun testAModuleAliasQualifierInAMacrosQuoteIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule U.Sub do\n  def helper(x), do: x\nend\n\n" + macroQuoting("def run, do: __MODULE__.Sub.helper(1)")
            )
        )
    }

    /** A module a `quote` writes is nested in the module the `quote` is injected into, here `U.Inner`. */
    fun testAModuleAliasQualifierInAModuleAQuoteWritesIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule U.Inner.Sub do\n  def helper(x), do: x\nend\n\n" +
                    macroQuoting("defmodule Inner do\n        def run, do: __MODULE__.Sub.helper(1)\n      end")
            )
        )
    }

    /** The module a `quote` writes is named by the caller's argument, here `N`. */
    fun testAModuleAliasQualifierInAModuleAQuoteWritesUnquotedIsSilent() {
        assertEquals(
            emptyList<String>(),
            reports(
                "defmodule N do\nend\n\ndefmodule N.Sub do\n  def helper(x), do: x\nend\n\n" +
                    quoting(
                        "m(name)",
                        "defmodule unquote(name) do\n        def run, do: __MODULE__.Sub.helper(1)\n      end",
                        "require L\n  L.m(N)"
                    )
            )
        )
    }

    fun testAnUndefinedModuleAliasQualifierInAModuleBodyDoesNotResolve() {
        assertEquals(
            listOf("__MODULE__.Nope => $UNRESOLVED", "__MODULE__.Nope.f() => $UNRESOLVED"),
            walkReports("defmodule M do\n  def run, do: __MODULE__.Nope.f()\nend\n")
        )
    }

    /** Outside any module `__MODULE__` is `nil`, so `__MODULE__.Nope` is `Nope`, which isn't defined. */
    fun testATopLevelModuleAliasQualifierDoesNotResolve() {
        assertEquals(
            listOf("__MODULE__ => $UNRESOLVED", "__MODULE__.Nope => $UNRESOLVED", "__MODULE__.Nope.f() => $UNRESOLVED"),
            walkReports("__MODULE__.Nope.f()\n")
        )
    }

    fun testATopLevelModuleQualifierDoesNotResolve() {
        assertEquals(
            listOf("__MODULE__ => $UNRESOLVED", "__MODULE__.helper(1) => $UNRESOLVED"),
            walkReports("__MODULE__.helper(1)\n")
        )
    }

    fun testAnUnknownModuleQualifierInAMacrosQuoteDoesNotResolve() {
        assertEquals(
            listOf("Unknown.Mod => $UNRESOLVED", "Unknown.Mod.helper(1) => $UNRESOLVED"),
            walkReports(macroQuoting("def run, do: Unknown.Mod.helper(1)"))
        )
    }

    fun testACallAfterAUseOfModuleInAMacrosQuoteIsSilent() {
        assertEquals(emptyList<String>(), reports(macroQuoting("use __MODULE__\n      def run, do: f()")))
    }

    fun testACallAfterAUseOfModuleInAUsingQuoteIsSilent() {
        assertEquals(emptyList<String>(), reports(usingQuoting("use __MODULE__\n      def run, do: f()")))
    }

    // An alias is asked for its whole module name, as the compiler checks it.

    /** The compiler asks for `Foo.Missing`, whatever `Foo` is. */
    fun testAMissingModuleUnderAKnownNamespaceDoesNotResolve() {
        assertEquals(
            listOf("Foo.Missing => $UNRESOLVED", "Foo.Missing.f() => $UNRESOLVED"),
            walkReports("defmodule Foo do\nend\n\ndefmodule Caller do\n  def run, do: Foo.Missing.f()\nend\n")
        )
    }

    /** A module needs no namespace module: `Foo.Bar` runs with no `Foo`. */
    fun testAModuleUnderNoNamespaceModuleResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo.Bar do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  def run, do: Foo.Bar.f()\nend\n")
        )
    }

    fun testAnElixirPrefixedMissingModuleDoesNotResolve() {
        assertEquals(
            listOf("Elixir.Missing => $UNRESOLVED", "Elixir.Missing.f() => $UNRESOLVED"),
            walkReports("defmodule Caller do\n  def run, do: Elixir.Missing.f()\nend\n")
        )
    }

    fun testAMissingModuleAliasDoesNotResolve() {
        assertEquals(listOf("Missing => $UNRESOLVED"), walkReports("defmodule Caller do\n  alias Missing\nend\n"))
    }

    fun testAMissingModuleStructDoesNotResolve() {
        assertEquals(listOf("Missing => $UNRESOLVED"), walkReports("defmodule Caller do\n  def run, do: %Missing{}\nend\n"))
    }

    fun testAMissingModuleAsAValueDoesNotResolve() {
        assertEquals(listOf("Missing => $UNRESOLVED"), walkReports("defmodule Caller do\n  def run, do: x = Missing\nend\n"))
    }

    fun testAMissingModuleInATypeDoesNotResolve() {
        assertEquals(listOf("Missing => $UNRESOLVED"), walkReports("defmodule Caller do\n  @type t :: Missing.t()\nend\n"))
    }

    fun testAMissingModuleAliasUnderAKnownNamespaceDoesNotResolve() {
        assertEquals(
            listOf("Foo.Missing => $UNRESOLVED", "Missing => $UNRESOLVED", "Missing.f() => $UNRESOLVED"),
            walkReports("defmodule Foo do\nend\n\ndefmodule Caller do\n  alias Foo.Missing\n\n  def run, do: Missing.f()\nend\n")
        )
    }

    /** `Target.Missing` is `Target` and then `.Missing`, whether `Target` is aliased or written out. */
    fun testAMissingModuleUnderAnAliasedNamespaceDoesNotResolve() {
        assertEquals(
            listOf("Target.Missing => $UNRESOLVED", "Target.Missing.f() => $UNRESOLVED"),
            walkReports("defmodule Target do\nend\n\ndefmodule Caller do\n  alias Target\n\n  def run, do: Target.Missing.f()\nend\n")
        )
    }

    fun testAMissingModuleUnderANamespaceAliasedAsDoesNotResolve() {
        assertEquals(
            listOf("Refd.Missing => $UNRESOLVED", "Refd.Missing.f() => $UNRESOLVED"),
            walkReports("defmodule Referenced do\nend\n\ndefmodule Caller do\n  alias Referenced, as: Refd\n\n  def run, do: Refd.Missing.f()\nend\n")
        )
    }

    /** The aliased namespace still reaches a module that exists under it. */
    fun testAModuleUnderAnAliasedNamespaceResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Target.Sub do\n  def f, do: 1\nend\n\ndefmodule Target do\nend\n\ndefmodule Caller do\n  alias Target\n\n  def run, do: Target.Sub.f()\nend\n")
        )
    }

    /** `alias Referenced, as: Refd` names `NS.Referenced` through the `alias NS.Referenced` before it. */
    fun testAModuleUnderANamespaceAliasedAsOverAnAliasResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports(
                "defmodule NS.Referenced do\nend\n\ndefmodule NS.Referenced.Sub do\n  def f, do: 1\nend\n\n" +
                    "defmodule Caller do\n  alias NS.Referenced\n  alias Referenced, as: Refd\n\n  def run, do: Refd.Sub.f()\nend\n"
            )
        )
    }

    /** A pin: `alias Foo.Bar` after `alias NS.Foo` is `NS.Foo.Bar`, and `Bar.Baz` is `NS.Foo.Bar.Baz`. */
    fun testAModuleUnderAnAliasOverAnAliasResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports(
                "defmodule NS.Foo.Bar.Baz do\n  def f, do: 1\nend\n\ndefmodule NS.Foo.Bar do\nend\n\n" +
                    "defmodule NS.Foo do\nend\n\ndefmodule Caller do\n  alias NS.Foo\n  alias Foo.Bar\n\n  def run, do: Bar.Baz.f()\nend\n"
            )
        )
    }

    /** A pin: a member after a member that aliases the base is still the base and then the member. */
    fun testAMemberAfterAMemberThatAliasesTheBaseResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo.Foo do\nend\n\ndefmodule Foo.Bar do\nend\n\ndefmodule Caller do\n  alias Foo.{Foo, Bar}\nend\n")
        )
    }

    /** The module a directive names is read before the directive gives it a name, so `Foo` of `Foo.Bar` is not `Foo.Bar`. */
    fun testTheModuleAnAliasAsNamesIsReadBeforeTheName() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo.Bar do\nend\n\ndefmodule Caller do\n  alias Foo.Bar, as: Foo\nend\n")
        )
    }

    /** `require` reads the module it names before its `as:` name too, as `alias` does. */
    fun testTheModuleARequireAsNamesIsReadBeforeTheName() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo.Bar do\nend\n\ndefmodule Caller do\n  require Foo.Bar, as: Foo\nend\n")
        )
    }

    /** `Nowhere` is only the base of `alias Nowhere.{A}`, which Elixir needs no module for. */
    fun testTheBaseOfAMultiAliasOfAHeldModuleIsNotAsked() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Nowhere.A do\nend\n\ndefmodule Caller do\n  alias Nowhere.{A}\nend\n")
        )
    }

    fun testAMemberOfAMultiAliasOfAMissingModuleDoesNotResolve() {
        assertEquals(listOf("Missing => $UNRESOLVED"), walkReports("defmodule Caller do\n  alias Foo.{Missing}\nend\n"))
    }

    /** The members name `Foo.BitString` and `Foo.Elixir`, which are missing, though `BitString` and `Elixir` alone are no modules. */
    fun testMembersOfAMultiAliasNamedBitStringAndElixirDoNotResolveWhenTheirModulesAreMissing() {
        assertEquals(
            listOf("BitString => $UNRESOLVED", "Elixir => $UNRESOLVED"),
            walkReports("defmodule Foo do\nend\n\ndefmodule Caller do\n  alias Foo.{BitString, Elixir}\nend\n")
        )
    }

    fun testMembersOfAMultiAliasNamedBitStringAndElixirResolveWhenTheirModulesAreHeld() {
        assertEquals(
            emptyList<String>(),
            walkReports(
                "defmodule Foo.BitString do\nend\n\ndefmodule Foo.Elixir do\nend\n\n" +
                    "defmodule Caller do\n  alias Foo.{BitString, Elixir}\nend\n"
            )
        )
    }

    /** `G` is a new name, like a declaration's, and `Foo.Gone` is what the compiler asks for. */
    fun testAnAsNameIsNotAskedAndItsModuleIs() {
        assertEquals(
            listOf("Foo.Gone => $UNRESOLVED"),
            walkReports("defmodule Foo do\nend\n\ndefmodule Caller do\n  alias Foo.Gone, as: G\nend\n")
        )
    }

    fun testABitStringImplementationNeedsNoModule() {
        assertEquals(
            emptyList<String>(),
            walkReports("defprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: BitString do\n  def f(x), do: x\nend\n")
        )
    }

    fun testAnElixirPrefixedBitStringImplementationNeedsNoModule() {
        assertEquals(
            emptyList<String>(),
            walkReports("defprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: Elixir.BitString do\n  def f(x), do: x\nend\n")
        )
    }

    /** The walk can't follow what `conn_case.ex`'s `use` injects, so it can't say `Repo` is missing. */
    fun testAnAliasAUseMayInjectIsNotAsked() {
        myFixture.addFileToProject("conn_case.ex", CONN_CASE)

        assertEquals(
            emptyList<String>(),
            walkReports("defmodule MyApp.RepoTest do\n  use MyApp.ConnCase, async: true\n\n  def test, do: Repo.insert!(%{})\nend\n")
        )
    }

    /** `require Foo, as: M` aliases `M` to `Foo`, so `M.f()` is `Foo.f()`. */
    fun testACallThroughARequireAsNameResolves() {
        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  require Foo, as: M\n\n  def run, do: M.f()\nend\n")
        )
    }

    /** `__MODULE__.Sub` is `Foo.Sub`, in a file of its own so that nothing but the `as:` reaches it. */
    fun testACallThroughARequireAsNameOfAModuleRelativeAliasResolves() {
        myFixture.addFileToProject("sub.ex", "defmodule Foo.Sub do\n  def f, do: 1\nend\n")

        assertEquals(
            emptyList<String>(),
            walkReports("defmodule Foo do\n  require __MODULE__.Sub, as: M\n\n  def run, do: M.f()\nend\n")
        )
    }

    /** A `quote`'s `__MODULE__` is its caller's, so the module `alias __MODULE__.{Sub}` names is not known. */
    fun testAMemberOfAMultiAliasUnderModuleInAQuoteIsNotAsked() {
        assertEquals(
            emptyList<String>(),
            walkReports(macroQuoting("alias __MODULE__.{Sub}"))
        )
    }

    fun testAQualifiedMemberOfAMultiAliasUnderModuleInAQuoteIsNotAsked() {
        assertEquals(
            emptyList<String>(),
            walkReports(macroQuoting("alias __MODULE__.{Sub.Deep}"))
        )
    }

    /** `L`, whose macro `m` returns a `quote` of [quoted], and `U`, which calls it. */
    private fun macroQuoting(quoted: String): String = quoting("m", quoted, "require L\n  L.m()")

    /** `L`, whose `__using__` returns a `quote` of [quoted], and `U`, which uses `L` and holds [user]. */
    private fun usingQuoting(quoted: String, user: String = ""): String = quoting("__using__(_)", quoted, "use L\n  $user")

    private fun quoting(macroHead: String, quoted: String, caller: String): String =
        "defmodule L do\n  def f, do: 1\n  def helper(x), do: x\n\n  defmacro $macroHead do\n    quote do\n      $quoted\n    end\n  end\nend\n\n" +
            "defmodule U do\n  $caller\nend\n"

    private fun reports(text: String): List<String> {
        myFixture.configureByText("m.ex", text)

        return myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${it.text} => ${it.description}" }
    }

    private fun routerHelpersCaller(helpers: String): String =
        "defmodule Caller do\n  alias $helpers, as: Routes\n\n  Routes.device_url(%{}, :index)\nend\n"

    /** [reports] with the walk answering, the switch cleared. */
    private fun walkReports(text: String): List<String> = ExpanderSourceRows.withoutExpanderSwitch { reports(text) }

    private companion object {
        val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
