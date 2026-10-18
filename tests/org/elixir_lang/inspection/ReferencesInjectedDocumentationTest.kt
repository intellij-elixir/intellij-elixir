package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Elixir in documentation is not compiled with the module, so the Elixir references inspection reports nothing there.
 * A problem reported inside the injected code also fails to restore: the platform logs `Cannot restore <element> from
 * injected`, and a logged error fails the test on its own.
 */
class ReferencesInjectedDocumentationTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
        // No SDK, so `defmodule` and `def` need declaring for the inspection to find them
        myFixture.addFileToProject(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro defmodule(alias, do_block), do: nil\n  defmacro def(call, expr \\\\ nil), do: nil\nend\n"
        )
    }

    fun testAnIexSampleCallingAnUndeclaredFunctionIsSilent() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @doc \"\"\"\n  ## Examples\n\n      iex> undeclared_function(1)\n      1\n  \"\"\"\n  def f, do: 1\nend\n"
        )

        assertEquals(emptyList<String>(), reports())
    }

    fun testACodeBlockCallingAnUndeclaredFunctionIsSilent() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @moduledoc \"\"\"\n  Example:\n\n      undeclared_function(1)\n  \"\"\"\n\n  def f, do: 1\nend\n"
        )

        assertEquals(emptyList<String>(), reports())
    }

    fun testCodeOutsideDocumentationIsStillReported() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f, do: undeclared_function(1)\nend\n")

        assertEquals(listOf("undeclared_function(1)"), reports())
    }

    fun testAccess() = highlightCorpusFile("lib/elixir/lib/access.ex")
    fun testMixTest() = highlightCorpusFile("lib/mix/test/mix_test.exs")
    fun testString() = highlightCorpusFile("lib/elixir/lib/string.ex")

    private fun reports(): List<String> =
        myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" }
            .map { it.text }

    private fun highlightCorpusFile(relativePath: String) {
        val corpus = System.getenv(CORPUS_ENVIRONMENT_VARIABLE)

        assertNotNull(
            "$CORPUS_ENVIRONMENT_VARIABLE is not set. The Gradle test task sets it when " +
                ".github/ci-versions.json declares a corpus for Elixir ${System.getenv("ELIXIR_VERSION")}",
            corpus
        )

        val elixir = Files.list(Path.of(corpus).resolve("elixir-lang")).use { it.findFirst() }
        assertTrue("No elixir-lang checkout under $corpus", elixir.isPresent)

        val path = elixir.get().resolve(relativePath)
        assertTrue("$path is missing from the corpus", Files.isRegularFile(path))

        myFixture.configureByText(path.fileName.toString(), Files.readString(path))
        myFixture.doHighlighting()
    }

    private companion object {
        const val CORPUS_ENVIRONMENT_VARIABLE = "ELIXIR_PARSING_CORPUS"
    }
}
