package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** The definitions a module's table holds of those a macro's output wrote into it. */
class InjectedDefinitionTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    /** The `use` stops, as the evaluator declines a `receive`, after `defexception` made its definitions overridable. */
    fun testAStoppedModuleKeepsTheDefinitionsDefexceptionMadeOverridable() {
        val code = "defmodule U do\n  defmacro __using__(_), do: (receive do: (_ -> nil), after: (0 -> nil))\nend\n\n" +
            "defmodule A do\n  defexception [:message]\n  use U\nend\n"

        assertEquals(
            "module Elixir.A stopped `use U` | def __struct__/0 line 6 clauses 1 | def __struct__/1 line 6 clauses 1 | " +
                "def message/1 line 6 clauses 1 | def exception/1 line 6 clauses 2",
            render(code),
        )
    }

    /** Each `def*` of the `quote` is placed at itself in the macro's file; the module's own `def` is not injected. */
    fun testAnInjectedDefinitionIsPlacedAtItsDefInTheQuote() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      def f, do: 1\n      defp g(x), do: x\n" +
                "    end\n  end\nend\n",
        )

        assertEquals(
            "def f/0 b.ex line 4 `def f, do: 1` | defp g/1 b.ex line 5 `defp g(x), do: x`",
            injected("defmodule A do\n  use U\n  def h, do: 2\nend\n"),
        )
    }

    /** The caller's own `def`, which the output holds as the caller wrote it, is not injected. */
    fun testADefinitionTheCallerPassesIsNotInjected() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro wrap(do: block) do\n    quote do\n      def f, do: 1\n      unquote(block)\n" +
                "    end\n  end\nend\n",
        )

        assertEquals(
            "def f/0 b.ex line 4 `def f, do: 1`",
            injected("defmodule A do\n  require U\n\n  U.wrap do\n    def h, do: 2\n  end\nend\n"),
        )
    }

    /** The caller's own `def`, which a macro the `quote` calls passes through, is not placed at that macro's call. */
    fun testADefinitionTheCallerPassesThroughAQuotedMacroIsNotInjected() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro wrap(do: block) do\n    quote do\n      if true do\n        unquote(block)\n" +
                "      end\n    end\n  end\nend\n",
        )

        val code = "defmodule A do\n  require U\n\n  U.wrap do\n    def h, do: 2\n  end\nend\n"
        val module = moduleOf(code)

        assertEquals("module Elixir.A compiled | def h/0 line 5 clauses 1", ModuleRendering.render(code, module))
        assertEquals("", injected(module.table))
    }

    /** The `def` a `defdelegate` in the `quote` defines is placed at the `defdelegate`. */
    fun testADelegationIsPlacedAtItsDefdelegate() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defdelegate upcase(s), to: String\n" +
                "    end\n  end\nend\n",
        )

        assertEquals(
            "def upcase/1 b.ex line 4 `defdelegate upcase(s), to: String`",
            injected("defmodule A do\n  use U\nend\n"),
        )
    }

    /** Each head of an injected definition is placed at its own `def`: a bodiless head and each clause. */
    fun testEachHeadOfAnInjectedDefinitionIsPlacedAtItsOwnDef() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      def pad(a, b \\\\ 1)\n" +
                "      def pad(a, 0), do: a\n      def pad(a, b), do: {a, b}\n    end\n  end\nend\n",
        )

        assertEquals(
            "def pad/2 b.ex line 4 `def pad(a, b \\\\ 1)` | " +
                "def pad/2 b.ex line 5 `def pad(a, 0), do: a` | " +
                "def pad/2 b.ex line 6 `def pad(a, b), do: {a, b}`",
            injected("defmodule A do\n  use U\nend\n"),
        )
    }

    /** A macro the output calls injects its own `quote`'s `def`, placed in its own file, from the outer call. */
    fun testANestedMacrosDefinitionIsPlacedInItsOwnQuote() {
        add(
            "c.ex",
            "defmodule V do\n  defmacro define do\n    quote do\n      def v, do: 1\n    end\n  end\nend\n",
        )
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      require V\n      V.define()\n" +
                "    end\n  end\nend\n",
        )

        assertEquals("def v/0 c.ex line 4 `def v, do: 1`", injected("defmodule A do\n  use U\nend\n"))
    }

    /** A `def` the `quote` calls remotely is placed at itself, as a local one is. */
    fun testARemoteDefIsPlacedAtItsDef() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      Kernel.def f, do: 1\n    end\n  end\nend\n",
        )

        assertEquals("def f/0 b.ex line 4 `Kernel.def f, do: 1`", injected("defmodule A do\n  use U\nend\n"))
    }

    /** A `def` the macro rebuilds around its own metadata is placed at itself, as its metadata keeps its location. */
    fun testADefRebuiltAroundItsMetadataIsPlacedAtItsDef() = assertRebuiltIsPlaced("put_elem(ast, 1, elem(ast, 1))")

    fun testADefAWalkRebuildsIsPlacedAtItsDef() = assertRebuiltIsPlaced("Macro.prewalk(ast, & &1)")

    /** A `def` whose metadata the macro adds to keeps the entries that place it. */
    fun testADefWhoseMetadataIsUpdatedIsPlacedAtItsDef() =
        assertRebuiltIsPlaced("Macro.update_meta(ast, &Keyword.put(&1, :x, 1))")

    /** `U.__using__` quotes `def f` and returns it as [rebuild] rebuilds `ast`; `f` is placed at its `def`. */
    private fun assertRebuiltIsPlaced(rebuild: String) {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    ast = quote do\n      def f, do: 1\n    end\n\n" +
                "    $rebuild\n  end\nend\n",
        )

        assertEquals("def f/0 b.ex line 4 `def f, do: 1`", injected("defmodule A do\n  use U\nend\n"))
    }

    /** A `def` the caller wrote, which the macro rebuilds around its metadata, is placed where the caller wrote it. */
    fun testACallersDefTheMacroRebuildsIsPlacedAtItsDef() =
        assertCallersDefIsPlaced(
            "defmacro deco(do: {:def, meta, [head, [do: body]]}) do\n" +
                "    {:def, meta, [head, [do: quote(do: (_ = :wrapped; unquote(body)))]]}\n  end",
        )

    fun testACallersDefTheMacroWalksIsPlacedAtItsDef() =
        assertCallersDefIsPlaced("defmacro deco(do: block), do: Macro.prewalk(block, & &1)")

    /** `A`'s `def f`, given to `Deco.deco`, defined as [deco], is placed at its own `def`. */
    private fun assertCallersDefIsPlaced(deco: String) {
        add("deco.ex", "defmodule Deco do\n  $deco\nend\n")

        assertEquals(
            "def f/0 a.ex line 5 `def f, do: raise(\"x\")`",
            injected("defmodule A do\n  require Deco\n\n  Deco.deco do\n    def f, do: raise(\"x\")\n  end\nend\n"),
        )
    }

    /** Each `def` another macro quotes, which `__using__` expands with `Macro.expand/2`, is placed at its `def`. */
    fun testADefAnExpandedMacroQuotesIsPlacedAtItsDef() = assertExpandedIsPlaced("Macro.expand")

    fun testADefAMacroExpandedOnceQuotesIsPlacedAtItsDef() = assertExpandedIsPlaced("Macro.expand_once")

    /**
     * `U.__using__` gives `V.define()` as [expand] expands it, and each `def` `V.define` quotes is placed at itself. The
     * block of two isn't a macro, so `Macro.expand/2` leaves it as `Macro.expand_once/2` does.
     */
    private fun assertExpandedIsPlaced(expand: String) {
        add(
            "b.ex",
            "defmodule V do\n  defmacro define do\n    quote do\n      def f, do: 1\n      def g, do: 2\n    end\n  end\n" +
                "end\n\ndefmodule U do\n  defmacro __using__(_) do\n    $expand(quote(do: V.define()), __CALLER__)\n  end\n" +
                "end\n",
        )

        assertEquals(
            "def f/0 b.ex line 4 `def f, do: 1` | def g/0 b.ex line 5 `def g, do: 2`",
            injected("defmodule A do\n  require V\n  use U\nend\n"),
        )
    }

    /** A `.beam`'s macro, read from its `Dbgi`, injects definitions placed in its decompiled mirror's `__using__`. */
    fun testADebugInfoMacrosDefinitionIsPlacedInTheBeam() {
        assertEquals(
            "def stop/1 Elixir.Application.beam `defmacro __using__",
            injected("defmodule A do\n  use Application\nend\n").replace(Regex(" line \\d+ (`defmacro __using__)[^`]*`"), " $1"),
        )
    }

    private fun render(code: String): String = ModuleRendering.render(code, moduleOf(code))

    /** Module `A` of `a.ex`, holding [code]. */
    private fun moduleOf(code: String): ExpansionResult {
        val file = myFixture.addFileToProject("a.ex", code) as ElixirFile
        val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.20.4"), legKernel)

        return holder.expansion(file, inputs).modules.single { it.module == "Elixir.A" }
    }

    private fun injected(code: String): String = injected(moduleOf(code).table, code)

    /**
     * [table]'s injected heads, of the definitions not stored for a default arity, each as `<kind> <name>/<arity> <file>
     * line <n> `<first line>``, `|`-separated. A head placed in the file expanded is in `a.ex`, [code].
     */
    private fun injected(table: DefinitionTable, code: String? = null): String =
        table.entries.filterValues { !it.default }.flatMap { (nameArity, entry) ->
            entry.heads.mapNotNull { head ->
                val place = head.injected?.place ?: return@mapNotNull null
                val text = place.file?.let(::textOf) ?: checkNotNull(code) { "${nameArity.name}/${nameArity.arity} in the file expanded" }
                val line = text.substring(0, place.origin.startOffset).count { it == '\n' } + 1

                "${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} ${place.file?.name ?: "a.ex"} " +
                    "line $line `${firstLine(place.origin, text)}`"
            }
        }.joinToString(" | ")

    private fun add(name: String, code: String) {
        myFixture.addFileToProject(name, code)
    }

    /** [file]'s text: a source file's, or a `.beam`'s decompiled mirror's. */
    private fun textOf(file: VirtualFile): String = PsiManager.getInstance(project).findFile(file)!!.text

    private fun firstLine(range: TextRange, text: String) = range.substring(text).lineSequence().first()
}
