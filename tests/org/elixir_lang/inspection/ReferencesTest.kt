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
            listOf("__MODULE__.Nope.f() => $UNRESOLVED"),
            walkReports("defmodule M do\n  def run, do: __MODULE__.Nope.f()\nend\n")
        )
    }

    /** Outside any module `__MODULE__` is `nil`, so `__MODULE__.Nope` is `Nope`, which isn't defined. */
    fun testATopLevelModuleAliasQualifierDoesNotResolve() {
        assertEquals(
            listOf("__MODULE__ => $UNRESOLVED", "__MODULE__.Nope.f() => $UNRESOLVED"),
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
            listOf("Unknown => $UNRESOLVED", "Unknown.Mod.helper(1) => $UNRESOLVED"),
            walkReports(macroQuoting("def run, do: Unknown.Mod.helper(1)"))
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

    /** [reports] with the walk answering, the switch cleared. */
    private fun walkReports(text: String): List<String> = ExpanderSourceRows.withoutExpanderSwitch { reports(text) }

    private companion object {
        val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
