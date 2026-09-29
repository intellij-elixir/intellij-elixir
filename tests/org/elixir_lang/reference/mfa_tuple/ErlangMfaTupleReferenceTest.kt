package org.elixir_lang.reference.mfa_tuple

import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.psi.ElixirAtom
import java.io.File

/**
 * Tests that Erlang-style MFA tuples (`{:math, :sqrt, 1}`) resolve the function atom to the
 * corresponding BEAM module function definition.
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

        val element = validResult!!.element
        assertNotNull("Valid resolve result has null element", element)
        assertTrue(
            "Expected BEAM CallDefinitionImpl, got ${element?.javaClass?.simpleName}",
            element is BeamCallDefinition
        )
        val resolvedName = (element as BeamCallDefinition).nameArityInterval.name
        assertEquals("sqrt", resolvedName)
    }

    /**
     * `resolve()` must return non-null for `:sqrt` in `{:math, :sqrt, 1}`.
     */
    fun testErlangMfaResolveReturnsSingleElement() {
        val reference = atomReferenceAtCaret("erlang_mfa.ex")

        val resolved = reference.resolve()
        assertNotNull("resolve() returned null for Erlang MFA {:math, :sqrt, 1}", resolved)
        assertTrue(
            "Expected BEAM CallDefinitionImpl, got ${resolved?.javaClass?.simpleName}",
            resolved is BeamCallDefinition
        )
        assertEquals("sqrt", (resolved as BeamCallDefinition).nameArityInterval.name)
    }

    /**
     * The reference must be soft so that MFA tuples with unresolvable Erlang modules
     * do not show unresolved-reference errors.
     */
    fun testErlangMfaReferenceIsSoft() {
        val reference = atomReferenceAtCaret("erlang_mfa.ex")

        assertTrue("AtomReference must be soft", reference.isSoft)
    }

    /**
     * `:log` only starts `:log10` and `:log2`, which are different functions, so a compiled module's
     * functions must be filtered to the exact name the same way a source module's are - visibly so at a
     * wrong arity, where no valid result exists to crowd them out.
     */
    fun testErlangMfaFunctionAtomResolvesOnlyItsExactName() {
        val reference = atomReferenceAtCaret("erlang_mfa_exact_name.ex")

        val names = reference.multiResolve(false).map { (it.element as BeamCallDefinition).nameArityInterval.name }

        assertTrue("Expected :log to resolve, got nothing", names.isNotEmpty())
        assertEquals("Expected only exactly-named functions, got $names", setOf("log"), names.toSet())
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
