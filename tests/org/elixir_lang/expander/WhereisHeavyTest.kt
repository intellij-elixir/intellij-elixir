package org.elixir_lang.expander

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.elixir_surface.LegManifest
import java.io.File

/**
 * `Process.whereis/1` while `mix compile` compiles a `.ex` file: `nil` for a module of the project or of a dependency,
 * which neither Mix nor Elixir's compiler registers, while nothing the expander couldn't follow has run. Every other
 * name, in every other build, isn't known. Gettext's `extracting?/0` reads its agent this way, so `gettext/1` in a
 * function body expands.
 */
class WhereisHeavyTest : MixProjectHeavyTestCase() {
    /** Gettext's `extracting?/0` shape in a dependency's macro, called from a function body. */
    fun testADependencysMacroReadingItsAgentExpandsInAFunctionBody() {
        val root = project("lib/a.ex" to caller(), *MACRO)
        dependencySource(root)

        assertEquals("f/0 expanded", units(root, "lib/a.ex"))
    }

    fun testADependencysModuleFromItsBeamIsUndefined() {
        val root = project("lib/a.ex" to reader(DEP))
        dependencyBuilt(root)

        assertEquals(UNDEFINED, defines(root, "lib/a.ex"))
    }

    fun testADependencysModuleFromItsSourceIsUndefined() {
        val root = project("lib/a.ex" to reader("Dep.Agent"), *MACRO)
        dependencySource(root)

        assertEquals(UNDEFINED, defines(root, "lib/a.ex"))
    }

    fun testAProjectModuleIsUndefined() {
        val root = project("lib/a.ex" to reader("B"), "lib/b.ex" to "defmodule B do\nend\n")

        assertEquals(UNDEFINED, defines(root, "lib/a.ex"))
    }

    /** A test, or any script a task runs, may run after applications have started. */
    fun testAScriptsReadIsUnknown() {
        val root = project(
            "test/a_test.exs" to caller("ATest"),
            "priv/script.exs" to caller("Script"),
            *MACRO,
        )
        dependencySource(root)

        assertEquals(
            "test/a_test.exs f/0 opaque\npriv/script.exs f/0 opaque",
            listOf("test/a_test.exs", "priv/script.exs").joinToString("\n") { "$it ${units(root, it)}" },
        )
    }

    /** Mix's own processes run while it compiles. */
    fun testAnSdkModuleIsUnknown() {
        val root = project("lib/a.ex" to reader("Mix.ProjectStack"), "lib/b.ex" to reader("Mix.PubSub", "B"))

        assertEquals(
            "lib/a.ex $UNKNOWN\nlib/b.ex $UNKNOWN",
            listOf("lib/a.ex", "lib/b.ex").joinToString("\n") { "$it ${defines(root, it)}" },
        )
    }

    /** A name no module has may be any process's. */
    fun testANameNoModuleHasIsUnknown() {
        val root = project("lib/a.ex" to reader(":nobody"))

        assertEquals(UNKNOWN, defines(root, "lib/a.ex"))
    }

    /**
     * A compiler the project adds runs before Elixir's compiler and could register the name, and isn't read: a miss
     * accepted, as the compiler every `mix phx.new` app adds, `:phoenix_live_view`'s, registers none.
     */
    fun testAProjectThatAddsACompilerIsStillUndefined() {
        val root = project(
            "lib/a.ex" to reader(DEP),
            mixExs = mixExs("compilers: [:gettext] ++ Mix.compilers()"),
        )
        dependencyBuilt(root)

        assertEquals(UNDEFINED, defines(root, "lib/a.ex"))
    }

    /** Where any copy of the name is the SDK's, the SDK's application may have registered it. */
    fun testAnSdkModulesNameWithADependencysCopyChosenIsUnknown() {
        val root = project("lib/a.ex" to reader("Enumerable"))
        dependencyBuilt(root, ENUMERABLE_BEAM, File(LegManifest.ebin(), ENUMERABLE_BEAM).readBytes())

        assertEquals(UNKNOWN, defines(root, "lib/a.ex"))
    }

    /** Code the expander couldn't follow may have started the process. */
    fun testANameAfterADeclineTheWalkWentOnPastIsUnknown() {
        val root = project("lib/a.ex" to reader(DEP, before = "  _ = :gen.call(self(), :x, :y)\n"))
        dependencyBuilt(root)

        assertEquals(UNKNOWN, defines(root, "lib/a.ex"))
    }

    /** With no `mix.exs` above, no `mix compile` is known to compile the file. */
    fun testAFileNoMixProjectHoldsIsUnknown() {
        val root = project("lib/a.ex" to reader(DEP), mixExs = null)
        dependencyBuilt(root)

        assertEquals(UNKNOWN, defines(root, "lib/a.ex"))
    }

    /** Each function body of the one module [path]'s file under [root] defines, and how its expansion ended. */
    private fun units(root: VirtualFile, path: String): String =
        expansion(holder, psi(root, path)).modules.single().units
            .mapNotNull { unit ->
                (unit.owner as? ExpansionResult.Owner.Definition)?.let { owner ->
                    "${owner.name}/${owner.arity} ${unit.expansion::class.simpleName!!.lowercase()}"
                }
            }
            .joinToString("\n")

    /** `deps/dep/lib` attached as Mix sync attaches a dependency's sources, with no build. */
    private fun dependencySource(root: VirtualFile) {
        BeamLibraryFixture.addLibrary(project, module, "dep", emptyList(), listOf(root.findFileByRelativePath("deps/dep/lib")!!))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private companion object {
        const val ENUMERABLE_BEAM = "Elixir.Enumerable.beam"
        const val UNDEFINED = "compiled [undefined/0]"
        const val UNKNOWN = "compiled [registered/0, undefined/0]"

        /**
         * A module [module] whose body, after [before], defines `undefined/0` where `Process.whereis(name)` is `nil`,
         * else `registered/0`.
         */
        fun reader(name: String, module: String = "A", before: String = ""): String =
            "defmodule $module do\n$before  if Process.whereis($name), do: def(registered, do: 1), else: def(undefined, do: 1)\nend\n"

        /** A dependency's macro that reads its agent as Gettext's `extracting?/0` does, and the agent's module. */
        val MACRO = arrayOf(
            "deps/dep/lib/dep.ex" to """
                |defmodule Dep do
                |  defmacro t(text) do
                |    if Process.whereis(Dep.Agent) && Agent.get(Dep.Agent, & &1) do
                |      quote do: {:extracting, unquote(text)}
                |    else
                |      quote do: Dep.translate(unquote(text))
                |    end
                |  end
                |
                |  def translate(text), do: text
                |end
                |
                |defmodule Dep.Agent do
                |  def start_link, do: Agent.start_link(fn -> false end, name: __MODULE__)
                |end
                |
            """.trimMargin(),
        )

        /** A module [module] whose `f/0` calls [MACRO]'s macro. */
        fun caller(module: String = "A"): String = "defmodule $module do\n  require Dep\n  def f, do: Dep.t(\"close\")\nend\n"
    }
}
