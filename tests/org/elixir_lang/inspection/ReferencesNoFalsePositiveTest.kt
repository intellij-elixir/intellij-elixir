package org.elixir_lang.inspection

import org.elixir_lang.beam.BeamLibraryTestCase
import java.io.File

/**
 * Two false positives `References` reported on entirely correct code (originally #4151), fixed
 * alongside the wrong-arity message change so `References` could be enabled by default without
 * surfacing them to real users. Both need a real `Kernel` on the classpath, since `checkHighlighting`
 * exercises the whole file and `References` also visits the file's own `defmodule` call - without a
 * `Kernel` to resolve `defmodule` against, that call alone would fail regardless of either fix.
 */
class ReferencesNoFalsePositiveTest : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    /** `Kernel.apply/3` is a real function; calling it must not be reported as unresolved. */
    fun testApplyThreeWithRealFunctionIsNotFlagged() {
        myFixture.configureByFiles("apply_three.ex")
        myFixture.checkHighlighting()
    }

    /**
     * Elixir normalises identifiers to NFC, so a declaration written with a base character plus a
     * combining mark (`c` + COMBINING ACUTE ACCENT) and a call written with the precomposed
     * character (`ć`) name the same function. Calling it must not be reported as unresolved.
     */
    fun testPrecomposedCallReachesDecomposedDeclaration() {
        myFixture.configureByFiles("decomposed_identifier.ex")
        myFixture.checkHighlighting()
    }

    /** A `defdelegate` head is a declaration too, and its name is compared the same way. */
    fun testPrecomposedCallReachesADecomposedDelegateHead() {
        myFixture.configureByFiles("decomposed_delegate.ex")
        myFixture.checkHighlighting()
    }

    override val ebinDirectory: File
        get() = File("testData/org/elixir_lang/mockSdk-1.0.4/lib/elixir/ebin").absoluteFile

    override fun getTestDataPath(): String = "testData/org/elixir_lang/inspection/references_no_false_positive"
}
