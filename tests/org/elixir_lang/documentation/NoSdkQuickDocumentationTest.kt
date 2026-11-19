package org.elixir_lang.documentation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.declaration.enableExpander
import java.io.File

/**
 * With Run the expander on and no SDK, the expander answers a call from the name index, and Quick Documentation
 * describes what the index finds as it does what the expansion finds.
 *
 * The library is the committed `stale_beam` one: `Mod` as it was before the project's `lib/mod.ex` changed.
 */
class NoSdkQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    override fun setUp() {
        super.setUp()
        enableExpander(testRootDisposable)
        // The index lists a definition by its file's id, and a directory's children get theirs when it is first read: read
        // the library's before the source is added, so that the stale `.beam` is the one the index lists first.
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebinDirectory)!!.children

        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              @doc "Current."
              def f(q, x), do: {q, x}
            end
            """.trimIndent()
        )
    }

    /** The project's module is the one that is built from, so a library `.beam` of the same name, left over from an older build, loses to it. */
    fun testAProjectModuleIsPreferredOverALibraryBeamOfTheSameName() {
        val documentation = quickDocumentationIn("Mod.<caret>f(1, 2)")

        assertTrue("Expected the project's `f/2`, got: $documentation", "Current." in documentation)
        assertFalse("Expected nothing of the library's `f/2`, got: $documentation", "Old." in documentation)
    }

    /** A default the project's function adds makes it a different range of arities from the library's `f/2`, which it still replaces. */
    fun testAProjectFunctionWithADefaultIsPreferredOverALibraryBeamsFunctionOfTheArityItAdds() {
        rewriteMod("defmodule Mod do\n  @doc \"Current.\"\n  def f(q, x \\\\ 0), do: {q, x}\nend\n")

        val documentation = quickDocumentationIn("Mod.<caret>f(1, 2)")

        assertTrue("Expected the project's `f/1..2`, got: $documentation", "Current." in documentation)
        assertFalse("Expected nothing of the library's `f/2`, got: $documentation", "Old." in documentation)
    }

    /**
     * A function the project's module defines through `EEx.function_from_string`, which the name index doesn't list, is
     * still the project's: Elixir loads the project's `Mod`, so the library's `f/2` of the same module is not shown for it.
     */
    fun testAProjectModuleWhoseFunctionTheIndexDoesNotListShowsNothingOfALibraryBeamsFunction() {
        rewriteMod("defmodule Mod do\n  require EEx\n\n  EEx.function_from_string(:def, :f, \"<%= q %><%= x %>\", [:q, :x])\nend\n")

        val documentation = quickDocumentationIn("Mod.<caret>f(1, 2)")

        assertFalse("Expected nothing of the library's `f/2`, got: $documentation", "old_q" in documentation || "Old." in documentation)
    }

    /** A module the project has no source for is the library's alone. */
    fun testAFunctionOnlyTheLibraryDefinesIsFound() {
        val documentation = quickDocumentationIn("BeamOnly.<caret>only(1)")

        assertTrue("Expected the library's `only/1`, got: $documentation", "only(x)" in documentation)
    }

    fun testTheAtomOfApplyIsDocumentedFromTheIndex() = assertShowsDouble("apply(Target, :dou<caret>ble, [1])")

    fun testTheAtomOfAnMfaTupleIsDocumentedFromTheIndex() = assertShowsDouble("{Target, :dou<caret>ble, 1}")

    fun testTheHeadOfASpecIsDocumentedFromTheIndex() {
        val documentation = myFixture.quickDocumentationIn(
            project,
            "spec.ex",
            """
            defmodule Spec do
              @spec ad<caret>d(integer, integer) :: integer
              @doc "Adds two numbers together."
              def add(a, b), do: a + b
            end
            """.trimIndent()
        )

        assertTrue("Expected the @doc, got: $documentation", "Adds two numbers together." in documentation)
    }

    /** A link to another module's function is drawn as one: the module is found by name where nothing is expanded. */
    fun testALinkToAnotherModulesFunctionIsDrawn() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def f(x), do: x\nend\n")

        val documentation = myFixture.quickDocumentationIn(
            project,
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "See `Other.f/1`."
            end
            """.trimIndent()
        )

        assertTrue("Expected a link to Other.f/1, got: $documentation", "href=\"psi_element://Other.f/1\"" in documentation)
    }

    /**
     * A call at an arity nothing takes documents what the module offers of its name from outside it, as it does with an
     * SDK: each public function and macro, and no private one.
     */
    fun testACallAtAnArityNothingTakesDocumentsWhatTheModuleOffersOfItsName() {
        myFixture.addFileToProject(
            "processor.ex",
            """
            defmodule Processor do
              @doc "Processes with options."
              def process(item, opts), do: {item, opts}

              @doc "Processes four at once."
              defmacro process(a, b, c, d), do: {a, b, c, d}

              defp process(secret), do: secret
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationIn("Processor.pro<caret>cess(x, x, x)")

        assertTrue("Expected process/2, got: $documentation", "process(item, opts)" in documentation)
        assertTrue("Expected the macro, got: $documentation", "Processes four at once." in documentation)
        assertFalse("Expected no private function, got: $documentation", "process(secret)" in documentation)
    }

    fun testAPrivateFunctionCalledInADefIsFound() = assertShowsSecret("se<caret>cret(1)")

    /** A `fn` is not a module, so a private function called in one is its module's own. */
    fun testAPrivateFunctionCalledInAFnIsFound() = assertShowsSecret("fn -> se<caret>cret(1) end")

    /** A capture is not a module, so a private function called in one is its module's own. */
    fun testAPrivateFunctionCalledInACaptureIsFound() = assertShowsSecret("&se<caret>cret(&1)")

    /** A `quote`'s body runs in its caller's module, so a private function called there is not this module's own. */
    fun testAPrivateFunctionCalledInAQuoteIsNotFound() {
        val documentation = ownCalling("quote(do: se<caret>cret(1))")

        assertFalse("Expected no private function, got: $documentation", "secret(x)" in documentation)
    }

    /** [call], in a `def` of the module that defines `secret/1` privately, documents it. */
    private fun assertShowsSecret(call: String) {
        val documentation = ownCalling(call)

        assertTrue("Expected the private `secret/1`, got: $documentation", "secret(x)" in documentation)
    }

    private fun ownCalling(call: String): String =
        myFixture.quickDocumentationIn(
            project,
            "own.ex",
            """
            defmodule Own do
              def calls, do: $call

              defp secret(x), do: x
            end
            """.trimIndent()
        )

    /** [call], with `Target` in the project, documents `Target.double/1`. */
    private fun assertShowsDouble(call: String) {
        myFixture.addFileToProject("target.ex", TARGET)

        val documentation = quickDocumentationIn(call)

        assertTrue("Expected the head, got: $documentation", "double(n)" in documentation)
        assertTrue("Expected the @doc, got: $documentation", "Doubles a number." in documentation)
    }

    /** Replaces the project's `lib/mod.ex` with [text]. */
    private fun rewriteMod(text: String) {
        val source = myFixture.findFileInTempDir("lib/mod.ex")!!
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(source, text) }
    }

    private fun quickDocumentationIn(call: String): String =
        myFixture.quickDocumentationIn(
            project,
            "caller.ex",
            """
            defmodule Caller do
              def calls, do: $call
            end
            """.trimIndent()
        )

    private companion object {
        const val TARGET = """
            defmodule Target do
              @doc "Doubles a number."
              def double(n), do: n * 2
            end
            """
    }
}
