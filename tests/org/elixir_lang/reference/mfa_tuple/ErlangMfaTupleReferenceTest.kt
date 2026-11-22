package org.elixir_lang.reference.mfa_tuple

import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.compiledDefinition
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.psi.ElixirAtom
import java.io.File

/**
 * Tests that Erlang-style MFA tuples (`{:math, :sqrt, 1}`) resolve the function atom to the
 * corresponding function definition in the BEAM module's decompiled source.
 *
 * The module is an atom (`:module` as element[0]) rather than an alias, so it resolves to a
 * BEAM-decompiled module rather than Elixir source.
 */
class ErlangMfaTupleReferenceTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = ERLANG_STDLIB_EBIN

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple"

    /**
     * `:sqrt` in `{:math, :sqrt, 1}` should resolve to the `sqrt/1` function
     * defined in the `:math` BEAM module.
     */
    fun testErlangMfaFunctionAtomResolvesFromBeam() {
        val reference = atomReferenceAtCaret("erlang_mfa.ex")

        val resolveResults = reference.multiResolve(false)
        assertTrue(
            "Expected {:math, :sqrt, 1} to resolve - BEAM module lookup failed",
            resolveResults.isNotEmpty()
        )
        val validResult = resolveResults.firstOrNull { it.isValidResult }
        assertNotNull("Expected at least one valid result for :math.sqrt/1", validResult)

        assertEquals("math.beam.decompiled.ex sqrt/1", compiledDefinition(validResult!!.element))
    }

    /**
     * `resolve()` must return non-null for `:sqrt` in `{:math, :sqrt, 1}`.
     */
    fun testErlangMfaResolveReturnsSingleElement() {
        val reference = atomReferenceAtCaret("erlang_mfa.ex")

        val resolved = reference.resolve()
        assertNotNull("resolve() returned null for Erlang MFA {:math, :sqrt, 1}", resolved)
        assertEquals("math.beam.decompiled.ex sqrt/1", compiledDefinition(resolved))
    }

    /**
     * The reference must be soft so that MFA tuples with unresolvable Erlang modules
     * do not show unresolved-reference errors.
     */
    fun testErlangMfaReferenceIsSoft() {
        val reference = atomReferenceAtCaret("erlang_mfa.ex")

        assertTrue("AtomReference must be soft", reference.isSoft)
    }

    private fun atomReferenceAtCaret(fileName: String): AtomReference {
        myFixture.configureByFile(fileName)

        val elementAtCaret = myFixture.file.findElementAt(myFixture.caretOffset)
            ?: error("No element at caret in $fileName")
        val atom = PsiTreeUtil.getParentOfType(elementAtCaret, ElixirAtom::class.java, false)
            ?: error("No ElixirAtom at caret in $fileName")

        @Suppress("UnstableApiUsage")
        return PsiSymbolReferenceService.getService()
            .getReferences(atom)
            .filterIsInstance<AtomReference>()
            .singleOrNull()
            ?: error("Expected AtomReference at caret in $fileName")
    }

}
