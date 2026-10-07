package org.elixir_lang.reference.mfa_tuple

import com.intellij.find.usages.impl.searchTargets
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewNodeTextLocation
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.psi.NamedElement
import java.io.File

/**
 * `Mod.f/2` is defined from an EEx template, which the editor does not resolve from source, so a compiled copy of `Mod`
 * with an older `f(old_q, old_x)` is the only definition it finds for `:"Elixir.Mod"`. Without the compiled copy it finds
 * none, and nothing of the compiled copy is shown with it: not Quick Documentation, not the usage description, not the
 * search target's label.
 */
class StaleBeamEexTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam_eex"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              require EEx

              EEx.function_from_string(:def, :f, "<%= q %><%= x %>", [:q, :x])
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def run(q, x), do: apply(:"Elixir.Mod", :f<caret>, [q, x])
            end
            """.trimIndent()
        )
    }

    fun testQuickDocumentationShowsNothingFromTheCompiledCopy() {
        myFixture.editor.caretModel.moveToOffset(myFixture.caretOffset - 1)

        val documentation = myFixture.quickDocumentationAtCaret(project).orEmpty()

        assertFalse("Quick Documentation shows the compiled copy: $documentation", documentation.contains("old_q"))
    }

    fun testUsageDescriptionIsNothingFromTheCompiledCopy() {
        val descriptions = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { target ->
            PsiTreeUtil.getParentOfType(target.destination, NamedElement::class.java, false)?.let {
                ElementDescriptionUtil.getElementDescription(it, UsageViewNodeTextLocation.INSTANCE)
            }
        }

        assertEmpty("The usage description describes the compiled copy: $descriptions", descriptions)
    }

    fun testSearchTargetLabelIsNothingFromTheCompiledCopy() {
        @Suppress("UnstableApiUsage")
        val labels = searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }

        assertEmpty("A search target presents the compiled copy: $labels", labels.filter { it.contains("old_q") })
    }
}
