package org.elixir_lang.expander

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.sdk.PushedVersions
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import java.io.IOException

/**
 * The inputs of a file's expansion, read from the file's own Elixir SDK: its language level and `Kernel`'s imports. An
 * SDK they can't be read from answers why, never a default.
 */
@Service(Service.Level.PROJECT)
internal class SdkInputs(private val project: Project) {
    sealed interface Answer {
        data class Ready(val sdk: Sdk, val inputs: ProjectModules.Inputs) : Answer
        data object NoSdk : Answer

        /** The file is a template's Elixir view, which Elixir compiles into another file's module, so nothing expands it. */
        data object Template : Answer

        /** The versions store hasn't read the SDK's home. */
        data class VersionUnread(val sdk: Sdk) : Answer

        /** No class root of the SDK holds `Elixir.Kernel.beam`. */
        data class KernelMissing(val sdk: Sdk) : Answer

        /** `Elixir.Kernel.beam` has no export table. */
        data class KernelAbsent(val sdk: Sdk) : Answer
        data class KernelUnreadable(val sdk: Sdk, val reason: String) : Answer
        data class KernelNotBeam(val sdk: Sdk) : Answer
    }

    @RequiresReadLock
    fun inputsFor(file: PsiFile): Answer {
        if (file.viewProvider.baseLanguage != ElixirLanguage) return Answer.Template

        val sdk = CachedValuesManager.getCachedValue(file, SDK_BY_FILE) {
            CachedValueProvider.Result.create(
                ElixirSdkLookup.resolve(file).sdk,
                // The roots tracker is what moves when the file's module is given another SDK.
                ProjectRootModificationTracker.getInstance(project),
                SdkVersionsStore.getInstance(),
            )
        }

        return sdk?.let { heldFor(it).answer } ?: Answer.NoSdk
    }

    /**
     * Held on [sdk] itself, so an SDK removed from the table takes its entry with it, and checked against its stamp on
     * each call: no tracker moves when the SDK's Erlang pairing changes or a `Kernel` is added to a class root.
     */
    private fun heldFor(sdk: Sdk): Held {
        sdk.getUserData(BY_SDK)?.takeIf { it.stamp == stamp(sdk) }?.let { return it }

        return read(sdk).also { if (it.held) sdk.putUserData(BY_SDK, it) }
    }

    /** @property held whether [answer] holds until [stamp] moves, which an I/O failure that may not recur doesn't */
    private class Held(val answer: Answer, val stamp: Stamp, val held: Boolean = true)

    /** What [read] reads: the level the SDK's versions give, and its `Kernel` as it is now. */
    private data class Stamp(
        val home: String?,
        val classRoots: List<VirtualFile>,
        val level: ElixirLanguageLevel?,
        val kernel: VirtualFile?,
        val kernelStamp: Long?,
    )

    private fun stamp(sdk: Sdk): Stamp {
        val classRoots = classRoots(sdk)
        val kernel = classRoots.firstNotNullOfOrNull { it.findChild(KERNEL_BEAM) }

        return Stamp(sdk.homePath, classRoots, PushedVersions.languageLevel(sdk), kernel, kernel?.modificationStamp)
    }

    private fun read(sdk: Sdk): Held {
        ProgressManager.checkCanceled()
        // Stamped before reading, so a change made during the read moves it.
        val stamp = stamp(sdk)
        val level = stamp.level ?: return Held(Answer.VersionUnread(sdk), stamp)
        val kernel = stamp.kernel ?: return Held(Answer.KernelMissing(sdk), stamp)
        val bytes = try {
            kernel.contentsToByteArray()
        } catch (e: IOException) {
            return Held(Answer.KernelUnreadable(sdk, "${kernel.path}: ${e.message}"), stamp, held = false)
        }

        return Held(answer(sdk, level, kernel, bytes), stamp)
    }

    private fun answer(sdk: Sdk, level: ElixirLanguageLevel, kernel: VirtualFile, bytes: ByteArray): Answer {
        ProgressManager.checkCanceled()

        return when (val read = KernelImports.read(bytes, kernel.path)) {
            null -> Answer.KernelNotBeam(sdk)
            ReadResult.Absent -> Answer.KernelAbsent(sdk)
            is ReadResult.Unreadable -> Answer.KernelUnreadable(sdk, read.reason)
            is ReadResult.Present -> Answer.Ready(sdk, ProjectModules.Inputs(level, read.value))
        }
    }

    private fun classRoots(sdk: Sdk): List<VirtualFile> = sdk.rootProvider.getFiles(OrderRootType.CLASSES).toList()

    companion object {
        private const val KERNEL_BEAM = "Elixir.Kernel.beam"
        private val SDK_BY_FILE = Key.create<CachedValue<Sdk?>>("org.elixir_lang.expander.SdkInputs.SDK_BY_FILE")
        private val BY_SDK = Key.create<Held>("org.elixir_lang.expander.SdkInputs.BY_SDK")

        fun getInstance(project: Project): SdkInputs = project.service()
    }
}
