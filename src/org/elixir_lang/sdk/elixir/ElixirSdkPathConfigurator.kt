package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import org.elixir_lang.sdk.SdkEbinPaths
import org.elixir_lang.util.WriteActions
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

object ElixirSdkPathConfigurator {
    private val LOG = Logger.getInstance(ElixirSdkPathConfigurator::class.java)

    fun configure(sdk: Sdk, sdkModel: SdkModel? = null) {
        LOG.info("Configuring SDK paths for ${sdk.name}")
        val sdkModificator = sdk.sdkModificator

        // Configure base paths
        org.elixir_lang.sdk.Type.addCodePaths(sdkModificator)
        sdkModificator.removeRoots(OrderRootType.SOURCES)
        org.elixir_lang.sdk.Type.documentationRootType()?.let { sdkModificator.removeRoots(it) }
        // addDocumentationPaths removed: VirtualFileManager.findFileByUrl returns null for HTTP URLs,
        // the hostname was wrong (hexdoc.pm vs hexdocs.pm), and no ExternalDocumentationProvider
        // is registered to consume JavadocOrderRootType roots.
        addSourcePaths(sdkModificator)

        ElixirInternalErlangSdkSetup.configureInternalErlangSdk(sdk, sdkModificator, sdkModel)

        WriteActions.runWriteAction {
            LOG.debug { "Committing SDK changes for ${sdk.name}" }
            sdkModificator.commitChanges()
            LOG.debug { "Committed SDK changes for ${sdk.name}" }
        }
        LOG.info("SDK paths configured for ${sdk.name}")
    }

    /**
     * Sets up again the code paths of [elixirSdks], whose stored Erlang home a pairing write changed: an Elixir SDK's code
     * paths include its Erlang SDK's. A write action still held, such as the table's around its listeners, is left
     * first, by running at the next non-modal moment.
     */
    fun followErlangHome(elixirSdks: Collection<Sdk>, sdkModel: SdkModel? = null) {
        if (elixirSdks.isEmpty()) return
        val application = ApplicationManager.getApplication()
        if (application.isWriteAccessAllowed) {
            application.invokeLater({ elixirSdks.forEach { configure(it, sdkModel) } }, ModalityState.nonModal(), application.disposed)
        } else {
            elixirSdks.forEach { configure(it, sdkModel) }
        }
    }

    /** Each `lib` beside an `ebin` under [sdkModificator]'s home, as a SOURCES root. */
    internal fun addSourcePaths(sdkModificator: SdkModificator) {
        val homePath = sdkModificator.homePath ?: return
        SdkEbinPaths.eachEbinPath(
            homePath,
        ) { ebinPath: Path -> addSourcePath(sdkModificator, ebinPath) }
    }

    private fun addSourcePath(
        sdkModificator: SdkModificator,
        libFile: File,
    ) {
        val sourcePath = VfsUtil.findFileByIoFile(libFile, true)
        if (sourcePath != null) {
            sdkModificator.addRoot(sourcePath, OrderRootType.SOURCES)
        }
    }

    private fun addSourcePath(
        sdkModificator: SdkModificator,
        ebinPath: Path,
    ) {
        val parentPath = ebinPath.parent
        val libPath = Paths.get(parentPath.toString(), "lib")
        val libFile = libPath.toFile()
        if (libFile.exists()) {
            addSourcePath(sdkModificator, libFile)
        }
    }
}
