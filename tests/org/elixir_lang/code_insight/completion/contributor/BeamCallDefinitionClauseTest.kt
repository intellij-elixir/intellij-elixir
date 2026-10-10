package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.vfs.LocalFileSystem
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import java.io.File

/**
 * Regression tests for BEAM (decompiled `.beam`) completion.
 *
 * Three fixed bugs:
 *
 * 1. **`Variants.execute(BeamCallDefinition)`** was a no-op: the always-false `element is Named`
 *    guard meant exported BEAM functions never appeared in unqualified scope-walk completion after
 *    `import SomeBeamModule`.  Tested by [testBeamExportedFunctionAppearsInUnqualifiedCompletion].
 *
 * 2. **`element_renderer.CallDefinitionClause`** had no branch for `BeamCallDefinition`, so the
 *    tail text was missing from BEAM completion items.  Tested by
 *    [testBeamExportedFunctionRendersWithParameters].
 *
 * 3. The same renderer set no icon for `BeamCallDefinition`, so BEAM items rendered without the
 *    function/macro + visibility icon that source items get.  Tested by
 *    [testBeamExportedFunctionRendersWithIcon].
 *
 * Plus a guard, [testSourcePreferredOverBeamInUnqualifiedCompletion], that pins the
 * source-over-decompiled preference for unqualified `import` completion.
 */
class BeamCallDefinitionClauseTest : PlatformTestCase() {

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/code_insight/completion/contributor/call_definition_clause/beam_function"

    /**
     * Adds this suite's own `ebin/Elixir.Code.beam` as a library CLASSES root.
     * `ebin/` contains only the `.beam` file (its companion `code.ex` lives in `lib/`, which is
     * NOT added here), so completion resolves through `BeamCallDefinition` stubs rather than source
     * PSI.  Uses the test method name to produce a unique library name, avoiding collisions across
     * tests in the same light-project instance.
     */
    private fun addBeamLibrary(): String {
        val ebinDir = File(testDataPath, "ebin").absoluteFile

        val ebinVirtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebinDir)
        assertNotNull("Could not find ebin/ test data directory in VFS", ebinVirtualDir)

        // Include test name to guarantee uniqueness within a shared light project.
        val libraryName = "beam-$name"

        BeamLibraryFixture.addLibrary(project, myFixture.module, libraryName, listOf(ebinVirtualDir!!))

