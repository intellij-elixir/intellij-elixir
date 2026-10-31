package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileContentImpl
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk

/** What the config index stores for a file does not depend on the Experimental setting. */
class ConfigIndexFlagTest : PlatformTestCase() {
    private var savedProperty: String? = null

    override fun setUp() {
        super.setUp()
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    /** One file holding each shape the setting moves a read's, a setting's or an import's answer for. */
    fun testTheIndexedEntriesDoNotMoveWithTheFlag() {
        val file = myFixture.configureByText(
            "config.exs",
            """
            import Config

            alias Config, as: C
            C.config(:my_app, MyApp.Repo, x: 1)
            C.import_config("x.exs")

            if true do
              import Config
              config :my_app, :nested, 1
            end

            alias Application, as: A
            A.put_env(:my_app, :flag, true)
            A.get_env(:my_app, MyApp.Repo)

            alias Mix, as: M
            import_config "#{M.env()}.exs"

            defmodule MyApp.Settings do
              import Application

              @config Config
              @application Application

              def read do
                @config.config(:my_app, :attribute, 1)
                @application.get_env(:my_app, :attribute)
                get_env(:my_app, __MODULE__)
                Application.get_env(:my_app, MyApp.Repo)
              end
            end
            """.trimIndent() + "\n",
        ).virtualFile

        val off = index(file)
        val on = Disposer.newDisposable().let { enabled ->
            enableExpander(enabled)
            try {
                index(file)
            } finally {
                Disposer.dispose(enabled)
            }
        }

        assertTrue("the file has entries", off.isNotEmpty())
        assertEquals(off, on)
    }

    private fun index(file: VirtualFile): Map<ConfigIndexKey, List<ConfigEntry>> =
        ConfigIndexing.INDEXER.map(FileContentImpl.createByFile(file, project))
}
