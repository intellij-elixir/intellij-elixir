package org.elixir_lang.facet

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.sdk.PushedVersions
import org.elixir_lang.sdk.SdkFixtures
import java.util.concurrent.Callable

/** On a small IDE, a file of the SDK an Elixir facet names is read with that SDK, though no module refers to it. */
class FacetModelSdkFileTest : SmallIdeFacetTestCase() {
    /** A facet naming an SDK of an Elixir older than [ElixirLanguageLevel.FALLBACK], and that SDK's source file. */
    private fun sdkFile(moduleName: String): PsiFile {
        val roots = models.roots(moduleName, testRootDisposable)
        val sdk = models.register("Facet Model SDK File Test $moduleName", roots, SdkFixtures.elixirHome("1.13.0"))
        assertEquals(
            "precondition: the SDK's version is read",
            ElixirLanguageLevel.parse("1.13.0"),
            PushedVersions.languageLevel(sdk),
        )
        FacetModelFixtures.nameFacet(module, sdk.name)
        settle()

        return ReadAction.nonBlocking(Callable { PsiManager.getInstance(project).findFile(roots.source)!! })
            .executeSynchronously()
    }

    fun testAnSdkFilesLanguageLevelIsItsModels() {
        val file = sdkFile("FacetModelSdkFileTestLevel")

        val level = ReadAction.nonBlocking(Callable { ElixirLanguageLevelResolver.languageLevelFor(file) })
            .executeSynchronously()

        assertEquals(
            "the SDK's file is not read at its SDK's language level",
            ElixirLanguageLevel.parse("1.13.0"),
            level,
        )
    }

    fun testAnSdkFileHasTheModelsExpanderInputs() {
        val file = sdkFile("FacetModelSdkFileTestInputs")

        val answer = ReadAction.nonBlocking(Callable { project.service<SdkInputs>().inputsFor(file) })
            .executeSynchronously()

        assertFalse("the SDK's file is expanded with no SDK", answer is SdkInputs.Answer.NoSdk)
    }
}
