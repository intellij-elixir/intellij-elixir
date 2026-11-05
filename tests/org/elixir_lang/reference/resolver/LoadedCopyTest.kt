package org.elixir_lang.reference.resolver

import com.intellij.facet.FacetManager
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.system.OS
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.beam.SdkBeams
import org.elixir_lang.declaration.Use
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.legKernel
import org.elixir_lang.expander.legLevel
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.mix.library.CONSOLIDATED_LIBRARY_BASE_NAME
import org.elixir_lang.mix.sync.contentRootToken
import org.elixir_lang.mix.sync.scopedDepLibraryName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.reference.Resolver
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import java.io.File

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

    fun testAnSdkCopyWinsOverAStaleLibrarysCopy() {
        layout(*sdkAnd("stale/ebin"))
        staleLibrary("stale", "stale/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testAnSdkCopyWinsOverTwoLibraryCopies() {
        layout(*sdkAnd("stale/ebin", "stale2/ebin"))
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testAnSdkCopyWinsOverACopyInAnotherRegisteredSdksRoots() {
        layout(*sdkAnd("stale/ebin"))
        staleLibrary("stale", "stale/ebin")
        registerSdk("Other SDK", "other", "stale/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testAnSdkCopyWinsOverStaleAndConsolidatedCopies() {
        layout(
            *sdkAnd("stale/ebin"),
            "app/_build/dev/lib/app/consolidated/$ENUMERABLE_BEAM" to enumerableBeam(),
        )
        staleLibrary("stale", "stale/ebin")
        consolidated("app/_build/dev/lib/app/consolidated")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testAnSdkCopyWinsForAnErlangModule() {
        val lists = SdkBeams.forSdk(System.getenv("ERLANG_SDK_HOME"), "erlang")
            .single { it.file.name == LISTS_BEAM }
            .file
            .readBytes()

        layout(
            "sdk/ebin/$LISTS_BEAM" to lists,
            "stale/ebin/$LISTS_BEAM" to lists,
            "sdk/lib/enum.ex" to enumerableSource(),
        )
        staleLibrary("stale", "stale/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$LISTS_BEAM"), loaded(LIB_ASKER, ":lists"))
        }
    }

    fun testASmallIdesSdkCopyWins() {
        layout(*sdkAnd("stale/ebin"))
        staleLibrary("Stale", "stale/ebin")
        val sdk = registerSdk("Small IDE", "small-ide", "sdk/ebin")
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(file("sdk/lib"), OrderRootType.SOURCES)
                commitChanges()
            }
        }
        ProcessOutput.isSmallIdeOverride = true
        try {
            FacetModelFixtures.assign(module, sdk)
            FacetModelFixtures.settle(project)

            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        } finally {
            FacetManager.getInstance(module).getFacetByType(Facet.ID)?.let(FacetUtil::deleteFacet)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    fun testASourceInContentStillComesFirst() {
        layout(*sdkAnd("stale/ebin"), "app/lib/enum.ex" to enumerableSource())
        staleLibrary("stale", "stale/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("app/lib/enum.ex"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testACopyInADependencysExcludedBuildBeatsTheSdks() {
        layout(
            *sdkAnd(),
            "app/deps/d/lib/loaded_copy.ex" to fixture(SOURCE),
            "app/_build/dev/lib/d/ebin/$ENUMERABLE_BEAM" to enumerableBeam(),
            "app/_build/test/lib/d/ebin/$ENUMERABLE_BEAM" to enumerableBeam(),
        )
        dependency("dev", "test")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("app/_build/dev/lib/d/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
            assertEquals(listOf("app/_build/test/lib/d/ebin/$ENUMERABLE_BEAM"), loaded(TEST_ASKER, ENUMERABLE))
        }
    }

    fun testACopyUnderContentBeatsBothTheSdksAndAStaleCopy() {
        layout(*sdkAnd("stale/ebin", "app/vendor/ebin"))
        staleLibrary("stale", "stale/ebin")
        staleLibrary("app", "app/vendor/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("app/vendor/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    fun testTwoCopiesUnderContentStaySeveralBesideTheSdks() {
        layout(*sdkAnd("app/vendor/one/ebin", "app/vendor/two/ebin"))
        staleLibrary("one", "app/vendor/one/ebin")
        staleLibrary("two", "app/vendor/two/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(
                listOf("app/vendor/one/ebin/$ENUMERABLE_BEAM", "app/vendor/two/ebin/$ENUMERABLE_BEAM"),
                loaded(LIB_ASKER, ENUMERABLE),
            )
        }
    }

    fun testTwoLibraryCopiesStaySeveralWithAnSdk() {
        layout(
            *copies("stale/ebin", "stale2/ebin"),
            "sdk/ebin/$DEP_BEAM" to fixture(DEP_BEAM),
            "sdk/lib/enum.ex" to enumerableSource(),
        )
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(
                listOf("stale/ebin/$ENUMERABLE_BEAM", "stale2/ebin/$ENUMERABLE_BEAM"),
                loaded(LIB_ASKER, ENUMERABLE),
            )
        }
    }

    fun testTwoLibraryCopiesStaySeveralWithoutAnSdk() {
        layout(*copies("stale/ebin", "stale2/ebin"))
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        assertEquals(
            listOf("stale/ebin/$ENUMERABLE_BEAM", "stale2/ebin/$ENUMERABLE_BEAM"),
            loaded(LIB_ASKER, ENUMERABLE),
        )
    }

    fun testTwoCopiesInsideTheSdkStaySeveral() {
        layout(*sdkAnd("sdk/ebin2"))

        withSdk("sdk/ebin", "sdk/ebin2") {
            assertEquals(
                listOf("sdk/ebin/$ENUMERABLE_BEAM", "sdk/ebin2/$ENUMERABLE_BEAM"),
                loaded(LIB_ASKER, ENUMERABLE),
            )
        }
    }

    fun testALibraryCopyStaysWhenTheSdkHoldsOnlySource() {
        layout(*copies("stale/ebin"), "sdk/lib/enum.ex" to enumerableSource())
        staleLibrary("stale", "stale/ebin")

        withSdk(classes = null) {
            assertEquals(listOf("stale/ebin/$ENUMERABLE_BEAM"), loaded(LIB_ASKER, ENUMERABLE))
        }
    }

    // The expander's answer where `locate` answers `Several`: every copy, each uncertain, as which one loads is unknown.

    fun testTwoLibraryCopiesWithAnSdkAnswerEveryCopy() {
        layout(
            *copies("stale/ebin", "stale2/ebin"),
            "sdk/ebin/$DEP_BEAM" to fixture(DEP_BEAM),
            "sdk/lib/enum.ex" to enumerableSource(),
            CALLER to COUNT_CALLER,
        )
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(
                listOf("stale/ebin/$ENUMERABLE_BEAM\tSEVERAL", "stale2/ebin/$ENUMERABLE_BEAM\tSEVERAL"),
                expanderAnswer("count"),
            )
        }
    }

    fun testTwoSourceCopiesInContentAnswerEveryCopy() {
        layout(
            "app/lib/twice_one.ex" to TWICE,
            "app/lib/twice_two.ex" to TWICE,
            CALLER to "defmodule Caller do\n  def f, do: LoadedCopy.Twice.g(1)\nend\n".toByteArray(),
        )

        assertEquals(
            listOf("app/lib/twice_one.ex\tSEVERAL", "app/lib/twice_two.ex\tSEVERAL"),
            expanderAnswer("g"),
        )
    }

    /** A source copy that leaves the name no module falls through to the code path, which holds two `.beam`s. */
    fun testASourceCopyWithNoModuleFallsThroughToEveryBeam() {
        layout(
            *copies("stale/ebin", "stale2/ebin"),
            "app/lib/enumerable.ex" to "defmodule Enumerable do\n  :ok = :bad\nend\n".toByteArray(),
            CALLER to COUNT_CALLER,
        )
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        assertEquals(
            listOf("stale/ebin/$ENUMERABLE_BEAM\tSEVERAL", "stale2/ebin/$ENUMERABLE_BEAM\tSEVERAL"),
            expanderAnswer("count"),
        )
    }

    fun testTwoCopiesUnderContentBesideTheSdksAnswerEveryCopy() {
        layout(*sdkAnd("app/vendor/one/ebin", "app/vendor/two/ebin"), CALLER to COUNT_CALLER)
        staleLibrary("one", "app/vendor/one/ebin")
        staleLibrary("two", "app/vendor/two/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(
                listOf("app/vendor/one/ebin/$ENUMERABLE_BEAM\tSEVERAL", "app/vendor/two/ebin/$ENUMERABLE_BEAM\tSEVERAL"),
                expanderAnswer("count"),
            )
        }
    }

    fun testTwoCopiesInsideTheSdkAnswerEveryCopy() {
        layout(*sdkAnd("sdk/ebin2"), CALLER to COUNT_CALLER)

        withSdk("sdk/ebin", "sdk/ebin2") {
            assertEquals(
                listOf("sdk/ebin/$ENUMERABLE_BEAM\tSEVERAL", "sdk/ebin2/$ENUMERABLE_BEAM\tSEVERAL"),
                expanderAnswer("count"),
            )
        }
    }

    fun testOneCopyIsCertain() {
        layout(*sdkAnd("stale/ebin"), CALLER to COUNT_CALLER)
        staleLibrary("stale", "stale/ebin")

        withSdk(classes = "sdk/ebin") {
            assertEquals(listOf("sdk/ebin/$ENUMERABLE_BEAM\tcertain"), expanderAnswer("count"))
        }
    }

    /** Which copy loads is unknown, so an absence there is too. */
    fun testAnAbsenceInSeveralCopiesIsUncertain() {
        layout(*copies("stale/ebin", "stale2/ebin"), CALLER to "defmodule Caller do\n  def f(x), do: Enumerable.missing(x)\nend\n".toByteArray())
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        assertEquals("SEVERAL", expanderAbsence())
    }

    /** Completion offers no copy that may not be the one Elixir loads, after the qualifier or through `Kernel`'s import. */
    fun testCompletionOffersNoneOfSeveralCopies() {
        val kernel = legBeam(KERNEL_BEAM)
        layout(
            *copies("stale/ebin", "stale2/ebin"),
            "stale/ebin/$KERNEL_BEAM" to kernel,
            "stale2/ebin/$KERNEL_BEAM" to kernel,
            CALLER to "defmodule Caller do\n  def f(x), do: Enumerable.count(x)\n  def g(x), do: count(x)\nend\n".toByteArray(),
        )
        staleLibrary("stale", "stale/ebin")
        staleLibrary("stale2", "stale2/ebin")

        assertEquals(
            listOf(listOf<String>(), listOf()),
            listOf(expanderVisible { it is Qualified }, expanderVisible { it !is Qualified }),
        )
    }

    /** The expander's candidates at the first call named [name] in [CALLER]: each one's file, and why it is uncertain or `certain`. */
    private fun expanderAnswer(name: String): List<String> =
        expander(name) { use, inputs, base ->
            ExpanderSource.candidates(use, inputs).map { found ->
                "${relative(base, found.element)}\t${found.uncertain ?: "certain"}"
            }.sorted()
        }

    /** Why the expander's answer at the first `missing` call in [CALLER] may be missing what it reaches, or `certain`. */
    private fun expanderAbsence(): String =
        expander("missing") { use, inputs, _ -> ExpanderSource.answer(use, inputs).absence?.name ?: "certain" }

    /** The files of what the expander offers at the first `count` call in [CALLER] that [which] picks, from the stale copies. */
    private fun expanderVisible(which: (Call) -> Boolean): List<String> =
        inFile(CALLER) { file ->
            val call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.functionName() == "count" && which(it) }
            val base = FileUtil.toSystemIndependentName(top.path)

            ExpanderSource.visible(call, inputs()).map { relative(base, it.element) }.filter { it.startsWith("stale") }.sorted()
        }

    private fun <T> expander(name: String, answer: (Use.OfCall, ProjectModules.Inputs, String) -> T): T =
        inFile(CALLER) { file ->
            val call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.functionName() == name }

            answer(Use.of(call) as Use.OfCall, inputs(), FileUtil.toSystemIndependentName(top.path))
        }

    /** [block] in a read action, on the file at [path] under the fixture. */
    private fun <T> inFile(path: String, block: (PsiFile) -> T): T =
        read { block(PsiManager.getInstance(project).findFile(file(path))!!) }

    private fun inputs(): ProjectModules.Inputs = ProjectModules.Inputs(legLevel(), legKernel, OS.CURRENT)

    private fun relative(base: String, element: PsiElement): String {
        val path = FileUtil.toSystemIndependentName(element.containingFile.originalFile.virtualFile.path)

        return FileUtil.getRelativePath(base, path, '/') ?: path
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

    /** The `Enumerable` beam in each of [dirs]. */
    private fun copies(vararg dirs: String): Array<Pair<String, ByteArray>> =
        dirs.map { "$it/$ENUMERABLE_BEAM" to enumerableBeam() }.toTypedArray()

    /** The SDK's own `Enumerable` beam and source, and a copy of the beam in each of [dirs]. */
    private fun sdkAnd(vararg dirs: String): Array<Pair<String, ByteArray>> =
        arrayOf("sdk/ebin/$ENUMERABLE_BEAM" to enumerableBeam(), "sdk/lib/enum.ex" to enumerableSource()) + copies(*dirs)

    /** A library named [name] with each of [classes] as a CLASSES root, in no content root unless [classes] sit in one. */
    private fun staleLibrary(name: String, vararg classes: String) {
        BeamLibraryFixture.addLibrary(project, module, name, classes.map(::file))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** An Elixir SDK in the table with [classes] as CLASSES roots, assigned to nothing. */
    private fun registerSdk(name: String, home: String, vararg classes: String): Sdk {
        val sdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk(name, SdkFixtures.fakeHome("elixir/$home")),
            testRootDisposable,
        )
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                classes.forEach { addRoot(file(it), OrderRootType.CLASSES) }
                commitChanges()
            }
        }

        return sdk
    }

    /**
     * An Elixir SDK with [classes] and [more] as CLASSES roots and `sdk/lib` as SOURCES, as the module's SDK while
     * [block] runs.
     */
    private fun withSdk(classes: String?, vararg more: String, block: () -> Unit) {
        val sdk = registerSdk("Loaded copy", "loaded-copy", *listOfNotNull(classes).toTypedArray(), *more)
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
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
        inFile(asker) { askerFile ->
            val base = FileUtil.toSystemIndependentName(top.path)

            val results = if (loaded) {
                Resolver.preferred(askerFile, false, Module.resolveLoadable(askerFile, name))
            } else {
                Module.resolvePreferred(askerFile, name, incompleteCode = false, inScope = false)
            }

            results
                .map { result ->
                    val path = FileUtil.toSystemIndependentName(result.element.containingFile.originalFile.virtualFile.path)

                    FileUtil.getRelativePath(base, path, '/') ?: path
                }
                .sorted()
        }

    private fun file(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(top, path))!!

    private fun fixture(name: String): ByteArray = File(FIXTURES, name).readBytes()

    private fun enumerableBeam(): ByteArray = legBeam(ENUMERABLE_BEAM)

    private fun enumerableSource(): ByteArray = legSource("lib/enum.ex")

    private fun legBeam(name: String): ByteArray = File(LegManifest.ebin(), name).readBytes()

    private fun legSource(path: String): ByteArray = LegManifest.source(path).readBytes()

    private companion object {
        val FIXTURES = File("testData/org/elixir_lang/expander/loaded_copy")
        val ASKER = "defmodule Asker do\nend\n".toByteArray()
        const val SOURCE = "loaded_copy.ex"
        const val LIB_ASKER = "app/lib/asker.ex"
        const val CALLER = "app/lib/caller.ex"
        val COUNT_CALLER = "defmodule Caller do\n  def f(x), do: Enumerable.count(x)\nend\n".toByteArray()
        val TWICE = "defmodule LoadedCopy.Twice do\n  def g(a), do: a\nend\n".toByteArray()
        const val TEST_ASKER = "app/test/asker_test.exs"
        const val APP = "LoadedCopy.App"
        const val APP_BEAM = "Elixir.LoadedCopy.App.beam"
        const val DEP = "LoadedCopy.Dep"
        const val DEP_BEAM = "Elixir.LoadedCopy.Dep.beam"
        const val PROTOCOL = "LoadedCopy.Protocol"
        const val PROTOCOL_BEAM = "Elixir.LoadedCopy.Protocol.beam"
        const val ENUMERABLE = "Enumerable"
        const val ENUMERABLE_BEAM = "Elixir.Enumerable.beam"
        const val KERNEL_BEAM = "Elixir.Kernel.beam"
        const val LISTS_BEAM = "lists.beam"
    }
}
