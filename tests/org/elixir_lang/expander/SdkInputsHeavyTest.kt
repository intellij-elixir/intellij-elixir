package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.junit.logs.expectErrors
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import java.io.File
import java.util.concurrent.Callable
import org.elixir_lang.sdk.Type as SdkRoots

class SdkInputsHeavyTest : HeavyTestCase() {
    private val inputs: SdkInputs get() = SdkInputs.getInstance(project)

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.setModuleSdk(module, null)
            SdkVersionsStore.getInstance().clearForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testAPairedSdkWithNoOtpVersionGivesTheUnpairedLevel() {
        val home = home("1.18.4", KERNEL)
        val erlang = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("unread Erlang", SdkFixtures.fakeHome("erlang/sdk-inputs-unread")),
            testRootDisposable,
        )
        val sdk = sdk("paired", home)
        SdkFixtures.commit(sdk, SdkAdditionalData(erlang, sdk))
        SdkFixtures.waitForRegistrationFills()
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "paired")
        assertEquals(ElixirLanguageLevel.of("1.18.4"), ready(file).level)

        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))

        assertEquals(ElixirLanguageLevel.of("1.18.4", "27"), ready(file).level)
    }

    fun testARePairingMovesTheLevel() {
        val home = home("1.18.4", KERNEL)
        val erlang25 = erlang("OTP 25", "25")
        val erlang27 = erlang("OTP 27", "27")
        val sdk = sdk("re-paired", home)
        WriteAction.runAndWait<Throwable> { ElixirSdkMutation.applyDependencySelection(sdk, erlang25) }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "re-paired")
        assertEquals(ElixirLanguageLevel.of("1.18.4", "25"), ready(file).level)

        WriteAction.runAndWait<Throwable> { ElixirSdkMutation.applyDependencySelection(sdk, erlang27) }

        assertEquals(ElixirLanguageLevel.of("1.18.4", "27"), ready(file).level)
    }

    fun testAKernelThatAppearsIsRead() {
        val home = home("1.18.4", null)
        val sdk = sdk("kernel appears", home)
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "appears")
        assertEquals(SdkInputs.Answer.KernelMissing(sdk), read { inputs.inputsFor(file) })

        File(home, "lib/elixir/ebin/Elixir.Kernel.beam").writeBytes(KERNEL)
        VfsUtil.markDirtyAndRefresh(false, true, true, ebin(home))

        assertEquals(SdkInputs.Answer.Ready(sdk, ProjectModules.Inputs(level(sdk), kernel(KERNEL))), read { inputs.inputsFor(file) })
    }

    fun testOneSdk() {
        val sdk = sdk("one", home("1.18.4", KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val root = root(module, "one", "lib/a.ex", "lib/b.ex")

        val a = read { inputs.inputsFor(psi(root, "lib/a.ex")) }
        val b = read { inputs.inputsFor(psi(root, "lib/b.ex")) }

        assertEquals(SdkInputs.Answer.Ready(sdk, ProjectModules.Inputs(level(sdk), kernel(KERNEL))), a)
        assertEquals("1.18.4", (a as SdkInputs.Answer.Ready).inputs.level.elixirVersion)
        assertSame("a second file on the same SDK gets the SDK's held answer", a, b)
    }

    fun testATemplateFilesElixirViewGetsNoInputs() {
        ModuleRootModificationUtil.setModuleSdk(module, sdk("template", home("1.18.4", KERNEL)))
        val root = root(module, "template", "lib/page.html.heex")
        val elixir = read { psi(root, "lib/page.html.heex").viewProvider.getPsi(ElixirLanguage)!! }

        assertEquals(SdkInputs.Answer.Template, read { inputs.inputsFor(elixir) })
    }

    fun testTwoModulesOnTwoSdks() {
        val other = createModule("other")
        val first = sdk("first", home("1.18.4", KERNEL))
        val second = sdk("second", home("1.15.7", OTHER_KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, first)
        ModuleRootModificationUtil.setModuleSdk(other, second)

        val ofFirst = ready(file(module, "first"))
        val ofSecond = ready(file(other, "second"))

        assertEquals(ProjectModules.Inputs(level(first), kernel(KERNEL)), ofFirst)
        assertEquals(ProjectModules.Inputs(level(second), kernel(OTHER_KERNEL)), ofSecond)
        assertEquals("1.15.7", ofSecond.level.elixirVersion)
    }

    fun testARootsEditMovesTheStamp() {
        val sdk = sdk("roots", home("1.18.4", KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "roots")
        val before = ready(file)
        val otherEbin = ebin(home("1.18.4", OTHER_KERNEL))

        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                removeRoots(OrderRootType.CLASSES)
                addRoot(otherEbin, OrderRootType.CLASSES)
                commitChanges()
            }
        }

        assertEquals(ProjectModules.Inputs(before.level, kernel(OTHER_KERNEL)), ready(file))
    }

    fun testAKernelRewrittenInPlace() {
        val home = home("1.18.4", KERNEL)
        val sdk = sdk("rewritten", home)
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "rewritten")
        val before = ready(file)

        WriteAction.runAndWait<Throwable> { ebin(home).findChild("Elixir.Kernel.beam")!!.setBinaryContent(OTHER_KERNEL) }

        assertEquals(ProjectModules.Inputs(before.level, kernel(OTHER_KERNEL)), ready(file))
    }

    fun testAHomeChange() {
        val firstHome = home("1.18.4", KERNEL)
        val sdk = sdk("home", firstHome)
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "home")
        sdk("keeps the first home", firstHome)
        val readHome = sdk("already read", home("1.15.7", OTHER_KERNEL)).homePath!!
        val before = ready(file)

        // The home alone, to one the store has read while it keeps the first: no versions change, and the class roots
        // stay the first home's.
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                homePath = readHome
                commitChanges()
            }
        }
        assertEquals(ProjectModules.Inputs(ElixirLanguageLevel.of("1.15.7"), before.kernel), ready(file))

        val otherHome = home("1.16.3", OTHER_KERNEL)
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                homePath = otherHome
                SdkRoots.addCodePaths(this)
                commitChanges()
            }
        }
        waitForVersions(otherHome)

        assertEquals(ProjectModules.Inputs(ElixirLanguageLevel.of("1.16.3"), kernel(OTHER_KERNEL)), ready(file))
    }

    fun testAModuleSdkSwitch() {
        val first = sdk("switched from", home("1.18.4", KERNEL))
        val second = sdk("switched to", home("1.15.7", OTHER_KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, first)
        val file = file(module, "switch")
        val ofFirst = read { inputs.inputsFor(file) }

        ModuleRootModificationUtil.setModuleSdk(module, second)
        assertEquals(ProjectModules.Inputs(level(second), kernel(OTHER_KERNEL)), ready(file))

        ModuleRootModificationUtil.setModuleSdk(module, first)
        assertSame("switching back gets the first SDK's held answer", ofFirst, read { inputs.inputsFor(file) })
    }

    fun testEachUnusableSdkByName() {
        val file = file(module, "unusable")
        assertEquals(SdkInputs.Answer.NoSdk, read { inputs.inputsFor(file) })

        val unread = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("version unread", SdkFixtures.fakeHome("elixir/sdk-inputs-unread")),
            testRootDisposable,
        )
        ModuleRootModificationUtil.setModuleSdk(module, unread)
        assertEquals(SdkInputs.Answer.VersionUnread(unread), read { inputs.inputsFor(file) })

        val missing = sdk("kernel missing", home("1.18.4", null))
        ModuleRootModificationUtil.setModuleSdk(module, missing)
        assertEquals(SdkInputs.Answer.KernelMissing(missing), read { inputs.inputsFor(file) })

        val absent = sdk("kernel absent", home("1.18.4", renamedChunk(KERNEL, "ExpT", "XxpT")))
        ModuleRootModificationUtil.setModuleSdk(module, absent)
        assertEquals(SdkInputs.Answer.KernelAbsent(absent), read { inputs.inputsFor(file) })

        // Indexing the SDK's class roots reads the same export table, and reports it as the plugin's error.
        var unreadable: SdkInputs.Answer? = null
        expectErrors(ReadResult::class.java, Regex("ExpT declares")) {
            val sdk = sdk("kernel unreadable", home("1.18.4", corruptExportCount(KERNEL)))
            ModuleRootModificationUtil.setModuleSdk(module, sdk)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            unreadable = read { inputs.inputsFor(file) }
        }
        assertTrue("$unreadable", unreadable is SdkInputs.Answer.KernelUnreadable)
        assertTrue("$unreadable", "ExpT declares" in (unreadable as SdkInputs.Answer.KernelUnreadable).reason)

        val notBeam = sdk("kernel not a beam", home("1.18.4", "not a beam".toByteArray()))
        ModuleRootModificationUtil.setModuleSdk(module, notBeam)
        assertEquals(SdkInputs.Answer.KernelNotBeam(notBeam), read { inputs.inputsFor(file) })
    }

    fun testDumbMode() {
        val sdk = sdk("dumb", home("1.18.4", KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "dumb")

        var answer: SdkInputs.Answer? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) { answer = read { inputs.inputsFor(file) } }

        assertEquals(SdkInputs.Answer.Ready(sdk, ProjectModules.Inputs(level(sdk), kernel(KERNEL))), answer)
    }

    fun testACancelledReadHoldsNothing() {
        val sdk = sdk("cancelled", home("1.18.4", KERNEL))
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        val file = file(module, "cancelled")
        val indicator = ProgressIndicatorBase().apply {
            start()
            cancel()
        }

        val cancelled = try {
            ReadAction.computeBlocking<SdkInputs.Answer, Throwable> {
                ProgressManager.getInstance().runProcess<SdkInputs.Answer>({ inputs.inputsFor(file) }, indicator)
            }
        } catch (_: ProcessCanceledException) {
            null
        }

        assertNull("a cancelled read answers nothing", cancelled)
        assertEquals(SdkInputs.Answer.Ready(sdk, ProjectModules.Inputs(level(sdk), kernel(KERNEL))), read { inputs.inputsFor(file) })
    }

    private fun ready(file: PsiFile): ProjectModules.Inputs =
        when (val answer = read { inputs.inputsFor(file) }) {
            is SdkInputs.Answer.Ready -> answer.inputs
            else -> throw AssertionError("$answer")
        }

    private fun level(sdk: Sdk): ElixirLanguageLevel =
        ElixirLanguageLevel.of(SdkVersionsStore.getInstance().elixirVersions(sdk.homePath)!!.elixirVersion)

    /** An Elixir home whose `elixir.app` gives [vsn], with [kernel] as its `Elixir.Kernel.beam`. */
    private fun home(vsn: String, kernel: ByteArray?): String =
        SdkFixtures.elixirHome(vsn).also { home ->
            kernel?.let { File(home, "lib/elixir/ebin/Elixir.Kernel.beam").writeBytes(it) }
        }

    private fun sdk(name: String, home: String): Sdk {
        val sdk = SdkFixtures.registerAndWaitForFill(SdkFixtures.elixirSdk(name, home), testRootDisposable)
        WriteAction.runAndWait<Throwable> {
            sdk.sdkModificator.apply {
                SdkRoots.addCodePaths(this)
                commitChanges()
            }
        }
        waitForVersions(home)
        return sdk
    }

    /** An Erlang SDK whose OTP the store holds as [otp]. */
    private fun erlang(name: String, otp: String): Sdk {
        val sdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk(name, SdkFixtures.fakeHome("erlang/sdk-inputs-$otp")),
            testRootDisposable,
        )
        SdkVersionsStore.getInstance().setOtpVersion(sdk.homePath!!, otp)

        return sdk
    }

    private fun waitForVersions(home: String) =
        SdkFixtures.waitUntil("the store reads $home", 30_000) { SdkVersionsStore.getInstance().elixirVersions(home) != null }

    private fun ebin(home: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(home, "lib/elixir/ebin"))!!

    private fun file(module: Module, name: String): PsiFile = psi(root(module, name, "lib/$name.ex"), "lib/$name.ex")

    private fun root(module: Module, name: String, vararg paths: String): VirtualFile {
        val directory = createTempDir(name)
        paths.forEach { FileUtil.writeToFile(File(directory, it), "defmodule M do\nend\n") }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        return root
    }

    private fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!

    private fun <T> read(block: () -> T): T = ReadAction.nonBlocking(Callable { block() }).executeSynchronously()

    private companion object {
        val KERNEL: ByteArray by lazy { File(LegManifest.ebin(), "Elixir.Kernel.beam").readBytes() }

        /** Another module's `.beam`, standing in for another Elixir's `Kernel`: its exports differ from [KERNEL]'s. */
        val OTHER_KERNEL: ByteArray by lazy { File(LegManifest.ebin(), "Elixir.Kernel.SpecialForms.beam").readBytes() }

        fun kernel(bytes: ByteArray): KernelImports =
            (KernelImports.read(bytes, "Elixir.Kernel.beam") as ReadResult.Present).value

        fun renamedChunk(bytes: ByteArray, from: String, to: String): ByteArray {
            val copy = bytes.copyOf()
            val at = String(copy, Charsets.ISO_8859_1).indexOf(from)
            check(at >= 0) { "no $from" }
            to.toByteArray(Charsets.ISO_8859_1).copyInto(copy, at)
            return copy
        }

        /** The export table's entry count made larger than the chunk holds. */
        fun corruptExportCount(bytes: ByteArray): ByteArray {
            val copy = bytes.copyOf()
            val count = String(copy, Charsets.ISO_8859_1).indexOf("ExpT") + 8
            for (i in 0..3) copy[count + i] = 0x7F
            return copy
        }
    }
}
