package org.elixir_lang.reference.mfa_tuple

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret
import java.io.File

/**
 * A module written as an atom resolves to the same definitions as the same module written as an alias, even when a
 * compiled copy of it (`Elixir.Mod.beam`, left over from an older build) sits beside the project's source.
 *
 * `Mod` is in `lib/mod.ex`; `Elixir.Mod.beam` has `f(old_q, old_x)`, a `stale_only/1`, a `stale_t` type and a
 * `__using__` defining `stale_used/0`, none of which the source has. Every assertion goes through what the user
 * does: Go to Declaration, Rename, completion and Quick Documentation.
 */
class StaleBeamModuleAtomTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              @type t :: term
              @callback cb(term) :: term

              @doc "Current."
              def f(q, x), do: {q, x}

              defmacro __using__(_), do: quote(do: nil)
            end
            """.trimIndent()
        )
        myFixture.addFileToProject("lib/mod/sub.ex", "defmodule Mod.Sub do\n  def g, do: :ok\nend\n")
        myFixture.addFileToProject("lib/other_sub.ex", "defmodule Sub do\n  def g, do: :ok\nend\n")
    }

    private val source = listOf("mod.ex")

    private fun goToDeclarationFiles(text: String): List<String> {
        myFixture.configureByText("caller.ex", text.trimIndent())

        return myFixture.gotoDeclarationFilesAtCaret()
    }

    private fun caller(body: String): String = "defmodule Caller do\n  def run(q, x), do: $body\nend\n"

    private fun assertNoCompiledCopy(files: List<String>) {
        assertEmpty("Go to Declaration reached the compiled copy: $files", files.filter { it.endsWith(".beam") })
    }

    // `apply` and an MFA tuple

    fun testApplyOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:"Elixir.Mod", :f<caret>, [q, x])""")))
    }

    fun testMfaTupleOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""{{:"Elixir.Mod", :f<caret>, 2}, q, x}""")))
    }

    // every spelling of the atom

    fun testSingleQuotedAtomOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:'Elixir.Mod', :f<caret>, [q, x])""")))
    }

    fun testHexEscapeOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:"Elixir.M\x6fd", :f<caret>, [q, x])""")))
    }

    fun testUnicodeEscapeOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:"Elixir.M\u006fd", :f<caret>, [q, x])""")))
    }

    fun testBracedUnicodeEscapeOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:"Elixir.M\u{6f}d", :f<caret>, [q, x])""")))
    }

    fun testIdentityEscapeOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(:"Elixir.M\od", :f<caret>, [q, x])""")))
    }

    fun testEscapedNewlineOffersTheSourceOnly() {
        val text = "defmodule Caller do\n  def run(q, x), do: apply(:\"Elixir.M\\\nod\", :f<caret>, [q, x])\nend\n"

        assertEquals(source, goToDeclarationFiles(text))
    }

    // an interpolated module atom

    fun testInterpolatedModuleAtomDoesNotReachTheCompiledCopy() {
        assertNoCompiledCopy(goToDeclarationFiles(caller("""{:"Elixir.#{"Mod"}".stale_only<caret>(x), q}""")))
    }

    // the atom on its own

    fun testBareAtomOffersTheSourceModuleOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""{:"Elixir.Mo<caret>d", q, x}""")))
    }

    fun testStructAtomOffersTheSourceModuleOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""{%:"Elixir.Mo<caret>d"{}, q, x}""")))
    }

    // a qualified call, a capture and an import

    fun testQualifiedCallDoesNotReachACompiledOnlyFunction() {
        assertEmpty(goToDeclarationFiles(caller("""{:"Elixir.Mod".stale_only<caret>(x), q}""")))
    }

    fun testAliasQualifiedCallDoesNotReachACompiledOnlyFunction() {
        assertEmpty(goToDeclarationFiles(caller("""{Mod.stale_only<caret>(x), q}""")))
    }

    fun testCaptureDoesNotReachACompiledOnlyFunction() {
        assertEmpty(goToDeclarationFiles(caller("""{&:"Elixir.Mod".stale_<caret>only/1, q, x}""")))
    }

    fun testImportDoesNotReachACompiledOnlyFunction() {
        assertEmpty(
            goToDeclarationFiles(
                """
                defmodule Caller do
                  import :"Elixir.Mod"

                  def run(x), do: stale_only<caret>(x)
                end
                """
            )
        )
    }

    // `use`, `apply/3` inside `__using__`, and `defdelegate`

    fun testUseDoesNotReachACompiledOnlyDefinition() {
        assertEmpty(
            goToDeclarationFiles(
                """
                defmodule Caller do
                  use :"Elixir.Mod"

                  def run, do: stale_used<caret>()
                end
                """
            )
        )
    }

    fun testApplyInUsingDoesNotReachACompiledOnlyFunction() {
        assertEmpty(
            goToDeclarationFiles(
                """
                defmodule Third do
                  defmacro __using__(_), do: apply(:"Elixir.Mod", :stale_only<caret>, [1])
                end

                defmodule User do
                  use Third
                end
                """
            )
        )
    }

    fun testDefdelegateToDoesNotReachACompiledOnlyFunction() {
        assertNoCompiledCopy(
            goToDeclarationFiles(
                """
                defmodule Caller do
                  defdelegate g(x), to: :"Elixir.Mod", as: :stale_only

                  def run(x), do: g<caret>(x)
                end
                """
            )
        )
    }

    // a remote type

    fun testRemoteTypeDoesNotReachACompiledOnlyType() {
        assertEmpty(
            goToDeclarationFiles(
                """
                defmodule Caller do
                  @type u :: :"Elixir.Mod".stale_<caret>t()
                end
                """
            )
        )
    }

    // Rename

    fun testRenameOffersOneTargetAtApply() {
        myFixture.configureByText("caller.ex", caller("""apply(:"Elixir.Mod", :f<caret>, [q, x])""").trimIndent())

        assertEquals(1, myFixture.renameTargetsAtCaret().size)
    }

    fun testRenameOffersOneTargetAtMfaTuple() {
        myFixture.configureByText("caller.ex", caller("""{{:"Elixir.Mod", :f<caret>, 2}, q, x}""").trimIndent())

        assertEquals(1, myFixture.renameTargetsAtCaret().size)
    }

    // guards that agree today and must keep agreeing

    fun testAliasApplyOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""apply(Mod, :f<caret>, [q, x])""")))
    }

    fun testAliasMfaTupleOffersTheSourceOnly() {
        assertEquals(source, goToDeclarationFiles(caller("""{{Mod, :f<caret>, 2}, q, x}""")))
    }

    fun testModuleMacroQualifiedAliasResolvesToTheNestedModule() {
        assertEquals(
            listOf("sub.ex"),
            goToDeclarationFiles(
                """
                defmodule Mod do
                  def run, do: __MODULE__.Sub.g<caret>()
                end
                """
            )
        )
    }

    fun testAliasAsResolvesToTheNestedModule() {
        assertEquals(
            listOf("sub.ex"),
            goToDeclarationFiles(
                """
                defmodule Caller do
                  alias Mod, as: A

                  def run, do: A.Sub.g<caret>()
                end
                """
            )
        )
    }

    fun testAtomIsNotAliasExpanded() {
        assertEquals(
            listOf("other_sub.ex"),
            goToDeclarationFiles(
                """
                defmodule Caller do
                  alias Mod.Sub

                  def run, do: :"Elixir.Sub".g<caret>()
                end
                """
            )
        )
    }

    fun testAliasOfAnAtomResolvesToTheSourceOnly() {
        assertEquals(
            source,
            goToDeclarationFiles(
                """
                defmodule Caller do
                  alias :"Elixir.Mod", as: A

                  def run(q, x), do: A.f<caret>(q, x)
                end
                """
            )
        )
    }

    fun testModuleWithOnlyACompiledCopyResolvesToIt() {
        assertEquals(
            listOf("Elixir.BeamOnly.beam"),
            goToDeclarationFiles(caller("""{:"Elixir.BeamOnly".only<caret>(x), q}"""))
        )
    }

    fun testRemoteTypeBothCopiesHaveOffersTheSource() {
        assertEquals(
            source,
            goToDeclarationFiles(
                """
                defmodule Caller do
                  @type u :: :"Elixir.Mod".<caret>t()
                end
                """
            )
        )
    }

    fun testBehaviourAtomOffersTheSourceOnly() {
        assertEquals(
            source,
            goToDeclarationFiles(
                """
                defmodule Caller do
                  @behaviour :"Elixir.Mo<caret>d"
                end
                """
            )
        )
    }

    fun testImportOnlyKeyOffersTheSourceOnly() {
        assertEquals(
            source,
            goToDeclarationFiles(
                """
                defmodule Caller do
                  import :"Elixir.Mod", only: [f<caret>: 2]
                end
                """
            )
        )
    }

    fun testQuickDocumentationShowsTheSourceDocumentation() {
        myFixture.configureByText("caller.ex", caller("""apply(:"Elixir.Mod", :<caret>f, [q, x])""").trimIndent())

        val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()

        assertTrue("Quick Documentation is missing the source's doc: $documentation", documentation.contains("Current."))
        assertFalse("Quick Documentation shows the compiled copy's doc: $documentation", documentation.contains("Old."))
    }

    fun testAtomCompletionOffersTheSourceFunctionsOnly() {
        myFixture.configureByText("caller.ex", caller("""{:"Elixir.Mod".<caret>, q, x}""").trimIndent())

        val offered = myFixture.completionStringsAtCaret().orEmpty()

        assertEquals(1, offered.count { it == "f" })
        assertFalse("completion offered a compiled-only function: $offered", offered.contains("stale_only"))
    }

    fun testCaptureCompletionOffersTheSourceFunctionsOnly() {
        myFixture.configureByText("caller.ex", caller("""{&:"Elixir.Mod".<caret>, q, x}""").trimIndent())

        val offered = myFixture.completionStringsAtCaret().orEmpty()

        assertEquals(1, offered.count { it == "f" })
        assertFalse("completion offered a compiled-only function: $offered", offered.contains("stale_only"))
    }

    fun testAliasCompletionOffersTheSourceFunctionsOnly() {
        myFixture.configureByText("caller.ex", caller("""{Mod.<caret>, q, x}""").trimIndent())

        val offered = myFixture.completionStringsAtCaret().orEmpty()

        assertEquals(1, offered.count { it == "f" })
        assertFalse("completion offered a compiled-only function: $offered", offered.contains("stale_only"))
    }

    fun testModuleWithOnlyACompiledCopyOffersItsFunctions() {
        myFixture.configureByText("caller.ex", caller("""{:"Elixir.BeamOnly".<caret>, q, x}""").trimIndent())

        assertContainsElements(myFixture.completionStringsAtCaret().orEmpty(), "only")
    }
}
