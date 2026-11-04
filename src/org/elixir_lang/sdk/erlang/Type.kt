package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import org.elixir_lang.util.runWithEdtGuard
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.cli.getExecutableFilepathWslSafe
import org.elixir_lang.sdk.SdkVersionsFiller
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.jps.shared.ErlangSdkTypeId
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.sdk.detectSource
import com.intellij.openapi.vfs.VfsUtil
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkEbinPaths
import org.elixir_lang.sdk.SdkHomeKey
import org.elixir_lang.sdk.SdkHomePaths
import org.elixir_lang.sdk.SdkHomeScan
import org.elixir_lang.sdk.SdkHomeChooser
import org.jdom.Element
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer
import javax.swing.JComponent
import org.elixir_lang.sdk.wsl.wslCompat

internal class Type : SdkType(ErlangSdkTypeId.ERLANG_SDK_TYPE_ID) {
    companion object {
        /**
         * Every application's `src` directory under [homePath], in whatever order
         * [SdkEbinPaths.eachEbinPath] walks the applications.
         *
         * Separated from [addSourcePaths] so it can be exercised without an SDK: this is the whole
         * decision, and everything around it is the platform's root bookkeeping.
         */
        internal fun sourcePaths(homePath: String): List<Path> {
            val sourcePaths = mutableListOf<Path>()

            SdkEbinPaths.eachEbinPath(homePath) { ebinPath ->
                ebinPath.parent?.resolve("src")?.takeIf { it.toFile().isDirectory }?.let(sourcePaths::add)
            }

            return sourcePaths
        }

        private const val WINDOWS_DEFAULT_HOME_PATH = "C:\\Program Files\\erl9.0"
        private val NIX_PATTERN = SdkHomePaths.nixPattern("erlang")

        @JvmStatic
        val instance: Type
            get() = findInstance(Type::class.java)

        @JvmStatic
        private fun createConfig() = SdkHomeScan.Config(
            toolName = "erlang",
            nixPattern = NIX_PATTERN,
            windowsDefaultPath = WINDOWS_DEFAULT_HOME_PATH,
            windows32BitPath = null,
            elixirInstallScriptDirName = "otp",
            kerlTransform = { it },
            travisCIKerlTransform = { it }
        )

        @JvmStatic
        fun getDefaultSdkName(
            sdkHome: String,
            version: Release?,
        ): String =
            buildString {
                val source = detectSource(sdkHome)
                if (source != null) {
                    append(source).append(" ")
                }
                append("Erlang for Elixir ")
                if (version != null) {
                    // Use directory name for version if it's more specific (e.g., "28.3" vs "28")
                    val dirVersion = File(sdkHome).name
                    val displayVersion = if (dirVersion.startsWith(version.otpMajor)) dirVersion else version.otpVersion
                    append(displayVersion)
                } else {
                    append("at ").append(sdkHome)
                }
            }

        @JvmStatic
        internal fun suggestSdkNameForHome(
            sdkHome: String,
            resolvedVersion: String?,
            release: Release? = null,
        ): String {
            val normalizedVersion = resolvedVersion?.takeIf { it.isNotBlank() }
            val baseName =
                if (normalizedVersion == null) {
                    getDefaultSdkName(
                        sdkHome,
                        release ?: SdkVersionsStore.getInstance().otpRelease(sdkHome),
                    )
                } else {
                    val source = detectSource(sdkHome)
                    val dirVersion = File(sdkHome).name
                    val displayVersion =
                        if (dirVersion.startsWith(normalizedVersion)) dirVersion else normalizedVersion
                    buildString {
                        if (source != null) {
                            append(source).append(" ")
                        }
                        append("Erlang for Elixir ").append(displayVersion)
                    }
                }

            return org.elixir_lang.sdk.Type.appendWslSuffix(baseName, sdkHome)
        }

        @JvmStatic
        internal fun versionStringForHome(
            sdkHome: String,
            resolvedVersion: String?,
            release: Release? = null,
        ): String? {
            val normalizedVersion = resolvedVersion?.takeIf { it.isNotBlank() }
            val version = normalizedVersion
                ?: (release ?: SdkVersionsStore.getInstance().otpRelease(sdkHome))
                    ?.otpVersion
                ?: return null
            val displayVersion =
                if (normalizedVersion == null) {
                    val dirVersion = File(sdkHome).name
                    if (dirVersion.startsWith(version)) dirVersion else version
                } else {
                    version
                }
            return erlangDisplayString(detectSource(sdkHome), displayVersion)
        }

        private fun erlangDisplayString(source: String?, version: String): String = buildString {
            source?.let { append(it).append(" ") }
            append("Erlang ").append(version)
        }

        @JvmStatic
        fun homePathByVersion(): Map<SdkHomeKey, String> {
            return SdkHomeScan.homePathByVersion(null, createConfig())
        }

        @JvmStatic
        fun homePathByVersion(path: Path?): Map<SdkHomeKey, String> {
            return SdkHomeScan.homePathByVersion(path, createConfig())
        }
    }

    override fun isRootTypeApplicable(type: OrderRootType): Boolean =
        type == OrderRootType.CLASSES ||
                type == OrderRootType.SOURCES ||
                type ==
                org.elixir_lang.sdk.Type
                    .documentationRootType()

    override fun getHomeChooserDescriptor(): FileChooserDescriptor =
        org.elixir_lang.sdk.Type.createHomeChooserDescriptor(presentableName, ::validateSdkHomePath)

