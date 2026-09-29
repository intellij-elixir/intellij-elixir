package org.elixir_lang.psi

import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewNodeTextLocation
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * A compiled definition's usage-view text names the `def*` that wrote it. `defguard` writes a macro marked
 * `guard: true`, as `Kernel.is_nil/1` is; `Kernel.is_atom/1` is a `def` marked the same, so it reads `def`.
 */
class CompiledDefinitionDescriptionTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/parser"

    override val ebinDirectory: File
        get() = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

    fun testACompiledDefinitionIsDescribedByTheDefThatWroteIt() {
        myFixture.configureByText(
            "described.ex",
            """
            defmodule Described do
              def f(x), do: {is_nil(x), is_atom(x), to_string(x)}
            end
            """.trimIndent()
        )

        val actual = listOf("is_nil", "is_atom", "to_string").joinToString("\n") { name ->
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.functionName() == name }
            val definition = (call.reference as PsiPolyVariantReference).multiResolve(false)
                .mapNotNull { it.element as? CallDefinition }
                .first()

            ElementDescriptionUtil.getElementDescription(definition, UsageViewNodeTextLocation.INSTANCE)
        }

        assertEquals(
            """
            defguard is_nil(p0), do: ...
            def is_atom(p0), do: ...
            defmacro to_string(p0), do: ...
            """.trimIndent(),
            actual
        )
    }
}
