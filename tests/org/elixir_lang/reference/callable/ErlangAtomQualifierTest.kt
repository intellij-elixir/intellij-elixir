package org.elixir_lang.reference.callable

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.code_insight.parameterInfoSignaturesAtCaret
import java.io.File

/**
 * Tests that Erlang modules referenced via atom qualifier syntax (`:module.function()`)
 * resolve correctly when the module comes from a BEAM file (not Elixir source).
 *
 * Uses a real `math.beam` file (Erlang stdlib) as a library class root to exercise
 * the BEAM decompilation -> stub indexing -> atom reference resolution path.
 *
 * The BEAM-decompiled `BeamModule` must not be filtered out by the `as? Call` cast
 * in `PsiReference.maybeModularNameToModulars()`.
 */
class ErlangAtomQualifierTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = ERLANG_STDLIB_EBIN

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/reference/callable/erlang_atom_qualifier"

    /**
     * `:math.sqrt(2)` - the reference on `sqrt` should resolve to the `sqrt` function
     * defined in the `:math` BEAM module.
     */
    fun testErlangAtomQualifiedCallResolves() {
        myFixture.configureByFiles("erlang_atom_qualifier.ex")

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
        assertNotNull("No element at caret", elementAtCaret)

        val grandParent = elementAtCaret!!.parent.parent
        assertNotNull(grandParent)

        val reference = grandParent.reference
        assertNotNull("No reference on qualified call", reference)

        val resolved = reference!!.resolve()
        assertNotNull(
            "Erlang atom-qualified call :math.sqrt(2) did not resolve - " +
            "the function reference within a BEAM module should resolve",
            resolved
        )
    }

    /** Parameter info shows on the EDT, where decompiling a large module takes hundreds of milliseconds. */
    fun testShowingParameterInfoAtTheCallDecompilesNothing() {
        myFixture.configureByText(
            "erlang_atom_qualifier.ex",
            "defmodule ErlangAtomQualifierTest do\n  def test do\n    :math.sqrt(<caret>2)\n  end\nend\n"
        )
        val beams = ebinDirectory.listFiles { file -> file.extension == "beam" }!!.map { file ->
            myFixture.psiManager.findFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!) as BeamFileImpl
        }
        fun decompiled() = beams.filter { it.cachedMirror != null }.map { it.name }
        assertEmpty("decompiled before parameter info", decompiled())

        assertNotEmpty(myFixture.parameterInfoSignaturesAtCaret())
        assertEmpty("decompiled by parameter info", decompiled())
    }

    /**
     * `:math.sqrt(2)` - multiResolve on the qualified call should produce results.
     */
    fun testErlangAtomQualifiedCallMultiResolves() {
        myFixture.configureByFiles("erlang_atom_qualifier.ex")

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
        assertNotNull("No element at caret", elementAtCaret)

        val grandParent = elementAtCaret!!.parent.parent
        assertNotNull(grandParent)

        val reference = grandParent.reference
        assertNotNull("No reference on qualified call", reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)

        val resolveResults = (reference as PsiPolyVariantReference).multiResolve(false)
        assertTrue(
            "Erlang atom-qualified call :math.sqrt(2) produced 0 multiResolve results - " +
            "BEAM module function lookup failed",
            resolveResults.isNotEmpty()
        )
    }

    /**
     * The atom `:math` in `:math.sqrt(2)` should itself resolve to the :math module definition.
     * This tests the atom reference resolver (Exact.kt -> AllName index) independently
     * of the qualified call resolution.
     *
     * This was the core bug: the atom reference finds the BEAM ModuleImpl via the stub index,
     * but PsiReference.maybeModularNameToModulars() discarded it with `as? Call` because
     * ModuleImpl does not implement Call.
     */
    fun testErlangAtomQualifierReferencesModule() {
        myFixture.configureByFiles("erlang_atom_qualifier_on_atom.ex")

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
        assertNotNull("No element at caret", elementAtCaret)

        // The caret is on `math` inside `:math` - parent is the ElixirAtom
        val atom = elementAtCaret!!.parent
        assertNotNull(atom)

        val reference = atom.reference
        assertNotNull("No reference on :math atom", reference)

        val resolved = reference!!.resolve()
        assertNotNull(
            "Atom :math did not resolve to the :math module - " +
            "BEAM ModuleImpl is likely being discarded by `as? Call` cast in " +
            "PsiReference.maybeModularNameToModulars()",
            resolved
        )
    }

}
