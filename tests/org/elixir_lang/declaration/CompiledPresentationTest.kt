package org.elixir_lang.declaration

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import com.intellij.ui.icons.RowIcon
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.PREDEFINED_CALL
import org.elixir_lang.Icons
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause
import org.elixir_lang.model.psi.function.FunctionCallReference
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.structure_view.element.Timed
import java.io.File

/**
 * What a user sees of a compiled definition. A `defguard` compiles to a `MACRO-` export, so `Kernel.is_struct/1`
 * shows as a macro.
 */
class CompiledPresentationTest : BeamLibraryTestCase() {
    private lateinit var scheme: DistinctKeyScheme

    override fun getTestDataPath(): String = EBIN.path

    override val ebinDirectory: File
        get() = EBIN

    override fun setUp() {
        super.setUp()
        scheme = DistinctKeyScheme(testRootDisposable)
    }

    fun testUsageViewType() {
        assertEquals(
            listOf("function", "macro", "function"),
            definitions().map { ElementDescriptionUtil.getElementDescription(it, UsageViewTypeLocation.INSTANCE) }
        )
    }

    fun testUsageViewNodeTextKeyword() {
        assertEquals(
            listOf("def", "defmacro", "defp"),
            definitions().map {
                ElementDescriptionUtil
                    .getElementDescription(it, UsageViewNodeTextLocation.INSTANCE)
                    .substringBefore(" ")
            }
        )
    }

    fun testLookupIcon() {
        assertEquals(
            listOf(Icons.Time.RUN, Icons.Time.COMPILE, Icons.Time.RUN),
            definitions().map { definition ->
                val presentation = LookupElementPresentation()
                LookupElementBuilder.create(definition, definition.nameArityInterval.name)
                    .withRenderer(CallDefinitionClause(definition.nameArityInterval.name))
                    .renderElement(presentation)

                (presentation.icon as RowIcon).getIcon(0)
            }
        )
    }

    fun testCallHighlighting() {
        val code = """
            defmodule Caller do
              def calls(x) do
                Kernel.abs(x)
                Kernel.is_struct(x)
              end
            end
        """.trimIndent() + "\n"
        myFixture.configureByText("caller.ex", code)

        assertEquals(
            mapOf(
                "abs" to setOf(FUNCTION_CALL, PREDEFINED_CALL),
                "is_struct" to setOf(MACRO_CALL, PREDEFINED_CALL)
            ),
            listOf("abs", "is_struct").associateWith { name ->
                keysAt(code.indexOf("Kernel.$name(") + "Kernel.".length, name.length)
            }
        )
    }

    /** A call of a compiled function is labelled with the head of its decompiled clause. */
    @Suppress("UnstableApiUsage")
    fun testCallLabel() {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(x), do: Kernel.a<caret>bs(x)\nend\n")

        assertEquals(
            listOf("def abs(number)"),
            generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
                .map { PsiSymbolReferenceService.getService().getReferences(it).filterIsInstance<FunctionCallReference>() }
                .first { it.isNotEmpty() }
                .single()
                .resolveReference()
                .map { (it as FunctionSymbol).presentation().presentableText }
        )
    }

    /** `Kernel.abs/1` (`def`), `Kernel.is_struct/1` (`defguard`) and a private function. */
    private fun definitions(): List<CallDefinition> {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(EBIN, "Elixir.Kernel.beam"))!!
        val compiled = PsiManager.getInstance(project).findFile(virtualFile) as PsiCompiledFile
        val definitions = (compiled.children.single() as ModuleImpl<*>).callDefinitions()

        fun exported(name: String, time: Timed.Time) =
            definitions.single {
                it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == 1 && it.time == time
            }

        return listOf(
            exported("abs", Timed.Time.RUN),
            exported("is_struct", Timed.Time.COMPILE),
            definitions.first { !it.isExported && it.time == Timed.Time.RUN }
        )
    }

    private fun keysAt(offset: Int, length: Int): Set<TextAttributesKey> =
        myFixture.doHighlighting()
            .filter { it.forcedTextAttributes != null && it.startOffset == offset && it.endOffset == offset + length }
            .flatMap { scheme.keys(it.forcedTextAttributes) }
            .toSet()

    companion object {
        private val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile
    }
}
