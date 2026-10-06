package org.elixir_lang.reference.mfa_tuple

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.documentation.ElixirDocumentationProvider
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * A link in a module's documentation is a link when what it names exists in the project's source, as it is with no
 * compiled copy of `Mod` beside it: `Mod.stale_only/1`, which only the compiled copy has, is plain code, and a callback
 * or a type of `Mod` is a link.
 */
class StaleBeamDocumentationLinkTest : BeamLibraryTestCase() {
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
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc ${"\"\"\""}
              See `Mod`, `Mod.f/2`, `Mod.stale_only/1`, `c:Mod.cb/1` and `t:Mod.t/0`.
              ${"\"\"\""}
            end
            """.trimIndent()
        )
    }

    private val documentation: String
        get() = myFixture.quickDocumentationAtCaret(project).orEmpty()

    private fun link(target: String): String = "psi_element://$target"

    private fun linkTarget(link: String) =
        ElixirDocumentationProvider().getDocumentationElementForLink(
            psiManager,
            link,
            PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), Call::class.java)!!
        )

    fun testFunctionOfTheSourceIsALink() {
        assertTrue(documentation, documentation.contains(link("Mod.f/2")))
    }

    fun testFunctionOnlyTheCompiledCopyHasIsNotALink() {
        assertFalse(documentation, documentation.contains(link("Mod.stale_only/1")))
    }

    fun testFunctionOnlyTheCompiledCopyHasReachesNothing() {
        assertNull(linkTarget("Mod.stale_only/1"))
    }

    fun testCallbackIsALink() {
        assertTrue(documentation, documentation.contains(link("c:Mod.cb/1")))
    }

    fun testTypeIsALink() {
        assertTrue(documentation, documentation.contains(link("t:Mod.t/0")))
    }

    fun testModuleIsALink() {
        assertTrue(documentation, documentation.contains(link("Mod")))
    }

    fun testModuleLinkReachesTheSource() {
        assertEquals("mod.ex", linkTarget("Mod").fileName())
    }
}
