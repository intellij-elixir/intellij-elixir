package org.elixir_lang.model.psi.config

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.PlatformTestCase
import java.io.File

class ConfigFilesTest : PlatformTestCase() {
    fun testDefaultConfigDirectoryCounts() {
        mixExs("my_app", "[app: :my_app]")
        val config = file("my_app/config/config.exs")
        val dev = file("my_app/config/dev.exs")
        val test = file("my_app/test/test_helper.exs")

        assertCounts(config, dev)
        assertDoesNotCount(test)
    }

    fun testConfigPathDirectoryCounts() {
        mixExs("my_app", "[app: :my_app, config_path: \"settings/main.exs\"]")
        val settings = file("my_app/settings/main.exs")
        val config = file("my_app/config/config.exs")

        assertCounts(settings)
        assertDoesNotCount(config)
    }

    fun testUmbrellaChildCountsTheRootConfig() {
        mixExs("umbrella/apps/child", "[app: :child, config_path: \"../../config/config.exs\"]")
        val rootConfig = file("umbrella/config/config.exs")
        val childConfig = file("umbrella/apps/child/config/config.exs")

        assertCounts(rootConfig)
        assertDoesNotCount(childConfig)
    }

    fun testUmbrellaChildWithoutConfigPathCountsItsOwnConfig() {
        mixExs("umbrella", "[apps_path: \"apps\"]")
        mixExs("umbrella/apps/child", "[app: :child]")
        val rootConfig = file("umbrella/config/config.exs")
        val childConfig = file("umbrella/apps/child/config/config.exs")

        assertCounts(rootConfig, childConfig)
    }

    fun testRuntimeExsCounts() {
        mixExs("my_app", "[app: :my_app]")

        assertCounts(file("my_app/config/runtime.exs"))
    }

    fun testDependencyConfigDoesNotCount() {
        mixExs("my_app", "[app: :my_app]")
        val dependency = FileUtil.createTempDirectory("dependency", null)
        File(dependency, "mix.exs").writeText(mixExsText("[app: :library_app]"))
        File(dependency, "config").mkdir()
        File(dependency, "config/config.exs").writeText(CONFIG)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dependency)!!
        val library = WriteAction.computeAndWait<Library, Throwable> {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary("library_app").also { library ->
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.SOURCES)
                    commit()
                }
            }
        }
        ModuleRootModificationUtil.addDependency(myFixture.module, library)

        try {
            assertCounts(file("my_app/config/config.exs"))
            assertDoesNotCount(root.findFileByRelativePath("config/config.exs")!!)
        } finally {
            ModuleRootModificationUtil.removeDependency(myFixture.module, library)
            WriteAction.runAndWait<Throwable> { LibraryTablesRegistrar.getInstance().getLibraryTable(project).removeLibrary(library) }
            FileUtil.delete(dependency)
        }
    }

    fun testNonLiteralConfigPathCountsNothing() {
        mixExs("my_app", "[app: :my_app, config_path: Path.join(\"config\", \"config.exs\")]")

        assertDoesNotCount(file("my_app/config/config.exs"))
    }

    private fun assertCounts(vararg files: VirtualFile) {
        val scope = ConfigFiles.scope(project)

        for (file in files) assertTrue("${file.path} does not count", scope.contains(file))
    }

    private fun assertDoesNotCount(vararg files: VirtualFile) {
        val scope = ConfigFiles.scope(project)

        for (file in files) assertFalse("${file.path} counts", scope.contains(file))
    }

    private fun mixExs(directory: String, project: String) {
        myFixture.addFileToProject("$directory/mix.exs", mixExsText(project))
    }

    private fun mixExsText(project: String): String =
        "defmodule MixProject do\n  use Mix.Project\n\n  def project do\n    $project\n  end\nend\n"

    private fun file(path: String): VirtualFile = myFixture.addFileToProject(path, CONFIG).virtualFile

    private companion object {
        const val CONFIG = "import Config\n\nconfig :my_app, greeting: \"hello\"\n"
    }
}
