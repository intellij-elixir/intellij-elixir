package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.jps.shared.ElixirSdkTypeId
import org.elixir_lang.jps.shared.ErlangSdkTypeId

/**
 * Application-scoped cache of virtual-file roots belonging to Elixir and Erlang SDKs, invalidated when the table adds,
 * removes, renames or commits one.
 */
@Service
internal class ElixirSdkRootsCache : Disposable {

    @Volatile private var classRootsCache: Set<VirtualFile>? = null
    @Volatile private var classAndSourceRootsCache: Set<VirtualFile>? = null

    init {
        SdkTableChanges.follow(this, Sdk::isElixirOrErlangSdk) { _, _ ->
            classRootsCache = null
            classAndSourceRootsCache = null
        }
    }

    private fun elixirSdks(): List<Sdk> = ProjectJdkTable.getInstance().allJdks.filter(Sdk::isElixirOrErlangSdk)

    /** CLASSES roots of all registered Elixir/Erlang SDKs. */
    fun classRoots(): Set<VirtualFile> =
        classRootsCache ?: buildSet {
            for (sdk in elixirSdks()) {
                addAll(sdk.rootProvider.getFiles(OrderRootType.CLASSES))
            }
        }.also { classRootsCache = it }

    /** CLASSES + SOURCES roots of all registered Elixir/Erlang SDKs. */
    fun classAndSourceRoots(): Set<VirtualFile> =
        classAndSourceRootsCache ?: buildSet {
            for (sdk in elixirSdks()) {
                addAll(sdk.rootProvider.getFiles(OrderRootType.CLASSES))
                addAll(sdk.rootProvider.getFiles(OrderRootType.SOURCES))
            }
        }.also { classAndSourceRootsCache = it }

    override fun dispose() {}
}

/** Whether the SDK is of the plugin's own Elixir or Erlang type, by its type's id; intellij-erlang's type is not. */
internal fun Sdk.isElixirOrErlangSdk(): Boolean =
    sdkType.name == ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID || sdkType.name == ErlangSdkTypeId.ERLANG_SDK_TYPE_ID
