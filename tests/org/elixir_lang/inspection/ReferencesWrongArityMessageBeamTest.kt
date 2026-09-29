package org.elixir_lang.inspection

import org.elixir_lang.beam.BeamLibraryTestCase

/**
 * The wrong-arity message's arities must also name a declaration compiled into a `.beam` library,
 * not only a source `def` - [org.elixir_lang.beam.psi.CallDefinition.nameArityInterval] is read from
 * the stub built off the module's raw export table ([org.elixir_lang.beam.psi.impl.CallDefinitionImpl]),
 * present for every compiled module regardless of what debug info it shipped with, so one compiled
 * backing (Erlang's `:queue`, decompiled from `erl_abst`) stands in for all of them here.
 */
class ReferencesWrongArityMessageBeamTest : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testWrongArityNamesTheBeamDeclaredArity() {
        myFixture.configureByFiles("beam_qualified_wrong_arity.ex")
        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }

        assertTrue(
            "Wrong-arity diagnostic against a compiled Erlang module should name 'new/0': $descriptions",
            descriptions.any { it.contains("new/0") }
        )
    }

    fun testWrongArityNamesAnImportedBeamArity() {
        myFixture.configureByFiles("imported_beam_wrong_arity.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'len/1': $message", message.contains("len/1"))
    }

    /**
     * A local `lenx` only starts with `len`. When every result is invalid, it must not crowd out the
     * compiled declaration of exactly `len` just because it is source.
     */
    fun testWrongArityPrefersAnExactCompiledNameOverASourcePrefix() {
        myFixture.configureByFiles("exact_beam_over_source_prefix.ex")
        val message = wrongArityDescription()

        assertTrue("Should name 'len/1': $message", message.contains("len/1"))
    }

    private fun wrongArityDescription(): String {
        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        return descriptions.firstOrNull { it.startsWith("Only resolves to invalid results") }
            ?: throw AssertionError("No 'Only resolves to invalid results' diagnostic found among: $descriptions")
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/inspection/references_wrong_arity_message"
}
