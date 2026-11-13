package org.elixir_lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.package_manager.DepsStatusResult
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

interface PackageManager {
    val fileName: String
    fun depGatherer(): DepGatherer

    /**
     * A gatherer for a package file that may belong to a dependency rather than to a project being
     * built, which decides whether options like `only:` and `optional:` exclude a dep.
     *
     * Defaulted rather than replacing [depGatherer] so an out-of-repo implementor of the
     * `org.elixir_lang.packageManager` extension point keeps compiling; a package manager with no
     * such distinction needs no override.
     */
    fun depGatherer(@Suppress("UNUSED_PARAMETER") isDependency: Boolean): DepGatherer = depGatherer()
    fun depsStatus(project: Project, packageVirtualFile: VirtualFile, snapshot: ElixirSdkSnapshot?): DepsStatusResult =
        DepsStatusResult.Unsupported
}

fun packageManagers(): Array<out PackageManager> {
    return EP_NAME.extensions
}

private val EP_NAME: ExtensionPointName<PackageManager> = ExtensionPointName.create("org.elixir_lang.packageManager")
