package org.elixir_lang

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.beam.BeamLibraryTestCase

/**
 * `EEx.isFunctionFrom` used to answer via [resolvesToModularName], which discards a `BeamCallDefinition`
 * match (`resolveResult.element?.let { it as? Call }`) - so a project depending on a *compiled* `EEx`,
 * which is how the real Elixir standard library ships in an SDK, never actually reached it. `EEx.kt` now
 * decides a qualified call's own reference via [org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars]
 * (alias resolution), which reaches a `BeamModule` the same way it reaches a source one - this pins that
 * against a real compiled `Elixir.EEx.beam`, not the source fixture `eex.ex` every other test uses.
 */
class EExResolvesAgainstCompiledDependencyTest : BeamLibraryTestCase() {
    fun testFunctionFromStringDeclaresAFunctionResolvableAgainstACompiledEEx() {
        myFixture.configureByText(
            "host.ex",
            """
            defmodule Host do
              require EEx

              EEx.function_from_string(:def, :render, "<%= @name %>", [:assigns])

              def usage do
                render(%{})
              end
            end
            """.trimIndent()
        )

        val text = myFixture.file.text
        val offset = text.indexOf("render(%{})", text.indexOf("def usage"))
        val leaf = myFixture.file.findElementAt(offset)!!
        val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()

        assertInstanceOf(reference, PsiPolyVariantReference::class.java)
        val resolved = (reference as PsiPolyVariantReference).multiResolve(false).filter { it.isValidResult }

        assertFalse(
            "`render/1`, declared by `EEx.function_from_string` against a real compiled `EEx`, must resolve",
            resolved.isEmpty()
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/mockSdk-1.0.4/lib/eex"
}
