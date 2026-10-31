package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import java.util.concurrent.Callable

class ConfigTableTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    /** `read_config/1` is a native only from 1.18, so the two versions' reads differ. */
    fun testAnotherSdkVersionReadsItsOwnTable() {
        myFixture.addFileToProject("mix.exs", "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n")
        myFixture.addFileToProject("config/config.exs", "import Config\n\nconfig :app, k: 1\nconfig :app, j: Config.read_config(:app)[:k]\n")
        val reader = myFixture.addFileToProject("lib/reader.ex", "defmodule Reader do\nend\n").virtualFile
        val before = ProjectModules.Inputs(ElixirLanguageLevel.of("1.17.3"), legKernel, OS.CURRENT)
        val after = ProjectModules.Inputs(ElixirLanguageLevel.of("1.18.0"), legKernel, OS.CURRENT)
        val table = { inputs: ProjectModules.Inputs ->
            ReadAction.nonBlocking(Callable { holder.configTable(reader, inputs, "dev") }).executeSynchronously()
        }

        assertEquals("[{app,[{k,1},{j,1}]}]", table(after)!!.config.toString())
        assertFalse(table(before)!!.config.toString() == table(after)!!.config.toString())
    }
}
