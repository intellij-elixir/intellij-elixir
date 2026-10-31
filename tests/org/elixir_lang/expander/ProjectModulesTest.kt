package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.codeInsight.completion.OffsetsInFile
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiFileEx
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.FileContentUtilCore
import com.intellij.util.system.OS
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPE_HEADS_NEED_LIST_META
import org.elixir_lang.language_level.ElixirLanguageFeature.FAILED_COMPILE_PURGES_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_CALL_EXPANDED_AS_MACRO
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.math.BigInteger
import java.util.concurrent.Callable

/**
 * A file's expansion reads the modules other files define through [ProjectModules], and each answer, cached or not,
 * is the one a fresh run gives after any change to what it read.
 */
class ProjectModulesTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    fun testSourceDependency() {
        val a = add("a.ex", A_CALLS_B)
        val b = add("b.ex", "defmodule B do\n  defmacro m, do: :ok\nend\n")
        val u = add("u.ex", "defmodule U do\nend\n")

        val first = expansion(holder, a)
        assertFresh("(a) first ask", a, first)
        assertEquals(listOf(NameArity("m", 0)), (exports(holder, a, "Elixir.B") as ModuleExports.Present).macros)
        assertSame("(a) second ask", first, expansion(holder, a))

        edit(project, b, "defmodule B do\n  defmacro m, do: :changed\nend\n")
        val bodyEdited = expansion(holder, a)
        assertNotSame("(b) B's macro body edited", first, bodyEdited)
        assertFresh("(b) B's macro body edited", a, bodyEdited)

        edit(project, u, "defmodule U do\n  def f, do: 1\nend\n")
        assertSame("(c) an unrelated file edited", bodyEdited, expansion(holder, a))

        edit(project, b, "defmodule B do\n  defmacro m, do: :changed\n  defmacro m3, do: :ok\nend\n")
        val macroAdded = expansion(holder, a)
        assertFresh("(d) a macro added to B", a, macroAdded)
        assertEquals(
            listOf(NameArity("m", 0), NameArity("m3", 0)),
            (exports(holder, a, "Elixir.B") as ModuleExports.Present).macros,
        )

