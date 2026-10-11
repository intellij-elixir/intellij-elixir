package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.mix.library.CONSOLIDATED_LIBRARY_BASE_NAME
import org.elixir_lang.mix.sync.contentRootToken
import org.elixir_lang.mix.sync.scopedDepLibraryName
import org.elixir_lang.sdk.SdkFixtures
import java.io.File
import java.util.concurrent.Callable

/**
 * The copy of a module Elixir loads, in a project laid out as Mix sync leaves it: a dependency's `.beam` built for the
 * asker's Mix env, a protocol's own `.beam` over its consolidated copy, and source where the project's content holds it
 * or no `.beam` exists.
 */
class LoadedCopyTest : PlatformTestCase() {
    fun testALibAskerReadsTheDevBeam() {
        layout(*dependencyFiles(DEP_BEAM, "dev", "test"))
        dependency("dev", "test")

        assertEquals(listOf("app/_build/dev/lib/d/ebin/$DEP_BEAM"), loaded(LIB_ASKER, DEP))
    }

    fun testATestAskerReadsTheTestBeam() {
        layout(*dependencyFiles(DEP_BEAM, "dev", "test"))
        dependency("dev", "test")

        assertEquals(listOf("app/_build/test/lib/d/ebin/$DEP_BEAM"), loaded(TEST_ASKER, DEP))
    }

    fun testAProdOnlyBuildReadsItsBeam() {
        layout(*dependencyFiles(DEP_BEAM, "prod"))
        dependency("prod")

        assertEquals(listOf("app/_build/prod/lib/d/ebin/$DEP_BEAM"), loaded(LIB_ASKER, DEP))
    }

    fun testADependencyWithSourcesOnlyReadsItsSource() {
        layout(*dependencyFiles(DEP_BEAM))
        dependency()

        assertEquals(listOf("app/deps/d/lib/loaded_copy.ex"), loaded(LIB_ASKER, DEP))
    }

    fun testAProjectModuleReadsItsSource() {
        layout(
            "app/lib/app.ex" to fixture(SOURCE),
            "app/_build/dev/lib/app/ebin/$APP_BEAM" to fixture(APP_BEAM),
            "app/_build/test/lib/app/ebin/$APP_BEAM" to fixture(APP_BEAM),
        )

        assertEquals(listOf("app/lib/app.ex"), loaded(LIB_ASKER, APP))
    }

