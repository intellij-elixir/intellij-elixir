package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.NameArity
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.code_insight.destinationLine
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.scope.WalkTestSupport
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** The definitions a module's table holds of those a macro's output wrote into it. */
class InjectedDefinitionTest : ExpanderSettingTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    /**
     * The `use` stops after `defexception` made its definitions overridable; the stopped module keeps both arms'
     * `__struct__/1` up to 1.13.
     */
    fun testAStoppedModuleKeepsTheDefinitionsDefexceptionMadeOverridable() {
        val code = "defmodule U do\n  defmacro __using__(_), do: (:erlang.send(:erlang.self(), :decline); receive do: (:decline -> nil; _ -> nil), after: (0 -> nil))\nend\n\n" +
            "defmodule A do\n  defexception [:message]\n  use U\nend\n"

        assertEquals(
            "module Elixir.A stopped `use U` | def __struct__/0 line 6 clauses 1 | " +
                "def __struct__/1 line 6 clauses ${stoppedStructClauses(legLevel())} | " +
                "def message/1 line 6 clauses 1 | def exception/1 line 6 clauses 2",
            render(code),
        )
    }

    /** What a `@before_compile` callback's output defines before a call that stops is the stopped module's. */
    fun testAStoppedBeforeCompileCallbackKeepsWhatItDefined() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro stop, do: (:erlang.send(:erlang.self(), :decline); receive do: (:decline -> nil; _ -> nil), after: (0 -> nil))\n\n" +
                "  defmacro __before_compile__(_) do\n    quote do\n      def f, do: 1\n      U.stop()\n    end\n" +
                "  end\nend\n",
        )
        val code = "defmodule A do\n  require U\n  @before_compile U\nend\n"

        assertEquals("module Elixir.A stopped `${code.trimEnd()}` | def f/0 line 1 clauses 1", render(code))
    }

    /** What a `@before_compile` callback's output defines before a call that raises is kept, as the body's is. */
    fun testARaisingBeforeCompileCallbackKeepsWhatItDefined() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __before_compile__(_) do\n    quote do\n      def f, do: 1\n      1.f()\n" +
                "    end\n  end\nend\n",
        )
        val code = "defmodule A do\n  @before_compile U\nend\n"

        assertEquals("module Elixir.A raised invalid_call `${code.trimEnd()}` | def f/0 line 1 clauses 1", render(code))
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
    fun testACallersDefTheMacroRebuildsIsPlacedAtItsDef() {
        add(
            "deco.ex",
            "defmodule Deco do\n  defmacro deco(do: {:def, meta, [head, [do: body]]}) do\n" +
                "    {:def, meta, [head, [do: quote(do: (_ = :wrapped; unquote(body)))]]}\n  end\nend\n",
        )

        assertEquals(
            "def f/0 a.ex line 5 `def f, do: raise(\"x\")`",
            injected("defmodule A do\n  require Deco\n\n  Deco.deco do\n    def f, do: raise(\"x\")\n  end\nend\n"),
        )
    }

    /** The term the walk gives back equals what the macro was given, so it is the caller's node, as a pass-through is. */
    fun testACallersDefTheMacroWalksIsNotInjected() {
        add("deco.ex", "defmodule Deco do\n  defmacro deco(do: block), do: Macro.prewalk(block, & &1)\nend\n")

        assertEquals("", injected("defmodule A do\n  require Deco\n\n  Deco.deco do\n    def f, do: raise(\"x\")\n  end\nend\n"))
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
            withoutUsingLine(injected("defmodule A do\n  use Application\nend\n")),
        )
    }

    /**
     * A definition injected from a `.beam`'s `__using__` is placed nowhere once the `.beam` is replaced by a compile whose
     * `__using__` is another clause, though where it was in the old mirror falls inside another call of the new one.
     */
    fun testADefinitionPlacedInABeamReplacedSinceIsPlacedNowhere() {
        val (before, after) = beams(
            "defmodule Placed do\n  defmacro __using__(_) do\n    quote do\n      def f, do: 1\n    end\n  end\nend\n",
            "defmodule Placed do\n  defmacro __a__(x) do\n    x\n    |> List.wrap()\n    |> Enum.map(&(&1 + 1))\n" +
                "    |> Enum.map(&(&1 * 2))\n    |> Enum.map(&(&1 - 3))\n    |> Enum.map(&(&1 + 4))\n    |> Enum.map(&(&1 * 5))\n" +
                "    |> Enum.map(&(&1 - 6))\n    |> Enum.map(&(&1 + 7))\n    |> Enum.sum()\n  end\n\n" +
                "  defmacro __using__(_) do\n    quote do\n      def g, do: 2\n    end\n  end\nend\n",
        )

        withBeam("Elixir.Placed.beam", before) { root ->
            val beam = root.findChild("Elixir.Placed.beam")!!
            val file = myFixture.addFileToProject("a.ex", "defmodule A do\n  use Placed\nend\n") as ElixirFile
            val result = ProjectModules.results(holder.expansion(file, legInputs)).single { it.module == "Elixir.A" }
            val place = result.table[NameArity("f", 0)]!!.heads.single().injected!!.place!!
            val used = read { ExpanderSource.placed(file, place)!!.let { it.text.lineSequence().first() to it.textRange } }

            assertEquals("defmacro __using__(_) do", used.first)

            WriteAction.runAndWait<Throwable> { beam.setBinaryContent(after) }

            assertEquals(
                "defmacro __a__/1",
                read {
                    mirroredClauses(holder.mirror(beam)!!.modulars().single()).entries
                        .single { (_, clauses) -> clauses.any { it.textRange.contains(used.second) } }
                        .key.let { "${it.macro} ${it.name}/${it.arity}" }
                },
            )
            assertNull(read { ExpanderSource.placed(file, place) })
        }
    }

    /**
     * A macro a `use` injected into another module, called from a third, places the `def` its `quote` writes at that
     * `def`, not at the `use` that injected the macro.
     */
    fun testADefinitionAUseInjectedMacroWritesIsPlacedAtItsDef() {
        addUsedMacro("          def h, do: 1\n")

        assertEquals("def h/0 b.ex line 6 `def h, do: 1`", injected(bCallingA().table))
    }

    /** The module that calls the injected macro, in the file of the module the `use` injected it into, places it alike. */
    fun testADefinitionAUseInjectedMacroWritesInTheSameFileIsPlacedAtItsDef() {
        addUsedMacro("          def h, do: 1\n")

        assertEquals(
            "def h/0 b.ex line 6 `def h, do: 1`",
            injected(moduleOf("defmodule A do\n  use U\nend\n\ndefmodule B do\n  require A\n  A.g()\nend\n", "Elixir.B").table),
        )
    }

    /** A `@callback` a `use`-injected macro's `quote` writes is placed at the `@callback`. */
    fun testACallbackAUseInjectedMacroWritesIsPlacedAtItsCallback() {
        addUsedMacro("          @callback c() :: term\n")

        assertEquals("callback c/0 b.ex line 6 `@callback c() :: term`", injectedCallbacks(bCallingA()))
    }

    /** Three macros deep, each `quote` stored by the module whose run expanded it, the `def` keeps its place. */
    fun testADefinitionThreeMacrosDeepIsPlacedAtItsDef() {
        addUsedMacro("          defmacro m do\n            quote do\n              def h, do: 1\n            end\n          end\n")
        add("c.ex", "defmodule A do\n  use U\nend\n")
        add("d.ex", "defmodule B do\n  require A\n  A.g()\nend\n")

        assertEquals(
            "def h/0 b.ex line 8 `def h, do: 1`",
            injected(moduleOf("defmodule C do\n  require B\n  B.m()\nend\n", "Elixir.C").table),
        )
    }

    /** A `defdelegate` a `use`-injected macro's `quote` writes is placed at the `defdelegate`. */
    fun testADelegationAUseInjectedMacroWritesIsPlacedAtItsDefdelegate() {
        addUsedMacro("          defdelegate up(s), to: String, as: :upcase\n")

        assertEquals(
            "def up/1 b.ex line 6 `defdelegate up(s), to: String, as: :upcase`",
            injected(bCallingA().table),
        )
    }

    /** A `defmacro` a `use`-injected macro's `quote` writes is placed at the `defmacro`. */
    fun testAMacroAUseInjectedMacroWritesIsPlacedAtItsDefmacro() {
        addUsedMacro("          defmacro k, do: 1\n")

        assertEquals("defmacro k/0 b.ex line 6 `defmacro k, do: 1`", injected(bCallingA().table))
    }

    /** A macro a `.beam`'s `__using__` injected places the `def` its `quote` writes in the `.beam`'s `__using__`. */
    fun testADefinitionAnInjectedMacroOfABeamWritesIsPlacedInTheBeam() {
        withBeam("Elixir.U.beam", beams(usedMacro("          def h, do: 1\n")).single()) {
            assertEquals("def h/0 Elixir.U.beam `defmacro __using__", withoutUsingLine(injected(bCallingA().table)))
        }
    }

    /**
     * A place record weighs a node for each entry, the nodes it holds counted once with the clauses that hold them: here
     * one entry, `def h`'s, which `g`'s `quote` escaped from `use U`'s output.
     * The clause cache's own weight tests can't hold this, as a `def` a macro's output writes needs the leg's `Kernel`.
     */
    fun testAPlaceRecordWeighsWhatItRetains() {
        val a = myFixture.addFileToProject("a.ex", "defmodule Asker do\nend\n")
        addUsedMacro("          def h, do: 1\n")
        add("c.ex", "defmodule B do\n  use U\nend\n")
        val b = clauses(holder, a, "Elixir.B")!!
        val unplaced = with(b) { ModuleClauses(module, definitions, source, quotes, dispatches, ModuleClauses.Places(), file) }

        assertEquals(1, b.places.nodes.size)
        assertEquals(ModuleClauses.BYTES_PER_NODE, b.weight() - unplaced.weight())
    }

    /** A `def` a `use`-injected macro builds as a literal tuple is placed at that tuple. */
    fun testADefinitionALiteralTupleOfAUseInjectedMacroBuildsIsPlacedAtTheTuple() {
        addUsing("      defmacro g, do: {:def, [], [{:h, [], nil}, [do: 1]]}\n")

        assertEquals(
            "def h/0 b.ex line 4 `{:def, [], [{:h, [], nil}, [do: 1]]}`",
            injected(bCallingA().table),
        )
    }

    /** A `def` a macro's clause gets from another macro's output, as a tuple that output builds, is placed at the tuple. */
    fun testADefinitionATupleAMacrosOutputBuildsInAnotherMacroIsPlacedAtTheTuple() {
        add("b.ex", "defmodule V do\n  defmacro mk, do: quote(do: {:def, [], [{:h, [], nil}, [do: 1]]})\nend\n")
        add("c.ex", "defmodule A do\n  require V\n  defmacro g, do: V.mk()\nend\n")

        assertEquals(
            "def h/0 b.ex line 2 `{:def, [], [{:h, [], nil}, [do: 1]]}`",
            injected(moduleOf("defmodule B do\n  require A\n  A.g()\nend\n", "Elixir.B").table),
        )
    }

    /** Each definition the output injected names the call it came from; the module's own `def` names none. */
    fun testAnInjectedDefinitionNamesTheUseThatInjectedIt() {
        addUsing("      def f, do: 1\n      defp g(x), do: x\n")

        assertEquals("def f/0 by `use U` (__using__) | defp g/1 by `use U` (__using__)", injectedBy("defmodule A do\n  use U\n  def h, do: 2\nend\n"))
    }

    /** A module the output defines, which compiles after the output, holds its definitions as the call injected them. */
    fun testANestedModulesDefinitionIsInjected() {
        addUsing("      defmodule Inner do\n        def f, do: 1\n      end\n")
        val code = "defmodule A do\n  use U\nend\n"
        val inner = moduleOf(code, "Elixir.A.Inner")

        assertEquals("def f/0 b.ex line 5 `def f, do: 1`", injected(inner.table))
        assertEquals("def f/0 by `use U` (__using__)", injectedBy(inner.table, code))
    }

    /** An implementation the output defines holds the definition the output wrote as the call injected it. */
    fun testANestedImplementationsDefinitionIsInjected() {
        addUsing("      defimpl String.Chars do\n        def to_string(_), do: \"a\"\n      end\n")
        val code = "defmodule A do\n  use U\nend\n"
        val impl = moduleOf(code, "Elixir.String.Chars.A")

        assertEquals("def to_string/1 b.ex line 5 `def to_string(_), do: \"a\"`", injected(impl.table).substringBefore(" | "))
        assertEquals("def to_string/1 by `use U` (__using__)", injectedBy(impl.table, code).substringBefore(" | "))
    }

    /** A protocol the output defines holds the definition the output wrote as the call injected it. */
    fun testANestedProtocolsDefinitionIsInjected() {
        addUsing("      defprotocol P do\n        def f(x)\n      end\n")
        val code = "defmodule A do\n  use U\nend\n"
        val protocol = moduleOf(code, "Elixir.A.P")

        assertEquals("def f/1 b.ex line 5 `def f(x)`", injected(protocol.table).substringBefore(" | "))
        assertEquals("def f/1 by `use U` (__using__)", injectedBy(protocol.table, code).substringBefore(" | "))
    }

    /** A macro the output calls injects its definition by the outermost call. */
    fun testANestedMacrosDefinitionNamesTheOutermostCall() {
        add("c.ex", "defmodule V do\n  defmacro define do\n    quote do\n      def v, do: 1\n    end\n  end\nend\n")
        addUsing("      require V\n      V.define()\n")

        assertEquals("def v/0 by `use U` (__using__)", injectedBy("defmodule A do\n  use U\nend\n"))
    }

    /** A macro the caller's block calls, which another macro's output passes through, injects by its own call. */
    fun testAMacroTheCallersBlockCallsNamesItsOwnCall() {
        add("c.ex", "defmodule V do\n  defmacro define do\n    quote do\n      def v, do: 1\n    end\n  end\nend\n")
        add("b.ex", "defmodule U do\n  defmacro wrap(do: block) do\n    quote do\n      unquote(block)\n    end\n  end\nend\n")

        assertEquals(
            "def v/0 by `V.define()` (define)",
            injectedBy("defmodule A do\n  require U\n  require V\n\n  U.wrap do\n    V.define()\n  end\nend\n"),
        )
    }

    /** A `use` of a module whose `__using__` is a `defmacrop` injects nothing and names no call. */
    fun testAUseOfAPrivateUsingInjectsNothing() {
        add(
            "b.ex",
            "defmodule U do\n  defmacrop __using__(_) do\n    quote do\n      def f, do: 1\n    end\n  end\nend\n",
        )
        val code = "defmodule A do\n  use U\nend\n"
        val module = moduleOf(code)

        assertEquals("", injected(module.table))
        assertEquals("", injectedBy(module.table, code))
    }

    /** The module's own `def` overriding an injected one names no call. */
    fun testAnOverridingDefinitionNamesNoCall() {
        addUsing("      def f, do: 1\n      defoverridable f: 0\n")

        assertEquals("", injectedBy("defmodule A do\n  use U\n  def f, do: 2\nend\n"))
    }

    /** The `def` a `defdelegate` in the output queues names the call, as the definition `defdelegate` makes of it. */
    fun testADelegationNamesTheUse() {
        addUsing("      defdelegate upcase(s), to: String\n")

        assertEquals("def upcase/1 by `use U` (__using__)", injectedBy("defmodule A do\n  use U\nend\n"))
    }

    /** The definitions `defstruct` fills with the fragments it knows name the call. */
    fun testAStructsDefinitionsNameTheUse() {
        addUsing("      defstruct [:a]\n")

        assertEquals(
            "def __struct__/0 by `use U` (__using__) | def __struct__/1 by `use U` (__using__)",
            injectedBy("defmodule A do\n  use U\nend\n"),
        )
    }

    /** The caller's own `def`, which the output holds as the caller wrote it, names no call. */
    fun testADefinitionTheCallerPassesNamesNoCall() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro wrap(do: block) do\n    quote do\n      def f, do: 1\n      unquote(block)\n" +
                "    end\n  end\nend\n",
        )

        assertEquals(
            "def f/0 by `U.wrap do` (wrap)",
            injectedBy("defmodule A do\n  require U\n\n  U.wrap do\n    def h, do: 2\n  end\nend\n"),
        )
    }

    /** A built-in macro's expansion injects nothing. */
    fun testABuiltInsDefinitionNamesNoCall() {
        val code = "defmodule A do\n  defstruct [:a]\nend\n"
        val module = moduleOf(code)

        assertEquals(
            "module Elixir.A compiled | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1",
            ModuleRendering.render(code, module),
        )
        assertEquals("", injectedBy(module.table, code))
    }

    /** An import the output adds names the call; one the module writes, and `Kernel`'s, name none. */
    fun testAnImportTheOutputAddsNamesTheUse() {
        addUsing("      import String\n")
        val code = "defmodule A do\n  use U\n  import Integer\n  def h, do: upcase(\"a\")\nend\n"
        val file = myFixture.addFileToProject("a.ex", code) as ElixirFile
        val start = code.indexOf("upcase")
        val env = holder.indexed(file, legInputs).envs.at(TextRange(start, code.indexOf(")", start) + 1))

        assertEquals(
            "Elixir.Integer | Elixir.String by `use U` | Elixir.Kernel",
            env.functions.joinToString(" | ") { imports ->
                imports.module + imports.injectedBy?.let { " by `${firstLine(it.meta.origin, code)}`" }.orEmpty()
            },
        )
    }

    /** With Run the expander on, a call lands on the function of the one `quote` its module expanded, of two alike. */
    fun testWithTheExpanderOnACallLandsOnTheFunctionOfTheQuoteItsModuleExpanded() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro a do\n    quote do\n      def f(x), do: {:a, x}\n    end\n  end\n\n" +
                "  defmacro b do\n    quote do\n      def f(x), do: {:b, x}\n    end\n  end\nend\n",
        )

        assertEquals(
            listOf("b.ex:10 def f(x), do: {:b, x}"),
            goToWithTheExpanderOn("defmodule A do\n  require U\n  U.b()\n\n  def run(y), do: <caret>f(y)\nend\n"),
        )
    }

    /** With Run the expander on, a call lands on a definition a `use` splices into its `quote` with `unquote`. */
    fun testWithTheExpanderOnACallLandsOnADefinitionAUseSplices() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n    defs = quote(do: def id(x), do: x)\n\n" +
                "    quote do\n      unquote(defs)\n    end\n  end\nend\n",
        )

        assertEquals(
            listOf("b.ex:3 defs = quote(do: def id(x), do: x)"),
            goToWithTheExpanderOn("defmodule A do\n  use U\n\n  def run(y), do: <caret>id(y)\nend\n"),
        )
    }

    fun testWithTheExpanderOnACompileInlineKeyLandsOnTheDefinitionAUseInjected() =
        assertLandsOnTheDefinitionAUseInjected("@compile inline: [<caret>id: 1]")

    fun testWithTheExpanderOnADialyzerKeyLandsOnTheDefinitionAUseInjected() =
        assertLandsOnTheDefinitionAUseInjected("@dialyzer {:nowarn_function, [<caret>id: 1]}")

    fun testWithTheExpanderOnASpecLandsOnTheDefinitionAUseInjected() =
        assertLandsOnTheDefinitionAUseInjected("@spec <caret>id(term) :: term")

    private fun assertLandsOnTheDefinitionAUseInjected(attribute: String) {
        addUsing("      def id(x), do: x\n")

        assertEquals(listOf("b.ex:4 def id(x), do: x"), goToWithTheExpanderOn("defmodule A do\n  use U\n  $attribute\nend\n"))
    }

    /** With Run the expander on, a call lands on the `def` the `quote` of a macro a `use` injected writes. */
    fun testWithTheExpanderOnACallLandsOnTheDefinitionAUseInjectedMacroWrote() {
        addUsedMacro("          def h, do: 1\n")
        add("c.ex", "defmodule A do\n  use U\nend\n")

        assertEquals(
            listOf("b.ex:6 def h, do: 1"),
            goToWithTheExpanderOn("defmodule B do\n  require A\n  A.g()\n\n  def k, do: <caret>h()\nend\n"),
        )
    }

    /** Go To Declaration's destinations from the caret in `a.ex`, holding [code], each as `<file>:<line> <line's text>`. */
    private fun goToWithTheExpanderOn(code: String): List<String> {
        expanderOn()
        myFixture.configureByText("a.ex", code)

        return myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destinationLine().let { (file, number, text) -> "$file:$number $text" } }
    }

    private fun render(code: String): String = ModuleRendering.render(code, moduleOf(code))

    /** Module [name] of `a.ex`, holding [code], at any depth. */
    private fun moduleOf(code: String, name: String = "Elixir.A"): ExpansionResult {
        val file = myFixture.addFileToProject("a.ex", code) as ElixirFile

        return ProjectModules.results(holder.expansion(file, legInputs)).single { it.module == name }
    }

    private fun injectedBy(code: String): String = injectedBy(moduleOf(code).table, code)

    /**
     * [table]'s injected heads, of the definitions not stored for a default arity, each as `<kind> <name>/<arity> by
     * `<call's first line>` (<call's name>)`, `|`-separated, the call's line in [code].
     */
    private fun injectedBy(table: DefinitionTable, code: String): String =
        table.entries.filterValues { !it.default }.flatMap { (nameArity, entry) ->
            entry.heads.mapNotNull { head ->
                val call = head.injected?.call ?: return@mapNotNull null
                val dot = call.callee as ElixirAst.Call
                val name = (dot.arguments!![1] as ElixirAst.Literal.Atom).name

                "${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} by " +
                    "`${firstLine(call.meta.origin, code)}` ($name)"
            }
        }.joinToString(" | ")

    private fun injected(code: String): String = injected(moduleOf(code).table, code)

    /**
     * [table]'s injected heads, of the definitions not stored for a default arity, each as `<kind> <name>/<arity> <file>
     * line <n> `<first line>``, `|`-separated. A head placed in the file expanded is in `a.ex`, [code].
     */
    private fun injected(table: DefinitionTable, code: String? = null): String =
        table.entries.filterValues { !it.default }.flatMap { (nameArity, entry) ->
            entry.heads.mapNotNull { head ->
                val place = head.injected?.place ?: return@mapNotNull null

                "${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} ${placed(place, code)}"
            }
        }.joinToString(" | ")

    /**
     * The callbacks [result]'s module defines that a macro's output injected, each as `callback <name>/<arity> <file> line
     * <n> `<first line>``, `|`-separated.
     */
    private fun injectedCallbacks(result: ExpansionResult): String =
        result.attributes.callbacks.orEmpty().mapNotNull { head ->
            head.injected?.place?.let { "callback ${head.nameArity.name}/${head.nameArity.arity} ${placed(it, null)}" }
        }.joinToString(" | ")

    /** [place] as `<file> line <n> `<first line>``. A place in the file expanded is in `a.ex`, [code]. */
    private fun placed(place: Place, code: String?): String {
        val (file, text, origin) = when (place) {
            is Place.Written -> Triple(
                place.file?.name ?: "a.ex",
                place.file?.let(::textOf) ?: checkNotNull(code) { "a place in the file expanded" },
                place.origin,
            )
            is Place.Compiled ->
                read { holder.placed(place.clause)!!.let { (mirror, origin) -> Triple(place.clause.beam.name, mirror.text, origin) } }
        }
        val line = text.substring(0, origin.startOffset).count { it == '\n' } + 1

        return "$file line $line `${firstLine(origin, text)}`"
    }

    /** `B` of `a.ex`, which calls `A.g()`, where `c.ex`'s `A` does `use U`. */
    private fun bCallingA(): ExpansionResult {
        add("c.ex", "defmodule A do\n  use U\nend\n")

        return moduleOf("defmodule B do\n  require A\n  A.g()\nend\n", "Elixir.B")
    }

    /** The `.beam` of the module each of [sources] defines, compiled on the quoter's node and unloaded there. */
    private fun beams(vararg sources: String): List<ByteArray> {
        val compiled = Quoter.compile(
            """
            compile = fn source ->
              [{module, binary}] = Code.compile_string(source)
              :code.delete(module)
              :code.purge(module)
              binary
            end

            IntellijElixir.Quoter.Probe.send(__ENV__, [${sources.joinToString { "compile.(${elixirString(it)})" }}])
            """.trimIndent(),
            60.seconds,
        )
        assertEquals("compile ${compiled.diagnostics.map(::inspect)}", OtpErlangAtom("ok"), compiled.status)

        return (compiled.messages.single() as OtpErlangList).elements().map { (it as OtpErlangBinary).binaryValue() }
    }

    /** [block] of the library root that holds [beam] as [name], while the library is on the module. */
    private fun <T> withBeam(name: String, beam: ByteArray, block: (VirtualFile) -> T): T {
        val ebin = FileUtil.createTempDirectory("injected_definition_ebin", null, true)
        File(ebin, name).writeBytes(beam)

        return WalkTestSupport.withLibrary(project, module, testRootDisposable, "injected_definition", ebin, block)
    }

    /** [placed] with the line of each place in a mirror's `__using__` and the rest of its first line dropped. */
    private fun withoutUsingLine(placed: String): String = placed.replace(Regex(" line \\d+ (`defmacro __using__)[^`]*`"), " $1")

    /** `b.ex`, [usedMacro]. */
    private fun addUsedMacro(body: String) = add("b.ex", usedMacro(body))

    /** `U`, whose `__using__` quotes `defmacro g`, whose own `quote` writes [body]. */
    private fun usedMacro(body: String) = using("      defmacro g do\n        quote do\n$body        end\n      end\n")

    /** `b.ex`, [using]. */
    private fun addUsing(body: String) = add("b.ex", using(body))

    /** `U`, whose `__using__` quotes [body]. */
    private fun using(body: String) = "defmodule U do\n  defmacro __using__(_) do\n    quote do\n$body    end\n  end\nend\n"

    private fun add(name: String, code: String) {
        myFixture.addFileToProject(name, code)
    }

    private fun textOf(file: VirtualFile): String = PsiManager.getInstance(project).findFile(file)!!.text

    private fun firstLine(range: TextRange, text: String) = range.substring(text).lineSequence().first()
}