    private fun validateSdkHomePath(virtualFile: VirtualFile) {
        val selectedPath = virtualFile.path
        val valid = isValidSdkHome(selectedPath)

        if (!valid) {
            throw Exception("The selected directory is not a valid home for $presentableName")
        }
    }

    // If called from inside a write action the modal progress dialog would deadlock, so skip
    // it in that case and call setupSdkPathsImpl directly (see ElixirSdkPathConfigurator for
    // the same pattern).
    override fun setupSdkPaths(sdk: Sdk) =
            runWithEdtGuard(
                "Setting Up Erlang SDK Paths...",
                skipModalIf = { ApplicationManager.getApplication().isWriteAccessAllowed },
            ) { setupSdkPathsImpl(sdk) }

    private fun setupSdkPathsImpl(sdk: Sdk) {
        val sdkModificator = sdk.sdkModificator
        org.elixir_lang.sdk.Type
            .addCodePaths(sdkModificator)
        addSourcePaths(sdkModificator)

        // Check if we're already in a write action (called from Elixir SDK setup)
        val app = ApplicationManager.getApplication()
        if (app.isWriteAccessAllowed) {
            // We're already in a write action, commit directly
            sdkModificator.commitChanges()
            return
        }

        val runnable = Runnable { app.runWriteAction { sdkModificator.commitChanges() } }
        if (app.isDispatchThread) {
            runnable.run()
        } else {
            app.invokeAndWait(runnable)
        }
    }

    /**
     * Registers each application's `src` directory as a [OrderRootType.SOURCES] root.
     *
     * OTP ships its own sources beside the compiled beams - `lib/<app>-<version>/{ebin,src}` - so
     * the sibling of every ebin is the source for what that ebin holds. Without these an Erlang
     * frame in a stack trace names a file nothing indexed can find, and `gen_server.erl` is not
     * navigable.
     *
     * Mirrors `ElixirSdkPathConfigurator.addSourcePaths`, which does the same for Elixir's
     * `lib/<app>/lib`. Existing roots are removed first so a refresh does not accumulate
     * duplicates, matching that configurator.
     */
    private fun addSourcePaths(sdkModificator: SdkModificator) {
        val homePath = sdkModificator.homePath ?: return

        sdkModificator.removeRoots(OrderRootType.SOURCES)

        for (srcPath in sourcePaths(homePath)) {
            VfsUtil.findFileByIoFile(srcPath.toFile(), true)?.let { srcVirtualFile ->
                sdkModificator.addRoot(srcVirtualFile, OrderRootType.SOURCES)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java", ReplaceWith("suggestHomePaths(null).firstOrNull()"))
    override fun suggestHomePath(): String? = suggestHomePaths().firstOrNull()

    @Deprecated("Deprecated in Java", ReplaceWith("suggestHomePaths(null)"))
    override fun suggestHomePaths(): Collection<String> = homePathByVersion().values

    override fun suggestHomePath(path: Path): String? {
        return homePathByVersion(path).values.firstOrNull()
    }

    /** Without it the platform picks the home itself, and its check against `user.home` rejects every WSL home. */
    override fun supportsCustomCreateUI(): Boolean = true

    override fun showCustomCreateUI(
        sdkModel: SdkModel,
        parentComponent: JComponent,
        selectedSdk: Sdk?,
        sdkCreatedCallback: Consumer<in Sdk>,
    ) {
        val basePath = SdkHomeChooser.defaultBasePath(SdkHomeChooser.projectOf(parentComponent))
        SdkHomeChooser.createSdk(sdkModel, this, basePath, onCreated = sdkCreatedCallback::accept)
    }

    override fun suggestHomePaths(project: Project?): Collection<String> {
        // SdkDetectionContext falls back to the wizard's import/new-project directory when the
        // platform supplies the default project (import wizard, New Project), so WSL locations
        // are still scanned for suggestions.
        return homePathByVersion(SdkDetectionContext.resolve(project)).values
    }

    /**
     * @param homePath the path selected in the file chooser.
     * @return the path to be used as the SDK home.
     */
    override fun adjustSelectedSdkHome(homePath: String): String =
        SdkHomePaths.adjustSelectedSdkHome(homePath, "erlang")

    override fun isValidSdkHome(path: String): Boolean {
        if (!wslCompat.isReachable(path)) return false
        val erlExe = File(CliTool.ERL.getExecutableFilepathWslSafe(path))
        return erlExe.canExecute()
    }

    override fun suggestSdkName(
        currentSdkName: String?,
        sdkHome: String,
    ): String {
        SdkVersionsFiller.fillIfUnreadBlocking(sdkHome)
        return suggestSdkNameForHome(sdkHome, null)
    }

    override fun getVersionString(sdkHome: String): String? {
        SdkVersionsFiller.fillIfUnreadBlocking(sdkHome)
        val release = SdkVersionsStore.getInstance().otpRelease(sdkHome) ?: return null
        val dirVersion = File(sdkHome).name
        val displayVersion = if (dirVersion.startsWith(release.otpMajor)) dirVersion else release.otpVersion
        return erlangDisplayString(detectSource(sdkHome), displayVersion)
    }

    override fun createAdditionalDataConfigurable(
        sdkModel: SdkModel,
        sdkModificator: SdkModificator,
    ): AdditionalDataConfigurable = PairingsConfigurable(sdkModel)

    override fun getPresentableName(): String = name

    /**
     * Saves nothing: the OTP version is held by [org.elixir_lang.sdk.SdkVersionsStore] under the home.
     */
    override fun saveAdditionalData(
        additionalData: com.intellij.openapi.projectRoots.SdkAdditionalData,
        additional: Element,
    ) = Unit

}