    fun testAnSdkProtocolReadsItsOwnBeam() {
        layout(
            "sdk/ebin/$ENUMERABLE_BEAM" to enumerableBeam(),
            "sdk/lib/enum.ex" to enumerableSource(),
            "app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM" to enumerableBeam(),
            "app/_build/test/lib/app/consolidated/$ENUMERABLE_BEAM" to enumerableBeam(),
        )
        consolidated("app/_build/dev/lib/app/consolidated", "app/_build/test/lib/app/consolidated")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(TEST_ASKER, ENUMERABLE))
        }
    }

    fun testAConsolidatedCopyAloneIsRead() {
        layout(
            "sdk/lib/enum.ex" to enumerableSource(),
            "app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM" to enumerableBeam(),
        )
        consolidated("app/_build/dev/lib/app/consolidated")

        withSdk(classes = null) {
            assertEquals(listOf("app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testADirectoryNamedConsolidatedOutsideMixsConsolidatedLibraryIsKept() {
        layout(
            "sdk/ebin/$ENUMERABLE_BEAM" to enumerableBeam(),
            "sdk/lib/enum.ex" to enumerableSource(),
            "app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM" to enumerableBeam(),
        )
        BeamLibraryFixture.addLibrary(project, module, "app", listOf(file("app/_build/dev/lib/app/consolidated")))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testADependencysProtocolReadsItsOwnBeam() {
        layout(
            *dependencyFiles(PROTOCOL_BEAM, "dev", "test"),
            "app/_build/dev/consolidated/$PROTOCOL_BEAM" to fixture(PROTOCOL_BEAM),
        )
        dependency("dev", "test")
        consolidated("app/_build/dev/consolidated")

        assertEquals(listOf("app/_build/dev/lib/d/ebin/$PROTOCOL_BEAM"), loaded(LIB_ASKER, PROTOCOL))
        assertEquals(listOf("app/_build/test/lib/d/ebin/$PROTOCOL_BEAM"), loaded(TEST_ASKER, PROTOCOL))
    }

    fun testWithoutLoadedADependencyKeepsItsSource() {
        layout(*dependencyFiles(DEP_BEAM, "dev", "test"))
        dependency("dev", "test")

        assertEquals(listOf("app/deps/d/lib/loaded_copy.ex"), resolved(LIB_ASKER, DEP, loaded = false))
    }

    private lateinit var top: File

    override fun setUp() {
        super.setUp()
        top = FileUtil.createTempDirectory("loaded_copy", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, top.path)
    }

    /** `deps/d`'s source, and its [beam] built for each of [envs]. */
    private fun dependencyFiles(beam: String, vararg envs: String): Array<Pair<String, ByteArray>> =
        arrayOf("app/deps/d/lib/loaded_copy.ex" to fixture(SOURCE)) +
            envs.map { "app/_build/$it/lib/d/ebin/$beam" to fixture(beam) }

    /** `deps/d` as Mix sync attaches it: `lib` as SOURCES, each of [envs]' `ebin` as CLASSES, its build excluded. */
    private fun dependency(vararg envs: String) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.single { it.file == file("app") }
            envs.forEach { entry.addExcludeFolder(file("app/_build/$it/lib/d")) }
        }
        BeamLibraryFixture.addLibrary(
            project,
            module,
            "d",
            envs.map { file("app/_build/$it/lib/d/ebin") },
            listOf(file("app/deps/d/lib")),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** [classes] as Mix sync attaches the consolidated protocols: one library, named for `app`'s content root. */
    private fun consolidated(vararg classes: String) {
        val name = scopedDepLibraryName(contentRootToken(project, file("app").url), CONSOLIDATED_LIBRARY_BASE_NAME)
        BeamLibraryFixture.addLibrary(project, module, name, classes.map(::file))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /**
     * Writes [files] and the askers, then attaches `app` as Mix leaves a project: a content root with `lib` and `test`
     * sources and `deps` excluded. The files are on disk before `app` is a content root, so no Mix sync reacts to them.
     */
    private fun layout(vararg files: Pair<String, ByteArray>) {
        (files.asList() + listOf(LIB_ASKER to ASKER, TEST_ASKER to ASKER)).forEach { (path, bytes) ->
            File(top, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }

        ModuleRootModificationUtil.updateModel(module) { model ->
            model.addContentEntry(file("app")).apply {
                addSourceFolder(file("app/lib"), false)
                addSourceFolder(file("app/test"), true)
                addExcludeFolder("${file("app").url}/deps")
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** An Elixir SDK with [classes] and `sdk/lib` as its roots, as the module's SDK while [block] runs. */
    private fun withSdk(classes: String?, block: () -> Unit) {
        val sdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Loaded copy", SdkFixtures.fakeHome("elixir/loaded-copy")),
            testRootDisposable,
        )
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                classes?.let { addRoot(file(it), OrderRootType.CLASSES) }
                addRoot(file("sdk/lib"), OrderRootType.SOURCES)
                commitChanges()
            }
        }
        val previous = ModuleRootManager.getInstance(module).sdk
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            block()
        } finally {
            ModuleRootModificationUtil.setModuleSdk(module, previous)
        }
    }

    private fun loaded(asker: String, name: String): List<String> = resolved(asker, name, loaded = true)

    /** The files of the copies [asker] resolves [name] to, relative to the fixture. */
    private fun resolved(asker: String, name: String, loaded: Boolean): List<String> =
        ReadAction.nonBlocking(Callable {
            val askerFile = PsiManager.getInstance(project).findFile(file(asker))!!
            val base = FileUtil.toSystemIndependentName(top.path)

            Module.resolvePreferred(askerFile, name, incompleteCode = false, inScope = false, loaded = loaded)
                .map { result ->
                    val path = FileUtil.toSystemIndependentName(result.element.containingFile.originalFile.virtualFile.path)

                    FileUtil.getRelativePath(base, path, '/') ?: path
                }
                .sorted()
        }).executeSynchronously()

    private fun file(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(top, path))!!

    private fun fixture(name: String): ByteArray = File(FIXTURES, name).readBytes()

    private fun enumerableBeam(): ByteArray = File(LegManifest.ebin(), ENUMERABLE_BEAM).readBytes()

    private fun enumerableSource(): ByteArray = File(LegManifest.ebin().parentFile, "lib/enum.ex").readBytes()

    private companion object {
        val FIXTURES = File("testData/org/elixir_lang/expander/loaded_copy")
        val ASKER = "defmodule Asker do\nend\n".toByteArray()
        const val SOURCE = "loaded_copy.ex"
        const val LIB_ASKER = "app/lib/asker.ex"
        const val TEST_ASKER = "app/test/asker_test.exs"
        const val APP = "LoadedCopy.App"
        const val APP_BEAM = "Elixir.LoadedCopy.App.beam"
        const val DEP = "LoadedCopy.Dep"
        const val DEP_BEAM = "Elixir.LoadedCopy.Dep.beam"
        const val PROTOCOL = "LoadedCopy.Protocol"
        const val PROTOCOL_BEAM = "Elixir.LoadedCopy.Protocol.beam"
        const val ENUMERABLE = "Enumerable"
        const val ENUMERABLE_BEAM = "Elixir.Enumerable.beam"
    }
}
