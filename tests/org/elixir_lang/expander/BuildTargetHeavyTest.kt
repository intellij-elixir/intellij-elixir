package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.buildEnv
import java.io.File
import java.util.concurrent.Callable
import org.elixir_lang.model.psi.config.disableMixDepsCheck

/**
 * A module body's `Mix.env/0` and `Mix.target/0` answer the build the file is compiled in: `:test` for a project file in
 * test-source content, `:dev` for any other, and for a dependency, which Mix compiles under an env of its own, no answer.
 * Each expected value for `lib/` and `test/` is what `mix compile` and `mix test` gave on Elixir 1.20.4 and 1.11.4; Mix
 * can't say by path alone what another folder builds in, so there it is that default.
 */
class BuildTargetHeavyTest : HeavyTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testAModuleBodyInLibSeesTheDevEnv() {
        val root = project("lib/in_lib.ex" to BODY)

        assertEquals("compiled [dev_only/0, dev_or_test/0, host/0]", defines(root, "lib/in_lib.ex"))
    }

    fun testAModuleBodyUnderTestSeesTheTestEnv() {
        val root = project("lib/in_lib.ex" to "defmodule InLib do\nend\n", "test/support/in_test.ex" to BODY)

        assertEquals("compiled [dev_or_test/0, host/0, test_only/0]", defines(root, "test/support/in_test.ex"))
    }

    /** A folder marked as test sources builds in `test`, by what the module body reads and by the copy it loads. */
    fun testASpecFileAndItsBuildCopyAgree() {
        val root = project("lib/in_lib.ex" to "defmodule InLib do\nend\n", "spec/support/in_spec.ex" to BODY)
        PsiTestUtil.addSourceRoot(module, root.findChild("spec")!!, true)
        dependencyBuilt(root)

        assertEquals(
            "compiled [dev_or_test/0, host/0, test_only/0] _build/test",
            "${defines(root, "spec/support/in_spec.ex")} ${buildLoaded(root, "spec/support/in_spec.ex")}",
        )
    }

    /** A `test/` not marked as test sources builds in `dev`, by what the module body reads and by the copy it loads. */
    fun testAnUnmarkedTestDirectoryAndItsBuildCopyAgree() {
        val root = project("lib/in_lib.ex" to "defmodule InLib do\nend\n", "test/support/in_test.ex" to BODY)
        PsiTestUtil.removeSourceRoot(module, root.findChild("test")!!)
        dependencyBuilt(root)

        assertEquals(
            "compiled [dev_only/0, dev_or_test/0, host/0] _build/dev",
            "${defines(root, "test/support/in_test.ex")} ${buildLoaded(root, "test/support/in_test.ex")}",
        )
    }

    fun testAMarkingChangeMovesTheExpansion() {
        val root = project("lib/in_lib.ex" to "defmodule InLib do\nend\n", "spec/in_spec.ex" to BODY)

        assertEquals("compiled [dev_only/0, dev_or_test/0, host/0]", defines(root, "spec/in_spec.ex"))

        PsiTestUtil.addSourceRoot(module, root.findChild("spec")!!, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertEquals("compiled [dev_or_test/0, host/0, test_only/0]", defines(root, "spec/in_spec.ex"))
    }

    /** Mix compiles a dependency under `:prod` unless its entry says otherwise, whichever `_build` env holds it. */
    fun testADependencysMixEnvIsUnknown() {
        val root = project(
            "lib/in_lib.ex" to "defmodule InLib do\nend\n",
            "deps/dep/mix.exs" to "defmodule Dep.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :dep, version: \"0.1.0\"]\nend\n",
            "deps/dep/lib/in_dep.ex" to "defmodule InDep do\n  if Mix.env() == :dev, do: def(dev_only, do: 1)\n  if Mix.env() != :dev, do: def(not_dev, do: 1)\nend\n",
            "_build/dev/lib/dep/ebin/dep.app" to "{application, dep, [{vsn, \"0.1.0\"}, {modules, []}]}.\n",
        )

        assertEquals("compiled [dev_only/0, not_dev/0]", defines(root, "deps/dep/lib/in_dep.ex"))
    }

    /** The dependency's table asked first, then a `lib/` file's, as each build's env is its own. */
    fun testADependencyBuiltInOneEnvReadsThatEnvsTable() {
        val reads = "  @k Application.get_env(:dep, :k)\n  if @k == 2, do: def(two, do: 1)\n  if @k == 3, do: def(three, do: 1)\n"
        val root = project(
            "config/config.exs" to "import Config\n\nconfig :dep, k: 1\nimport_config \"#{Mix.env()}.exs\"\n",
            "config/dev.exs" to "import Config\n\nconfig :dep, k: 2\n",
            "config/test.exs" to "import Config\n\nconfig :dep, k: 3\n",
            "lib/in_lib.ex" to "defmodule InLib do\n${reads}end\n",
            "deps/dep/mix.exs" to "defmodule Dep.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :dep, version: \"0.1.0\"]\nend\n",
            "deps/dep/lib/in_dep.ex" to "defmodule InDep do\n${reads}end\n",
            "_build/test/lib/dep/ebin/dep.app" to "{application, dep, [{vsn, \"0.1.0\"}, {modules, []}]}.\n",
        )

        assertEquals("compiled [three/0]", defines(root, "deps/dep/lib/in_dep.ex"))
        assertEquals("compiled [two/0]", defines(root, "lib/in_lib.ex"))
    }

    fun testMixTargetIsHost() {
        val root = project("lib/in_lib.ex" to "defmodule InLib do\n  @target Mix.target()\n  if @target == :host, do: def(host, do: 1)\n  if @target != :host, do: def(other, do: 1)\nend\n")

        assertEquals("compiled [host/0]", defines(root, "lib/in_lib.ex"))
    }

    private fun defines(root: VirtualFile, path: String): String =
        defines(holder, PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!)

    /** The `_build/<env>` of the copy of the built dependency that [path]'s file loads. */
    private fun buildLoaded(root: VirtualFile, path: String): String {
        val located = ReadAction.nonBlocking(Callable {
            holder.locate(PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!, "Elixir.$DEP", legInputs)
        }).executeSynchronously() as ProjectModules.Located.Compiled

        return "_build/${buildEnv(located.beam)}"
    }

    /** A dependency's `.beam` in `_build/dev` and `_build/test`, attached as Mix sync attaches it. */
    private fun dependencyBuilt(root: VirtualFile) {
        val ebins = listOf("dev", "test").map { env ->
            val beam = File(root.path, "_build/$env/lib/d/ebin/$DEP_BEAM")
            FileUtil.writeToFile(beam, File("testData/org/elixir_lang/expander/loaded_copy/$DEP_BEAM").readBytes())
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam.parentFile)!!
        }
        PsiTestUtil.addExcludedRoot(module, root.findChild("_build")!!)
        BeamLibraryFixture.addLibrary(project, module, "d", ebins)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun project(vararg sources: Pair<String, String>): VirtualFile {
        val directory = createTempDir("app")
        (listOf("mix.exs" to MIX_EXS) + sources).forEach { (path, text) -> FileUtil.writeToFile(File(directory, path), text) }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        root.findChild("test")?.let { PsiTestUtil.addSourceRoot(module, it, true) }
        root.findChild("deps")?.let { PsiTestUtil.addExcludedRoot(module, it) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    private companion object {
        const val DEP = "LoadedCopy.Dep"
        const val DEP_BEAM = "Elixir.LoadedCopy.Dep.beam"
        const val MIX_EXS = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"

        const val BODY =
            "defmodule InBuild do\n  if Mix.env() == :test, do: def(test_only, do: 1)\n  @env Mix.env()\n" +
                "  if @env == :dev, do: def(dev_only, do: 1)\n  if Mix.env() in [:dev, :test], do: def(dev_or_test, do: 1)\n" +
                "  if Mix.target() == :host, do: def(host, do: 1)\nend\n"
    }
}
