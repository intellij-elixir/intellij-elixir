package org.elixir_lang.language_level

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.sdk.PushedVersions
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import org.jetbrains.annotations.TestOnly

/**
 * Resolves the [ElixirLanguageLevel] a given element is written for.
 *
 * Quoting recurses through the parameterless `Quotable.quote()`, so a child inherits nothing from
 * its parent's call, and consumers call `quote()` on arbitrary nodes rather than only on a file
 * root. The language level therefore has to be derivable from any element on its own, which is what this
 * resolves: element to containing file to module to Elixir SDK to version.
 */
object ElixirLanguageLevelResolver {
    /**
     * Set by tests that must exercise a specific language level. See [overrideLanguageLevel] for why this exists
     * at all rather than the test reading the version the way production does.
     */
    private val OVERRIDE_KEY = Key.create<ElixirLanguageLevel>("ELIXIR_LANGUAGE_LEVEL_OVERRIDE")

    private val CACHE_KEY = Key.create<CachedValue<ElixirLanguageLevel>>("ELIXIR_LANGUAGE_LEVEL")

    /**
     * The language level for [element], or [ElixirLanguageLevel.FALLBACK] when its Elixir version cannot be
     * determined.
     */
    @RequiresReadLock
    @JvmStatic
    fun languageLevelFor(element: PsiElement): ElixirLanguageLevel {
        // Before the read-access assertion below, and before touching the module model: an override
        // needs neither, which is what lets the parser tests - light fixtures with a mock project
        // that has no module, no SDK and no ProjectFileIndex - resolve a language level at all.
        element.project.getUserData(OVERRIDE_KEY)?.let { return it }

        val file = element.containingFile ?: return ElixirLanguageLevel.FALLBACK

        return CachedValuesManager.getCachedValue(file, CACHE_KEY) {
            // Invalidated on root changes, so pointing a module at a different Elixir SDK - or
            // changing that SDK's home - re-resolves rather than serving the old language level for the
            // rest of the session.
            CachedValueProvider.Result.create(
                resolve(file),
                ProjectRootModificationTracker.getInstance(file.project),
                SdkVersionsStore.getInstance(),
            )
        }
    }

    /** Whether [feature] applies at [element]'s language level. */
    @RequiresReadLock
    @JvmStatic
    fun isAvailable(feature: ElixirLanguageFeature, element: PsiElement): Boolean =
        feature.isSufficient(languageLevelFor(element))

    @RequiresReadLock
    private fun resolve(file: PsiFile): ElixirLanguageLevel {
        ThreadingAssertions.assertReadAccess()
        pushed(file)?.let { return it }
        val sdk = ElixirSdkLookup.resolve(file).sdk ?: return ElixirLanguageLevel.FALLBACK

        return PushedVersions.languageLevelOf(sdk)
            ?: PushedVersions.unpairedLanguageLevelOf(sdk)
            ?: ElixirLanguageLevel.FALLBACK
    }

    /** Indexing parses a copy in a `LightVirtualFile`, which is in no directory, so its original's is read. */
    @Suppress("UnstableApiUsage")
    private fun pushed(file: PsiFile): ElixirLanguageLevel? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val original = (virtualFile as? LightVirtualFile)?.originalFile ?: virtualFile

        return PushedVersions.decode(PushedVersions.KEY.getPersistentValue(original.parent))
    }

    /**
     * Forces [languageLevel] for every element in [project], or clears the override when it is null.
     *
     * The parser tests run against light fixtures with no Elixir SDK, so production resolution would
     * always reach [ElixirLanguageLevel.FALLBACK] and every CI leg would test the same language level no matter
     * which Elixir it ran against. They set this instead, from the `ELIXIR_VERSION` the build
     * already exports to the test JVM.
     *
     * The env var stays on the test side of that seam on purpose: it is a build artefact, and a
     * production code path that read it would be a test-only backdoor in shipped code of exactly
     * the kind that later gets depended on.
     */
    @TestOnly
    @JvmStatic
    fun overrideLanguageLevel(project: Project, languageLevel: ElixirLanguageLevel?) {
        project.putUserData(OVERRIDE_KEY, languageLevel)
    }
}
