package org.elixir_lang.code_insight

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import java.io.File

/**
 * Parameter hints for calls whose definition is in a `.beam` rather than in source. The expected names are
 * the ones the decompiler shows for the first clause.
 */
class BeamParameterInfoTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/code_insight/parameter_info/beam"

    fun testErlangFunction() {
        assertEquals(listOf("l"), signaturesAtCaret("erlang_function.ex"))
    }

    /** `in_r/2` also resolves from `:queue.in`, and is not the function being called. */
    fun testErlangPatternParameters() {
        assertEquals(listOf("x, {[_] = erlangVariableIn, []}"), signaturesAtCaret("erlang_pattern_parameters.ex"))
    }

    fun testErlangZeroArity() {
        assertEquals(listOf("<no parameters>"), signaturesAtCaret("erlang_zero_arity.ex"))
    }

    fun testElixirFunction() {
        assertEquals(listOf("module"), signaturesAtCaret("elixir_function.ex"))
    }

    /** Each arity a default argument generates is an export of the `.beam`, and the hint is the one head. */
    fun testElixirDefaultArguments() {
        assertEquals(
            listOf("string, binding \\\\ [], opts \\\\ []"),
            signaturesAtCaret("elixir_default_arguments.ex")
        )
    }

    fun testAMacroWithADefaultDescribedByDocsShowsItsHead() =
        assertEquals(listOf("q, x \\\\ nil"), shownFromGenerated("DocsDefaults.snoc(<caret>)"))

    fun testAMacroWithADefaultDescribedByDebugInfoShowsItsHead() =
        assertEquals(listOf("q, x \\\\ nil"), shownFromGenerated("DebugDefaults.snoc(<caret>)"))

    fun testADefaultInTheMiddleDescribedByDocsShowsItsHead() =
        assertEquals(listOf("a, b \\\\ 1, c, d \\\\ 2"), shownFromGenerated("DocsDefaults.h(<caret>)"))

    fun testADefaultInTheMiddleDescribedByDebugInfoShowsItsHead() =
        assertEquals(listOf("a, b \\\\ 1, c, d \\\\ 2"), shownFromGenerated("DebugDefaults.h(<caret>)"))

    fun testAnImportOfOneArityDescribedByDocsShowsTheHead() =
        assertEquals(listOf("q, x \\\\ nil"), shownFromGeneratedImport("DocsDefaults"))

    fun testAnImportOfOneArityDescribedByDebugInfoShowsTheHead() =
        assertEquals(listOf("q, x \\\\ nil"), shownFromGeneratedImport("DebugDefaults"))

    /** A module with neither debug info nor `Docs` has no head to show: one signature per arity. */
    fun testAFunctionWithNoNamesKeepsOneSignatureForEachArity() =
        assertEquals(
            listOf("arg1", "arg1, arg2"),
            shownFromGenerated("NoNamesDefaults.d(<caret>)").sortedBy { it.length }
        )

    /**
     * Resolution prefers a source module over a `.beam` of the same name, so the hint describes only the source
     * definition rather than both.
     */
    fun testSourcePreferredOverBeam() {
        assertEquals(
            listOf("module_or_path"),
            signaturesAtCaret("elixir_function.ex", "source_over_beam_declaration.ex")
        )
    }

    fun testSourcePreferredOverBeamWhenImported() {
        assertEquals(
            listOf("module_or_path"),
            signaturesAtCaret("source_over_beam_import.ex", "source_over_beam_declaration.ex")
        )
    }

    /** The hint is built on the EDT, where decompiling a large module takes hundreds of milliseconds. */
    fun testHintDoesNotDecompileTheModule() {
        val math = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebinDirectory, "math.beam"))!!
        val beamFile = ReadAction.computeBlocking<BeamFileImpl, Throwable> {
            myFixture.psiManager.findFile(math) as BeamFileImpl
        }
        assertNull("math.beam was decompiled before the hint was asked for", beamFile.cachedMirror)

        assertNotEmpty(signaturesAtCaret("undecompiled.ex"))

        assertNull("Building the hint decompiled math.beam", beamFile.cachedMirror)
    }

    fun testModuleIndexedTwiceIsDescribedOnce() {
        val copy = File(testDataPath, "ebin_copy").absoluteFile
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, copy.path)
        val copyRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(copy)!!
        BeamLibraryFixture.addLibrary(project, myFixture.module, "beam-copy", listOf(copyRoot))

        assertEquals(1, signaturesAtCaret("undecompiled.ex").size)
    }

    fun testOpeningParenthesisPopsUpTheHint() {
        myFixture.configureByFile("auto_popup_opening_parenthesis.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping('(')

        assertNotNull("Typing an opening parenthesis should pop up the parameter hint", popup)
        assertEquals(listOf("l"), popup!!.signatures)
        assertEquals(0, popup.currentParameterIndex)
    }

    fun testCommaPopsUpTheHint() {
        myFixture.configureByFile("auto_popup_comma.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping(',')

        assertNotNull("Typing a comma should pop up the parameter hint", popup)
        assertEquals(listOf("string, binding \\\\ [], opts \\\\ []"), popup!!.signatures)
        assertEquals(1, popup.currentParameterIndex)
    }

    /** The `.beam`s `GeneratedArgumentsTest` describes by their debug info, their `Docs`, or neither. */
    private fun shownFromGenerated(call: String): List<String> {
        addGeneratedArgumentsLibrary()
        myFixture.configureByText(
            "generated.ex",
            "defmodule Caller do\n  def run(q), do: $call\nend\n"
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private fun shownFromGeneratedImport(module: String): List<String> {
        addGeneratedArgumentsLibrary()
        myFixture.configureByText(
            "generated_import.ex",
            "defmodule Caller do\n  import $module, only: [snoc: 1]\n\n  defmacro run(q), do: snoc(q<caret>)\nend\n"
        )

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private fun addGeneratedArgumentsLibrary() {
        val ebin = File("testData/org/elixir_lang/beam/generated_arguments/ebin").absoluteFile
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, ebin.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)!!
        BeamLibraryFixture.addLibrary(project, myFixture.module, "beam-generated-arguments", listOf(root))
    }

    private fun signaturesAtCaret(vararg paths: String): List<String> {
        val path = paths.first()
        myFixture.configureByFiles(*paths)

        val handler = ParameterInfo()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val arguments = handler.findElementForParameterInfo(context)
        assertNotNull("No Arguments at the caret", arguments)

        handler.showParameterInfo(arguments!!, context)
        val items = context.itemsToShow
        assertFalse("No parameter hint for the call in $path", items.isNullOrEmpty())

        return items!!.map { item ->
            val uiContext = MockParameterInfoUIContext(arguments)
            uiContext.currentParameterIndex = 0
            handler.updateUI(item as Signature, uiContext)
            uiContext.text
        }
    }
}
