package org.elixir_lang.reference

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/** A reference with several valid results resolves to the first preferred of them, not to nothing. */
class ResolvedTest : PlatformTestCase() {
    fun testAModuleDefinedInTwoFilesResolves() {
        myFixture.addFileToProject("first.ex", "defmodule Twice do\nend\n")
        myFixture.addFileToProject("second.ex", "defmodule Twice do\nend\n")
        myFixture.configureByText("use.ex", "defmodule User do\n  def calls, do: Twice\nend\n")

        val reference = referenceAt("Twice")

        assertTrue("Expected two valid results", reference.multiResolve(false).count { it.isValidResult } >= 2)
        assertNotNull(reference.resolve())
    }

    fun testACaptureOfAMultiClauseFunctionResolves() {
        myFixture.configureByText(
            "capture.ex",
            "defmodule Definer do\n  def snoc(q, nil), do: q\n  def snoc(q, x), do: {q, x}\nend\n\n" +
                "defmodule Caller do\n  def capture, do: &Definer.snoc/2\nend\n"
        )

        val reference = referenceAt("snoc/2")

        assertTrue("Expected two valid results", reference.multiResolve(false).count { it.isValidResult } >= 2)
        assertNotNull(reference.resolve())
    }

    private fun referenceAt(fragment: String): PsiPolyVariantReference =
        generateSequence(myFixture.file.findElementAt(myFixture.file.text.indexOf(fragment))) { it.parent }
            .mapNotNull { it.reference as? PsiPolyVariantReference }
            .first()
}
