package org.elixir_lang.model.psi.atom

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.psi.ElixirAtom
import java.io.File

/**
 * An MFA tuple's atom reaches a compiled module's public functions, as it does a source module's: `Kernel.build_if/2`
 * is private, and `Kernel.is_atom/1` is the control.
 */
class CompiledMfaTargetTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/parser"

    override val ebinDirectory: File
        get() = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

    fun testAnMfaAtomReachesOnlyACompiledModulesPublicFunctions() {
        myFixture.configureByText(
            "compiled_mfa.ex",
            """
            defmodule Caller do
              def mfas, do: [{Kernel, :is_atom, 1}, {Kernel, :build_if, 2}]
            end
            """.trimIndent()
        )

        val actual = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).joinToString("\n") { atom ->
            val targets = (atom.reference as? PsiPolyVariantReference)?.multiResolve(false)?.count { it.isValidResult } ?: 0

            "${atom.text} -> ${if (targets > 0) "targeted" else "nothing"}"
        }

        assertEquals(":is_atom -> targeted\n:build_if -> nothing", actual)
    }
}
