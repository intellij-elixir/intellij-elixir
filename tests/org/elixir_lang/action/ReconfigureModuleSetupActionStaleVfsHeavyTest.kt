package org.elixir_lang.action

import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.facet.Type
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * Reproduces what the New Project wizard leaves behind when it creates an umbrella sub-app.
 *
 * `mix new` writes the sub-app from an external process, so the only entries VFS holds for it are
 * the ones the IDE itself touched while setting the project up. `findChild("mix.exs")` then answers
 * null from that cached child list without going to disk, and every folder-mark scan skips the
 * sub-app in silence.
 *
 * Needs a real filesystem, hence [HeavyPlatformTestCase]: the light fixture's `temp://` VFS is
 * in-memory, so writing through [File] there produces no stale entry to reproduce - it produces no
 * entry at all.
 */
class ReconfigureModuleSetupActionStaleVfsHeavyTest : HeavyTestCase() {

    @RequiresReadLock
    fun testAddsMarksForAnUmbrellaSubAppWrittenOutsideVfs() {
        val umbrellaDir = createTempDir("umbrella")
        FileUtil.writeToFile(File(umbrellaDir, "mix.exs"), "")
        // apps/c1/lib exists before VFS first sees the umbrella, standing in for the source path
        // JavaModuleBuilder resolves (and creates) while the wizard is setting the sub-app up.
        File(umbrellaDir, "apps/c1/lib").mkdirs()

        val umbrellaVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(umbrellaDir)
            ?: error("umbrella not found in VFS after refresh")
        VfsUtil.markDirtyAndRefresh(false, true, true, umbrellaVf)

        val module = createModule("umbrella")
        PsiTestUtil.addContentRoot(module, umbrellaVf)
        // The action only touches modules with an Elixir identity; createModule has none.
        FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))

        // The rest of the sub-app arrives behind VFS's back, exactly as `mix new` writes it.
        File(umbrellaDir, "apps/c1/test").mkdirs()
        FileUtil.writeToFile(File(umbrellaDir, "apps/c1/mix.exs"), "")

        val subAppDir = umbrellaVf.findFileByRelativePath("apps/c1")
            ?: error("apps/c1 not found in VFS")
        assertNull(
            "Precondition: VFS must not see the externally written mix.exs, or this test proves nothing",
            subAppDir.findChild("mix.exs")
        )

        runReconfigure(project)

        val entry = ModuleRootManager.getInstance(module).contentEntries
            .single { it.file == umbrellaVf }
        val sourceUrls = entry.sourceFolders.associate { it.url to it.isTestSource }

        assertTrue(
            "apps/c1/lib should be Sources despite the stale VFS entry, got: ${sourceUrls.keys}",
            sourceUrls.any { it.key.endsWith("/apps/c1/lib") && !it.value }
        )
        assertTrue(
            "apps/c1/test should be Test Sources despite the stale VFS entry, got: ${sourceUrls.keys}",
            sourceUrls.any { it.key.endsWith("/apps/c1/test") && it.value }
        )
    }

    override fun tearDown() {
        try {
            val moduleManager = ModuleManager.getInstance(project)
            for (module in moduleManager.modules.filter { it.name == "umbrella" }) {
                val model = moduleManager.getModifiableModel()
                model.disposeModule(module)
                com.intellij.openapi.application.WriteAction.run<Throwable> { model.commit() }
            }
        } finally {
            super.tearDown()
        }
    }
}
