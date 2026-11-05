package org.elixir_lang.reference.resolver

import com.intellij.codeInsight.daemon.SyntheticPsiFileSupport
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.FacetModelFixtures
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import java.io.File
import java.util.concurrent.Callable

class ScopeUtilTest : PlatformTestCase() {

    private val source =
        """
        defmodule Foo do
        end
        """.trimIndent()

    /** A real file resolves to its module's narrow scope, not the global fallback. */
    fun testNormalFileResolvesNarrowModuleScope() {
        val psiFile = myFixture.configureByText("foo.ex", source)
        val realVirtualFile = psiFile.virtualFile

        val scope = ReadAction.nonBlocking(Callable {
            narrowedScope(elementIn(psiFile), project)
        }).executeSynchronously()

        assertNotSame(GlobalSearchScope.allScope(project), scope)
        assertTrue("Module scope should contain the module's own file", scope.contains(realVirtualFile))
    }

    /**
     * Reproduces the commit / VCS diff view as observed in the debugger: the PSI is backed by a
     * [com.intellij.testFramework.LightVirtualFile] surfaced through `originalFile.virtualFile`
     * (event system enabled). That light file belongs to no module, so [narrowedScope] must map it
     * back to the real file via the marked original URL instead of falling back to
     * [GlobalSearchScope.allScope].
     */
    fun testOutsiderDiffFileWithBackingVirtualFileRecoversModuleScope() {
        assertRecoversModuleScope(eventSystemEnabled = true)
    }

    /** Same recovery when the backing light file is only reachable via `viewProvider.virtualFile`. */
    fun testOutsiderDiffFileWithoutBackingVirtualFileRecoversModuleScope() {
        assertRecoversModuleScope(eventSystemEnabled = false)
    }

    private fun assertRecoversModuleScope(eventSystemEnabled: Boolean) {
        val realVirtualFile = myFixture.configureByText("foo.ex", source).virtualFile

        val outsiderFile = PsiFileFactory.getInstance(project)
            .createFileFromText("foo.ex", ElixirLanguage, source, eventSystemEnabled, false)
        val backingFile = outsiderFile.viewProvider.virtualFile
        assertFalse("Precondition: backing file is not a real local file", backingFile.isInLocalFileSystem)
        SyntheticPsiFileSupport.markFileWithUrl(backingFile, realVirtualFile.url)

        val scope = ReadAction.nonBlocking(Callable {
            narrowedScope(elementIn(outsiderFile), project)
        }).executeSynchronously()

        assertNotSame(
            "Should narrow to the recovered module instead of allScope",
            GlobalSearchScope.allScope(project),
            scope
        )
        assertTrue("Recovered module scope should contain the original file", scope.contains(realVirtualFile))
    }

    /** A synthetic file with no recoverable on-disk original keeps the global fallback. */
    fun testUnmarkedSyntheticFileFallsBackToAllScope() {
        val syntheticFile = PsiFileFactory.getInstance(project)
            .createFileFromText("foo.ex", ElixirLanguage, source, false, false)

        val scope = ReadAction.nonBlocking(Callable {
            narrowedScope(elementIn(syntheticFile), project)
        }).executeSynchronously()

        assertSame(GlobalSearchScope.allScope(project), scope)
    }

    /** An injected fragment's own light file is never marked, so only its host leads back to the file on disk. */
    fun testInjectedFragmentInDiffFileRecoversModuleScope() {
        val markdown = "```elixir\nFoo\n```\n"
        val realVirtualFile = myFixture.configureByText("foo.md", markdown).virtualFile

        val outsiderFile = PsiFileFactory.getInstance(project)
            .createFileFromText("foo.md", MarkdownLanguage.INSTANCE, markdown, true, false)
        SyntheticPsiFileSupport.markFileWithUrl(outsiderFile.viewProvider.virtualFile, realVirtualFile.url)

        val scope = ReadAction.nonBlocking(Callable {
            val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(outsiderFile, markdown.indexOf("Foo"))
            assertEquals("Precondition: Elixir is injected into the code block", ElixirLanguage, injected?.language)

            narrowedScope(injected!!, project)
        }).executeSynchronously()

        assertNotSame(
            "Should narrow to the host's recovered module instead of allScope",
            GlobalSearchScope.allScope(project),
            scope
        )
        assertTrue("Recovered module scope should contain the original file", scope.contains(realVirtualFile))
    }

    /** Only a marked URL leads back to a file on disk: a light file's path does not say it shows that file. */
    fun testUnmarkedLightFileIsNotMappedByItsPath() {
        val directory = FileUtil.createTempDirectory("scope_util", null)
        File(directory, "foo.ex").writeText(source)
        val localDirectory = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        val lightFile = object : LightVirtualFile("foo.ex", ElixirFileType.INSTANCE, source) {
            override fun getParent(): VirtualFile = localDirectory
        }
        assertNotNull(
            "Precondition: the light file's path names a file on disk",
            LocalFileSystem.getInstance().refreshAndFindFileByPath(lightFile.path)
        )

        val effectiveFile = ReadAction.nonBlocking(Callable {
            effectiveVirtualFile(elementIn(PsiManager.getInstance(project).findFile(lightFile)!!))
        }).executeSynchronously()

        assertSame(lightFile, effectiveFile)
    }

    /** On a small IDE, a module file's narrowed scope holds the SDK its facet names, which no order entry carries. */
    fun testASmallIdesFacetModelIsInTheNarrowedScope() {
        val roots = onASmallIdeWithAFacetModel("ScopeUtilTestNarrowed")
        val psiFile = myFixture.configureByText("foo.ex", source)

        val scope = ReadAction.nonBlocking(Callable { narrowedScope(elementIn(psiFile), project) })
            .executeSynchronously()

        assertTrue("the facet's SDK's source is not in the narrowed scope", scope.contains(roots.source))
        assertTrue("the facet's SDK's beam is not in the narrowed scope", scope.contains(roots.beam))
    }

    /** On a small IDE, a module file's resolve scope holds the SDK its facet names. */
    fun testAFacetModulesResolveScopeHoldsItsModel() {
        val roots = onASmallIdeWithAFacetModel("ScopeUtilTestResolve")
        val psiFile = myFixture.configureByText("foo.ex", source)

        val scope = ReadAction.nonBlocking(Callable { psiFile.resolveScope }).executeSynchronously()

        assertTrue("the facet's SDK's source is not in the resolve scope", scope.contains(roots.source))
        assertTrue("the facet's SDK's beam is not in the resolve scope", scope.contains(roots.beam))
    }

    /** A small IDE whose module's facet names an SDK with [moduleName]'s roots, all undone at tear-down. */
    private fun onASmallIdeWithAFacetModel(moduleName: String): FacetModelFixtures.Roots {
        val models = FacetModelFixtures()
        models.onSmallIde(project, testRootDisposable)
        val roots = models.roots(moduleName, testRootDisposable)
        FacetModelFixtures.nameFacet(module, models.register("Scope Util Test $moduleName", roots).name)
        FacetModelFixtures.settle(project)

        return roots
    }

    private fun elementIn(file: PsiFile): PsiElement =
        file.findElementAt(source.indexOf("Foo")) ?: file
}
