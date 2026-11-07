package org.elixir_lang.mix.project._import

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.UsefulTestCase.assertContainsElements
import com.intellij.testFramework.VfsTestUtil
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.mix.sync.contentRootToken
import org.elixir_lang.mix.sync.scopedDepLibraryName

/** The `mix.exs` of the Mix app [app] in [dir], the least the import finds as an app. */
internal fun mixExs(dir: VirtualFile, app: String): VirtualFile =
    VfsTestUtil.createFile(dir, "mix.exs", "defmodule ${app.replaceFirstChar(Char::uppercase)}.MixProject do\n  def project, do: [app: :$app]\nend\n")

/** The Mix app [app] in [dir], declaring `phoenix`, which it has fetched and built. */
internal fun mixAppUsingPhoenix(project: Project, dir: VirtualFile, app: String) {
    VfsTestUtil.createDir(dir, "deps/phoenix/lib")
    VfsTestUtil.createDir(dir, "_build/dev/lib/phoenix/ebin")
    VfsTestUtil.createFile(
        dir,
        "mix.exs",
        "defmodule ${app.replaceFirstChar(Char::uppercase)}.MixProject do\n" +
            "  use Mix.Project\n\n" +
            "  def project do\n    [app: :$app, deps: deps()]\n  end\n\n" +
            "  def deps do\n    [{:phoenix, \">= 0.0.0\"}]\n  end\n" +
            "end\n",
    )
    // The syncs these files queue would otherwise drain after an import and wire it themselves.
    MixSyncTestHelpers.awaitMixDepsSyncs(project)
}

/** Once the syncs queued so far finish, [module] has the `phoenix` library of the Mix app in [dir]. */
internal fun assertPhoenixIsALibraryOf(project: Project, module: Module, dir: VirtualFile) {
    MixSyncTestHelpers.awaitMixDepsSyncs(project)
    val libraries = ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>().mapNotNull { it.libraryName }
    assertContainsElements(libraries, scopedDepLibraryName(contentRootToken(project, dir.url), "phoenix"))
}
