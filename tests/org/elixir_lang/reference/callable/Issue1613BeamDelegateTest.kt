package org.elixir_lang.reference.callable

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase
import java.io.File

/**
 * Go To Declaration on a call to a function declared by `defdelegate` whose `to:` target is a
 * **compiled** module.
 *
 * [Issue1613Test] pins the source-to-source case; this is the reported one, where the target exists
 * only as `.beam` (`queue.beam` stands in for `:maps`). The gesture must reach the compiled target,
 * not stop at the `defdelegate` head - the head alone would satisfy a non-empty check.
 */
class Issue1613BeamDelegateTest : ExpanderSettingTestCase() {
    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/documentation/erlang_atom_qualifier_hover"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        addBeamLibrary()
    }

    /**
     * The gesture must reach `:queue.new`, not merely reach something.
     *
     * Asserting only that a declaration was found does not discriminate the fix: the head fallback in
     * `FunctionCallReference` makes the result non-empty on its own, so a `Resolver` reverted to main
     * still passes. Measured - reverting only `Resolver.kt` leaves this class green under the weaker
     * assertion.
     */
    fun testQualifiedUsageOfDefdelegateToBeamModuleReachesTheCompiledTarget() {
        myFixture.configureByFile("defdelegate_to_beam_hover.ex")

        val declarations = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { it.destination }

        assertTrue(
            "Go To Declaration from a usage of a function delegated to a compiled module should " +
                "reach a declaration, got none",
            declarations.isNotEmpty()
        )

        val destinations = declarations.joinToString { "${it.javaClass.simpleName} in ${it.containingFile?.originalFile?.name}" }

        assertTrue(
            "Go To Declaration should reach the delegation's compiled target in queue.beam, not stop " +
                "at the defdelegate head. The head alone satisfies a non-empty check, which is why " +
                "this asserts the destination. Got: $destinations",
            declarations.any { it.containingFile?.originalFile is BeamFileImpl }
        )
    }

    /**
     * The control for [testQualifiedUsageOfDefdelegateToBeamModuleReachesTheCompiledTarget].
     *
     * Calling `:queue.new()` directly must navigate. Without this, an empty result from the delegated
     * call is equally consistent with the delegation being broken and with `queue.beam` not being
     * mounted, not exporting `new/0`, or the fixture not resolving at all - and the suite could not
     * tell a fixed plugin from a fixed fixture.
     */
    fun testDirectCallToBeamModuleNavigates() {
        myFixture.configureByFile("direct_beam_call_control.ex")

        val declarations = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { it.destination }

        assertTrue(
            "Go To Declaration on a direct :queue.new() call should reach a declaration, got none - " +
                "the beam library fixture is not usable and the delegation assertion above proves nothing",
            declarations.isNotEmpty()
        )
    }

    private fun addBeamLibrary() {
        val beamFile = File(testDataPath, "queue.beam")
        assertTrue("queue.beam not found at ${beamFile.absolutePath}", beamFile.exists())

        val beamDir = beamFile.parentFile.absolutePath
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, beamDir)

        val beamDirVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(beamDir))
        assertNotNull("Could not find beam test data directory: $beamDir", beamDirVf)

        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY_NAME, listOf(beamDirVf!!))
    }

    companion object {
        private const val LIBRARY_NAME = "defdelegate_beam_goto_test_lib"
    }
}
