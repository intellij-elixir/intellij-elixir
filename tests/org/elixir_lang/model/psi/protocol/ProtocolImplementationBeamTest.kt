package org.elixir_lang.model.psi.protocol

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.code_insight.line_marker_provider.Implementation as ImplementationMarker
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * The rule read against compiled modules, from `beam/size.ex.txt`, `beam/count.ex.txt` and `beam/shape.ex.txt`
 * compiled on Elixir 1.20.4: `Size.Map` keeps `size/1` as a private function its `count/1` calls, `Size.Tuple` and
 * `Size.BitString` export it as a macro, and `Size.List` and `Size.Atom` export it as a function. `Shape` stays
 * compiled for a compiled macro member.
 *
 * A compiled `defimpl` is in no index, so no query reaches one; the rule is read on its definitions directly.
 */
@Suppress("UnstableApiUsage")
class ProtocolImplementationBeamTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/protocol/implementation_rule"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testImplementationGutterSkipsACompiledMacroMember() {
        myFixture.configureByText("shape.ex", SHAPE_IMPLS)

        val targets = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .flatMap { markerTargets(ImplementationMarker().getLineMarkerInfo(it)) }

        assertEquals(emptyList<PsiElement>(), targets.filter { it is CallDefinition })
    }

    fun testImplementationGutterLinksACompiledFunctionMember() {
        myFixture.configureByText("count_impls.ex", COUNT_IMPLS)

        val targets = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
            .flatMap { markerTargets(ImplementationMarker().getLineMarkerInfo(it)) }

        assertEquals(listOf("Count"), targets.filterIsInstance<CallDefinition>().map { it.parent.name }.distinct())
    }

    fun testACompiledFunctionImplementsAFunctionMember() {
        val member = source("def size(data)")

        assertEquals(
            listOf("Size.List", "Size.Atom"),
            IMPLEMENTERS.filter { ProtocolImplementation.implementedBy(member, compiled(it, "size")) }
        )
    }

    fun testACompiledPrivateFunctionImplementsNothing() {
        assertFalse(ProtocolImplementation.implementedBy(source("def size(data)"), compiled("Size.Map", "size")))
    }

    fun testACompiledMacroImplementsNothing() {
        val member = source("def size(data)")

        assertFalse(ProtocolImplementation.implementedBy(member, compiled("Size.Tuple", "size")))
        assertFalse(ProtocolImplementation.implementedBy(member, compiled("Size.BitString", "size")))
    }

    fun testNothingImplementsACompiledMacroMember() {
        val member = compiled("Shape", "describe")

        assertTrue("fixture member must be a macro", member.capabilities.quotesArguments)
        assertFalse(ProtocolImplementation.implementedBy(member, compiled("Shape.Map", "describe")))
        assertFalse(ProtocolImplementation.implementedBy(member, source("def describe(shape), do: shape")))
    }

    private fun markerTargets(info: com.intellij.codeInsight.daemon.LineMarkerInfo<*>?): List<PsiElement> =
        (info as? RelatedItemLineMarkerInfo<*>)?.createGotoRelatedItems()?.mapNotNull { it.element }.orEmpty()

    /** What the compiled [module] defines under [name], which each fixture module defines once. */
    private fun compiled(module: String, name: String): Declaration {
        val file = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(File(ebinDirectory, "Elixir.$module.beam"))!!
        val beam = (PsiManager.getInstance(project).findFile(file) as PsiCompiledFile)
            .children
            .filterIsInstance<BeamModule>()
            .single()

        return beam.callDefinitions().single { it.nameArityInterval.name == name }.declaration()
    }

    /** What [code], written in `defmodule M`, defines. */
    private fun source(code: String): Declaration {
        val file = myFixture.configureByText("definition.ex", "defmodule M do\n  $code\nend\n")

        return PsiTreeUtil.findChildrenOfType(file, Call::class.java)
            .filter { DefinitionDeclaration.`is`(it) }
            .mapNotNull { DefinitionDeclaration.of(it, ResolveState.initial()) }
            .single()
    }

    private companion object {
        val IMPLEMENTERS = listOf("Size.List", "Size.Map", "Size.Tuple", "Size.BitString", "Size.Atom")

        const val SHAPE_IMPLS =
            "defimpl Shape, for: List do\n  defmacro describe(shape), do: shape\nend\n\n" +
                "defimpl Shape, for: Tuple do\n  def describe(shape), do: shape\nend\n"
        const val COUNT_IMPLS =
            "defimpl Count, for: List do\n  def count(list), do: length(list)\nend\n\n" +
                "defimpl Count, for: Tuple do\n  defmacro count(tuple), do: tuple\nend\n"
    }
}
