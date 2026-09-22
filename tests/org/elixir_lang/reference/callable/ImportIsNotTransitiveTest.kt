package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * `import` is not transitive in Elixir and `require` imports nothing at all, so neither can make a name
 * callable without a qualifier in a module that only reached it second-hand. Probing the *explicit* forms
 * here, as the general counterpart to the implicit `import Kernel` case pinned by
 * [org.elixir_lang.psi.scope.ImplicitKernelImportModuleSizeIndependenceTest] - nothing about `Kernel` makes
 * the rule specific to it.
 */
class ImportIsNotTransitiveTest : PlatformTestCase() {
    fun testImportDoesNotReExportWhatTheImportedModuleImported() {
        assertUnqualifiedCallDoesNotResolve(
            middleBody = "import Deep",
            callerStatement = "import Middle",
            calledName = "deep_fun"
        )
    }

    fun testRequireDoesNotImportAnything() {
        assertUnqualifiedCallDoesNotResolve(
            middleBody = "def middle_fun, do: :ok",
            callerStatement = "require Middle",
            calledName = "middle_fun"
        )
    }

    /**
     * The control for the two negatives above: same fixture shape, same resolve call, one `import` hop
     * instead of two. Without it a green negative proves nothing - a fixture whose modules were never
     * indexed, or a `calledName` that never got a reference, would resolve to nothing too and read as a
     * pass.
     */
    fun testASingleImportHopDoesResolve() {
        val validResults = validResolveResults(
            middleBody = "def middle_fun, do: :ok",
            callerStatement = "import Middle",
            calledName = "middle_fun"
        )

        assertTrue(
            "one `import` hop must resolve, or the negative cases above prove nothing - resolved: " +
                "${validResults.map { it.element?.text }}",
            validResults.any { it.element?.text?.contains("def middle_fun") == true }
        )
    }

    private fun assertUnqualifiedCallDoesNotResolve(
        middleBody: String,
        callerStatement: String,
        calledName: String
    ) {
        val validResults = validResolveResults(middleBody, callerStatement, calledName)

        assertEmpty(
            "`$calledName/0` must not resolve from `Caller`, which only reached it through " +
                "`$callerStatement` - resolved: ${validResults.map { it.element?.text }}",
            validResults
        )
    }

    private fun validResolveResults(
        middleBody: String,
        callerStatement: String,
        calledName: String
    ): List<com.intellij.psi.ResolveResult> {
        myFixture.addFileToProject(
            "deep.ex",
            """
            defmodule Deep do
              def deep_fun, do: :ok
            end
            """.trimIndent()
        )

        myFixture.addFileToProject(
            "middle.ex",
            """
            defmodule Middle do
              $middleBody
            end
            """.trimIndent()
        )

        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              $callerStatement

              def go, do: $calledName()
            end
            """.trimIndent()
        )

        val call = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(myFixture.file, Call::class.java)
            .single { it.text == "$calledName()" }

        val reference = call.reference
        assertNotNull("`$calledName()` should carry a reference to resolve", reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)

        return (reference as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
    }
}
