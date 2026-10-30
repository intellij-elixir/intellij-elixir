package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IdempotenceChecker
import com.intellij.util.system.OS
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.assertSizeIndependent
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.Use
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.ExpanderSource.Probe
import org.elixir_lang.expander.ExpanderSource.Probe.Counter
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport
import java.io.File

/**
 * The work [ExpanderSource.candidates] does for one use, at 10, 100 and 1,000 unrelated definitions. [USES] distinct
 * uses follow them: the first may pay a linear build, and every later one must cost the same at every size.
 */
class ExpanderSourceSizeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // At the platform's random rate a cached value is recomputed on a read, which a count of work would include.
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            Probe.reset()
        } finally {
            super.tearDown()
        }
    }

    /** Uses after the module's other definitions. */
    fun testModuleBody() = assertSizeIndependent(SIZES.associateWith(::moduleBody), Counter.entries)

    /** Uses the expansion never reached, after the module's other definitions and a call that raises. */
    fun testAfterAnError() = assertSizeIndependent(SIZES.associateWith(::afterAnError), Counter.entries)

    /** Uses of a definition an `import` of a growing module brings in. */
    fun testImport() = assertSizeIndependent(SIZES.associateWith(::import), Counter.entries)

    /** Uses of a definition only the implicit `import Kernel` brings in, with `Kernel` growing. */
    fun testKernel() = assertSizeIndependent(SIZES.associateWith(::kernel), Counter.entries)

    /** Uses in `test` blocks, after many others, in a module that `use`s a case. */
    fun testExUnit() = withElixir { assertSizeIndependent(SIZES.associateWith(::exUnit), Counter.entries) }

    /** Uses after many `scope ... do` blocks, as in a router. */
    fun testRouter() = withElixir { assertSizeIndependent(SIZES.associateWith(::router), Counter.entries) }

    /** [block] with the leg's Elixir as a library, whose `Kernel` a `use` and an evaluated macro read. */
    private fun withElixir(block: () -> Unit) =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "elixir", LegManifest.ebin()) { block() }

    /** Qualified uses of an export of a growing `.beam` (`psi/scope/callable_table/beam_mirror/generate.exs`). */
    fun testBeam() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "beam_mirror", BEAM_MIRROR) {
            assertSizeIndependent(SIZES.associateWith(::beam), Counter.entries)
        }

    private fun moduleBody(size: Int): List<Map<Counter, Long>> {
        val file = myFixture.addFileToProject(
            "module_body_$size.ex",
            buildString {
                appendLine("defmodule ModuleBody$size do")
                for (i in 0 until size) appendLine("  def unrelated_$i(a), do: a")
                appendLine("  def target(a), do: a")
                for (i in 0 until USES) appendLine("  def use_$i, do: target($i)")
                appendLine("end")
            }
        )

        return measure(file, USE, inputs())
    }

    private fun afterAnError(size: Int): List<Map<Counter, Long>> {
        val file = myFixture.addFileToProject(
            "after_an_error_$size.ex",
            buildString {
                appendLine("defmodule Target$size do")
                appendLine("  def target(a), do: a")
                appendLine("end")
                appendLine()
                appendLine("defmodule AfterAnError$size do")
                for (i in 0 until size) appendLine("  def unrelated_$i(a), do: a")
                appendLine("  1.f()")
                for (i in 0 until USES) appendLine("  def use_$i, do: Target$size.target($i)")
                appendLine("end")
            }
        )

        return measure(file, USE, inputs())
    }

    private fun import(size: Int): List<Map<Counter, Long>> {
        myFixture.addFileToProject(
            "big_$size.ex",
            buildString {
                appendLine("defmodule Big$size do")
                for (i in 0 until size) appendLine("  def unrelated_$i(a), do: a")
                appendLine("  def target(a), do: a")
                appendLine("end")
            }
        )
        val file = myFixture.addFileToProject(
            "importer_$size.ex",
            buildString {
                appendLine("defmodule Importer$size do")
                appendLine("  import Big$size")
                for (i in 0 until USES) appendLine("  def use_$i, do: target($i)")
                appendLine("end")
            }
        )

        return measure(file, USE, inputs())
    }

    private fun kernel(size: Int): List<Map<Counter, Long>> {
        val names = (0 until size).map { "kernel_unrelated_$it" } + "kernel_target"
        val kernel = myFixture.addFileToProject(
            "kernel_$size.ex",
            buildString {
                appendLine("defmodule Kernel do")
                for (name in names) appendLine("  def $name(a), do: a")
                appendLine("end")
            }
        )
        val user = myFixture.addFileToProject(
            "kernel_user_$size.ex",
            buildString {
                appendLine("defmodule KernelUser$size do")
                for (i in 0 until USES) appendLine("  def use_$i, do: kernel_target($i)")
                appendLine("end")
            }
        )
        val inputs = ProjectModules.Inputs(
            legLevel(),
            KernelImports(legKernel.functions + names.map { NameArity(it, 1) }, legKernel.macros),
            OS.CURRENT,
        )

        return try {
            measure(user, Regex("""kernel_target\([0-9]"""), inputs)
        } finally {
            // Only one `Kernel` at a time, or no copy is the one Elixir loads.
            WriteAction.runAndWait<Throwable> {
                kernel.virtualFile.delete(this)
                user.virtualFile.delete(this)
            }
        }
    }

    private fun exUnit(size: Int): List<Map<Counter, Long>> {
        if (size == SIZES.first()) myFixture.addFileToProject("ex_unit_case.ex", EX_UNIT_CASE)
        val file = myFixture.addFileToProject(
            "ex_unit_$size.ex",
            buildString {
                appendLine("defmodule ExUnit$size do")
                appendLine("  use ExUnit.Case")
                appendLine("  def target(a), do: a")
                for (i in 0 until size) appendLine("  test \"unrelated $i\" do\n    :ok\n  end")
                for (i in 0 until USES) appendLine("  test \"use $i\" do\n    target($i)\n  end")
                appendLine("end")
            }
        )
        checkCompiled(file)

        return measure(file, USE, inputs())
    }

    private fun router(size: Int): List<Map<Counter, Long>> {
        if (size == SIZES.first()) myFixture.addFileToProject("router_dsl.ex", ROUTER_DSL)
        val file = myFixture.addFileToProject(
            "router_$size.ex",
            buildString {
                appendLine("defmodule Router$size do")
                appendLine("  use Router.DSL")
                appendLine("  def target(a), do: a")
                for (i in 0 until size) appendLine("  scope \"/unrelated_$i\" do\n    get \"/\", Controller, :index\n  end")
                for (i in 0 until USES) appendLine("  def use_$i, do: target($i)")
                appendLine("end")
            }
        )
        checkCompiled(file)

        return measure(file, USE, inputs())
    }

    /** [file]'s module compiled, so each use is reached after every macro call before it expanded. */
    private fun checkCompiled(file: PsiFile) {
        val ended = ProjectModules.getInstance(project).expansion(file as ElixirFile, inputs()).modules.single().ended
        check(ended == ExpansionResult.Ended.Compiled) {
            "${file.name} ended ${(ended as? ExpansionResult.Ended.Raised)?.error?.at?.meta?.origin?.substring(file.text) ?: ended}"
        }
    }

    private fun beam(size: Int): List<Map<Counter, Long>> {
        val file = myFixture.addFileToProject(
            "beam_user_$size.ex",
            buildString {
                appendLine("defmodule BeamUser$size do")
                for (i in 0 until USES) appendLine("  def use_$i, do: CallableTable.Size$size.target($i)")
                appendLine("end")
            }
        )

        return measure(file, USE, inputs())
    }

    /** Per use matching [use] in [file], each counter's work for its candidates. */
    private fun measure(file: PsiFile, use: Regex, inputs: ProjectModules.Inputs): List<Map<Counter, Long>> {
        val offsets = use.findAll(file.text).map { it.range.first }.toList()
        check(offsets.size == USES) { "expected $USES uses matching $use, found ${offsets.size}" }

        return offsets.map { offset ->
            val call = PsiTreeUtil.getParentOfType(file.findElementAt(offset), Call::class.java)!!
            Probe.reset()
            val found = Probe.counting { ExpanderSource.candidates(Use.of(call)!!, inputs) }
            check(found.any { it.candidate.applicability == Applicability.VALID }) { "${call.text} has no valid candidate" }

            Probe.snapshot()
        }
    }

    private fun inputs() = ProjectModules.Inputs(legLevel(), legKernel, OS.CURRENT)

    private companion object {
        val SIZES = listOf(10, 100, 1000)
        const val USES = 5
        val USE = Regex("""\btarget\([0-9]""")
        val BEAM_MIRROR = File("testData/org/elixir_lang/psi/scope/callable_table/beam_mirror/ebin")

        val EX_UNIT_CASE = """
            defmodule ExUnit.Case do
              defmacro __using__(_opts) do
                quote do
                  import ExUnit.Case
                end
              end

              defmacro test(message, do: block) do
                name = String.to_atom("test " <> message)

                quote do
                  def unquote(name)(_context) do
                    unquote(block)
                  end
                end
              end
            end
        """.trimIndent()

        val ROUTER_DSL = """
            defmodule Router.DSL do
              defmacro __using__(_opts) do
                quote do
                  import Router.DSL
                end
              end

              defmacro scope(path, do: block) do
                quote do
                  @scope unquote(path)
                  unquote(block)
                end
              end

              defmacro get(path, controller, action) do
                quote do
                  @route {unquote(path), unquote(controller), unquote(action)}
                end
              end
            end
        """.trimIndent()
    }
}