        edit(project, b, "defmodule B do\n  defmacro m2, do: :ok\nend\n")
        expansion(holder, b)
        val renamed = expansion(holder, a)
        assertNotSame("(e) B asked first after m is renamed m2", macroAdded, renamed)
        assertFresh("(e) B asked first after m is renamed m2", a, renamed)
    }

    /**
     * Before 1.14.4 a kept quote's file is relative to the directory of the innermost Mix project holding it, each
     * umbrella app's own, and unknown outside any.
     */
    fun testAKeptQuotesFileIsRelativeToItsOwnMixProject() {
        add("umbrella/mix.exs", MIX_EXS)
        add("umbrella/apps/a/mix.exs", MIX_EXS)
        add("umbrella/apps/b/mix.exs", MIX_EXS)
        val files = listOf(
            "a" to add("umbrella/apps/a/lib/a.ex", kept("A") + caller("A")),
            "b" to add("umbrella/apps/b/lib/nested/b.ex", kept("B") + caller("B")),
            "outside" to add("outside.ex", kept("Outside") + caller("Outside")),
        )
        val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.13.4"), legKernel, OS.CURRENT)

        assertEquals(
            "a: lib/a.ex\nb: lib/nested/b.ex\noutside: none",
            files.joinToString("\n") { (name, file) -> "$name: ${keptFiles(file, inputs)}" },
        )
    }

    /** A macro's clauses read for a sibling umbrella app keep its file relative to the macro's own app. */
    fun testAKeptQuoteReadFromASiblingAppIsRelativeToItsOwnApp() {
        add("umbrella/mix.exs", MIX_EXS)
        add("umbrella/apps/a/mix.exs", MIX_EXS)
        add("umbrella/apps/b/mix.exs", MIX_EXS)
        add("umbrella/apps/a/lib/a.ex", kept("A"))
        val b = add("umbrella/apps/b/lib/b.ex", caller("A"))
        val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.13.4"), legKernel, OS.CURRENT)

        assertEquals("lib/a.ex", keptFiles(b, inputs))
    }

    /** A `mix.exs` written above a file that keeps a quote's location moves the file's directory. */
    fun testAMixProjectAddedAboveAKeptQuoteRecomputes() {
        val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.13.4"), legKernel, OS.CURRENT)
        val a = add("later/lib/a.ex", kept("A"))
        val before = expansion(holder, a, inputs)

        add("later/mix.exs", MIX_EXS)
        val after = expansion(holder, a, inputs)

        assertFresh("a mix.exs added above", a, after, inputs)
        assertFalse("A's answer moves", render(a, before) == render(a, after))
    }

    /** A module read only for the clauses a macro body calls moves the answer of the file that called the macro. */
    fun testAModuleReadForItsClausesMovesTheAsker() {
        addLegElixir()
        val a = add("a.ex", "defmodule A do\n  require B\n  B.m()\nend\n")
        add("b.ex", "defmodule B do\n  defmacro m do\n    mod = C\n    mod.code()\n  end\nend\n")
        val c = add("c.ex", "defmodule C do\n  def code, do: quote(do: def(f(), do: 1))\nend\n")
        val before = expansion(holder, a)

        edit(project, c, "defmodule C do\n  def code, do: quote(do: def(g(), do: 1))\nend\n")
        val after = expansion(holder, a)

        assertFresh("C's clause edited", a, after)
        assertFalse("A's answer moves", render(a, before) == render(a, after))
    }

    /**
     * A definition a macro writes under `location: :keep` is in another file only where its head's `keep:` names one: a
     * head the caller wrote keeps the caller's file, and a kept quote in a moved definition's body stops. The files are in
     * a Mix project, so a kept quote's file is known before 1.14.4 too.
     */
    fun testAMacroWrittenDefinitionIsMovedByItsHeadsKeep() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        add(
            "w.ex",
            "defmodule W do\n" +
                "  defmacro defw(head, do: body) do\n    quote location: :keep do\n      defmacro unquote(head), do: unquote(body)\n    end\n  end\n\n" +
                "  defmacro defk(body) do\n    quote location: :keep do\n      defmacro k, do: unquote(body)\n    end\n  end\n" +
                "end\n",
        )
        val a = add("a.ex", "defmodule A do\n  require W\n  W.defw m(), do: quote(location: :keep, do: :v)\nend\n")
        val b = add("b.ex", "defmodule B do\n  require W\n  W.defk quote(location: :keep, do: :v)\nend\n")

        assertEquals(
            "a:\n" +
                "top expanded\n" +
                "Elixir.A compiled errors[] loads Present(functions=[], macros=[NameArity(name=m, arity=0)], hasInfo=true, " +
                "behaviour=None, deprecated=Known(reasons={}), attributes=Unreadable)\n" +
                "b:\n" +
                "top expanded\n" +
                "Elixir.B stopped `quote(location: :keep, do: :v)` unported quote errors[] loads Unreadable",
            "a:\n${render(a, expansion(holder, a))}\nb:\n${render(b, expansion(holder, b))}",
        )
    }

    fun testStructRead() {
        addLegElixir()
        val a = add("a.ex", "defmodule A do\n  %B{y: 1}\nend\n")
        val b = add("b.ex", "defmodule B do\n  defstruct [:x]\nend\n")

        val before = render(a, expansion(holder, a))
        edit(project, b, "defmodule B do\n  defstruct [:x, :y]\nend\n")
        val after = expansion(holder, a)

        assertFresh("B's struct gains :y", a, after)
        assertFalse("A's answer moves", before == render(a, after))
    }

    /**
     * From v1.20.0-rc.4 a module whose compile raises is purged, so a redefinition that raises leaves no module under
     * the name; before it, the module the run loaded first stays, with the struct it defined.
     */
    fun testARedefinitionThatRaisesKeepsTheLoadedModuleBeforeThePurge() {
        addLegElixir()
        add("a.ex", "defmodule A do\n  defstruct [:x]\nend\ndefmodule A do\n  defstruct [:y]\n  :ok = :bad\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: %A{}\nend\n")

        val rendered = render(b, expansion(holder, b))
        val struct = ReadAction.nonBlocking(Callable { holder.struct(b, "Elixir.A", legInputs) }).executeSynchronously()

        if (FAILED_COMPILE_PURGES_MODULE.isSufficient(legLevel())) {
            assertEquals(ModuleStruct.Absent, struct)
            assertFalse(rendered, rendered.contains("Elixir.B compiled"))
        } else {
            assertEquals(struct.toString(), setOf("x"), (struct as? ModuleStruct.Present)?.fields)
            assertTrue(rendered, rendered.contains("Elixir.B compiled"))
        }
    }

    /**
     * Before the purge, the module a failed redefinition leaves loaded keeps its clauses, so its macros still expand
     * in other files; from the purge on, the name has none.
     */
    fun testARedefinitionThatRaisesKeepsTheLoadedModulesClausesBeforeThePurge() {
        addLegElixir()
        add("a.ex", "defmodule A do\n  defmacro m, do: :from_v1\n  def f, do: 1\nend\ndefmodule A do\n  defmacro m, do: :from_v2\n  :ok = :bad\nend\n")
        val b = add("b.ex", "defmodule B do\n  require A\n  def g, do: A.m()\nend\n")

        val rendered = render(b, expansion(holder, b))
        val clauses = ReadAction.nonBlocking(Callable { holder.clauses(b, "Elixir.A", legInputs) }).executeSynchronously()

        if (FAILED_COMPILE_PURGES_MODULE.isSufficient(legLevel())) {
            assertNull(rendered, clauses)
            assertFalse(rendered, rendered.contains("Elixir.B compiled"))
        } else {
            assertEquals(rendered, setOf(NameArity("m", 0), NameArity("f", 0)), clauses?.definitions?.keys)
            assertTrue(rendered, rendered.contains("Elixir.B compiled"))
        }
    }

    /**
     * A redefinition that raises in a branch Elixir may not run purges nothing on any release: where the project
     * compiles, the branch didn't run, so the module the run loaded first stays, with its struct and its clauses.
     */
    fun testARedefinitionThatRaisesWhereItMayNotRunKeepsTheLoadedModule() {
        addLegElixir()
        add(
            "a.ex",
            "defmodule A do\n  defstruct [:x]\n  defmacro m, do: :from_v1\nend\n" +
                "if false do\n  defmodule A do\n    defstruct [:y]\n    :ok = :bad\n  end\nend\n",
        )
        val b = add("b.ex", "defmodule B do\n  require A\n  def f, do: %A{}\n  def g, do: A.m()\nend\n")

        val rendered = render(b, expansion(holder, b))
        val struct = ReadAction.nonBlocking(Callable { holder.struct(b, "Elixir.A", legInputs) }).executeSynchronously()
        val clauses = ReadAction.nonBlocking(Callable { holder.clauses(b, "Elixir.A", legInputs) }).executeSynchronously()

        assertEquals(struct.toString(), setOf("x"), (struct as? ModuleStruct.Present)?.fields)
        assertTrue(rendered, clauses?.definitions?.keys.orEmpty().contains(NameArity("m", 0)))
        assertTrue(rendered, rendered.contains("Elixir.B compiled"))
    }

    /**
     * A definition that raises shadows nothing: the name answers as it would without it. A project module that defines
     * one a library ships as a `.beam` again, and raises, leaves the library's copy to answer on every release: before
     * the purge nothing replaced it, and from the purge on it is loaded again from the code path.
     */
    fun testAFailedRedefinitionOfALibraryModuleShadowsNothing() {
        addLegElixir()
        add("a.ex", "defmodule IO.Stream do\n  defstruct [:y]\n  :ok = :bad\nend\n")
        // Every field is written: the `.beam`s of 1.18 and 1.19.0-rc.0 record no enforced keys.
        val b = add("b.ex", "defmodule B do\n  def f, do: %IO.Stream{device: nil, raw: true, line_or_bytes: :line}\nend\n")

        val rendered = render(b, expansion(holder, b))
        val struct = ReadAction.nonBlocking(Callable { holder.struct(b, "Elixir.IO.Stream", legInputs) }).executeSynchronously()

        assertEquals(struct.toString(), setOf("device", "raw", "line_or_bytes"), (struct as? ModuleStruct.Present)?.fields)
        assertTrue(rendered, rendered.contains("Elixir.B compiled"))
    }

    /**
     * Where the chosen source copy leaves the name no module, Elixir loads it from the code path, and with two `.beam`s
     * there which one is as unknown as it is for two source copies.
     */
    fun testAFailedRedefinitionWithTwoBeamsOnTheCodePathIsSeveral() {
        addLegElixir()
        val beam = File("testData/org/elixir_lang/expander/loaded_copy/Elixir.LoadedCopy.Dep.beam")
        val first = copiedLibrary(beam, "first_ebin")
        val second = copiedLibrary(beam, "second_ebin")
        add("dep.ex", "defmodule LoadedCopy.Dep do\n  :ok = :bad\nend\n")
        val a = add("a.ex", "defmodule A do\nend\n")

        val located = ReadAction.nonBlocking(Callable { holder.locate(a, "Elixir.LoadedCopy.Dep", legInputs) }).executeSynchronously()

        assertEquals(
            located.toString(),
            setOf(first.findChild(beam.name), second.findChild(beam.name)),
            (located as? ProjectModules.Located.Several)?.copies?.toSet(),
        )
        assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.LoadedCopy.Dep"))
    }

    /**
     * The project's own build output is never on the code path while Elixir compiles the project's sources, as Mix
     * deletes a stale `.beam` before it compiles its source, so a name the project's sources leave no module is absent
     * whatever `_build` still holds: after a failed redefinition, and before its `defmodule` has run.
     */
    fun testTheProjectsOwnBuildOutputIsNeverWhereANameFallsThrough() {
        addLegElixir()
        built("_build/dev/lib/app/ebin/$DEP_BEAM", DEP_BEAM)
        val dep = add("dep.ex", "defmodule LoadedCopy.Dep do\n  :ok = :bad\nend\n")
        val a = add("a.ex", "defmodule A do\nend\n")

        val exports = exports(holder, a, "Elixir.LoadedCopy.Dep")
        val located = ReadAction.nonBlocking(Callable { holder.locate(a, "Elixir.LoadedCopy.Dep", legInputs) }).executeSynchronously()
        edit(project, dep, "defmodule B do\n  import LoadedCopy.Dep\nend\ndefmodule LoadedCopy.Dep do\n  def f, do: 1\nend\n")
        val rendered = render(dep, expansion(holder, dep))

        assertEquals(
            "exports Absent\nlocated None\nB raised",
            "exports $exports\nlocated $located\n" + if (rendered.contains("Elixir.B raised unloaded_module")) "B raised" else rendered,
        )
    }

    /**
     * The project's consolidated protocols are its own build output too, though their directory is a library: Mix puts
     * them on the code path only once the project has compiled, so a protocol the project's sources define later is
     * absent.
     */
    fun testTheProjectsConsolidatedProtocolsAreNeverWhereANameFallsThrough() {
        addLegElixir()
        val consolidated = built("_build/dev/lib/app/consolidated/$PROTOCOL_BEAM", PROTOCOL_BEAM).parent
        BeamLibraryFixture.addLibrary(project, module, "app_consolidated", listOf(consolidated), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val x = add("x.ex", "defmodule B do\n  import LoadedCopy.Protocol\nend\ndefprotocol LoadedCopy.Protocol do\n  def p(t)\nend\n")

        val rendered = render(x, expansion(holder, x))

        assertTrue(rendered, rendered.contains("Elixir.B raised unloaded_module"))
    }

    /**
     * A module a file defines after the module that asks for it isn't loaded from that file yet, so Elixir loads it from
     * the code path: a library's `.beam`, its exports and its clauses.
     */
    fun testAModuleNotYetDefinedInTheRunningFileIsALibrarysBeam() {
        addLegElixir()
        copiedLibrary(File("testData/org/elixir_lang/expander/loaded_copy/$DEP_BEAM"))
        val x = add("x.ex", "defmodule B do\n  import LoadedCopy.Dep\nend\ndefmodule LoadedCopy.Dep do\n  def f, do: 2\nend\n")

        val renderedImport = render(x, expansion(holder, x))

        assertTrue(renderedImport, renderedImport.contains("Elixir.B compiled"))

        edit(project, x, "defmodule B do\n  :ok = LoadedCopy.Dep.f()\nend\ndefmodule LoadedCopy.Dep do\n  def f, do: 2\nend\n")
        val renderedCall = render(x, expansion(holder, x))

        assertTrue(renderedCall, renderedCall.contains("Elixir.B raised Elixir.MatchError"))
    }

    /**
     * A stop in the expansion of a `@before_compile` callback's output, after a module the output defines, can't change
     * what that module defined: it loads, while the module the callback ran in stopped.
     */
    fun testAModuleABeforeCompileCallbacksOutputDefinesBeforeTheStopInItsExpansionLoads() {
        addLegElixir()
        val a = add("a.ex", MACRO_M_OK + callback(foo("def f, do: 1") + CALL_M))
        val b = add("b.ex", IMPORTS_FOO)

        val renderedA = render(a, expansion(holder, a))
        val renderedB = render(b, expansion(holder, b))

        assertTrue(renderedA, renderedA.contains("\nElixir.O stopped `"))
        assertTrue(renderedA, renderedA.contains("\nElixir.Foo compiled errors[] loads Present"))
        assertTrue(renderedB, renderedB.contains("Elixir.B compiled"))
    }

    /**
     * A module a `@before_compile` callback's output defines before the stop in its expansion, whose body raises from
     * what is known, is absent: Elixir raised there, or before it, so it never loaded it.
     */
    fun testAModuleABeforeCompileCallbacksOutputDefinesBeforeTheStopInItsExpansionThatRaisesIsAbsent() {
        addLegElixir()
        val a = add("a.ex", MACRO_M_RAISES + callback(foo("raise KeyError") + CALL_M))
        // A node the output holds renders with the callback call's text.
        val foo = render(a, expansion(holder, a)).lines().single { it.startsWith("Elixir.Foo ") }

        assertTrue(foo, foo.startsWith("Elixir.Foo stopped `") && foo.endsWith(" loads Absent"))
    }

    /**
     * A call the evaluator declines may have written a module being compiled only where it was given that module: its
     * atom, `__MODULE__`, `__ENV__`, an unknown value that came from one, or an `fn` that names or captures one. A call
     * never run because an argument declined, and a native that declines, are declined calls given their arguments. So
     * are a call given a tuple, list or map, or a variable a match bound, that holds the module beside a part that
     * declined, a call of a function value that isn't known, and each call in a branch, `for` body or function a
     * declined form or native may run. A decline ends no block, iteration, `with` clause, segment or segment's size:
     * what comes after it runs, and a `reduce:` arrow is given the accumulator the iterations before it left. Nor does
     * a raise after it that Elixir catches, in a `try` or a guard, or one from a table the call may have written. A
     * native that declines is given all its arguments where it runs a function they name, known or not, or where a
     * function is reachable in them, in a value that isn't known or in an option list, and a `for` that declines gives
     * its `into:` to the `Collectable.into/1` it calls. After such a call the module's attributes are unknown, so
     * `def unquote(@x)` stops where Elixir, given an atom, defines `ok/0`, not `bad/0`, and what the module defines is
     * unreadable to another file.
     */
    fun testADeclinedCallGivenTheModuleLeavesItsAttributesUnknownAndItUnreadable() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.put_attribute(m, :x, :ok)\n  end\n" +
                "  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\n" +
                "  def set(m, _), do: set(m)\n" +
                "  def set2(_, m), do: set(m)\n" +
                "  def setl([_, m]), do: set(m)\n" +
                "  def sett({m, _}), do: set(m)\n" +
                "  def setk(opts), do: set(opts[:module])\n" +
                "  def seth([m | _]), do: set(m)\n" +
                "  def setm(%{module: m}), do: set(m)\n" +
                "  def later(f), do: receive(do: (x -> x), after: (0 -> f.()))\n" +
                "  def deep(m, 0), do: set(m)\n" +
                "  def deep(m, n), do: deep(m, n - 1)\n" +
                "  def setb(m) do\n    set(m)\n    \"\"\n  end\n" +
                "  def nb(_), do: receive(do: (x -> x), after: (0 -> \"\"))\n" +
                "  def int(_), do: receive(do: (x -> x), after: (0 -> 1))\n" +
                "  def set8(m) do\n    set(m)\n    8\n  end\nend\n" +
                "defmodule S do\n  defstruct [:module, :t]\nend\n" +
                "defimpl Collectable, for: S do\n  def into(%S{module: m} = s) do\n    H.set(m)\n    {s, fn acc, _ -> acc end}\n  end\nend\n",
        )
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A\n  def h, do: g()\nend\n")
        val given = listOf("__MODULE__", "__ENV__", "A", "[in: {__MODULE__}]", "__ENV__.module").map { "H.set($it)" }
        // Elixir rejects a size that is neither a variable nor an integer before 1.14.
        val sizes = listOf(
            "<<H.int(1)::size(H.set8(__MODULE__))>>",
            "_ = <<H.int(1)::size(H.set8(__MODULE__))>>",
            "\"#{H.int(1)}\" <> <<1::size(H.set8(__MODULE__))>>",
        ).takeIf { BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(legLevel()) }.orEmpty()

        val wrong = buildList {
            addAll(given)
            addAll(sizes)
            addAll(listOf(
                "H.set(__MODULE__, H.noop(1))",
                "H.set(Keyword.get(Application.get_env(:nope, :o, []), :m, __MODULE__))",
                "H.set(:maps.get(:module, __ENV__))",
                "env = __ENV__\n  H.set(env.module)",
                "H.seth([__MODULE__ | Application.get_env(:nope, :rest, [])])",
                "H.seth([__MODULE__] ++ Application.get_env(:nope, :rest, []))",
                "%{module: m} = __ENV__\n  H.set(m)",
                "H.setm(%{__ENV__ | line: 1})",
                "@opts %{module: __MODULE__}\n  H.setm(@opts)",
                ":lists.foreach(&H.set/1, [__MODULE__])",
                "H.deep(__MODULE__, 100_000)",
                "Enum.each(Application.get_env(:nope, :l, [:ok]), fn _ -> H.set(__MODULE__) end)",
                "Enum.map(Application.get_env(:nope, :l, [:ok]), fn _ -> H.set(__MODULE__) end)",
                "m = __MODULE__\n  Enum.each(Application.get_env(:nope, :l, [:ok]), fn _ -> H.set(m) end)",
                "H.later(fn -> H.set(__MODULE__) end)",
                "try do\n    H.set(__MODULE__)\n  after\n    receive(do: (x -> x), after: (0 -> :ok))\n  end",
                "H.sett({__MODULE__, H.noop(1)})",
                "H.setk(module: __MODULE__, t: H.noop(1))",
                "H.setm(%{module: __MODULE__, t: H.noop(1)})",
                "H.seth([__MODULE__, H.noop(1)])",
                "H.set2(H.noop(1), __MODULE__)",
                "H.set2(H.noop(1), Keyword.get(Application.get_env(:nope, :o, []), :m, __MODULE__))",
                "x = [H.noop(1), __MODULE__]\n  H.setl(x)",
                "x = [H.noop(1), Keyword.get(Application.get_env(:nope, :o, []), :m, __MODULE__)]\n  H.setl(x)",
                "H.setm(%S{module: __MODULE__, t: H.noop(1)})",
                "x = %S{%S{module: __MODULE__} | t: H.noop(1)}\n  H.setm(x)",
                "apply(H, :sett, [{__MODULE__, H.noop(1)}])",
                "{m, :ok} = {__MODULE__, Application.get_env(:nope, :k, :ok)}\n  H.set(m)",
                "x = %{Application.get_env(:nope, :m, %{module: nil}) | module: __MODULE__}\n  H.setm(x)",
                "m = Keyword.get(Application.get_env(:nope, :o, []), :m, __MODULE__)\n  H.set(m)",
                "t = {__MODULE__, H.noop(1)}\n  H.sett(t)",
                "case Keyword.get(Application.get_env(:nope, :o, []), :m, __MODULE__) do\n    m -> H.set(m)\n  end",
                "m = Map.get(Application.get_env(:nope, :o, %{}), :m, __MODULE__)\n  H.set(m)",
                "for v <- Application.get_env(:nope, :l, [:ok]), do: H.set(__MODULE__, v)",
                "for m <- [__MODULE__ | Application.get_env(:nope, :l, [])], do: H.set(m)",
                "if Application.get_env(:nope, :c, true), do: H.set(__MODULE__)",
                "unless Application.get_env(:nope, :c, false), do: H.set(__MODULE__)",
                "case Application.get_env(:nope, :c, 1) do\n    _ -> H.set(__MODULE__)\n  end",
                "cond do\n    Application.get_env(:nope, :c, true) -> H.set(__MODULE__)\n  end",
                "with true <- Application.get_env(:nope, :c, true), do: H.set(__MODULE__)",
                "with {:ok, _} <- Application.fetch_env(:nope, :c) do\n    :ok\n  else\n    _ -> H.set(__MODULE__)\n  end",
                "Application.get_env(:nope, :c, false) || H.set(__MODULE__)",
                "Application.get_env(:nope, :c, true) && H.set(__MODULE__)",
                "receive do\n    x -> x\n  after\n    0 -> H.set(__MODULE__)\n  end",
                "Application.get_env(:nope, :cb, fn -> H.set(__MODULE__) end).()",
                "f = Application.get_env(:nope, :f, fn -> H.set(__MODULE__) end)\n  f.()",
                "Enum.map([__MODULE__ | Application.get_env(:nope, :l, [])], fn m -> H.set(m) end)",
                "Enum.map([__MODULE__ | Application.get_env(:nope, :l, [])], &H.set/1)",
                "Enum.filter([__MODULE__ | Application.get_env(:nope, :l, [])], fn m -> H.set(m) end)",
                "Enum.reduce(Application.get_env(:nope, :l, [1]), __MODULE__, fn _, acc -> H.set(acc)\n    acc end)",
                "case 1 do\n    1 -> H.noop(1)\n      H.set(__MODULE__)\n  end",
                "try do\n    H.noop(1)\n    H.set(__MODULE__)\n  rescue\n    _ -> :ok\n  end",
                "for _ <- [1], do: (H.noop(1); H.set(__MODULE__))",
                "{(H.noop(1); H.set(__MODULE__))}",
                "f = fn -> H.noop(1); H.set(__MODULE__) end\n  f.()",
                "case (H.noop(1); __MODULE__) do\n    m -> H.set(m)\n  end",
                "for m <- [1, __MODULE__], do: H.set(m)",
                "H.seth(for m <- [__MODULE__, 1], do: (if m == 1, do: H.noop(1), else: m))",
                "for _ <- [1], into: Application.get_env(:nope, :c, []), do: H.set(__MODULE__)",
                "for _ <- [1], reduce: H.noop(0) do\n    _ -> H.set(__MODULE__)\n  end",
                "for _ <- Application.get_env(:nope, :l, [1]), reduce: __MODULE__ do\n    acc -> H.set(acc)\n  end",
                "for x <- Application.get_env(:nope, :l, [1]), reduce: {__MODULE__, 0} do\n    {m, n} -> H.set(m)\n      {m, n + x}\n  end",
                "for {_, m} <- [{:a, __MODULE__}, Application.get_env(:nope, :e, {:b, 1})], reduce: 1 do\n    acc -> if acc == 1, do: m, else: H.set(acc)\n  end",
                "for x when x > 0 <- [__MODULE__, Application.get_env(:nope, :k, 1)], reduce: :none do\n    acc -> if is_atom(x), do: x, else: (H.set(acc); acc)\n  end",
                "for _ <- [1], {y} <- [{__MODULE__}, Application.get_env(:nope, :k, {1})], reduce: :none do\n    acc -> if is_atom(y), do: y, else: (H.set(acc); acc)\n  end",
                "for x <- [1], into: %S{module: __MODULE__}, do: x",
                "for x <- [1], into: Application.get_env(:nope, :c, %S{module: __MODULE__}), do: x",
                "with _ = H.noop(1), do: H.set(__MODULE__)",
                "\"#{H.noop(1)}#{H.set(__MODULE__)}\"",
                "<<H.nb(1)::binary, H.setb(__MODULE__)::binary>>",
                "H.noop(\"#{H.nb(1)}#{H.setb(__MODULE__)}\")",
                "Enum.map(Application.get_env(:nope, :l, [__MODULE__]), Application.get_env(:nope, :f, &H.set/1))",
                "Enum.map(Application.get_env(:nope, :l, [__MODULE__]), Application.get_env(:nope, :f, fn m -> H.set(m) end))",
                "Enum.each(Application.get_env(:nope, :l, [__MODULE__]), Application.get_env(:nope, :f))",
                "Enum.map(Application.get_env(:nope, :l, [__MODULE__]), Application.get_env(:nope, :f))",
                ":lists.map(Application.get_env(:nope, :f), Application.get_env(:nope, :l, [__MODULE__]))",
                "Enum.map([__MODULE__], Application.get_env(:nope, :f))",
                "inspect(Application.get_env(:nope, :v, 1), inspect_fun: fn _, _ -> H.set(__MODULE__)\n    \"x\" end)",
                "inspect(1, inspect_fun: fn _, _ -> H.set(__MODULE__)\n    \"x\" end)",
                "{try do H.noop(1); raise \"x\" rescue _ -> :ok end, H.set(__MODULE__)}",
                "if true do (try do H.noop(1); raise \"x\" rescue _ -> :ok end); H.set(__MODULE__) end",
                "{try(do: (H.noop(1); throw(:x)), catch: (:x -> :ok)), H.set(__MODULE__)}",
                "f = fn -> H.noop(1); raise \"x\" end\n  {try do f.() rescue _ -> :ok end, H.set(__MODULE__)}",
                "for n <- [1, 2] do\n    try do\n      H.noop(n)\n      raise \"x\"\n    rescue\n      _ -> :ok\n    end\n" +
                    "    if n == 2, do: H.set(__MODULE__)\n  end",
                "x = (try do\n    H.noop(1)\n    throw :x\n  catch\n    :x -> :ok\n  end\n  H.set(__MODULE__))",
                "{try do H.noop(1); raise \"x\" rescue _ -> H.noop(2) end, H.set(__MODULE__)}",
                "{try do H.noop(1); raise \"x\" rescue [ArgumentError, RuntimeError] -> :ok end, H.set(__MODULE__)}",
                "{try do H.noop(1); raise \"x\" rescue :\"Elixir.RuntimeError\" -> :ok end, H.set(__MODULE__)}",
                "H.seth(try do H.noop(1); throw __MODULE__ catch m -> [m] end)",
                "H.seth(try do throw __MODULE__ catch m -> [m] end)",
                "H.seth(try do H.noop(1); raise KeyError, key: :k, term: __MODULE__ rescue e in KeyError -> [e.term] end)",
                "H.seth(try do H.noop(1); exit(__MODULE__) catch :exit, m -> [m] end)",
                "H.seth(try do H.noop(1); throw {__MODULE__} catch {m} -> [m | H.noop(2)] end)",
                "H.seth(try do H.noop(1); throw __MODULE__ catch m -> [m] after :ok end)",
                "x = try do H.noop(1); throw __MODULE__ catch m -> [m] end\n  H.seth(x)",
                "{case Application.get_env(:nope, :k, 1) do x when x + 1 + tuple_size(:a) > 0 -> :a; _ -> :b end, H.set(__MODULE__)}",
                "{(fn x when x + 1 + tuple_size(:a) > 0 -> :a; _ -> :b end).(Application.get_env(:nope, :k, 1)), H.set(__MODULE__)}",
                "{with(x when x + 1 + tuple_size(:a) > 0 <- Application.get_env(:nope, :k, 1), do: :a, else: (_ -> :b)), H.set(__MODULE__)}",
                "defmodule I do\n    if true do\n      H.set(__MODULE__)\n      :ok = Module.get_attribute(__MODULE__, :x)\n" +
                    "      H.set(A)\n    end\n  end",
                "defmodule I do\n    {H.set(__MODULE__), (:ok = Module.get_attribute(__MODULE__, :x)), H.set(A)}\n  end",
                "apply(H, :set, Application.get_env(:nope, :a, [__MODULE__]))",
            ))
        }.mapNotNull { statement ->
            edit(project, a, "defmodule A do\n  @x :bad\n  $statement\n  def unquote(@x)(), do: 1\n  def g, do: 1\nend\n")
            val module = expansion(holder, a).modules.single { it.module == "Elixir.A" }
            val renderedB = render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }

            "$statement: ${module.table.entries.keys} $renderedB".takeUnless {
                NameArity("bad", 0) !in module.table.entries.keys && renderedB.startsWith("Elixir.B stopped `import A`")
            }
        }

        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * A call whose receiver isn't known is given it, as a call of a function value that isn't known is: where the file
     * defined the module before, `m.set()` may run that copy, which writes the module. So may `&m.set/0`'s call, an
     * `apply/3` whose name or arguments aren't known, and a declined call given a function value that names the module.
     * So after it the module's attributes are unknown, and `def unquote(@x)` stops where Elixir defines `ok/0`.
     */
    fun testACallOfAReceiverThatIsntKnownIsGivenIt() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A\n  def h, do: g()\nend\n")
        val first = "defmodule A do\n  def set, do: Module.put_attribute(A, :x, :ok)\n  def set(_), do: Module.put_attribute(A, :x, :ok)\nend\n"

        val wrong = listOf(
            "Application.get_env(:nope, :m, __MODULE__).set()",
            "Application.get_env(:nope, :m, __MODULE__).set",
            "m = Application.get_env(:nope, :m, __MODULE__)\n  m.set(1)",
            "apply(Application.get_env(:nope, :m, __MODULE__), :set, [])",
            "apply(Application.get_env(:nope, :m, __MODULE__), :set, [1])",
            "m = Application.get_env(:nope, :m, __MODULE__)\n  f = &m.set/0\n  f.()",
            "m = Application.get_env(:nope, :m, __MODULE__)\n  Enum.each([1], &m.set/1)",
            "apply(Application.get_env(:nope, :m, __MODULE__), :set, Application.get_env(:nope, :a, []))",
            "apply(Application.get_env(:nope, :m, __MODULE__), Application.get_env(:nope, :f, :set), [])",
            "apply(__MODULE__, Application.get_env(:nope, :f, :set), [])",
            "Enum.each(Application.get_env(:nope, :l, [1]), &__MODULE__.set/1)",
            ":lists.foreach(&__MODULE__.set/1, Application.get_env(:nope, :l, [1]))",
            "f = Application.get_env(:nope, :f, &__MODULE__.set/0)\n  f.()",
            "apply(__MODULE__, :set, Application.get_env(:nope, :a, []))",
            "apply(__MODULE__, :set, Application.get_env(:nope, :a, [1]))",
        ).mapNotNull { statement ->
            edit(project, a, first + "defmodule A do\n  @x :bad\n  $statement\n  def unquote(@x)(), do: 1\n  def g, do: 1\nend\n")
            val module = expansion(holder, a).modules.last { it.module == "Elixir.A" }
            val renderedB = render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }

            "$statement: ${module.table.entries.keys} $renderedB".takeUnless {
                NameArity("bad", 0) !in module.table.entries.keys && renderedB.startsWith("Elixir.B stopped `import A`")
            }
        }

        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * A `try` whose body declines a call given the module raises nowhere for certain: its `after` may read what the call
     * wrote. Elixir writes `@x 1`, so `@x + 1` doesn't raise, and the next module in the file loads.
     */
    fun testATryWhoseBodyDeclinesACallGivenTheModuleRaisesNowhereForCertain() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.put_attribute(m, :x, 1)\n  end\nend\n",
        )
        add(
            "a.ex",
            "defmodule A do\n  @x :a\n  try do\n    H.set(__MODULE__)\n  after\n    @x + 1\n  end\n  def g, do: 1\nend\n" +
                "defmodule C do\n  def g, do: 1\nend\n",
        )

        val answers = listOf("A", "C").map { name ->
            val b = add("b$name.ex", "defmodule B$name do\n  import $name\n  def h, do: g()\nend\n")

            render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B$name ") }.substringBefore(" errors")
        }

        assertEquals(listOf("Elixir.BA stopped `import A` unported import", "Elixir.BC compiled"), answers)
    }

    /**
     * A `@before_compile` callback's output that declines a call given the module may have defined in it: Elixir's
     * defines `g/0`, so another file can't read the module.
     */
    fun testABeforeCompileCallbacksDeclinedCallGivenTheModuleLeavesItUnreadable() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.eval_quoted(m, quote(do: def(g, do: 1)))\n  end\nend\n",
        )
        add("cb.ex", "defmodule Cb do\n  defmacro __before_compile__(_env) do\n    quote do\n      H.set(__MODULE__)\n    end\n  end\nend\n")
        add("a.ex", "defmodule A do\n  @before_compile Cb\n  def f, do: 1\nend\n")
        val b = add("b.ex", "")

        assertEquals(ModuleExports.Unreadable, exports(holder, b, "Elixir.A"))
    }

    /**
     * A module that compiles, where a declined call given it at a statement is all the expander doesn't know, may hold
     * what that call defined in it: Elixir's defines `g/0`, so another file can't read the module.
     */
    fun testADeclinedCallGivenTheModuleThatMayDefineInItLeavesItUnreadable() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.eval_quoted(m, quote(do: def(g, do: 1)))\n  end\nend\n",
        )
        add("a.ex", "defmodule A do\n  H.set(__MODULE__)\n  def f, do: 1\nend\n")
        val b = add("b.ex", "")

        assertEquals(ModuleExports.Unreadable, exports(holder, b, "Elixir.A"))
    }

    /**
     * A stop in the file's expansion after a module's `defmodule` can't change what the module defined, so it loads,
     * while the module after the stop, which the expansion never reached, may have compiled too.
     */
    fun testAModuleBeforeAStopInTheFilesExpansionIsLoaded() {
        addLegElixir()
        add("mac.ex", "defmodule Mac do\n  defmacro m do\n    receive(do: (x -> x), after: (0 -> :ok))\n    :ok\n  end\nend\n")
        add("a.ex", "defmodule A do\n  def f, do: 1\nend\nrequire Mac\nMac.m()\ndefmodule B do\n  def g, do: 1\nend\n")
        val c = add("c.ex", "")

        assertEquals(listOf("Present", "Unreadable"), listOf("A", "B").map { exports(holder, c, "Elixir.$it")::class.simpleName })
    }

    /**
     * Code the expander doesn't follow, a macro that declines before it writes, may have written every module being
     * compiled around it, a `defimpl`'s included: Elixir defines `b/0` from the write; the expander can't read `P.Foo`'s
     * attribute after the give-up, so the definition's name stops it. An `@on_definition` callback the expander runs
     * writes it as Elixir does, so both define `b/0`. With neither, both define `a/0`.
     */
    fun testUnfollowedCodeMayHaveWrittenAnImplAroundIt() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def cb(_env, _kind, name, _args, _guards, _body) do\n    if name == :h, do: Module.put_attribute(:\"Elixir.P.Foo\", :x, :b)\n  end\nend\n",
        )
        add(
            "w.ex",
            "defmodule W do\n  defmacro w(m) do\n    receive do after 0 -> :ok end\n    Module.put_attribute(m, :x, :b)\n  end\n  defmacro r(module, key), do: Module.get_attribute(module, key)\nend\n",
        )
        add("p.ex", "defprotocol P do\n  def f(x)\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: P.Foo.Inner.b()\nend\n")
        val read = "def unquote(Module.get_attribute(:\"Elixir.P.Foo\", :x))(), do: 1"
        val shapes = listOf(
            Triple("callback-loaded", "@on_definition {H, :cb}\n        def h, do: :ok\n        $read", "Present [b/0, h/0] Compiled"),
            Triple("macro-expander-unreadable", "def h, do: W.w(:\"Elixir.P.Foo\")\n        $read", "Unreadable Stopped"),
            Triple("none-loaded", "def h, do: :ok\n        $read", "Present [a/0, h/0] Compiled"),
        )
        val a = add("a.ex", "")

        assertEquals(
            shapes.joinToString("\n") { (label, _, expected) -> "$label: $expected" },
            shapes.joinToString("\n") { (label, body, _) ->
                edit(
                    project,
                    a,
                    "defmodule Foo do\n  defstruct [:a]\n  defimpl P do\n    @x :a\n    def f(_), do: :ok\n    defmodule Inner do\n      require W\n" +
                        "        $body\n    end\n  end\nend\n",
                )

                val inner = expansion(holder, a).modules.flatMap { it.withNested() }.single { it.module == "Elixir.P.Foo.Inner" }

                "$label: ${describe(exports(ProjectModules(project), b, "Elixir.P.Foo.Inner"))} ${inner.ended::class.simpleName}"
            },
        )
    }

    /**
     * A nested module's `@after_compile` callback, which the expander doesn't follow, is given the env, so it may have
     * written the module around it: Elixir's callback writes `@x :ok`, so `def unquote(@x)` stops rather than define
     * `bad/0`, and another file can't read the outer module.
     */
    fun testANestedModulesAfterCompileCallbackMayHaveWrittenTheModuleAroundIt() {
        addLegElixir()
        add(
            "cb.ex",
            "defmodule CB do\n  def __after_compile__(env, _bytecode) do\n" +
                "    outer = env.module |> Module.split() |> hd() |> List.wrap() |> Module.concat()\n" +
                "    Module.put_attribute(outer, :x, :ok)\n  end\nend\n",
        )
        val a = add(
            "a.ex",
            "defmodule Outer do\n  @x :bad\n  defmodule I do\n    @after_compile CB\n  end\n  def unquote(@x)(), do: 1\n  def g, do: 1\nend\n",
        )
        val b = add("b.ex", "defmodule B do\n  import Outer\n  def h, do: g()\nend\n")

        val outer = expansion(holder, a).modules.single { it.module == "Elixir.Outer" }

        assertFalse(outer.table.entries.keys.toString(), NameArity("bad", 0) in outer.table.entries.keys)
        assertTrue(render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.startsWith("Elixir.B stopped `import Outer`"))
    }

    /**
     * Premise: the project compiles. A call never run because an argument declined is a declined call given its other
     * arguments, so it may have written the module it was given: `def unquote(@x)` stops, and another file can't read
     * the module. (Elixir, where `noop/2` writes nothing, defines `ok/0`.)
     */
    fun testACallNeverRunBecauseAnArgumentDeclinedMayHaveWrittenTheModuleItWasGiven() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\n  def noop(_, _), do: :ok\nend\n")
        val a = add("a.ex", "defmodule A do\n  @x :ok\n  H.noop(__MODULE__, H.noop(1))\n  def unquote(@x)(), do: 1\n  def g, do: 1\nend\n")
        val b = add("b.ex", "defmodule B do\n  import A\n  def h, do: g()\nend\n")

        val module = expansion(holder, a).modules.single { it.module == "Elixir.A" }

        assertEquals(setOf(NameArity("g", 0)), module.table.entries.keys)
        assertTrue(render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.startsWith("Elixir.B stopped `import A`"))
    }

    /**
     * A declined call not given the module, and a `receive`, write nothing the expander reads: the module after them is
     * read by another file as it defined itself.
     */
    fun testADeclineNotGivenTheModuleLeavesItLoaded() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A\n  def h, do: g()\nend\n")

        val wrong = listOf(
            "H.noop(1)",
            "H.noop(__ENV__.file)",
            "H.noop(__ENV__.aliases)",
            "H.noop(\"#{inspect(__MODULE__)}\")",
            "receive(do: (x -> x), after: (0 -> :ok))",
        ).mapNotNull { statement ->
            edit(project, a, "defmodule A do\n  @x :ok\n  $statement\n  def unquote(@x)(), do: 1\n  def g, do: 1\nend\n")
            val module = expansion(holder, a).modules.single { it.module == "Elixir.A" }
            val renderedB = render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }

            "$statement: ${module.table.entries.keys} $renderedB".takeUnless {
                NameArity("ok", 0) in module.table.entries.keys && renderedB.startsWith("Elixir.B compiled")
            }
        }

        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * A block one of whose expressions declines gives a decline, wherever it runs: as a match's right side the walk runs,
     * or as a fragment the evaluator does, and whether the expression is a call or a match. So `def unquote(x)` after
     * `x = (H.noop(1); :ok)` stops, as `def unquote((H.noop(1); :ok))` does. (Elixir defines `ok/0`.) A definition the
     * walk ran before the last expression declines nothing: after `x = (def f, do: 1; :ok)`, `ok/0` is defined.
     */
    fun testABlockWithADeclinedExpressionDeclinesWhereverItRuns() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        val a = add("a.ex", "")

        val tables = listOf(
            "x = (H.noop(1); :ok)\n  def unquote(x)(), do: 1",
            "def unquote((H.noop(1); :ok))(), do: 1",
            "x = (y = H.noop(1); :ok)\n  def unquote(x)(), do: 1",
            "def unquote((y = H.noop(1); :ok))(), do: 1",
        ).map { definition ->
            edit(project, a, "defmodule A do\n  $definition\n  def g, do: 1\nend\n")

            definition to expansion(holder, a).modules.single { it.module == "Elixir.A" }.table.entries.keys
        }

        assertEquals(tables.map { it.first to setOf(NameArity("g", 0)) }, tables)

        edit(project, a, "defmodule A do\n  x = (def f, do: 1; :ok)\n  def unquote(x)(), do: 1\n  def g, do: 1\nend\n")

        assertEquals(
            setOf(NameArity("f", 0), NameArity("ok", 0), NameArity("g", 0)),
            expansion(holder, a).modules.single { it.module == "Elixir.A" }.table.entries.keys,
        )
    }

    /**
     * A raise after a decline ends the loop, block or tuple it is in, as one before a decline does, and so does a raise
     * in the `after` of a `try` whose body declined, a decline in the `after` of one whose body raised, and a raise no
     * `rescue` or `catch` clause takes or whose clause raises again: Elixir raises at the first iteration or call, so
     * the expander walks no further than a bound well below the loop's length.
     */
    fun testARaiseAfterADeclineEndsTheLoopItIsIn() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        add("e.ex", "defmodule E do\n  defmacro errs, do: quote(do: [ArgumentError])\nend\n")
        val a = add("a.ex", "")

        val unended = listOf(
            "for n <- 1..1_000_000_000_000, do: (H.noop(n); raise \"stop\")",
            "for n <- 1..1_000_000_000_000, do: {H.noop(n), raise \"stop\"}",
            "for n <- 1..1_000_000_000_000, do: (if n == 1, do: H.noop(n), else: raise \"stop\")",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n) after raise \"stop\" end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n) after (H.noop(n); raise \"stop\") end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" after H.noop(n) end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue ArgumentError -> :ok after H.noop(n) end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue ArgumentError -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" catch :throw, x -> x end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); throw :stop rescue _ -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue _ -> raise \"again\" end)",
            "for n <- 1..1_000_000_000_000, do: (try do (try do H.noop(n) after raise \"stop\" end) rescue ArgumentError -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" after :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" else _ -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue [ArgumentError] -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue [ArgumentError] -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue [ArgumentError, KeyError] -> :ok end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue :\"Elixir.ArgumentError\" -> :ok end)",
            "require E\n  for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue E.errs() -> n end)",
            "require E\n  for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue x in E.errs() -> {n, x} end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue var!(x) in [ArgumentError] -> {n, x} end)",
            "for n <- 1..1_000_000_000_000, do: (try do H.noop(n); raise \"stop\" rescue var!(x) in [ArgumentError, KeyError] -> {n, x} end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue x in _ -> raise \"again #{n} #{inspect(x)}\" end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue x in _ -> raise \"again #{n} #{x.message}\" end)",
            "for n <- 1..1_000_000_000_000, do: (try do raise \"stop\" rescue _ -> raise inspect(n) end)",
            "f = fn f, n ->\n    H.noop(n)\n    raise \"stop\"\n    if n > 0, do: (f.(f, n - 1); f.(f, n - 1))\n  end\n  f.(f, 60)",
        ).filter { statement ->
            edit(project, a, "defmodule A do\n  $statement\n  def g, do: 1\nend\n")

            try {
                expandUnder(ProjectModules(project), a, RAISE_AFTER_DECLINE_CHECKS)
                false
            } catch (_: ProcessCanceledException) {
                true
            }
        }

        assertEquals(emptyList<String>(), unended)
    }

    /**
     * A module-body value whose parts are shared, as `t = {t, t}` shares them, converts for an effect in work bounded by
     * the nodes a converted term holds, though copying each part where it appears would take more than the IDE's heap.
     */
    fun testASharedValueAnEffectIsGivenConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  @a t\n")
    }

    /** As a value an effect is given, `defdelegate`'s options whose parts are shared convert in bounded work. */
    fun testASharedValueInDefdelegatesOptionsConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  defdelegate f(x), to: B, extra: t\n")
    }

    /** A shared module-body value a definition unquotes converts in bounded work. */
    fun testASharedValueADefinitionUnquotesConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  def f, do: unquote(t)\n")
    }

    /** A shared module-body value a definition splices converts in bounded work. */
    fun testASharedValueADefinitionSplicesConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  def f, do: [unquote_splicing([{:f, [], [t]}])]\n")
    }

    /**
     * A shared module-body value in the call a definition's `Left.unquote(x)` names converts in bounded work. The
     * definition is a macro's output, as a `Left.unquote(x)` with no parentheses can only be built.
     */
    fun testASharedValueADefinitionDotsConvertsInBoundedWork() {
        add(
            "b.ex",
            "defmodule B do\n  defmacro m do\n" +
                "    {:def, [], [{:f, [], []}, [do: {{:unquote, [], [:a]}, {{{:., [], [B, :unquote]}, [], [{:x, [], nil}]}, [], nil}}]]}\n" +
                "  end\nend\n",
        )
        assertSharedValueConvertsInBoundedWork("  x = {:f, [], [t]}\n  require B\n  B.m()\n")
    }

    /** A shared value a macro's body unquotes in a map in a definition converts in bounded work. */
    fun testASharedValueAMacroUnquotesInAMapConvertsInBoundedWork() {
        assertSharedValueAMacroBuildsConvertsInBoundedWork("{:def, [], [{:f, [], []}, [do: {{:unquote, [], [:a]}, %{a: {:unquote, [], [t]}}}]]}")
    }

    /** A shared value `Macro.escape/1` is given escapes in bounded work. */
    fun testASharedValueMacroEscapesConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  x = Macro.escape(t)\n")
    }

    /** A list `:lists.flatten/1` is given whose parts are shared flattens in bounded work. */
    fun testASharedValueListsFlattenConvertsInBoundedWork() {
        assertSharedValueConvertsInBoundedWork("  x = :lists.flatten(t)\n", doubling = "t = [t, :a, t]")
    }

    /** A shared value in the metadata of a node a macro's body returns converts in bounded work. */
    fun testASharedValueInTheMetadataAMacroReturnsConvertsInBoundedWork() {
        assertSharedValueAMacroBuildsConvertsInBoundedWork("{:f, [a: t], []}")
    }

    /** A shared value in a map a macro's body returns in a definition converts in bounded work. */
    fun testASharedValueInAMapAMacroReturnsConvertsInBoundedWork() {
        assertSharedValueAMacroBuildsConvertsInBoundedWork("{:def, [], [{:f, [], []}, [do: {{:unquote, [], [:a]}, %{a: t}}]]}")
    }

    /** A shared value a macro's body gives `Macro.expand/2` converts in bounded work. */
    fun testASharedValueAMacroExpandsConvertsInBoundedWork() {
        assertSharedValueAMacroBuildsConvertsInBoundedWork("Macro.expand(t, __CALLER__)\n    :ok")
    }

    /** A module body that applies [doubling] forty times to `t = []`, then runs [after], expands in [SHARED_VALUE_CHECKS]. */
    private fun assertSharedValueConvertsInBoundedWork(after: String, doubling: String = "t = {t, t}") {
        addLegElixir()
        val doubled = "  $doubling\n".repeat(40)
        val a = add("a.ex", "defmodule A do\n  t = []\n$doubled$after  def g, do: 1\nend\n")

        val checks = expandUnder(ProjectModules(project), a, SHARED_VALUE_CHECKS)

        assertTrue("$checks", checks < SHARED_VALUE_CHECKS)
    }

    /** A macro whose body doubles `t` forty times, then ends in [last], expands where it is called in [SHARED_VALUE_CHECKS]. */
    private fun assertSharedValueAMacroBuildsConvertsInBoundedWork(last: String) {
        addLegElixir()
        val doubled = "    t = {t, t}\n".repeat(40)
        add("b.ex", "defmodule B do\n  defmacro m do\n    t = []\n$doubled    $last\n  end\nend\n")
        val a = add("a.ex", "defmodule A do\n  require B\n  B.m()\n  def g, do: 1\nend\n")

        val checks = expandUnder(ProjectModules(project), a, SHARED_VALUE_CHECKS)

        assertTrue("$checks", checks < SHARED_VALUE_CHECKS)
    }

    /**
     * A `rescue` clause rescues what its head, read as Elixir reads it, names: a macro call is read as what it expands
     * to, `x in _` as the variable `x`, a macro left of `in` as the variable it expands to, and an empty list as every
     * error, binding the error as it was raised. Where the clause takes the raise, the module loads.
     */
    fun testARescueClausesHeadIsReadAsElixirReadsIt() {
        addLegElixir()
        add(
            "e.ex",
            "defmodule E do\n  defmacro errs, do: quote(do: [ArgumentError])\n  defmacro vin, do: quote(do: var!(e) in [ArgumentError])\nend\n",
        )
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A\n  def h, do: g()\nend\n")

        val answers = listOf(
            "try do raise ArgumentError rescue [] -> :ok end",
            "try do raise ArgumentError rescue x in [] -> x end",
            "try do :erlang.error(:badarg) rescue x in [] -> (:badarg = x) end",
            "try do :erlang.error(:badarg) rescue x in [ArgumentError] -> (%ArgumentError{} = x) end",
            "try do raise ArgumentError rescue E.errs() -> :ok end",
            "try do raise ArgumentError rescue x in E.errs() -> x end",
            "try do raise ArgumentError rescue x in _ -> x end",
            "try do :erlang.error(:badarg) rescue x in _ -> (%ArgumentError{} = x) end",
            "try do raise KeyError, key: :k rescue E.errs() -> :ok end",
            "try do raise ArgumentError rescue var!(x) in [ArgumentError] -> x end",
            "try do raise KeyError, key: :k rescue var!(x) in [ArgumentError] -> x end",
        ).plus(
            // Before a call head is expanded as a macro, it is `_ in` the call, where `var!(e)` reads no variable.
            listOf("try do raise KeyError, key: :k rescue E.vin() -> :ok end").takeIf {
                RESCUE_CALL_EXPANDED_AS_MACRO.isSufficient(legLevel())
            }.orEmpty(),
        ).map { statement ->
            edit(project, a, "defmodule A do\n  require E\n  $statement\n  def g, do: 1\nend\n")

            statement to render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors")
        }

        assertEquals(
            answers.map { (statement, _) ->
                statement to if ("KeyError" in statement) "Elixir.B raised unloaded_module `import A`" else "Elixir.B compiled"
            },
            answers,
        )
    }

    /**
     * An error whose inputs are certain ends the walk whatever came before it: Elixir raises there, or at a unit
     * before it. The module, and one after it in the file, are absent to another file.
     */
    fun testAnErrorFromCertainInputsAfterADeclineLeavesTheModuleAndTheNextAbsent() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        add("a.ex", "defmodule A do\n  H.noop(1)\n  :ok = :bad\n  def g, do: 1\nend\ndefmodule C do\n  def g, do: 1\nend\n")

        val answers = listOf("A", "C").map { name ->
            val b = add("b$name.ex", "defmodule B$name do\n  import $name\n  def h, do: g()\nend\n")

            render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B$name ") }.substringBefore(" errors")
        }

        assertEquals(listOf("Elixir.BA raised unloaded_module `import A`", "Elixir.BC raised unloaded_module `import C`"), answers)
    }

    /**
     * A generator's `when` is a filter, not a guard: `true` keeps the element and `false` skips it, while an error in
     * it, or any other value, raises. So the module, and one after it in the file, are absent to another file.
     */
    fun testAGeneratorsWhenRaisesAsAFilterDoes() {
        addLegElixir()
        val a = add("a.ex", "")
        val importers = listOf("A", "C").map { name ->
            add("b$name.ex", "defmodule B$name do\n  import $name\n  def h, do: g()\nend\n")
        }
        val raised = listOf("A", "C").map { "Elixir.B$it raised unloaded_module `import $it`" }

        val answers = listOf(
            "for x when tuple_size(x) > 0 <- [:a], do: x",
            "for x when x <- [1], do: x",
            "for x when is_atom(x) or tuple_size(x) > 0 <- [:a, 1], reduce: 0, do: (acc -> acc + 1)",
            "x = for x when x > 1 <- [1, 2], do: x\n  [2] = x",
            "x = for x when x == 1 when x == 2 <- [1, 2, 3], do: x\n  [1, 2] = x",
            "x = try do\n    for x when 1 when true <- [1], do: x\n  catch\n    :error, e -> e\n  end\n  {:badarg, 1} = x",
            "x = try do\n    for x when x <- [1], do: x\n  catch\n    :error, e -> e\n  end\n  {:case_clause, 1} = x",
        ).map { statement ->
            edit(project, a, "defmodule A do\n  $statement\n  def g, do: 1\nend\ndefmodule C do\n  def g, do: 1\nend\n")

            importers.map { b ->
                render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B") }.substringBefore(" errors")
            }
        }

        assertEquals(List(3) { raised } + List(4) { listOf("Elixir.BA compiled", "Elixir.BC compiled") }, answers)
    }

    /**
     * After a declined call given a module being compiled, a read of that module's table in the same statement
     * declines, since the call may have written it, and so does one in a function made by an earlier statement: a match
     * on the read doesn't raise, and the call after it that is given the nested module still runs. Elixir's `H.set/1`
     * sets both modules' `@x` to `:ok`, so `A.I` defines `ok/0`, which another file can't read.
     */
    fun testAReadOfAModuleADeclinedCallWasGivenDeclines() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.put_attribute(m, :x, :ok)\n  end\nend\n",
        )
        val a = add("a.ex", "defmodule Z do\nend\n")
        val b = add("b.ex", "defmodule B do\n  import A.I\n  def h, do: ok()\nend\n")

        val read = "f = fn -> :ok = Module.get_attribute(A, :x) end"
        // The code before `defmodule I` in `A`, and the statement in `I`.
        val answers = listOf(
            "" to "{H.set(A), (:ok = Module.get_attribute(A, :x)), H.set(__MODULE__)}",
            "" to "if true do\n      H.set(A)\n      :ok = Module.get_attribute(A, :x)\n      H.set(__MODULE__)\n    end",
            "" to "{try do H.set(A) after :ok = Module.get_attribute(A, :x) end, H.set(__MODULE__)}",
            "" to "$read\n    {H.set(A), f.(), H.set(__MODULE__)}",
            "  $read\n" to "{H.set(A), f.(), H.set(__MODULE__)}",
            "" to "$read\n    {try do H.set(A) after f.() end, H.set(__MODULE__)}",
        ).map { (outer, statement) ->
            edit(
                project,
                a,
                "defmodule A do\n  @x :bad\n${outer}  defmodule I do\n    @x :a\n    $statement\n" +
                    "    def unquote(@x)(), do: 1\n  end\n  def unquote(@x)(), do: 1\nend\n",
            )
            render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors")
        }

        assertEquals(List(6) { "Elixir.B stopped `import A.I` unported import" }, answers)
    }

    /**
     * A read in a macro's body of a module a declined call there was given declines, as one in a module body does, so
     * a loop the read bounds isn't run: Elixir's `H.set/1` sets `@x` to `:ok`, and the loop runs once.
     */
    fun testAReadInAMacrosBodyOfAModuleADeclinedCallThereWasGivenDeclines() {
        addLegElixir()
        add(
            "h.ex",
            "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n" +
                "    if is_atom(m), do: Module.put_attribute(m, :x, :ok)\n  end\n" +
                "  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n",
        )
        add(
            "m.ex",
            "defmodule M do\n  defmacro m do\n    H.set(A)\n" +
                "    bound = if Module.get_attribute(A, :x) == :bad, do: 1_000_000_000_000, else: 1\n" +
                "    for n <- 1..bound, do: H.noop(n)\n    :ok\n  end\nend\n",
        )
        val a = add("a.ex", "defmodule A do\n  @x :bad\n  defmodule I do\n    require M\n    M.m()\n    def i, do: 1\n  end\nend\n")

        expandUnder(ProjectModules(project), a, RAISE_AFTER_DECLINE_CHECKS)
    }

    /**
     * A nested module's assumptions are its own: where it raises as its body expands, an assumed bang read of the
     * application's environment the walk still runs in it leaves the module around it raised, not stopped there.
     */
    fun testANestedModulesAssumptionsAreItsOwn() {
        addLegElixir()
        val a = add(
            "a.ex",
            "defmodule Boom do\n  defmacro boom, do: raise(\"boom\")\nend\n" +
                "defmodule A do\n  defmodule I do\n    @b Application.fetch_env!(:nope, :k)\n    require Boom\n    Boom.boom()\n  end\nend\n",
        )

        val line = render(a, expansion(holder, a)).lines().first { it.startsWith("Elixir.A ") }

        assertTrue(line, line.startsWith("Elixir.A raised "))
    }

    /**
     * Premise: the project compiles. An error computed from a table a declined call given the module may have written
     * is assumed not to happen, and the walk goes on: the module after it in the file loads. (Elixir, where the call
     * writes nothing, raises at the `defoverridable` and never defines `C`.)
     */
    fun testAssumingItCompilesAnErrorFromATableTheModuleMayHaveWrittenLeavesTheNextModuleLoaded() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(m), do: Other.call(m)\nend\n")
        add("a.ex", "defmodule A do\n  H.noop(__MODULE__)\n  defoverridable nope: 0\nend\ndefmodule C do\n  def g, do: 1\nend\n")
        val b = add("b.ex", "defmodule B do\n  import C\n  def h, do: g()\nend\n")

        assertEquals("Elixir.B compiled", render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors"))
    }

    /** A declined call whose callee writes nothing leaves the table known, so an error computed from it raises. */
    fun testAnErrorFromATableADeclinedCallDoesNotWriteLeavesTheNextModuleAbsent() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        add("a.ex", "defmodule A do\n  H.noop(__MODULE__)\n  defoverridable nope: 0\nend\ndefmodule C do\n  def g, do: 1\nend\n")
        val b = add("b.ex", "defmodule B do\n  import C\n  def h, do: g()\nend\n")

        assertEquals("Elixir.B raised unloaded_module `import C`", render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors"))
    }

    /**
     * An error whose inputs are certain ends the walk after an earlier stop too: Elixir raises there, or at the stop.
     * The module may raise, so it and one after it in the file are absent to another file.
     */
    fun testAnErrorFromCertainInputsAfterAStopLeavesTheModuleAndTheNextAbsent() {
        addLegElixir()
        add("a.ex", "defmodule A do\n  :elixir_def.take_definition(__MODULE__, {:f, 0})\n  :ok = :bad\n  def g, do: 1\nend\ndefmodule C do\n  def g, do: 1\nend\n")

        val answers = listOf("A", "C").map { name ->
            val b = add("b$name.ex", "defmodule B$name do\n  import $name\n  def h, do: g()\nend\n")

            render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B$name ") }.substringBefore(" errors")
        }

        assertEquals(listOf("Elixir.BA raised unloaded_module `import A`", "Elixir.BC raised unloaded_module `import C`"), answers)
    }

    /**
     * Premise: the project compiles. After an earlier stop, an error computed from a table a declined call given the
     * module may have written is assumed not to happen, and the walk goes on: the module after it in the file loads.
     */
    fun testAssumingItCompilesAnErrorFromATableTheModuleMayHaveWrittenAfterAStopLeavesTheNextModuleLoaded() {
        addLegElixir()
        add("h.ex", "defmodule H do\n  def noop(_), do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        add(
            "a.ex",
            "defmodule A do\n  :elixir_def.take_definition(__MODULE__, {:f, 0})\n  H.noop(__MODULE__)\n  defoverridable nope: 0\nend\n" +
                "defmodule C do\n  def g, do: 1\nend\n",
        )
        val b = add("b.ex", "defmodule B do\n  import C\n  def h, do: g()\nend\n")

        assertEquals("Elixir.B compiled", render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors"))
    }

    /**
     * A module that uses `GenServer`, `Supervisor`, `Agent`, `Task` or `DynamicSupervisor` compiles and another file
     * reads what it defines: the `if` whose branch the walk doesn't select writes only `@doc`, which no export reads.
     */
    fun testEachOfTheFiveUsesLoads() {
        addLegElixir()
        val a = add("a.ex", "")

        val wrong = listOf(
            "use GenServer\n  def init(state), do: {:ok, state}",
            "use Supervisor",
            "use Agent",
            "use Task",
            "use DynamicSupervisor",
        ).mapNotNull { body ->
            edit(project, a, "defmodule A do\n  $body\n  def g, do: 1\nend\n")
            val rendered = render(a, expansion(holder, a)).lines().first { it.startsWith("Elixir.A ") }

            "$body: $rendered".takeUnless {
                rendered.startsWith("Elixir.A compiled ") && rendered.contains(" loads Present(functions=[") &&
                    rendered.contains("NameArity(name=g, arity=0)")
            }
        }

        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * An attribute that code the walk can't place may write leaves what the module defines known, but for the export
     * parts that read it, and the definitions whose clauses read it.
     */
    fun testAnUnorderedAttributeWriteLeavesUnknownOnlyWhatReadsTheAttribute() {
        addLegElixir()
        val a = add("a.ex", "")
        val unordered = { write: String -> "  if Application.get_env(:app, :k) do\n    $write\n  end\n" }

        val rendered = listOf(
            unordered("@a 1") + "  def g, do: 1\n",
            unordered("@callback c() :: :ok") + "  def g, do: 1\n",
            unordered("@deprecated \"x\"") + "  def g, do: 1\n",
            unordered("@a 1") + "  def g, do: @a\n",
        ).joinToString("\n") { body ->
            edit(project, a, "defmodule A do\n$body\nend\n")

            render(a, expansion(holder, a)).lines().first { it.startsWith("Elixir.A ") }
        }

        assertEquals(
            listOf(
                "Elixir.A compiled errors[] loads Present(functions=[NameArity(name=g, arity=0)], macros=[], hasInfo=true, " +
                    "behaviour=None, deprecated=Known(reasons={}), attributes=Unreadable)",
                "Elixir.A compiled errors[] loads Present(functions=[NameArity(name=g, arity=0)], macros=[], hasInfo=true, " +
                    "behaviour=Unreadable, deprecated=Known(reasons={}), attributes=Unreadable)",
                "Elixir.A compiled errors[] loads Present(functions=[NameArity(name=g, arity=0)], macros=[], hasInfo=true, " +
                    "behaviour=None, deprecated=Unreadable, attributes=Unreadable)",
                "Elixir.A compiled errors[] loads Unreadable",
            ).joinToString("\n"),
            rendered,
        )
    }

    /**
     * A module a `@before_compile` callback's output defines after the stop in its expansion was never queued, while
     * Elixir may have defined it: unreadable to another file, not absent.
     */
    fun testAModuleABeforeCompileCallbacksOutputDefinesAfterTheStopInItsExpansionIsUnreadable() {
        addLegElixir()
        val a = add("a.ex", MACRO_M_OK + callback(CALL_M + foo("def f, do: 1")))
        val b = add("b.ex", IMPORTS_FOO)

        val renderedA = render(a, expansion(holder, a))
        val renderedB = render(b, expansion(holder, b))

        assertTrue(renderedA, renderedA.contains("\nElixir.O stopped `"))
        assertFalse(renderedA, renderedA.contains("Elixir.Foo"))
        assertTrue(renderedB, renderedB.contains("Elixir.B stopped `import Foo`"))
    }

    /**
     * A stop anywhere in the module, before its `@before_compile` callbacks run, leaves them unrun, while Elixir runs
     * them: what they would define is unreadable to another file, not absent.
     */
    fun testAStopInTheModuleLeavesWhatItsBeforeCompileCallbacksDefineUnreadable() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", IMPORTS_FOO)

        val wrong = listOf(
            "  require M\n  def g, do: M.m()\n",
            "  defmodule I do\n    require M\n    def g, do: M.m()\n  end\n",
        ).mapNotNull { body ->
            edit(project, a, MACRO_M_OK + callback(foo("def f, do: 1"), body))
            val renderedA = render(a, expansion(holder, a))
            val renderedB = render(b, expansion(holder, b))

            "$renderedA\n$renderedB".takeUnless {
                renderedA.contains("\nElixir.O stopped `") && renderedB.contains("Elixir.B stopped `import Foo`")
            }
        }

        assertEquals("", wrong.joinToString("\n\n"))
    }

    /**
     * A module that stops with no `@before_compile` callbacks, at code given no module, leaves none unrun: a module its
     * file holds only in a quote that nothing runs is absent. A module nested in it that stops marks it as possibly
     * written, as what Elixir runs after the stop isn't followed, so the name is then unreadable, not absent.
     */
    fun testAStopLeavesANameItsQuoteHoldsAbsentUnlessANestedModuleStops() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", IMPORTS_FOO)

        val wrong = listOf(
            "  def unquote((receive do after 0 -> :g end))(), do: 1\n" to "Elixir.B raised unloaded_module `import Foo`",
            "  defmodule I do\n    def unquote((receive do after 0 -> :g end))(), do: 1\n  end\n" to "Elixir.B stopped `import Foo`",
        ).mapNotNull { (body, endedB) ->
            edit(project, a, "defmodule O do\n  def q do\n    quote do\n${foo("def f, do: 1")}    end\n  end\n${body}end\n")
            val renderedA = render(a, expansion(holder, a))
            val renderedB = render(b, expansion(holder, b))

            "$renderedA\n$renderedB".takeUnless { renderedA.contains("\nElixir.O stopped `") && renderedB.contains(endedB) }
        }

        assertEquals("", wrong.joinToString("\n\n"))
    }

    /**
     * A module that stops at a macro the expander can't follow may have registered a `@before_compile` callback on
     * itself through the macro's caller env, whose output may define the name: unreadable to another file, not absent.
     */
    fun testAStopAtAnUnfollowedMacroLeavesOtherNamesUnreadable() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", IMPORTS_FOO)

        val wrong = listOf(
            "  require M\n  def g, do: M.m()\n",
            "  defmodule I do\n    require M\n    def g, do: M.m()\n  end\n",
        ).mapNotNull { body ->
            edit(project, a, MACRO_M_OK + "defmodule O do\n  def q do\n    quote do\n${foo("def f, do: 1")}    end\n  end\n${body}end\n")
            val renderedA = render(a, expansion(holder, a))
            val renderedB = render(b, expansion(holder, b))

            "$renderedA\n$renderedB".takeUnless {
                renderedA.contains("\nElixir.O stopped `") && renderedB.contains("Elixir.B stopped `import Foo`")
            }
        }

        assertEquals("", wrong.joinToString("\n\n"))
    }

    /**
     * A stop in what one `@before_compile` callback's output queues leaves the callbacks after it unrun, while Elixir
     * runs them: what they would define is unreadable to another file, not absent.
     */
    fun testAStopInOneBeforeCompileCallbacksOutputLeavesWhatTheNextDefinesUnreadable() {
        addLegElixir()
        val first = "defmodule CB1 do\n  defmacro __before_compile__(_) do\n    quote do\n      require M\n      def g, do: M.m()\n    end\n  end\nend\n"
        val a = add("a.ex", MACRO_M_OK + first + callback(foo("def f, do: 1"), name = "CB2", calls = listOf("CB1", "CB2")))
        val b = add("b.ex", IMPORTS_FOO)

        val renderedA = render(a, expansion(holder, a))
        val renderedB = render(b, expansion(holder, b))

        assertTrue(renderedA, renderedA.contains("\nElixir.O stopped `"))
        assertFalse(renderedA, renderedA.contains("Elixir.Foo"))
        assertTrue(renderedB, renderedB.contains("Elixir.B stopped `import Foo`"))
    }

    /**
     * A macro can build a definition whose body holds an `unquote` as a value, not as code: Elixir reads its expression
     * as code and expands it with the module body, before any statement runs, so an unbound variable there raises before
     * a module nested in the body is defined, before or after the definition. With no `unquote` the definition isn't
     * escaped, and the raise its body gives is the module's when the definition runs: a module nested before it loads,
     * one after it is never defined.
     */
    fun testADefinitionAMacroBuildsWithAnUnquoteInItsValueRaisesAsTheBodyExpands() {
        addLegElixir()
        val raised = "Elixir.B raised unloaded_module `import A.I`"

        assertEquals(
            listOf(
                listOf(raised, raised, raised),
                listOf(raised, raised, "Elixir.B compiled"),
            ),
            nestedAround(listOf("unquote", "unquote_splicing", "x").map { "[{:$it, [], [{:y, [], nil}]}]" }),
        )
    }

    /**
     * `elixir_quote:has_unquotes/1` reads a macro's value as a term, whatever its metadata: an `unquote` among an
     * improper list's elements, or under a call whose metadata isn't a list, makes the definition one with unquotes,
     * whose unbound variable raises as the body expands, and an improper list with none before its tail raises
     * `FunctionClauseError` as the `def` expands, a tail that is a binary whose bytes aren't known included, so neither
     * the module nor one nested in it loads. From [ESCAPE_HEADS_NEED_LIST_META] the escape reads an `unquote` whose
     * metadata isn't a list as a tuple, so the definition's raise is the module's when it runs: a module nested before
     * it loads, one after it is never defined.
     */
    fun testADefinitionAMacroBuildsReadsItsValueAsATerm() {
        addLegElixir()
        val raised = "Elixir.B raised unloaded_module `import A.I`"
        val tuple = ESCAPE_HEADS_NEED_LIST_META.isSufficient(legLevel())

        assertEquals(
            listOf(
                listOf(raised, raised, raised, raised, raised, raised),
                listOf(raised, raised, if (tuple) "Elixir.B compiled" else raised, raised, raised, raised),
            ),
            nestedAround(
                listOf(
                    "[[{:unquote, [], [{:y, [], nil}]} | :t]]",
                    "[{:x, 1, [{:unquote, [], [{:y, [], nil}]}]}]",
                    "[{:unquote, 1, [{:y, [], nil}]}]",
                    "[:a | :b]",
                    "[[:a | :b]]",
                    "[:a | inspect(1)]",
                ),
            ),
        )
    }

    /**
     * An `unquote` of a term that is code, in a definition a macro builds, expands with the body, so a module nested
     * before it loads.
     */
    fun testAnUnquoteOfCodeInADefinitionAMacroBuildsLoadsAModuleNestedBeforeIt() {
        addLegElixir()

        assertEquals(listOf(listOf("Elixir.B compiled")), nestedAround(listOf("[{:unquote, [], [1]}]"), positions = listOf(true)))
    }

    /**
     * An `unquote` in a node's metadata, in a definition a macro builds, is read as code, as `elixir_quote` reads it:
     * an unbound variable there raises as the body expands, so neither the module nor one nested in it loads. Code
     * that expands builds the node, and both load.
     */
    fun testAnUnquoteInTheMetadataOfANodeAMacroBuildsIsReadAsCode() {
        addLegElixir()
        val raised = "Elixir.B raised unloaded_module `import A.I`"

        assertEquals(
            listOf(listOf(raised, "Elixir.B compiled"), listOf(raised, "Elixir.B compiled")),
            nestedAround(
                listOf("{:y, [], nil}", "{:+, [], [1, 2]}"),
                body = { "{:foo, [k: {:unquote, [], [$it]}], [{:unquote, [], [1]}]}" },
                after = "  def foo(x), do: x\n",
            ),
        )
    }

    /**
     * An `unquote` read as code from a node's metadata is evaluated with the definition's other fragments, in escape
     * order, and checked as its place gives: where it, or another fragment, raises, or a splice there isn't a list or
     * a name isn't an atom, an alias or a quoted call, the raise is the module's when the definition runs, so a module
     * nested before it loads and one after it is never defined.
     */
    fun testARaiseBesideAnUnquoteInTheMetadataOfANodeAMacroBuildsIsTheDefinitions() {
        addLegElixir()
        val raised = "Elixir.B raised unloaded_module `import A.I`"

        assertEquals(
            listOf(
                buildList {
                    addAll(listOf(raised, raised, raised, raised, "Elixir.B compiled", raised, "Elixir.B compiled"))
                    addAll(List(5) { raised })
                    addAll(List(4) { "Elixir.B compiled" })
                    addAll(List(3) { "Elixir.B compiled" })
                    add(raised)
                },
                List(20) { "Elixir.B compiled" },
            ),
            nestedAround(
                listOf(
                    "[k: {:unquote, [], [:ok]}], [{:unquote, [], [{:+, [], [1, :a]}]}]",
                    "[k: {:unquote, [], [{:+, [], [1, :a]}]}], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [:ok]}]], [{:unquote, [], [1]}]",
                    "[k: {{:., [], [Kernel, :unquote]}, [], [1]}], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[1]]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[1]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[]]}, {:unquote, [], [{:-, [], [1]}]}]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [:ok]}, {:unquote, [], [{:-, [], [1]}]}]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[]]}, {:unquote, [], [{:+, [], [1, 2]}]}]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[]]}, {:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote, [], [{:+, [], [1, 2]}]}, {:|, [], [{:unquote_splicing, [], [:ok]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[1]]}, {:unquote, [], [{:-, [], [1]}]}]}]], [{:unquote, [], [1]}]",
                    "[k: [{:|, [], [{:unquote_splicing, [], [[1]]}, {:unquote, [], [{:+, [], [1, 2]}]}]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[]]}, {:|, [], [{:unquote_splicing, [], [[1]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote, [], [{:+, [], [1, 2]}]}, {:|, [], [{:unquote_splicing, [], [[1]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[]]}, :b, {:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[]]}, :b, :c, {:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [[]]}, {:unquote, [], [{:+, [], [1, 2]}]}, {:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                    "[k: [{:unquote_splicing, [], [:ok]}, :b, {:|, [], [{:unquote_splicing, [], [[]]}, :t]}]], [{:unquote, [], [1]}]",
                ),
                body = { "{:foo, $it}" },
                after = "  def foo(x), do: x\n",
            ),
        )
    }

    /**
     * A splice a macro's term holds before a list's tail is checked by `tail_list/3` with the tail the escape's code
     * builds, whatever term that is: splicing an empty list there raises when the definition runs, so a module nested
     * after it is never defined.
     */
    fun testASpliceBeforeATailAMacroBuildsIsCheckedWithTheTailTheEscapeBuilds() {
        addLegElixir()

        assertEquals(
            listOf(List(4) { "Elixir.B raised unloaded_module `import A.I`" }),
            nestedAround(
                listOf(":t", "{:a, :b, :c}", "%{}", "%{a: 1}").map { tail ->
                    "[], [%{k: [{:|, [], [{:unquote_splicing, [], [[]]}, $tail]}]}, {:unquote, [], [1]}]"
                },
                positions = listOf(false),
                body = { "{:foo, $it}" },
                after = "  def foo(x, y), do: {x, y}\n",
            ),
        )
    }

    /**
     * A name a macro's term unquotes is checked as the helper the escape calls checks it: given an atom for arguments,
     * it is a called name, so a call of no context raises when the definition runs, and a module nested after it is
     * never defined.
     */
    fun testANameAMacroBuildsIsCheckedAsTheEscapeCallsIt() {
        addLegElixir()

        assertEquals(
            listOf(listOf("Elixir.B raised unloaded_module `import A.I`"), listOf("Elixir.B compiled")),
            nestedAround(
                listOf("{{{:., [], [Kernel, :unquote]}, [], [{:{}, [], [:node, [], []]}]}, [], Elixir}"),
                body = { "{$it, {:unquote, [], [1]}}" },
            ),
        )
    }

    /**
     * An uncalled name, `Left.unquote(x)`, stops its definition where the escape reaches it, and the escape goes on:
     * the fragments before and after it, its own expression and the check that its value is an atom, an alias or a
     * quoted call are evaluated, so where one raises, the raise is the module's when the definition runs, and a module
     * nested after it is never defined.
     */
    fun testAnUncalledNameStopsItsDefinitionWhereItsEscapeReachesIt() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A.I\n  def h, do: g()\nend\n")
        val nested = "  defmodule I do\n    def g, do: 1\n  end\n"
        val raised = "Elixir.B raised unloaded_module `import A.I`"

        val answers = listOf(
            "  def f(unquote(1 + :a)), do: Kernel.unquote(:node)\n",
            "  def f(unquote(:x)), do: Kernel.unquote(1 + :a)\n",
            "  def f(unquote(:x)), do: Kernel.unquote(:node)\n  def h, do: unquote(1 + :a)\n",
            "  def f, do: {Kernel.unquote(:node), unquote(1 + :a)}\n",
            "  def f, do: Kernel.unquote(1)\n",
            "  def f, do: {Kernel.unquote(:node), [unquote_splicing(:ok)]}\n",
            "  def f(unquote(:x)), do: {Kernel.unquote(:node), Kernel.unquote(1)}\n",
            "  def f(Kernel.unquote(:node)), do: unquote(1 + :a)\n",
            "  def f(unquote(:x)), do: Kernel.unquote(:node)\n",
            "  def f, do: {Kernel.unquote(:node), 1}\n",
        ).map { definition ->
            listOf(nested + definition, definition + nested).map { body ->
                edit(project, a, "defmodule A do\n${body}end\n")
                render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors")
            }
        }

        val compiled = "Elixir.B compiled"

        assertEquals(List(8) { listOf(compiled, raised) } + List(2) { listOf(compiled, compiled) }, answers)
    }

    /**
     * Whether a macro's bang read of the application's environment finds its key isn't known, so its raise is assumed
     * not to happen, and an error after it stops the module: at the call where the macro raises, and at the error
     * where the module does, as after a bang read in the module body.
     */
    fun testAnErrorAfterAMacrosAssumedApplicationReadStopsTheModule() {
        addLegElixir()
        val a = add("a.ex", "")
        val read = "Application.fetch_env!(:nope, :k)"

        assertEquals(
            listOf("Elixir.A stopped `:ok = :bad`", "Elixir.A stopped `Mac.m()`"),
            listOf("($read; :ok)" to "  :ok = :bad\n", "($read; raise \"after the read\")" to "").map { (body, after) ->
                edit(project, a, "defmodule Mac do\n  defmacro m, do: $body\nend\ndefmodule A do\n  require Mac\n  Mac.m()\n${after}end\n")
                render(a, expansion(holder, a)).lines().first { it.startsWith("Elixir.A ") }.substringBefore("` ") + "`"
            },
        )
    }

    /**
     * A certain error after a macro's bang read of the application's environment, which is assumed not to raise, ends
     * the run as Elixir's raise does, in the macro, in the module or in a `@before_compile` callback's output: the
     * module and the next one in the file are absent, and so is a module nested before a macro that raises as the body
     * expands. One nested before an error the body's run reaches loads.
     */
    fun testAnErrorAfterAMacrosAssumedApplicationReadEndsTheRun() {
        addLegElixir()
        val a = add("a.ex", "")
        val read = "Application.fetch_env!(:nope, :k)"
        val mac = "defmodule Mac do\n  defmacro m, do: ($read; :ok)\n  defmacro r, do: ($read; raise \"after the read\")\nend\n"
        val nested = "  defmodule I do\n    def g, do: 1\n  end\n"
        val callback = "defmodule CB do\n  defmacro __before_compile__(_) do\n    quote do\n      require Mac\n      Mac.m()\n" +
            "      :ok = :bad\n    end\n  end\nend\n"
        val names = listOf("A", "A.I", "C")
        val importers = names.map { name ->
            val importer = "B" + name.replace(".", "")

            importer to add("${importer.lowercase()}.ex", "defmodule $importer do\n  import $name\nend\n")
        }
        val raised = names.zip(importers) { name, (importer, _) -> "Elixir.$importer raised unloaded_module `import $name`" }
        val nestedLoads = raised.toMutableList().also { it[1] = "Elixir.BAI compiled" }

        assertEquals(
            listOf(raised, raised, nestedLoads, nestedLoads),
            listOf(
                mac + "defmodule A do\n  require Mac\n  Mac.m()\n  :ok = :bad\nend\n",
                mac + "defmodule A do\n${nested}  require Mac\n  Mac.r()\nend\n",
                mac + "defmodule A do\n${nested}  require Mac\n  Mac.m()\n  :ok = :bad\nend\n",
                mac + callback + "defmodule A do\n  @before_compile CB\n${nested}end\n",
            ).map { text ->
                edit(project, a, text + "defmodule C do\n  def g, do: 1\nend\n")
                importers.map { (importer, file) ->
                    render(file, expansion(holder, file)).lines().first { it.startsWith("Elixir.$importer ") }.substringBefore(" errors")
                }
            },
        )
    }

    /**
     * An error after a macro's bang read of the application's environment, which is assumed not to raise, stops where
     * it is, at the file's top as in a module body, in a definition's clause body as the body expands, and in a
     * `@before_compile` callback's output: what Elixir defines before the raise loads, and nothing after it does.
     */
    fun testAnErrorAfterAMacrosAssumedApplicationReadStopsAtTheFileTopAndInAClauseBody() {
        addLegElixir()
        add(
            "mac.ex",
            "defmodule Mac do\n  defmacro m, do: (Application.fetch_env!(:nope, :k); :ok)\n" +
                "  defmacro r, do: (Application.fetch_env!(:nope, :k); raise \"after the read\")\nend\n",
        )
        val a = add("a.ex", "")
        val nested = { name: String -> "  defmodule $name do\n    def g, do: 1\n  end\n" }
        val c = "defmodule C do\n  def g, do: 1\nend\n"
        val names = listOf("A", "A.I", "A.J", "B", "C")
        val importers = names.map { name ->
            val importer = "B" + name.replace(".", "")

            importer to add("${importer.lowercase()}.ex", "defmodule $importer do\n  import $name\nend\n")
        }

        val answers = listOf(
            "defmodule B do\n  def g, do: 1\nend\nrequire Mac\nMac.r()\n$c",
            "defmodule B do\n  def g, do: 1\nend\nrequire Mac\nMac.m()\n:ok = :bad\n$c",
            "defmodule A do\n${nested("I")}  require Mac\n  def f, do: Mac.r()\n${nested("J")}end\n$c",
            "defmodule A do\n${nested("I")}  require Mac\n  def f, do: Mac.m()\n  :ok = :bad\n${nested("J")}end\n$c",
            "defmodule CB do\n  defmacro __before_compile__(_) do\n    quote do\n      require Mac\n      Mac.m()\n      :ok = :bad\n" +
                "    end\n  end\nend\ndefmodule A do\n  @before_compile CB\n${nested("I")}end\n$c",
        ).map { text ->
            edit(project, a, text)
            val ended = render(a, expansion(holder, a)).lines().map { it.substringBefore(" errors") }

            ended + importers.map { (importer, file) ->
                render(file, expansion(holder, file)).lines().first { it.startsWith("Elixir.$importer ") }.substringBefore(" errors")
            }
        }

        val loading = { compiled: Set<String> ->
            names.zip(importers) { name, (importer, _) ->
                if (importer in compiled) "Elixir.$importer compiled" else "Elixir.$importer raised unloaded_module `import $name`"
            }
        }
        val inA = { top: List<String>, stop: String, kind: String ->
            buildList {
                addAll(top)
                add("Elixir.A stopped `$stop` unported $kind")
                add("Elixir.A.I compiled")
                addAll(loading(setOf("BAI")))
            }
        }

        assertEquals(
            listOf(
                listOf("top unported `Mac.r()`") + loading(emptySet()),
                listOf("top unported `:ok = :bad`", "Elixir.B compiled") + loading(setOf("BB")),
                inA(listOf("top unported `def f, do: Mac.r()`"), "def f, do: Mac.r()", "def"),
                inA(listOf("top unported `:ok = :bad`"), ":ok = :bad", "="),
                inA(listOf("top unported `defmodule A do`", "Elixir.CB compiled"), "defmodule A do", "="),
            ),
            answers,
        )
    }

    /**
     * A definition a macro builds whose term holds an improper list with no `unquote` before its tail raises at the
     * definition, from `elixir_quote:has_unquotes/1`.
     */
    fun testADefinitionAMacroBuildsWithAnImproperListRaisesAtTheDefinition() {
        addLegElixir()
        val a = add(
            "a.ex",
            "defmodule Mac do\n  defmacro m do\n    {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], " +
                "[{:f, [], []}, [do: {1, 2, [:a | :b]}]]}\n  end\nend\n" +
                "defmodule A do\n  require Mac\n  Mac.m()\nend\n",
        )

        assertEquals(
            "Elixir.A raised Elixir.FunctionClauseError `Mac.m()` errors[] loads Absent",
            render(a, expansion(holder, a)).lines().first { it.startsWith("Elixir.A ") },
        )
    }

    fun testTwoModulesDown() {
        val a = add("a.ex", A_CALLS_B)
        add("b.ex", "defmodule B do\n  require C\n  defmacro m, do: :ok\nend\n")
        val c = add("c.ex", "defmodule C do\nend\n")
        add("d.ex", "defmodule D do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")

        val before = render(a, expansion(holder, a))
        edit(project, c, "defmodule C do\n  require D\n  D.m()\nend\n")
        val after = expansion(holder, a)

        assertFresh("C stops at D.m()", a, after)
        assertFalse("A's answer moves", before == render(a, after))
    }

    fun testAbsentThenDefined() {
        val created = add("created.ex", "defmodule Created do\n  require D\nend\n")
        val outside = add("outside.ex", "defmodule Outside do\n  require E\nend\n")
        val edited = add("edited.ex", "defmodule Edited do\n  require F\nend\n")
        val host = add("host.ex", "defmodule Host do\nend\n")
        val createdBefore = render(created, expansion(holder, created))
        expansion(holder, outside)

        add("d.ex", "defmodule D do\nend\n")
        val createdAfter = expansion(holder, created)
        assertFresh("(a) D defined in a new file under content", created, createdAfter)
        assertFalse("(a) the answer moves", createdBefore == render(created, createdAfter))

        val elsewhere = FileUtil.createTempDirectory("project_modules", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, elsewhere.path)
        val elsewhereRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(elsewhere)!!
        WriteAction.runAndWait<Throwable> {
            elsewhereRoot.createChildData(this, "e.ex").setBinaryContent("defmodule E do\nend\n".toByteArray())
        }
        assertFresh("(c) E defined in a new file outside content", outside, expansion(holder, outside))

        val editedBefore = render(edited, expansion(holder, edited))
        edit(project, host, "defmodule Host do\nend\n\ndefmodule F do\nend\n")
        val editedAfter = expansion(holder, edited)
        assertFresh("(d) F defined by an edit to an existing file", edited, editedAfter)
        assertFalse("(d) the answer moves", editedBefore == render(edited, editedAfter))
    }

    fun testAModuleDefinedOnDiskInAFileWithNoTree() {
        val edited = add("edited.ex", "defmodule Edited do\n  require F\n  require G\nend\n")
        val unloaded = add("unloaded.ex", "defmodule Unloaded do\nend\n").virtualFile
        val held = add("held.ex", "defmodule Held do\nend\n").virtualFile
        val before = render(edited, expansion(holder, edited))
        repeat(3) { System.gc() }
        assertNull("unloaded.ex has no PSI", PsiManagerEx.getInstanceEx(project).fileManager.findCachedViewProvider(unloaded))
        val heldFile = psi(held)
        assertFalse("held.ex's tree is let go", (heldFile as PsiFileEx).isContentsLoaded)

        rewrite(unloaded, "defmodule Unloaded do\nend\n\ndefmodule F do\nend\n")
        val afterUnloaded = expansion(holder, edited)
        assertFresh("F defined on disk in a file with no PSI", edited, afterUnloaded)
        assertFalse("F defined on disk in a file with no PSI: the answer moves", before == render(edited, afterUnloaded))

        rewrite(held, "defmodule Held do\nend\n\ndefmodule G do\nend\n")
        val afterHeld = expansion(holder, edited)
        assertFresh("G defined on disk in a file whose PSI has no tree", edited, afterHeld)
        assertFalse(
            "G defined on disk in a file whose PSI has no tree: the answer moves",
            render(edited, afterUnloaded) == render(edited, afterHeld),
        )
        assertTrue(heldFile.isValid)
    }

    /** As Elixir makes each module available once its own `defmodule` compiles, while the rest of its file compiles. */
    fun testAModuleFinishedEarlierInARunningFileIsLoaded() {
        val a = add("a.ex", "defmodule A1 do\n  defmacro m, do: :ok\nend\n\ndefmodule A2 do\n  require B\nend\n")
        val b = add("b.ex", "defmodule B do\n  require A1\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        inBothOrders("A1 then A2, which requires B, which requires A1", a, b, x, legInputs) { ofA, ofB ->
            val rendered = render(a, ofA) + "\n" + render(b, ofB)

            assertTrue(rendered, render(a, ofA).contains("Elixir.A1 compiled"))
            assertTrue(rendered, render(a, ofA).contains("Elixir.A2 compiled"))
            assertTrue(rendered, render(b, ofB).contains("Elixir.B compiled"))
        }
    }

    /** B stops at `A1.m()`, as a macro from another source file isn't expanded, but no module reads as unloaded. */
    fun testAMacroFinishedEarlierInARunningFileIsLoaded() {
        val a = add("a.ex", "defmodule A1 do\n  defmacro m, do: :ok\nend\n\ndefmodule A2 do\n  require B\nend\n")
        val b = add("b.ex", "defmodule B do\n  require A1\n  A1.m()\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        inBothOrders("A1 then A2, which requires B, which calls A1's macro", a, b, x, legInputs) { ofA, ofB ->
            val rendered = render(a, ofA) + "\n" + render(b, ofB)

            assertFalse(rendered, rendered.contains("unloaded_module"))
        }
    }

    fun testTypingInABodyRecomputesNothing() {
        val asker = add("asker.ex", "defmodule Asker do\n  require Missing\n  require B\nend\n")
        add("b.ex", "defmodule B do\nend\n")
        val typed = add("typed.ex", "defmodule Typed do\n  def f, do: 1\nend\n")
        val held = expansion(holder, asker)
        val body = "defmodule Typed do\n  def f, do: 1".length
        val manager = PsiDocumentManager.getInstance(project)

        " + 2 * (3 - 4) |> Enum.map(fn x -> x * 2 end) |> g()".forEachIndexed { i, character ->
            WriteCommandAction.runWriteCommandAction(project) {
                manager.getDocument(typed)!!.insertString(body + i, "$character")
                manager.commitAllDocuments()
            }

            assertSame("after ${i + 1} characters", held, expansion(holder, asker))
        }
    }

    fun testACopyHoldsItsExpansionUntilItChanges() {
        val a = add("a.ex", "defmodule A do\n  require B\nend\n")
        add("b.ex", "defmodule B do\nend\n")
        val copy = completionCopy(a)

        assertTrue("the second ask is answered from the hold", expansion(holder, copy) === expansion(holder, copy))
    }

    fun testAnEditedCopyIsExpandedAgain() {
        val a = add("a.ex", "defmodule A do\n  require B\nend\n")
        add("b.ex", "defmodule B do\nend\n")
        val copy = completionCopy(a)
        val before = expansion(holder, copy)

        replaceInCopy(a, copy, "defmodule ".length, "defmodule A".length, "C")

        val after = expansion(holder, copy)
        assertNotSame(before, after)
        assertTrue(render(copy, after), render(copy, after).contains("Elixir.C compiled"))
        assertFresh("the edited copy", copy, after)
    }

    fun testSeveralCopiesUnreadable() {
        val a = add("a.ex", "defmodule A do\n  require B\nend\n")
        add("b1.ex", "defmodule B do\n  def f, do: 1\nend\n")
        val b2 = add("b2.ex", "defmodule B do\n  def f, do: 1\nend\n")

        assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.B"))
        val several = expansion(holder, a)
        assertFresh("two copies", a, several)

        edit(project, b2, "defmodule B2 do\n  def f, do: 1\nend\n")

        assertTrue(exports(holder, a, "Elixir.B") is ModuleExports.Present)
        assertFresh("one copy left", a, expansion(holder, a))
    }

    fun testTwoCopiesAreSeveralThroughLocateAndUnreadableThroughExports() {
        val a = add("a.ex", "defmodule A do\nend\n")
        val b1 = add("b1.ex", "defmodule B do\n  def f, do: 1\nend\n")
        val b2 = add("b2.ex", "defmodule B do\n  def f, do: 1\nend\n")

        val located = ReadAction.nonBlocking(Callable { holder.locate(a, "Elixir.B", legInputs) }).executeSynchronously()

        assertEquals(
            setOf(b1.virtualFile, b2.virtualFile),
            (located as? ProjectModules.Located.Several)?.copies?.toSet(),
        )
        assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.B"))
    }

    fun testAReparsedCopyIsLocatedAsItsNewFile() {
        val a = add("a.ex", "defmodule A do\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: 1\nend\n")
        val locate = { ReadAction.nonBlocking(Callable { holder.locate(a, "Elixir.B", legInputs) }).executeSynchronously() }
        assertSame(b, (locate() as ProjectModules.Located.Source).file)

        FileContentUtilCore.reparseFiles(b.virtualFile)

        val reparsed = (locate() as ProjectModules.Located.Source).file
        assertTrue("the located file is valid", ReadAction.nonBlocking(Callable { reparsed.isValid }).executeSynchronously())
        assertSame(psiManager.findFile(b.virtualFile), reparsed)
    }

    fun testDumbLocatesUnknown() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f, do: 1\nend\n")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val located = ReadAction.nonBlocking(Callable { holder.locate(a, "Elixir.B", legInputs) }).executeSynchronously()

            assertSame(ProjectModules.Located.Unknown, located)
        }
    }

    fun testLibraryReadsBeam() {
        val beam = File("testData/org/elixir_lang/expander/loaded_copy/Elixir.LoadedCopy.Dep.beam")
        val ebin = copiedLibrary(beam)
        val a = add("a.ex", "defmodule A do\n  require LoadedCopy.Dep\nend\n")

        assertEquals(
            ModuleExports.Present(
                listOf(NameArity("f", 0)),
                emptyList(),
                true,
                ModuleExports.Behaviour.None,
                Deprecations.Known(emptyMap()),
                vsn("170776559974243269538155803473822908025"),
            ),
            exports(holder, a, "Elixir.LoadedCopy.Dep"),
        )
        val before = expansion(holder, a)
        assertFresh("the .beam read", a, before)

        val rebuilt = File("testData/org/elixir_lang/expander/loaded_copy/changed/${beam.name}").readBytes()
        WriteAction.runAndWait<Throwable> { ebin.findChild(beam.name)!!.setBinaryContent(rebuilt) }

        assertEquals(
            ModuleExports.Present(
                listOf(NameArity("g", 0)),
                emptyList(),
                true,
                ModuleExports.Behaviour.None,
                Deprecations.Known(emptyMap()),
                vsn("280858559122594208050365940252033341601"),
            ),
            exports(holder, a, "Elixir.LoadedCopy.Dep"),
        )
        val after = expansion(holder, a)
        assertNotSame("the .beam rebuilt", before, after)
        assertFresh("the .beam rebuilt", a, after)
    }

    /** A library's deprecations are read from its `.beam`, and read again once it is rebuilt. */
    fun testCompiledDependency() {
        val beam = File("testData/org/elixir_lang/expander/deprecated_copy/Elixir.DeprecatedCopy.beam")
        val ebin = copiedLibrary(beam)
        val a = add("a.ex", "defmodule A do\n  DeprecatedCopy.old()\nend\n")

        assertEquals(
            listOf("DeprecatedCopy.old/0 is deprecated. Use new/0 instead line 2"),
            DeprecationWarnings.of(expansion(holder, a)),
        )

        val rebuilt = File("testData/org/elixir_lang/expander/deprecated_copy/changed/${beam.name}").readBytes()
        WriteAction.runAndWait<Throwable> { ebin.findChild(beam.name)!!.setBinaryContent(rebuilt) }

        assertEquals(
            listOf("DeprecatedCopy.old/0 is deprecated. Use newer/0 instead line 2"),
            DeprecationWarnings.of(expansion(holder, a)),
        )
    }

    /**
     * A macro of the SDK's compiled `EEx` is dispatched to remotely, though `EEx`'s sources are attached too, and the
     * function its output defines is exported, its body unknown.
     */
    fun testCompiledEex() {
        addCompiledEex()
        val a = add("a.ex", "defmodule A do\n  require EEx\n  EEx.function_from_string(:def, :f, \"x\")\nend\n")
        val rendered = render(a, expansion(holder, a))

        assertEquals(
            "top expanded\n" +
                "Elixir.A compiled errors[] loads Present(functions=[NameArity(name=f, arity=0)], macros=[], " +
                "hasInfo=true, behaviour=None, deprecated=Known(reasons={}), attributes=Unreadable)",
            rendered,
        )
        assertFresh("the compiled EEx read", a, expansion(holder, a))
    }

    /**
     * Options that hold the module give the declined `EEx.compile_string/2` the module, so it loads unreadable, and
     * the `@before_compile` it may have written stops the module at its end, a known divergence: Elixir compiles it.
     */
    fun testCompiledEexGivenTheModuleIsUnreadable() {
        addCompiledEex()
        val a = add("a.ex", "defmodule A do\n  require EEx\n  EEx.function_from_string(:def, :f, \"x\", [], m: __MODULE__)\nend\n")
        val expansion = expansion(holder, a)

        assertEquals(
            "top expanded\nElixir.A stopped `defmodule A do` unported defmodule errors[] loads Unreadable",
            render(a, expansion),
        )
        assertEquals(setOf(NameArity("f", 0)), expansion.modules.single().table.entries.keys)
    }

    /**
     * An implementation of a missing protocol, in a branch the file may not run, is not present to other files: Elixir
     * never defines it, as `Protocol.assert_protocol!` raises before it wherever the branch runs.
     */
    fun testAnImplementationOfAMissingProtocolInABranchTheFileMayNotRunIsNotPresent() {
        addLegElixir()
        val a = add(
            "a.ex",
            "case (receive do after 0 -> 1 end) do\n  2 -> defimpl NoSuch, for: Integer do\n  end\n  _ -> :ok\nend\n",
        )
        val implementation = render(a, expansion(holder, a)).lines().single { it.startsWith("Elixir.NoSuch.Integer ") }

        assertFalse(implementation, implementation.contains(" loads Present"))
    }

    /**
     * Options an unknown fragment gives whole leave the body unknown, so a call from another module's body declines,
     * where Elixir runs the body the options hold.
     */
    fun testUnknownOptionsLeaveTheBodyUnknownWhereCalled() {
        addLegElixir()
        add("a.ex", "defmodule A do\n  o = Application.get_env(:m2, :o, [do: 1])\n  def f, unquote(o)\nend\n")
        val b = add("b.ex", "defmodule B do\n  @x A.f()\n  def x, do: @x\nend\n")

        assertEquals(
            "top expanded\n" +
                "Elixir.B compiled errors[] loads Present(functions=[NameArity(name=x, arity=0)], macros=[], " +
                "hasInfo=true, behaviour=None, deprecated=Known(reasons={}), attributes=Unreadable)",
            render(b, expansion(holder, b)),
        )
    }

    /**
     * A kind that isn't known is a subject the evaluator can't produce for `EEx`'s `case`, so the module stops there,
     * where Elixir defines the one function of the kind it reads.
     */
    fun testCompiledEexOfAnUnknownKindStopsAtItsCase() {
        addCompiledEex()
        val call = "EEx.function_from_string(kind, :k, \"x\")"
        val a = add("a.ex", "defmodule A do\n  require EEx\n  kind = Application.get_env(:m2, :kind, :defp)\n  $call\nend\n")

        assertEquals("top expanded\nElixir.A stopped `$call` unported case errors[] loads Unreadable", render(a, expansion(holder, a)))
    }

    /** `__ENV__.file` is the file's own path, so its basename names a function. */
    fun testTheEnvFileIsTheFilesOwnPath() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        val a = add("env_file.ex", named("EnvFile", "Path.basename(__ENV__.file, \".ex\")"))

        assertEquals("compiled [env_file/0]", defines(a))
    }

    /** `__DIR__` is the directory of `__ENV__.file`, as `Path.dirname/1` gives it. */
    fun testTheDirIsTheDirectoryOfTheFile() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        val a = add(
            "lib/a.ex",
            "defmodule A do\n" +
                "  dir = String.to_atom(__DIR__)\n" +
                "  dirname = String.to_atom(Path.dirname(__ENV__.file))\n" +
                "  base = String.to_atom(Path.basename(__DIR__))\n" +
                "  def unquote(dir)(), do: 1\n" +
                "  def unquote(dirname)(), do: 2\n" +
                "  def unquote(base)(), do: 3\n" +
                "end\n",
        )

        assertEquals("compiled [/src/lib/0, lib/0]", defines(a))
    }

    /** `Path.expand/2` of a name from `__DIR__` is the name joined to the directory. */
    fun testAPathExpandedFromTheDir() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        val a = add("lib/a.ex", named("A", "Path.expand(\"t.eex\", __DIR__)"))

        assertEquals("compiled [/src/lib/t.eex/0]", defines(a))
    }

    /** `__CALLER__.file` is the caller's file, so a macro can name what it defines by it. */
    fun testTheCallersFileIsItsOwnPath() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        add(
            "m.ex",
            "defmodule M do\n" +
                "  defmacro m do\n" +
                "    name = String.to_atom(Path.basename(__CALLER__.file, \".ex\"))\n" +
                "    quote do\n      def unquote(name)(), do: 1\n    end\n" +
                "  end\n" +
                "end\n",
        )
        val b = add("b_caller.ex", "defmodule BCaller do\n  require M\n  M.m()\nend\n")

        assertEquals("compiled [b_caller/0]", defines(b))
    }

    /** A list holding the file is a known value, so a fragment can splice it. */
    fun testAListHoldingTheFileIsAKnownFragment() {
        addLegElixir()
        add("mix.exs", MIX_EXS)
        val a = add("a.ex", "defmodule A do\n  def f, do: unquote([__ENV__.file])\nend\n")

        assertEquals("compiled [f/0]", defines(a))
    }

    /** A template named from `__DIR__` is read from the file's own folder, so the function it defines is exported. */
    fun testAnEexFileNamedFromTheDirDefinesItsFunction() {
        addCompiledEex()
        add("mix.exs", MIX_EXS)
        add("t.eex", "<%= @a %>")
        val a = add(
            "a.ex",
            "defmodule A do\n  require EEx\n  EEx.function_from_file(:def, :t, Path.expand(\"t.eex\", __DIR__), [:assigns])\nend\n",
        )

        assertEquals("compiled [t/1]", defines(a))
    }

    /**
     * With no `mix.exs` the compile directory is unknown. From 1.16.0-rc.1 `Path.expand/2` reads the working directory
     * only for a relative directory, so a unix host defines the function; before it, and on a Windows host, where a path
     * with no drive letter is relative to the volume, it reads it and the module stops at the call, the function it
     * defines still known.
     */
    fun testAnEexFileNamedFromTheDirWithNoMixProject() {
        addCompiledEex()
        add("t.eex", "<%= @a %>")
        val a = add(
            "a.ex",
            "defmodule A do\n  require EEx\n  EEx.function_from_file(:def, :t, Path.expand(\"t.eex\", __DIR__), [:assigns])\nend\n",
        )

        assertEquals(if (legExpandsPathsLazily() && OS.CURRENT != OS.Windows) "compiled [t/1]" else "stopped [t/1]", defines(a))
    }

    /** A scratch file is a local file, so its path is known. */
    fun testAScratchFilesPath() {
        addLegElixir()
        val scratch = ScratchRootType.getInstance().createScratchFile(
            project,
            "scratch_path.ex",
            ElixirLanguage,
            named("ScratchPath", "Path.basename(__ENV__.file, \".ex\")"),
            ScratchFileService.Option.create_if_missing,
        )!!

        try {
            assertEquals("compiled [scratch_path/0]", defines(psi(scratch)))
        } finally {
            WriteAction.runAndWait<Throwable> { scratch.delete(this) }
        }
    }

    /**
     * A light virtual file is not on disk, so it has no path: the function it would name stops at its head, and a value
     * wholly unknown leaves a body unknown where a list holding it stops.
     */
    fun testALightVirtualFileHasNoPath() {
        addLegElixir()
        val named = LightVirtualFile("light_file.ex", ElixirFileType.INSTANCE, named("LightFile", "Path.basename(__ENV__.file, \".ex\")"))
        val body = LightVirtualFile("body_file.ex", ElixirFileType.INSTANCE, "defmodule BodyFile do\n  def f, do: unquote(__ENV__.file)\nend\n")
        val list = LightVirtualFile("list_file.ex", ElixirFileType.INSTANCE, "defmodule ListFile do\n  def f, do: unquote([__ENV__.file])\nend\n")

        assertEquals("stopped []", defines(psi(named)))
        assertEquals("compiled [f/0]", defines(psi(body)))
        assertEquals("stopped [f/0]", defines(psi(list)))
    }

    /** An Elixir code block injected into another file has no path of its own. */
    fun testAnInjectedFragmentHasNoPath() {
        addLegElixir()
        val code = named("I", "Path.basename(__ENV__.file, \".ex\")").lines().filter(String::isNotEmpty)
        val host = add(
            "host.ex",
            "defmodule Host do\n  @moduledoc \"\"\"\n  Text.\n\n" + code.joinToString("\n") { "      $it" } + "\n  \"\"\"\nend\n",
        )
        val injected = InjectedLanguageManager.getInstance(project)
            .findInjectedElementAt(host, host.text.indexOf("defmodule I") + 1)!!.containingFile

        assertEquals("stopped []", defines(injected))
    }

    /** A completion copy has no virtual file, so no path. */
    fun testACompletionCopyHasNoPath() {
        addLegElixir()
        val a = add("copy_file.ex", named("CopyFile", "Path.basename(__ENV__.file, \".ex\")"))

        assertEquals("stopped []", defines(completionCopy(a)))
    }

    /** A file made as the decompiled mirror is has no virtual file, so no path. */
    fun testADecompiledMirrorHasNoPath() {
        addLegElixir()
        val mirror = PsiFileFactory.getInstance(project).createFileFromText(
            "mirror_file.ex",
            ElixirLanguage,
            named("MirrorFile", "Path.basename(__ENV__.file, \".ex\")"),
            false,
            false,
            true,
        )

        assertNull(mirror.virtualFile)
        assertEquals("stopped []", defines(mirror))
    }

    /** A file on a local file system, which names its own path, as one on the host's disk does. */
    private fun local(path: String): VirtualFile =
        object : LightVirtualFile(path.substringAfterLast('/'), ElixirFileType.INSTANCE, "") {
            override fun getPath() = path

            override fun isInLocalFileSystem() = true
        }

    /** The path of a file the run reads is the one it is on, where its SDK runs as the host does. */
    fun testAFileOnTheHostsDiskNamesItsPath() {
        assertEquals("/src/a.ex", ProjectModules.runPath(local("/src/a.ex"), OS.Linux, OS.Linux))
        assertEquals("/src/a.ex", ProjectModules.runPath(local("/src/a.ex"), OS.macOS, OS.macOS))
        assertNull(ProjectModules.runPath(null, OS.Linux, OS.Linux))
        assertNull(ProjectModules.runPath(LightVirtualFile("a.ex", ElixirFileType.INSTANCE, ""), OS.Linux, OS.Linux))
    }

    /** `path:drive-letter`: Elixir lowercases the drive letter of each file it compiles on Windows. */
    fun testAnUppercaseDriveLetterIsLowercased() {
        assertEquals("c:/Src/A.ex", ProjectModules.runPath(local("C:/Src/A.ex"), OS.Windows, OS.Windows))
        assertEquals("c:/Src/A.ex", ProjectModules.runPath(local("c:/Src/A.ex"), OS.Windows, OS.Windows))
        assertEquals("//server/share/A.ex", ProjectModules.runPath(local("//server/share/A.ex"), OS.Windows, OS.Windows))
    }

    /** `path:wsl`: an SDK running as Linux under a Windows IDE compiles at a path the IDE's own isn't. */
    fun testAnSdkOfAnotherOsLeavesThePathUnknown() {
        assertNull(ProjectModules.runPath(local("C:/src/a.ex"), OS.Linux, OS.Windows))
        assertNull(ProjectModules.runPath(local("/src/a.ex"), OS.Windows, OS.Linux))
    }

    /** `module`, defining the one function [name] gives. */
    private fun named(module: String, name: String) =
        "defmodule $module do\n  name = String.to_atom($name)\n  def unquote(name)(), do: 1\nend\n"

    private fun defines(file: PsiFile): String = defines(holder, file)

    /** The leg's compiled `Elixir` and `EEx`, `EEx` with its sources. */
    private fun addCompiledEex() {
        val lib = File(LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH"), "lib")
        addLibrary("leg_elixir", File(lib, "elixir/ebin"))
        addLibrary("leg_eex", File(lib, "eex/ebin"), File(lib, "eex/lib"))
    }

    fun testCycleAnswersAbsent() {
        val a = add("a.ex", "")
        val b = add("b.ex", "")
        val c = add("c.ex", "defmodule C do\n  require D\nend\n\ndefmodule D do\n  def f, do: :ok\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        for (version in listOf("1.20.4", "1.12.3")) {
            val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of(version), legKernel, OS.CURRENT)

            edit(project, a, "defmodule A do\n  require B\n  B.m()\n  defmacro n, do: :ok\nend\n")
            edit(project, b, "defmodule B do\n  require A\n  A.n()\n  defmacro m, do: :ok\nend\n")
            inBothOrders("$version, each requires the other", a, b, x, inputs) { ofA, ofB ->
                assertTrue(render(a, ofA).contains("Elixir.A raised unloaded_module `require B`"))
                assertTrue(render(b, ofB).contains("Elixir.B raised unloaded_module `require A`"))
            }
            val holder = ProjectModules(project)
            assertEquals(ModuleExports.Absent, exports(holder, a, "Elixir.B", inputs))
            assertFresh("$version, A after exports(B) from A", a, expansion(holder, a, inputs), inputs)
            assertFresh("$version, B after exports(B) from A", b, expansion(holder, b, inputs), inputs)

            edit(project, a, "defmodule A do\n  def g, do: :ok\n  def f, do: B.f()\nend\n")
            edit(project, b, "defmodule B do\n  def f, do: A.g()\nend\n")
            inBothOrders("$version, each calls the other unrequired", a, b, x, inputs) { ofA, ofB ->
                assertTrue(render(a, ofA).contains("Elixir.A compiled"))
                assertTrue(render(b, ofB).contains("Elixir.B compiled"))
            }

            val later = expansion(ProjectModules(project), c, inputs)
            assertFresh("$version, a call to a module defined later in the file", c, later, inputs)
            assertTrue(render(c, later).contains("Elixir.C raised unloaded_module `require D`"))
        }

        val level = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        edit(project, a, "defmodule A do\n  def f, do: B.f()\n  defmacro g, do: :ok\nend\n")
        edit(project, b, "defmodule B do\n  def f, do: A.g()\nend\n")
        inBothOrders("1.12.3, B calls A's macro unrequired", a, b, x, level) { _, ofB ->
            assertTrue(render(b, ofB).contains("Elixir.B stopped `A.g()`"))
        }
    }

    fun testOtherInputsRecompute() {
        val a = add("a.ex", "defmodule A do\n  def f, do: B.f()\n  defmacro g, do: :ok\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: A.g()\nend\n")
        val newer = ProjectModules.Inputs(ElixirLanguageLevel.of("1.20.4"), legKernel, OS.CURRENT)
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val holder = ProjectModules(project)
        expansion(holder, a, newer)
        expansion(holder, b, newer)

        val recomputed = expansion(holder, b, older)

        assertFresh("B at 1.12.3 after A and B at 1.20.4", b, recomputed, older)
        assertTrue(render(b, recomputed), render(b, recomputed).contains("Elixir.B stopped `A.g()`"))
    }

    fun testWarmAskLoadsNoTree() {
        val a = add("a.ex", "defmodule A do\nend\n").virtualFile
        val b = add("b.ex", "defmodule B do\n  def f, do: 1\nend\n").virtualFile
        val cold = exports(holder, psi(a), "Elixir.B")
        repeat(3) { System.gc() }
        assertFalse("b.ex's tree is let go", (psi(b) as PsiFileEx).isContentsLoaded)
        val loaded = mutableListOf<String>()
        val recording = Disposer.newDisposable()
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(
            {
                loaded += it.name
                false
            },
            recording,
        )

        try {
            assertEquals(cold, exports(holder, psi(a), "Elixir.B"))
        } finally {
            Disposer.dispose(recording)
        }

        assertEquals(emptyList<String>(), loaded)
    }

    fun testDumbAnswersUnreadableUncached() {
        val a = add("a.ex", A_CALLS_B)
        add("b.ex", "defmodule B do\n  defmacro m, do: :ok\nend\n")
        var dumb: FileExpansion? = null

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.B"))
            dumb = expansion(holder, a)
            assertTrue(render(a, dumb).contains("Elixir.A stopped `require B`"))
            assertNotSame("asked again while dumb", dumb, expansion(holder, a))
        }

        val smart = expansion(holder, a)
        assertNotSame("asked once smart", dumb, smart)
        assertFresh("asked once smart", a, smart)
    }

    /**
     * Below 1.13 every remote call reads its receiver's exports, so eight files that each call the others form one
     * group of re-entries. A value that read a file in progress is reused within the outermost call wherever it would
     * read the same, so the group costs a bounded multiple of one file whose calls go to absent modules, not one
     * expansion per path through the group.
     */
    fun testAGroupOfFilesThatCallEachOtherIsExpandedOncePerState() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val n = 8
        val group = (1..n).map { i ->
            val calls = (1..n).filter { it != i }.joinToString(", ") { "M$it.f()" }
            add("m$i.ex", "defmodule M$i do\n  def f, do: [$calls]\nend\n")
        }
        val calls = (2..n).joinToString(", ") { "Absent$it.f()" }
        val alone = add("alone.ex", "defmodule Alone do\n  def f, do: [$calls]\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")
        // The first run also loads what later runs find cached, so it makes more checks.
        expandUnder(ProjectModules(project), alone, 0, older)
        val unit = expandUnder(ProjectModules(project), alone, 0, older)
        val holder = ProjectModules(project)

        val checks = expandUnder(holder, group.first(), 0, older)

        // Measured at 1.20.4's leg: 534 times with the reuse, 15,597 times without.
        assertTrue("$checks checks against $unit for one file", checks < 1000 * unit)
        assertFresh("M1", group.first(), expansion(holder, group.first(), older), older)

        for (i in 1..n) {
            assertEquals(
                "M$i from x.ex",
                exports(ProjectModules(project), x, "Elixir.M$i", older),
                exports(holder, x, "Elixir.M$i", older),
            )
        }
    }

    /**
     * C requires A1 while A0 is still compiling, so C raises. Once A1 has finished, A2's `require D` reaches C again,
     * which now compiles, so D stops at C's remote macro, as it does from a fresh run of D.
     */
    fun testAFileExpandedWhileAModuleWasUnfinishedIsExpandedAgainOnceItFinishes() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\nend\n\ndefmodule A2 do\n  require D\nend\n",
        )
        add("c.ex", "defmodule C do\n  require A1\n  defmacro f, do: :ok\nend\n")
        add("d.ex", "defmodule D do\n  def h, do: C.f()\nend\n")

        val rendered = render(a, expansion(ProjectModules(project), a, older))

        assertTrue(rendered, rendered.contains("Elixir.A2 stopped `require D`"))
    }

    /**
     * A0 reaches C, which requires X from f.ex once f.ex has finished, and defines the macro `x`. A2's `require G` then
     * compiles f.ex, whose G calls `C.x()` while X is unfinished, so C raises at `require X` this time: below 1.13 G's
     * call reads C's exports, finds none, and is a function call, so G and A2 compile.
     */
    fun testAFileThatReadAnotherAsLoadedIsExpandedAgainWhileThatOneIsCompiling() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val a = add("a.ex", "defmodule A0 do\n  def g, do: C.y()\nend\n\ndefmodule A2 do\n  require G\nend\n")
        add("c.ex", "defmodule C do\n  require X\n  def y, do: A2.h()\n  defmacro x, do: :ok\nend\n")
        add("f.ex", "defmodule G do\n  def g, do: C.x()\nend\n\ndefmodule X do\nend\n")

        val rendered = render(a, expansion(ProjectModules(project), a, older))

        assertTrue(rendered, rendered.contains("Elixir.A2 compiled"))
    }

    /**
     * A0's call reads C while A1 is unfinished, so C raises at `require A1`. Once A1 has finished, A2's `require C`
     * reads C again, which now compiles and defines `f`, as Elixir compiles all three.
     */
    fun testLaterModuleRereads() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\nend\n\ndefmodule A2 do\n  require C\nend\n",
        )
        val c = add("c.ex", "defmodule C do\n  require A1\n  defmacro f, do: :ok\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")
        val holder = ProjectModules(project)

        val rendered = render(a, expansion(holder, a, older))

        assertFalse(rendered, rendered.contains("unloaded_module"))
        assertTrue(rendered, rendered.contains("Elixir.A2 compiled"))
        assertFresh("C after a.ex", c, expansion(holder, c, older), older)

        for (atom in listOf("Elixir.A0", "Elixir.A1", "Elixir.A2", "Elixir.C")) {
            assertEquals(
                "$atom from x.ex",
                exports(ProjectModules(project), x, atom, older),
                exports(holder, x, atom, older),
            )
        }
    }

    /**
     * A0's call reads C while A1 is unfinished: C passes `A1.m()` in an `fn` as a function call and stops at X's
     * macro. A2 reads C again once A1 has finished, and C stops at `A1.m()` without reading X. The file's expansion
     * used both answers: editing X's macro into a function lets C compile for A0, and A0 with it, while A2's read still
     * stops at `require C`, as its C saw A1's macro.
     */
    fun testASupersededAnswerKeepsWhatItRead() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel, OS.CURRENT)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\n\n" +
                "defmodule A2 do\n  require C\nend\n",
        )
        add("c.ex", "defmodule C do\n  _ = fn -> A1.m() end\n  def f, do: 1\n  require X\n  X.k()\nend\n")
        val x = add("x.ex", "defmodule X do\n  defmacro k, do: receive(do: (x -> x), after: (0 -> :ok))\nend\n")
        val holder = ProjectModules(project)
        val before = expansion(holder, a, older)
        assertFresh("X's macro", a, before, older)
        assertTrue(render(a, before), render(a, before).contains("Elixir.A0 stopped `C.f()`"))

        edit(project, x, "defmodule X do\n  def k, do: :ok\nend\n")
        val after = expansion(holder, a, older)
        val rendered = render(a, after)

        assertFresh("X's macro edited into a function", a, after, older)
        assertTrue(rendered, rendered.contains("Elixir.A0 compiled"))
        assertTrue(rendered, rendered.contains("Elixir.A2 stopped `require C`"))
    }

    fun testCancelledCachesNothing() {
        val a = add("a.ex", A_CALLS_B)
        add(
            "b.ex",
            "defmodule B do\n  def f(x), do: x + 1\n  def g(x), do: f(x) * 2\n  def h(x, y), do: {g(x), f(y), [x, y]}\n" +
                "  defmacro m, do: :ok\nend\n",
        )
        val x = add("x.ex", "defmodule X do\nend\n")
        // The first run also loads what later runs find cached, so it makes more checks.
        expandUnder(ProjectModules(project), a, cancelAt = 0)
        // A run's count varies (one that finds what an earlier run loaded gone loads it again), so a later run can end
        // before a cancel point even below the least of several runs' counts, and that run has nothing to check.
        val checks = (1..MEASURED_RUNS).minOf { expandUnder(ProjectModules(project), a, cancelAt = 0) }
        var cancels = 0

        for (n in 1 until checks step maxOf(1, checks / 12)) {
            val holder = ProjectModules(project)

            try {
                expandUnder(holder, a, n)
                continue
            } catch (_: ProcessCanceledException) {
                cancels++
            }

            assertFresh("A asked after a cancel at check $n of $checks", a, expansion(holder, a))

            for (atom in listOf("Elixir.A", "Elixir.B")) {
                assertEquals(
                    "$atom from x.ex after a cancel at check $n of $checks",
                    exports(ProjectModules(project), x, atom),
                    exports(holder, x, atom),
                )
            }
        }

        assertTrue("no run was cancelled", cancels > 0)
    }

    /**
     * Elixir compiles `a.ex` before `b.ex` here, so `A` is loaded when `b.ex` expands. Compiled together, a call in a
     * module body to a module not yet required races its load, as `code:ensure_loaded` doesn't wait for it.
     */
    fun testDeprecatedInAnotherFile() {
        val a = add("a.ex", DEPRECATED)
        val bodies = listOf(
            "defmodule B do\n  A.old()\nend\n",
            "defmodule B do\n  def f, do: A.old()\nend\n",
            "defmodule B do\n  require A\n  A.old_m()\nend\n",
            "defmodule B do\n  require A\n  def f, do: A.old_m()\nend\n",
            "defmodule B do\n  import A\n  old()\n  old_m()\nend\n",
        )
        val b = add("b.ex", bodies.first())
        val expected = bodies.map { DeprecationWarnings.compiled(listOf("a.ex" to DEPRECATED, "b.ex" to it)) }
        val actual = bodies.map {
            edit(project, b, it)
            DeprecationWarnings.of(expansion(holder, b))
        }

        assertTrue("no case warns", expected.any { it.isNotEmpty() })
        assertEquals(show(bodies, expected), show(bodies, actual))

        edit(project, b, bodies.first())
        DeprecationWarnings.of(expansion(holder, b))
        edit(project, a, DEPRECATED.replace("Use A.new/0", "Use A.newer/0"))
        assertEquals(
            listOf("A.old/0 is deprecated. Use A.newer/0 instead line 2"),
            DeprecationWarnings.of(expansion(holder, b)),
        )
    }

    /**
     * A definition in the clause a `case` selects takes the attributes where the `case` runs, so each deprecation is
     * known and both calls warn, as Elixir's do.
     */
    fun testADeprecationTakenInASelectedClauseWarns() {
        val a = "defmodule A do\n  @deprecated \"Use A.new/0 instead\"\n  case 1 do\n    _ -> def old, do: 1\n  end\n" +
            "  @deprecated \"Use A.new_m/0 instead\"\n  defmacro old_m, do: 1\n  def plain, do: 1\nend\n"
        val b = "defmodule B do\n  require A\n  A.old()\n  A.old_m()\nend\n"

        add("a.ex", a)
        val expected = DeprecationWarnings.compiled(listOf("a.ex" to a, "b.ex" to b))

        assertEquals(2, expected.size)
        assertEquals(expected, DeprecationWarnings.of(expansion(holder, add("b.ex", b))))
    }

    /**
     * A definition in a clause of a `case` whose subject the evaluator can't produce may take the attributes at any
     * point, so whether a later one is deprecated is unknown, and Elixir may not define the module as the expander did.
     * Elixir warns of both calls; the module isn't read, so none of its deprecations is.
     */
    fun testAnUnknownDeprecationWarnsNothing() {
        add(
            "a.ex",
            "defmodule A do\n  @deprecated \"Use A.new/0 instead\"\n  case (receive do after 0 -> 1 end) do\n    _ -> def old, do: 1\n  end\n" +
                "  @deprecated \"Use A.new_m/0 instead\"\n  defmacro old_m, do: 1\n  def plain, do: 1\nend\n",
        )
        val b = add("b.ex", "defmodule B do\n  require A\n  A.old()\n  A.old_m()\nend\n")

        assertEquals(emptyList<String>(), DeprecationWarnings.of(expansion(holder, b)))
        assertEquals(ModuleExports.Unreadable, exports(holder, b, "Elixir.A"))
    }

    /**
     * A `def` in a module-body `for`, in an `fn` a call runs, or in a `case`'s selected branch is on the statement path:
     * the module loads with what ran defined, a certain raise there leaves it absent, and a decline in the name or in
     * the subject leaves it unreadable. A `cond` selects nothing, so a `def` in one leaves the module unreadable. A row
     * labelled `expander` pins the expander's answer where Elixir loads the module with what it defines.
     */
    fun testASelectedBranchIsOnTheStatementPath() {
        addLegElixir()
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  def f, do: A.f()\nend\n")
        add("m.ex", "defmodule M do\n  defmacro m(e), do: quote(do: _ = unquote(e))\nend\n")
        val declined = "_ = (receive do after 0 -> 1 end)"
        val pair = "def f, do: 1\n      defp f, do: 2"
        // Each row is a module alone in a file; the label names its family and the answer, `expander` where Elixir's differs.
        val rows = listOf(
            Triple("if-loaded", "x = true\n  if x do\n    def f, do: 1\n  end", "Present [f/0] Compiled"),
            Triple("for-loaded", "for n <- [:f, :g], do: def unquote(n)(), do: 1", "Present [f/0, g/0] Compiled"),
            Triple("each-loaded", "Enum.each([:f, :g], fn n -> def unquote(n)(), do: 1 end)", "Present [f/0, g/0] Compiled"),
            Triple("foreach-loaded", ":lists.foreach(fn n -> def unquote(n)(), do: 1 end, [:f, :g])", "Present [f/0, g/0] Compiled"),
            // The declined call isn't given the module, so it is no possible write, and `f/0` is loaded.
            Triple("if-declined", "x = true\n  if x do\n    $declined\n    def f, do: 1\n  end", "Present [f/0] Compiled"),
            Triple("for-declined", "for _ <- [1] do\n    $declined\n    def f, do: 1\n  end", "Present [f/0] Compiled"),
            Triple("each-declined", "Enum.each([1], fn _ ->\n    $declined\n    def f, do: 1\n  end)", "Present [f/0] Compiled"),
            // A `def`/`defp` pair, or a raising write, in the selected unit raises from certain inputs: never loaded.
            Triple("if-raised", "x = true\n  if x do\n    $pair\n  end", "Absent Raised"),
            Triple("for-raised", "for _ <- [1] do\n    $pair\n  end", "Absent Raised"),
            Triple("each-raised", "Enum.each([1], fn _ ->\n    $pair\n  end)", "Absent Raised"),
            Triple("foreach-raised", ":lists.foreach(fn _ ->\n    $pair\n  end, [1])", "Absent Raised"),
            Triple("case-attribute-raised", "k = :a\n  case k do\n    :a -> @a hd([])\n    _ -> :ok\n  end", "Absent Raised"),
            Triple("if-attribute-raised", "x = true\n  if x do\n    @a hd([])\n  end\n  def f, do: 1", "Absent Raised"),
            // The store raises before it runs the `@on_definition` callbacks.
            Triple("on-definition-pair-raised", "def f, do: 1\n  @on_definition {H, :cb}\n  defp f, do: 2", "Absent Raised"),
            // The error's inputs are certain, so the module is absent, but its reach isn't, so the body stops.
            Triple("if-declined-raised", "x = true\n  if x do\n    $declined\n    $pair\n  end", "Absent Stopped"),
            Triple("each-declined-raised", "Enum.each([1], fn _ ->\n    $declined\n    $pair\n  end)", "Absent Stopped"),
            // A name the evaluator declines is stored unnamed, so what the module defines isn't read; Elixir loads `f/0`.
            Triple("if-unnamed-expander-unreadable", "x = true\n  if x do\n    def unquote((receive do after 0 -> :f end))(), do: 1\n  end", "Unreadable Stopped"),
            Triple("for-unnamed-expander-unreadable", "for n <- [:f] do\n    def unquote((receive do after 0 -> n end))(), do: 1\n  end", "Unreadable Stopped"),
            Triple("each-unnamed-expander-unreadable", "Enum.each([:f], fn n ->\n    def unquote((receive do after 0 -> n end))(), do: 1\n  end)", "Unreadable Stopped"),
            Triple("if-defdelegate-loaded", "x = true\n  if x do\n    defdelegate f(l), to: :erlang, as: :length\n  end", "Present [f/1] Compiled"),
            // Controls the walk doesn't follow: Elixir loads `f/0` from the `cond` and the `case` (#4508), none from the `fn`.
            Triple("cond-expander-unreadable", "cond do\n    true -> def f, do: 1\n  end", "Unreadable Compiled"),
            Triple("case-declined-expander-unreadable", "case (receive do after 0 -> :a end) do\n    :a -> def f, do: 1\n    _ -> :ok\n  end", "Unreadable Compiled"),
            Triple("fn-unrun-expander-unreadable", "_ = fn -> def f, do: 1 end", "Unreadable Compiled"),
            // A statement the evaluator declines may have run the units nested in its statements any number of times.
            Triple("case-declined-if-expander-unreadable", "case (receive do after 0 -> :a end) do\n    :a -> if true do def f, do: 1 end\n    _ -> :ok\n  end", "Unreadable Compiled"),
            Triple("case-declined-for-expander-unreadable", "case (receive do after 0 -> :a end) do\n    :a -> for n <- [:f], do: def unquote(n)(), do: 1\n    _ -> :ok\n  end", "Unreadable Compiled"),
            // A definition whose name the elements the evaluator couldn't follow decide stops the module.
            Triple("for-declined-case-expander-unreadable", "for n <- (receive do after 0 -> [:f] end) do\n    case n do\n      _ -> def unquote(n)(), do: 1\n    end\n  end", "Unreadable Stopped"),
            // A unit reached for one element may have run for the elements the evaluator couldn't follow.
            Triple("for-filter-declined-expander-unreadable", "for x <- [:a, :b], x == :a or (receive do after 0 -> true end), do: def unquote(x)(), do: 1", "Unreadable Stopped"),
            Triple("each-element-declined-expander-unreadable", "x = receive do after 0 -> {:ok, :g} end\n  Enum.each([{:ok, :f}, x], fn {:ok, n} -> def unquote(n)(), do: 1 end)", "Unreadable Stopped"),
            Triple("foreach-element-declined-expander-unreadable", "x = receive do after 0 -> {:ok, :g} end\n  :lists.foreach(fn {:ok, n} -> def unquote(n)(), do: 1 end, [{:ok, :f}, x])", "Unreadable Stopped"),
            // A body they decide is unknown, and the module runs on.
            Triple("for-filter-declined-body-expander-unreadable", "for x <- [1, 2], x == 1 or (receive do after 0 -> true end), do: def f, do: unquote(x)", "Unreadable Compiled"),
            // An `fn` a call doesn't run defines where it is called later, which the walk doesn't follow.
            Triple("fn-returned-expander-unreadable", "f = Function.identity(fn -> def g, do: 1 end)\n  f.()", "Unreadable Compiled"),
            Triple("fn-in-map-expander-unreadable", "callbacks = Map.put(%{}, :init, fn -> def init, do: :ok end)\n  callbacks.init.()", "Unreadable Compiled"),
            Triple("fn-returned-macro-expander-unreadable", "f = Function.identity(fn -> if true do def g, do: 1 end end)\n  f.()", "Unreadable Compiled"),
            // An `fn` a call ran and then kept, in its value or a binding, defines where it is called later, with a name the walk can't produce.
            Triple("fn-kept-expander-unreadable", "f = Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end)\n  f.(:b)", "Unreadable Stopped"),
            // An `fn` a match in a followed body binds is kept, whether or not that run called it.
            Triple("for-fn-entered-earlier-expander-unreadable", "for x <- [:a, :b] do\n    f = Enum.reduce(if(x == :a, do: [1], else: []), fn -> def unquote(x)(), do: 1 end, fn _, g -> g.(); g end)\n    f.()\n  end", "Unreadable Stopped"),
            // An `fn` a nested statement returns, kept by the statement around it, defines where it is called later.
            Triple("if-fn-kept-expander-unreadable", "f = if true, do: Enum.reduce([:a], fn y -> def unquote(y)(), do: 1 end, fn z, g -> g.(z); g end)\n  f.(:b)", "Unreadable Stopped"),
            Triple("for-fn-kept-expander-unreadable", "f = for _ <- [1], do: Enum.reduce([:a], fn y -> def unquote(y)(), do: 1 end, fn z, g -> g.(z); g end)\n  hd(f).(:b)", "Unreadable Stopped"),
            // A kept `fn` whose unit is in a macro's output in its body.
            Triple("fn-kept-macro-expander-unreadable", "f = Enum.reduce([:a], fn x -> if true do def unquote(x)(), do: 1 end end, fn x, g -> g.(x); g end)\n  f.(:b)", "Unreadable Stopped"),
            // A `case` subject the evaluator can't produce, or a unit's decline that ends its block, declines the statement,
            // so every unit is stepped unordered: `a/0` is stored where the run reached it, and the unit again unnamed.
            Triple("fn-kept-unknown-expander-unreadable", "f = Enum.reduce([:a], fn y -> def unquote(y)(), do: 1 end, fn y, g -> g.(y); case (receive do after 0 -> true end) do _ -> g end end)\n  f.(:b)", "Unreadable Stopped"),
            Triple("fn-kept-unit-declined-expander-unreadable", "f = Enum.reduce([:a], fn y -> def unquote(y)(), do: 1 end, fn y, g -> g.(y); h = (receive do after 0 -> g end); h end)\n  f.(:b)", "Unreadable Stopped"),
            // An `fn` a match binds to nothing is never called later.
            Triple("fn-discarded-loaded", "_ = Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end)", "Present [a/0] Compiled"),
            // An `fn` running when a nested statement starts, or entered by a nested unit's run, was entered.
            Triple("each-if-loaded", "Enum.each([:a, :b], fn n -> if n == :a do def a, do: 1 end end)", "Present [a/0] Compiled"),
            Triple("each-case-loaded", "Enum.each([:b], fn x -> case x do :a -> def g, do: 1; _ -> :ok end end)\n  def h, do: 1", "Present [h/0] Compiled"),
            Triple("reduce-clause-loaded", "Enum.reduce([1], fn :a -> :ok; :b -> def g, do: 1 end, fn _, acc -> acc.(:a); acc end)", "Present [] Compiled"),
            // An `fn` entered in an earlier run of the statement wasn't entered in this one, which kept it uncalled.
            Triple("reduce-fn-entered-earlier-expander-unreadable", "Enum.reduce([{:a, [1]}, {:b, []}, :call], nil, fn\n    :call, g -> g.(); g\n    {n, l}, _ -> Enum.reduce(l, fn -> def unquote(n)(), do: 1 end, fn _, g -> g.(); g end)\n  end)", "Unreadable Stopped"),
            Triple("reduce-for-fns-called-later-expander-unreadable", "Enum.reduce([1, 2], nil, fn\n    1, nil -> for x <- [:a, :b], do: Enum.reduce(if(x == :a, do: [1], else: []), fn -> def unquote(x)(), do: 1 end, fn _, g -> g.(); g end)\n    2, fs -> Enum.each(fs, fn f -> f.() end)\n  end)", "Unreadable Stopped"),
            // A match whose value the match around it takes keeps that value.
            Triple("nested-match-fn-kept-expander-unreadable", "y = (_ = Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end))\n  y.(:b)", "Unreadable Stopped"),
            Triple("nested-match-if-fn-kept-expander-unreadable", "y = (_ = if true, do: Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end))\n  y.(:b)", "Unreadable Stopped"),
            // An `fn` a statement's run keeps and calls in a later iteration of the same run defines where it is called.
            Triple("reduce-fn-called-later-loaded", "Enum.reduce([:a, :b], nil, fn\n    x, nil -> Enum.reduce([x], fn y -> def unquote(y)(), do: 1 end, fn y, g -> g.(y); g end)\n    x, g -> g.(x); g\n  end)", "Present [a/0, b/0] Compiled"),
            // A value the evaluator couldn't produce before the statement keeps no `fn` of it.
            Triple("unknown-kept-loaded", "x = receive do after 0 -> :x end\n  r = Enum.map([:a], fn n -> def unquote(n)(), do: 1; {n, x} end)", "Present [a/0] Compiled"),
            // Nor does one in a list, or one that is the `fn`'s value.
            Triple("unknown-element-kept-loaded", "x = receive do after 0 -> :x end\n  r = Enum.map([:a], fn n -> def unquote(n)(), do: 1; [n, x] end)", "Present [a/0] Compiled"),
            Triple("unknown-variable-kept-loaded", "x = receive do after 0 -> :x end\n  r = Enum.map([:a], fn n -> def unquote(n)(), do: 1; x end)", "Present [a/0] Compiled"),
            // A match whose value the match around it discards keeps nothing.
            Triple("discarded-nested-match-loaded", "_ = (_ = Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end))", "Present [a/0] Compiled"),
            // A closure of an earlier run of the statement, still running when a later run of it starts, is called later.
            Triple("running-closure-of-earlier-run-expander-unreadable", "Enum.each([fn r, n, l ->\n    Enum.reduce(l, fn\n      true -> r.(r, :b, [])\n      false -> def unquote(n)(), do: 1\n    end, fn _, g -> g.(false); g end)\n  end], fn r -> Module.put_attribute(__MODULE__, :k, r.(r, :a, [1]).(true)) end)\n  Module.get_attribute(__MODULE__, :k).(false)", "Unreadable Stopped"),
            // A macro's output a match discards keeps nothing either, in either tier: the output's value is the call's.
            Triple("rewritten-discarded-match-loaded", "require M\n  _ = M.m(Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end))", "Present [a/0] Compiled"),
            Triple("each-rewritten-discarded-match-loaded", "require M\n  Enum.each([1], fn _ -> _ = M.m(Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end)) end)", "Present [a/0] Compiled"),
            // A match a followed body discards keeps nothing either.
            Triple("each-discarded-nested-match-loaded", "Enum.each([1], fn _ -> _ = (_ = Enum.reduce([:a], fn x -> def unquote(x)(), do: 1 end, fn x, g -> g.(x); g end)) end)", "Present [a/0] Compiled"),
            // A second `defstruct` or `defexception`, in a later run of the body, raises.
            Triple("for-defexception-twice-raised", "for _ <- [1, 2], do: defexception [:message]", "Absent Raised"),
            Triple("for-defstruct-twice-raised", "for _ <- [1, 2], do: (s = defstruct [:a])", "Absent Raised"),
            // A raise in a followed unit is rescued by the code that ran it.
            Triple("try-rescue-each-loaded", "try do\n    Enum.each([1], fn _ -> @a hd([]) end)\n  rescue\n    _ -> :ok\n  end\n  def f, do: 1", "Present [f/0] Compiled"),
            // A decline in the statement's run leaves every later raise uncertain.
            Triple("case-declined-then-raise-stopped", "case (receive do after 0 -> raise \"boom\" end) do\n    :a -> @a 1\n    _ -> :ok\n  end\n  x = hd([])", "Absent Stopped"),
            Triple("for-filter-declined-raise-stopped", "for n <- [1, 2], hd(if n == 1, do: [true], else: []) do\n    _ = (receive do after 0 -> raise \"boom\" end)\n  end", "Absent Stopped"),
            Triple("for-filter-later-raise-stopped", "for x <- [:a, :b], x == :b or (receive do after 0 -> raise \"boom\" end), do: hd([])", "Absent Stopped"),
            // A `for` whose `do:` is a list literal runs that list.
            Triple("for-list-body-loaded", "for n <- [:a, :b], do: [def(unquote(n)(), do: 1)]", "Present [a/0, b/0] Compiled"),
            // A match nested in a followed match's right side binds afresh on each run of the body.
            Triple("for-rebinding-loaded", "for n <- [:a, :b] do\n    x = y = n\n    def unquote(x)(), do: 1\n  end", "Present [a/0, b/0] Compiled"),
            Triple("each-rebinding-loaded", "Enum.each([:a, :b], fn n ->\n    x = y = n\n    def unquote(x)(), do: 1\n  end)", "Present [a/0, b/0] Compiled"),
            Triple("for-block-rebinding-loaded", "for n <- [:a, :b] do\n    x = (y = n; y)\n    def unquote(x)(), do: 1\n  end", "Present [a/0, b/0] Compiled"),
            // A `defexception` in a followed body gives its struct to the output's `if`, which selects the branch.
            Triple("if-defexception-loaded", "x = true\n  if x do\n    defexception [:message]\n  end", "Present [__struct__/0, __struct__/1, exception/1, message/1] Compiled"),
            Triple("for-defexception-loaded", "for _ <- [1], do: defexception [:message]", "Present [__struct__/0, __struct__/1, exception/1, message/1] Compiled"),
        ).map { (label, body, expected) -> Triple(label, "defmodule A do\n  $body\nend\n", expected) }

        assertEquals(
            rows.joinToString("\n") { (label, _, expected) -> "$label: $expected" },
            rows.joinToString("\n") { (label, code, _) ->
                edit(project, a, code)

                "$label: ${describe(exports(ProjectModules(project), b, "Elixir.A"))} ${ending(expansion(holder, a))}"
            },
        )

        edit(project, a, "defmodule A do\n  x = true\n  if x do\n    defmodule Inner do\n      def f, do: 1\n    end\n  end\nend\n")
        assertEquals("if-nested-loaded", "Present [f/0]", describe(exports(ProjectModules(project), b, "Elixir.A.Inner")))

        edit(project, a, "defmodule A do\n  x = true\n  if x do\n    defstruct [:a]\n  end\nend\n")
        assertEquals("if-defstruct-loaded", "Present [__struct__/0, __struct__/1]", describe(exports(ProjectModules(project), b, "Elixir.A")))
    }

    /**
     * A module nested in another stops at `require W` where `W`'s exports can't be read, before the clause's `W.w()` is
     * expanded. Elixir runs `W.w()`, which writes the enclosing module, so from there on the enclosing module's tables
     * aren't known, and a later read of them declines rather than giving the value from before.
     */
    fun testAStopAtAnUnreadableModuleMarksTheEnclosingModules() {
        addLegElixir()
        add("sw.ex", "defmodule SW do\n  cond do\n    true -> def f, do: 1\n  end\n\n  defmacro w, do: Module.put_attribute(Enum.at(__CALLER__.context_modules, 1), :x, :b)\nend\n")
        add("sr.ex", "defmodule SR do\n  defmacro r(module, key), do: Module.get_attribute(module, key)\nend\n")
        val two = "defmodule Two do\n    require SR\n    def unquote(SR.r(:\"Elixir.SM\", :x))(), do: 1\n  end"
        val stopping = "defmodule N do\n    def f do\n      require SW\n      SW.w()\n    end\n  end"
        val unnamed = "defmodule N do\n    def unquote((receive do after 0 -> :g end))(), do: 1\n    Module.put_attribute(:\"Elixir.SM\", :x, :b)\n  end"
        val m = add("sm.ex", "defmodule SM do\n  Module.put_attribute(__MODULE__, :x, :a)\n\n  $two\nend\n")
        val b = add("sb.ex", "defmodule SB do\n  def f, do: SM.Two.b()\nend\n")

        assertEquals("Unreadable", describe(exports(ProjectModules(project), b, "Elixir.SW")))
        // The control: with nothing writing `SM`, `Two` reads `:a`, Elixir's answer.
        assertEquals("Present [a/0]", describe(exports(ProjectModules(project), b, "Elixir.SM.Two")))

        // `Two` a sibling of `N`: `N`'s unit of `SM`'s body isn't certain, so the read after it declined before too.
        for ((label, nested) in listOf("clause stop" to stopping, "body stop" to unnamed)) {
            edit(project, m, "defmodule SM do\n  Module.put_attribute(__MODULE__, :x, :a)\n\n  $nested\n\n  $two\nend\n")
            assertEquals(label, "Unreadable", describe(exports(ProjectModules(project), b, "Elixir.SM.Two")))
        }

        // `Two` nested in `N` after the stop, where `N`'s run goes on: only the mark declines the read.
        for ((label, nested) in listOf("clause stop" to stopping, "body stop" to unnamed)) {
            edit(project, m, "defmodule SM do\n  Module.put_attribute(__MODULE__, :x, :a)\n\n  ${nested.removeSuffix("\n  end")}\n\n    $two\n  end\nend\n")
            assertEquals("$label, nested read", "Unreadable", describe(exports(ProjectModules(project), b, "Elixir.SM.N.Two")))
        }
    }

    /**
     * A redefinition that fails in a later iteration of a module-body loop leaves the earlier iteration's module where
     * the failed compile doesn't purge it, and none where it does.
     */
    fun testAFailedRedefinitionInALaterIterationFallsThrough() {
        addLegElixir()
        add("outer.ex", "defmodule Outer do\n  for n <- [1, 2] do\n    defmodule Inner do\n      def f, do: unquote(n)\n      if n == 2 do\n        defp f, do: 0\n      end\n    end\n  end\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: Outer.Inner.f()\nend\n")

        assertEquals(
            if (FAILED_COMPILE_PURGES_MODULE.isSufficient(legLevel())) "Absent" else "Present [f/0]",
            describe(exports(ProjectModules(project), b, "Elixir.Outer.Inner")),
        )
    }

    /** A redefinition that fails in a selected branch leaves the library's copy, as Elixir does on every leg. */
    fun testAFailedRedefinitionInASelectedBranchLeavesTheLibraryCopy() {
        addLegElixir()
        add("version.ex", "defmodule Version do\n  x = true\n  if x do\n    def f, do: 1\n    defp f, do: 2\n  end\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: Version.parse!(\"1.2.3\")\nend\n")

        val functions = (exports(ProjectModules(project), b, "Elixir.Version") as ModuleExports.Present).functions.map { "${it.name}/${it.arity}" }

        assertTrue(functions.toString(), "parse!/1" in functions)
        assertFalse(functions.toString(), "f/0" in functions)
    }

    private fun describe(exports: ModuleExports): String =
        exports::class.simpleName + ((exports as? ModuleExports.Present)?.functions?.map { "${it.name}/${it.arity}" }?.sorted()?.let { " $it" } ?: "")

    private fun ending(expansion: FileExpansion): String = expansion.modules.single().ended::class.simpleName!!

    /** This module and every module nested in it, however deep. */
    private fun ExpansionResult.withNested(): List<ExpansionResult> = listOf(this) + nested.flatMap { it.withNested() }

    /**
     * A new holder per order asks [a] then [b], and [b] then [a], and each answer equals a fresh run's, as does each module's
     * exports asked from [asker] afterwards.
     */
    private fun inBothOrders(
        label: String,
        a: PsiFile,
        b: PsiFile,
        asker: PsiFile,
        inputs: ProjectModules.Inputs,
        check: (ofA: FileExpansion, ofB: FileExpansion) -> Unit,
    ) {
        for ((first, second) in listOf(a to b, b to a)) {
            val holder = ProjectModules(project)
            val firstExpansion = expansion(holder, first, inputs)
            val secondExpansion = expansion(holder, second, inputs)
            assertFresh("$label, ${first.name} first", first, firstExpansion, inputs)
            assertFresh("$label, ${first.name} first, then ${second.name}", second, secondExpansion, inputs)

            for (atom in listOf("Elixir.A", "Elixir.B")) {
                assertEquals(
                    "$label, ${first.name} first, then $atom from ${asker.name}",
                    exports(ProjectModules(project), asker, atom, inputs),
                    exports(holder, asker, atom, inputs),
                )
            }

            if (first == a) check(firstExpansion, secondExpansion) else check(secondExpansion, firstExpansion)
        }
    }

    /** Expands [file], cancelled at this thread's [cancelAt]th check if that is above 0, and counts the checks. */
    private fun expandUnder(holder: ProjectModules, file: PsiFile, cancelAt: Int, inputs: ProjectModules.Inputs = legInputs): Int {
        val indicator = CancelAt(if (cancelAt > 0) cancelAt else Int.MAX_VALUE)

        whileCheckingIndicators {
            ProgressManager.getInstance().runProcess(
                { ReadAction.computeBlocking<FileExpansion, Throwable> { holder.expansion(file as ElixirFile, inputs) } },
                indicator,
            )
        }

        return indicator.checks
    }

    /** The leg's Elixir `.beam`s as a library, which a `defstruct` dispatches to. */
    private fun addLegElixir() = addLibrary("leg_elixir", LegManifest.ebin())

    /** A library [name] holding a copy of [beam], whose `ebin` the test may rewrite. */
    private fun copiedLibrary(beam: File, name: String = "project_modules_ebin"): VirtualFile {
        val directory = FileUtil.createTempDirectory(name, null, true)
        beam.copyTo(File(directory, beam.name))

        return addLibrary(name, directory)
    }

    /** The loaded-copy fixture's [beam] at [path] in the project's own build output, as a build writes it. */
    private fun built(path: String, beam: String): VirtualFile {
        val file = add(path, "").virtualFile
        val bytes = File("testData/org/elixir_lang/expander/loaded_copy/$beam").readBytes()

        WriteAction.runAndWait<Throwable> { file.setBinaryContent(bytes) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return file
    }

    /** A library of the `.beam`s in [classes], with [sources] attached when given; its classes root is returned. */
    private fun addLibrary(name: String, classes: File, sources: File? = null): VirtualFile {
        val fileSystem = LocalFileSystem.getInstance()
        val roots = listOfNotNull(classes, sources).map {
            VfsRootAccess.allowRootAccess(testRootDisposable, it.path)
            fileSystem.refreshAndFindFileByIoFile(it)!!
        }
        BeamLibraryFixture.addLibrary(project, module, name, roots.take(1), roots.drop(1))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return roots.first()
    }

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

    /** A module [name] with a macro whose `quote` keeps its location. */
    private fun kept(name: String) = "defmodule $name do\n  defmacro m do\n    quote location: :keep, do: f()\n  end\nend\n"

    /**
     * How `B`, importing `A.I`, ends for each of [arguments], from which [body] builds what a macro-built `def f` of `A`
     * returns (by default, the third element of a tuple): at each of [positions], with `A.I` nested after the macro's
     * call (`false`) or before it (`true`), and [after] following both.
     */
    private fun nestedAround(
        arguments: List<String>,
        positions: List<Boolean> = listOf(false, true),
        body: (String) -> String = { "{1, 2, $it}" },
        after: String = "",
    ): List<List<String>> {
        val a = add("a.ex", "")
        val b = add("b.ex", "defmodule B do\n  import A.I\n  def h, do: g()\nend\n")
        val nested = "  defmodule I do\n    def g, do: 1\n  end\n"
        val call = "  require Mac\n  Mac.m()\n"

        return positions.map { before ->
            arguments.map { arguments ->
                edit(
                    project,
                    a,
                    "defmodule Mac do\n  defmacro m do\n    {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], " +
                        "[{:f, [], []}, [do: ${body(arguments)}]]}\n  end\nend\n" +
                        "defmodule A do\n${if (before) nested + call else call + nested}${after}end\n",
                )
                render(b, expansion(holder, b)).lines().first { it.startsWith("Elixir.B ") }.substringBefore(" errors")
            }
        }
    }

    /** A module calling [name]'s `m`. */
    private fun caller(name: String) = "defmodule ${name}Caller do\n  require $name\n  def f, do: $name.m()\nend\n"

    /** `CB`, whose `@before_compile` callback quotes [output], and `O`, which calls it back and then runs [body]. */
    private fun callback(output: String, body: String = "", name: String = "CB", calls: List<String> = listOf(name)): String =
        "defmodule $name do\n  defmacro __before_compile__(_) do\n    quote do\n$output    end\n  end\nend\n" +
            "defmodule O do\n${calls.joinToString("") { "  @before_compile $it\n" }}${body}end\n"

    /** `Foo`, with [body], as a callback's output defines it. */
    private fun foo(body: String): String = "      defmodule Elixir.Foo do\n        $body\n      end\n"

    /** The files the `keep:` entries name on the nodes [file]'s expansion enters, or `none`. */
    private fun keptFiles(file: PsiFile, inputs: ProjectModules.Inputs): String {
        val files = mutableListOf<String>()
        val observer = ExpansionObserver { node, _, _ ->
            for (key in node.meta.keys) {
                val keep = (key as? Meta.Key.Entry)?.takeIf { it.name == "keep" }?.value as? Meta.Value.Tuple ?: continue

                files += (keep.elements.first() as Meta.Value.Binary).text
            }
        }
        ReadAction.nonBlocking(Callable { holder.observe(file as ElixirFile, inputs, observer) }).executeSynchronously()

        return files.distinct().joinToString().ifEmpty { "none" }
    }

    /** [file]'s content replaced on disk, as a checkout or another editor writes it, with no document. */
    private fun rewrite(file: VirtualFile, text: String) {
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(text.toByteArray()) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun psi(virtualFile: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(virtualFile)!!

    /** [file]'s copy as completion makes it, which has no virtual file. */
    private fun completionCopy(file: PsiFile): PsiFile =
        ReadAction.nonBlocking(Callable { file.copy() as PsiFile }).executeSynchronously().also {
            assertNull("a copy has no virtual file", it.virtualFile)
        }

    /** [copy] re-synced to [original]'s text with [replacement] in `start until end`, as completion re-syncs a reused copy. */
    private fun replaceInCopy(original: PsiFile, copy: PsiFile, start: Int, end: Int, replacement: String) {
        WriteAction.runAndWait<Throwable> {
            OffsetsInFile(original).replaceInCopy(copy, start, end, replacement).ensureUpdatedAndGetNewOffsets()
        }
    }

    private fun assertFresh(
        message: String,
        file: PsiFile,
        expansion: FileExpansion,
        inputs: ProjectModules.Inputs = legInputs,
    ) = assertEquals(message, render(file, freshExpansion(project, file, inputs)), render(file, expansion))

    /** The attributes of a `.beam` that persists only the `vsn` the compiler gives it. */
    private fun vsn(vsn: String) =
        ModuleAttributes.Known(OtpErlangList(arrayOf(OtpErlangTuple(arrayOf(OtpErlangAtom("vsn"), OtpErlangList(OtpErlangLong(BigInteger(vsn))))))))

    private fun show(bodies: List<String>, warnings: List<List<String>>) =
        bodies.indices.joinToString("\n\n") { "${bodies[it]}  => ${warnings[it].ifEmpty { listOf("none") }}" }

    private companion object {
        const val A_CALLS_B = "defmodule A do\n  require B\n  B.m()\nend\n"
        const val MIX_EXS = "defmodule MixProject do\nend\n"

        /** `M`, whose macro `m` the evaluator can't run, as it `receive`s, and Elixir runs to a raise. */
        const val MACRO_M_RAISES = "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> raise ArgumentError))\nend\n"

        /** `M`, whose macro `m` the evaluator can't run, as it `receive`s, and Elixir runs to `:ok`. */
        const val MACRO_M_OK = "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> :ok))\nend\n"

        /** `M.m()` as a `@before_compile` callback's output calls it. */
        const val CALL_M = "      require M\n      M.m()\n"

        /** `B`, which reads the `Foo` a callback's output defines. */
        const val IMPORTS_FOO = "defmodule B do\n  import Foo\n  def g, do: f()\nend\n"

        const val DEP_BEAM = "Elixir.LoadedCopy.Dep.beam"

        const val PROTOCOL_BEAM = "Elixir.LoadedCopy.Protocol.beam"

        /** How many runs `testCancelledCachesNothing` counts the checks of. */
        const val MEASURED_RUNS = 3

        /**
         * The checks `testARaiseAfterADeclineEndsTheLoopItIsIn` allows an expansion that ends: about four times the
         * dearest of its rows, which each cost about 460,000 checks on 1.20.4, as `1..3` does.
         */
        const val RAISE_AFTER_DECLINE_CHECKS = 2_000_000

        /**
         * The checks each `testASharedValue` test allows: about twice the 0.72 to 1.15 million each costs on 1.20.4, nearly
         * all of them made as the value is counted or copied, which stops past 2^20 nodes where copying every part takes
         * 2^41.
         */
        const val SHARED_VALUE_CHECKS = 2_000_000

        const val DEPRECATED =
            "defmodule A do\n  @deprecated \"Use A.new/0 instead\"\n  def old, do: 1\n" +
                "  @deprecated \"Use A.new_m/0 instead\"\n  defmacro old_m, do: 1\nend\n"
    }
}
