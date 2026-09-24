package org.elixir_lang.psi

import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.stub.index.ModularName
import com.intellij.psi.stubs.StubIndex

/** The implicit `import Kernel` of a compiled `Kernel`, as an SDK provides it. */
class ImplicitImportTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28"

    /** The walk stops once its processor has what it needs, and says so, as a resolve stops at the first valid result. */
    fun testTheWalkOfACompiledKernelStopsWhenToldTo() {
        myFixture.copyFileToProject("Elixir.Kernel.beam")
        val kernel = StubIndex.getElements(
            ModularName.KEY, "Kernel", project, GlobalSearchScope.allScope(project), NamedElement::class.java
        ).filterIsInstance<BeamModule>().single()
        var processed = 0

        val keepProcessing = Import.treeWalkUpImplicitly(kernel, ResolveState.initial()) { _, _ -> processed++; false }

        assertEquals(false to 1, keepProcessing to processed)
    }
}