        return libraryName
    }

    /**
     * Adds this suite's own library with BOTH roots:
     *   - CLASSES root -> ebin/ (contains Elixir.Code.beam, the decompiled stubs)
     *   - SOURCES root -> lib/  (contains code.ex, the real source for the same Code module)
     *
     * This reproduces a real SDK layout where source and decompiled forms of the same module
     * coexist.  Used to assert that unqualified completion prefers source over decompiled,
     * mirroring the resolution behaviour verified by PreferSourceOverDecompiledTest.
     */
    private fun addBeamLibraryWithSource(): String {
        val ebinDir = File(testDataPath, "ebin").absoluteFile
        val libDir = File(testDataPath, "lib").absoluteFile

        val ebinVirtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebinDir)
        assertNotNull("Could not find ebin/ directory in VFS", ebinVirtualDir)
        val libVirtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(libDir)
        assertNotNull("Could not find lib/ directory in VFS", libVirtualDir)

        // Include test name to guarantee uniqueness within a shared light project.
        val libraryName = "beam-source-$name"

        BeamLibraryFixture.addLibrary(
            project,
            myFixture.module,
            libraryName,
            listOf(ebinVirtualDir!!),
            listOf(libVirtualDir!!),
        )

        return libraryName
    }

    /**
     * When both the source (`code.ex`) and decompiled (`Elixir.Code.beam`) forms of the `Code`
     * module are in scope, UNQUALIFIED completion after `import Code` must NOT offer the BEAM
     * (`BeamCallDefinition`) variants alongside the source clauses - exactly like resolution
     * prefers source over decompiled (see PreferSourceOverDecompiledTest).
     *
     * Regression guard for the source-over-decompiled preference at the *module-alias resolution*
     * layer, which is what dedups the unqualified `import` path (a different and earlier point than
     * the qualified `provider.CallDefinitionClause`, which filters BEAM stubs itself).  The `import`
     * scope walk only visits the modulars that `Import.modulars` returns, and `import Code` resolves
     * the `Code` alias via `QualifiableAlias.maybeModularNameToModulars` -> `reference.multiResolve`,
     * which returns only the source `defmodule Code` when source exists.  As a result the walk takes
     * the source `executeOnCallDefinitionClause(Call)` path and never reaches
     * `Variants.execute(BeamCallDefinition)` for `Code`, so no BEAM lookup elements are produced.
     *
     * This test should fail only if that alias-resolution preference regresses (e.g. the alias
     * starts resolving to both the source and BEAM modulars), which would let BEAM duplicates leak
     * into unqualified completion.
     */
    fun testSourcePreferredOverBeamInUnqualifiedCompletion() {
        addBeamLibraryWithSource()

        myFixture.configureByFiles("unqualified_usage.ex")
        val lookupElements: Array<LookupElement>? = myFixture.complete(CompletionType.BASIC, 1)
        assertNotNull(NO_POPUP_MESSAGE, lookupElements)

        val stringToQuotedElements = lookupElements!!.filter { it.lookupString == "string_to_quoted" }
        assertFalse(
            "Expected at least one 'string_to_quoted' completion from source code.ex",
            stringToQuotedElements.isEmpty()
        )

        val beamElements = stringToQuotedElements.filter { it.psiElement is BeamCallDefinition }
        assertTrue(
            "Unqualified completion offered BEAM-decompiled 'string_to_quoted' variants " +
                "(${beamElements.size}) even though source code.ex is in scope; source should be " +
                "preferred over decompiled, as it is during resolution",
            beamElements.isEmpty()
        )
    }

    /**
     * Exported functions from a BEAM module must appear in UNQUALIFIED completion after
     * `import Code`.  This exercises `Variants.execute(BeamCallDefinition)` - the method
     * that was previously a no-op due to the always-false `element is Named` guard.
     *
     * Without the fix, the scope walker visits each `BeamCallDefinition` from the imported
     * BEAM module but returns `true` without adding it to the lookup, so no BEAM functions
     * appear in unqualified completion.  With the fix, exported functions are added.
     *
     * `Code.string_to_quoted` is a stable exported function present in every Elixir release.
     */
    fun testBeamExportedFunctionAppearsInUnqualifiedCompletion() {
        addBeamLibrary()

        myFixture.configureByFiles("unqualified_usage.ex")
        val lookupElements: Array<LookupElement>? = myFixture.complete(CompletionType.BASIC, 1)
        assertNotNull(NO_POPUP_MESSAGE, lookupElements)

        val strings = lookupElements!!.map { it.lookupString }
        assertTrue(
            "Expected 'string_to_quoted' (from Elixir.Code.beam) in unqualified completions " +
                "after `import Code` - BEAM functions missing from scope-walk Variants path",
            strings.contains("string_to_quoted")
        )
    }

    /**
     * The lookup element for a BEAM-decompiled function renders its parameters as tail text, the way a
     * source-defined function does.
     */
    fun testBeamExportedFunctionRendersWithParameters() {
        addBeamLibrary()

        myFixture.configureByFiles("usage.ex")
        val lookupElements: Array<LookupElement>? = myFixture.complete(CompletionType.BASIC, 1)
        assertNotNull(NO_POPUP_MESSAGE, lookupElements)

        val presentations = lookupElements!!
            .filter { it.lookupString == "string_to_quoted" }
            .map { element -> LookupElementPresentation().also { element.renderElement(it) } }

        assertEquals(listOf("string_to_quoted", "string_to_quoted"), presentations.map { it.itemText })
        assertSameElements(presentations.map { it.tailText }, "(string)", "(string, opts)")
    }

    /**
     * `Code.eval_string(string, binding \\ [], opts \\ [])` is offered once for each arity it answers to, and each
     * names the parameters a call at that arity passes: the head without the defaults it leaves out.
     */
    fun testBeamFunctionWithDefaultsRendersEachArityWithTheParametersItPasses() {
        addBeamLibrary()

        myFixture.configureByFiles("usage.ex")
        val lookupElements: Array<LookupElement>? = myFixture.complete(CompletionType.BASIC, 1)
        assertNotNull(NO_POPUP_MESSAGE, lookupElements)

        val tails = lookupElements!!
            .filter { it.lookupString == "eval_string" }
            .map { element -> LookupElementPresentation().also { element.renderElement(it) }.tailText }

        assertSameElements(tails, "(string)", "(string, binding)", "(string, binding, opts)")
    }

    /** Accepting the arity with one default left out inserts the parameters of a call at that arity. */
    fun testBeamFunctionWithDefaultsInsertsTheParametersOfTheArityChosen() {
        addBeamLibrary()

        myFixture.configureByFiles("usage.ex")
        val lookupElements = myFixture.complete(CompletionType.BASIC, 1)!!
        val twoArguments = lookupElements.first { element ->
            element.lookupString == "eval_string" &&
                LookupElementPresentation().also { element.renderElement(it) }.tailText == "(string, binding)"
        }

        myFixture.lookup.currentItem = twoArguments
        myFixture.finishLookup('\n')

        assertTrue(
            "Expected `Code.eval_string(string, binding)` inserted, got: ${myFixture.editor.document.text}",
            myFixture.editor.document.text.contains("Code.eval_string(string, binding)")
        )
    }

    /**
     * The lookup element for a BEAM-decompiled function must render with an icon, just like a
     * source-defined function does (the source path forwards `ItemPresentation.getIcon` via
     * `renderItemPresentation`).  `renderBeamCallDefinition` builds the equivalent RowIcon
     * (time/function-vs-macro, visibility, call-definition-clause) so the popup is visually
     * consistent and keeps those cues.
     */
    fun testBeamExportedFunctionRendersWithIcon() {
        addBeamLibrary()

        myFixture.configureByFiles("usage.ex")
        val lookupElements: Array<LookupElement>? = myFixture.complete(CompletionType.BASIC, 1)
        assertNotNull(NO_POPUP_MESSAGE, lookupElements)

        val element = lookupElements!!.firstOrNull { it.lookupString == "string_to_quoted" }
        assertNotNull("No lookup element for 'string_to_quoted' - BEAM exported functions missing from completion", element)

        val presentation = LookupElementPresentation()
        element!!.renderElement(presentation)

        assertNotNull(
            "Expected a non-null icon for BEAM completion item 'string_to_quoted' - " +
                "renderBeamCallDefinition should set the same icon as the source render path",
            presentation.icon
        )
    }

    companion object {
        /**
         * `myFixture.complete()` returns `null` when the result set narrows to a single element and
         * the platform auto-inserts it instead of showing the popup.  These tests rely on >= 2
         * candidates surviving the prefix; if that ever stops holding, fail with a message that
         * points at auto-insertion rather than a misleading "no completions" diagnosis.
         */
        private const val NO_POPUP_MESSAGE =
            "No completion popup - a single match was auto-inserted; broaden the fixture/prefix so " +
                "at least two candidates remain"
    }
}
