package org.elixir_lang.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import org.elixir_lang.beam.BeamLibraryTestCase
import java.io.File

/**
 * What the Elixir references inspection reports for a call, a capture and a `@spec` head: "Does not resolve to
 * anything" where nothing is a candidate, "Only resolves to invalid results" where candidates exist and none applies.
 */
class ReferencesTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = EBIN.path

    override val ebinDirectory: File
        get() = EBIN

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
        // The fixture library holds `Kernel` only, so the special forms the fixtures use need declaring
        myFixture.addFileToProject(
            "special_forms.ex",
            "defmodule Kernel.SpecialForms do\n  defmacro quote(opts \\\\ [], block), do: nil\n  defmacro require(module, opts \\\\ []), do: nil\nend\n"
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

    private fun reports(text: String): List<String> {
        myFixture.configureByText("m.ex", text)

        return myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${it.text} => ${it.description}" }
    }

    private companion object {
        val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
