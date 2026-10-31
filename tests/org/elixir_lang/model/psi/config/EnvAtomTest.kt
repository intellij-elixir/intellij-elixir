package org.elixir_lang.model.psi.config

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.psi.call.Call

/** The atom an alias or `__MODULE__` key names where the file's expansion reached it. */
class EnvAtomTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
        enableExpander(testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testAliasIsItsElixirAtom() {
        val key = key("defmodule MyApp.Worker do\n  def repo, do: Application.get_env(:my_app, MyApp.Repo)\nend\n")

        assertEquals("Elixir.MyApp.Repo", KeyAtom.of(key))
    }

    fun testAliasedAliasExpands() {
        val key = key(
            "defmodule MyApp.Worker do\n  alias MyApp.Repo\n\n  def repo, do: Application.get_env(:my_app, Repo)\nend\n",
        )

        assertEquals("Elixir.MyApp.Repo", KeyAtom.of(key))
    }

    fun testModuleIsTheEnclosingModule() {
        val key = key("defmodule MyApp.Worker do\n  def own, do: Application.get_env(:my_app, __MODULE__)\nend\n")

        assertEquals("Elixir.MyApp.Worker", KeyAtom.of(key))
    }

    fun testAliasOfErlangModuleIsThatAtom() {
        val key = key(
            "defmodule MyApp.Worker do\n  alias :lists, as: L\n\n  def lists, do: Application.get_env(:my_app, L)\nend\n",
        )

        assertEquals("lists", KeyAtom.of(key))
    }

    fun testKeyAfterAStopGivesNull() {
        val key = key(
            "defmodule MyApp.Worker do\n  def repo do\n    alias MyApp.Repo, as: R\n    1.f()\n    Application.get_env(:my_app, R)\n  end\nend\n",
        )

        assertNull(KeyAtom.of(key))
    }

    fun testKeyInsideAnUnportedFormGivesNull() {
        val key = key(
            "defmodule MyApp.Worker do\n  def repo do\n    quote(location: :other, do: Application.get_env(:my_app, MyApp.Repo))\n  end\nend\n",
        )

        assertNull(KeyAtom.of(key))
    }

    fun testKeyInAMacroCallBeforeAStopInTheModuleBodyIsItsAtom() {
        val key = key(
            "defmodule MyApp.Worker do\n  require Logger\n\n" +
                "  def repo, do: Logger.info(inspect(Application.get_env(:my_app, __MODULE__)))\n\n" +
                "  quote(location: :other, do: :ok)\nend\n",
        )

        assertEquals("Elixir.MyApp.Worker", KeyAtom.of(key))
    }

    fun testKeyInAModuleAfterAnUnportedModuleNameGivesNull() {
        val key = key(
            "defmodule String.to_atom(\"A\") do\nend\n\n" +
                "defmodule MyApp.Worker do\n  alias MyApp.Repo, as: R\n\n  def repo, do: Application.get_env(:my_app, R)\nend\n",
        )

        assertNull(KeyAtom.of(key))
    }

    fun testKeyInAModuleAfterARaisingModuleGivesNull() {
        val key = key(
            "defmodule MyApp.Broken do\n  def broken, do: undefined_function()\nend\n\n" +
                "defmodule MyApp.Worker do\n  alias MyApp.Repo, as: R\n\n  def repo, do: Application.get_env(:my_app, R)\nend\n",
        )

        assertNull(KeyAtom.of(key))
    }

    fun testKeyInANestedModuleAfterARaiseInTheBodyAroundItGivesNull() {
        val key = key(
            "defmodule MyApp.Outer do\n  :erlang.error(:boom)\n\n" +
                "  defmodule Worker do\n    alias MyApp.Repo, as: R\n\n    def repo, do: Application.get_env(:my_app, R)\n  end\nend\n",
        )

        assertNull(KeyAtom.of(key))
    }

    fun testDumbModeGivesNull() {
        val key = key("defmodule MyApp.Worker do\n  def repo, do: Application.get_env(:my_app, MyApp.Repo)\nend\n")

        assertNull(DumbModeTestUtils.computeInDumbModeSynchronously(project) { KeyAtom.of(key) })
    }

    fun testNoSdkInputsGivesNull() {
        val key = key("defmodule MyApp.Worker do\n  def repo, do: Application.get_env(:my_app, MyApp.Repo)\nend\n")
        ModuleRootModificationUtil.setModuleSdk(module, null)

        assertNull(KeyAtom.of(key))
    }

    /** The key of the one read in `lib/worker.ex` holding [text]. */
    private fun key(text: String): PsiElement =
        PsiTreeUtil.findChildrenOfType(myFixture.addFileToProject("lib/worker.ex", text), Call::class.java)
            .mapNotNull(Read::of)
            .single()
            .element
}
