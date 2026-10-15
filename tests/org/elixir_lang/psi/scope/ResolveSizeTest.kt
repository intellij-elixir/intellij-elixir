package org.elixir_lang.psi.scope

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IdempotenceChecker
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkProbe.Counter
import org.elixir_lang.psi.scope.WalkTestSupport.location
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import java.io.File

/**
 * The work one resolve does, at 10, 100 and 1,000 unrelated definitions. [USES] distinct uses sit at a fixed offset
 * from the module's end: the first may pay a linear build, and every later one must cost the same at every size.
 */
class ResolveSizeTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // At the platform's random rate a cached value is recomputed on a read, which a count of work would include.
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    }

    override fun tearDown() {
        try {
            CallableTable.enabled = true
            WalkProbe.reset()
        } finally {
            super.tearDown()
        }
    }

    /** A call that only the implicit `import Kernel` declares, with `Kernel` growing. */
    fun testKernel() = assertSizeIndependent(SIZES.associateWith(::kernel))

    /** Uses after the module's other definitions, as in a large decompiled module. */
    fun testModuleBody() = assertSizeIndependent(SIZES.associateWith(::moduleBody))

    /** Uses in `test` blocks, after many others. */
    fun testExUnit() = assertSizeIndependent(SIZES.associateWith(::exUnit))

    /** [testExUnit]'s module, with the uses in an `.html.leex` template: in another file than the module. */
    fun testExUnitTemplate() = assertSizeIndependent(SIZES.associateWith(::exUnitTemplate))

    /** Uses in `~H` fragments of a nested module, after many `test`s, with the target after the nested module. */
    fun testHeex() = WalkTestSupport.withHeexInjection(project, testRootDisposable) {
        assertSizeIndependent(SIZES.associateWith(::heex))
    }

    /** Uses after many `scope ... do` blocks, as in a router. */
    fun testRouter() = assertSizeIndependent(SIZES.associateWith(::router))

    /**
     * The decompiled mirror of an Elixir-compiled `.beam`, each function calling the next, with uses inside the
     * mirror's own bodies (`callable_table/beam_mirror/generate.exs`).
     */
    fun testBeamMirror() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "beam_mirror", BEAM_MIRROR) { root ->
            val cached = mutableMapOf<Int, Boolean>()
            val perSize = SIZES.associateWith { beamMirror(root, it, cached) }
            assertSizeIndependent(perSize)
            assertEquals("tables cached on the mirrors' modules", SIZES.associateWith { true }, cached)
            assertEquals(
                "tables built after the first use",
                SIZES.associateWith { listOf(0L, 0L, 0L, 0L) },
                perSize.mapValues { (_, perUse) -> perUse.drop(1).map { it.getValue(Counter.TABLE_BUILD) } }
            )
        }

    private fun beamMirror(root: VirtualFile, size: Int, cached: MutableMap<Int, Boolean>): List<Map<Counter, Long>> {
        val beam = PsiManager.getInstance(project).findFile(root.findChild("Elixir.CallableTable.Size$size.beam")!!) as BeamFileImpl
        val mirror = beam.decompiledPsiFile
        val calls = PsiTreeUtil.findChildrenOfType(mirror, Call::class.java)
        val references = calls
            .filter { call ->
                call.functionName() == "target" &&
                    generateSequence(call.parent) { it.parent }
                        .filterIsInstance<Call>()
                        .firstOrNull { CallDefinitionClause.`is`(it) }
                        ?.let { CallDefinitionClause.nameArityInterval(it, ResolveState.initial())?.name }
                        ?.startsWith("use_") == true
            }
            .map { it.reference as PsiPolyVariantReference }
        check(references.size == USES) { "expected $USES uses of target in the Size$size mirror, found ${references.size}" }

        val perUse = count(references)
        // Before the table-off comparison, whose PSI change drops the table.
        cached[size] = CallableTable.isCached(calls.first { isModular(it) })
        assertAnswersAreLive(references)

        return perUse
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

        return measure(file, USE)
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

        return measure(file, USE)
    }

    private fun exUnitTemplate(size: Int): List<Map<Counter, Long>> {
        if (size == SIZES.first()) myFixture.addFileToProject("ex_unit_case.ex", EX_UNIT_CASE)
        myFixture.addFileToProject(
            "ex_unit_template_$size.ex",
            buildString {
                appendLine("defmodule ExUnitTemplate$size do")
                appendLine("  use ExUnit.Case")
                appendLine("  def target(a), do: a")
                for (i in 0 until size) appendLine("  test \"unrelated $i\" do\n    :ok\n  end")
                appendLine("end")
            }
        )
        val template = myFixture.addFileToProject(
            "ex_unit_template_$size.html.leex",
            buildString { for (i in 0 until USES) appendLine("<%= target($i) %>") }
        )

        return measure(template, USE)
    }

    private fun heex(size: Int): List<Map<Counter, Long>> {
        if (size == SIZES.first()) myFixture.addFileToProject("ex_unit_case.ex", EX_UNIT_CASE)
        val file = myFixture.addFileToProject(
            "heex_$size.ex",
            buildString {
                appendLine("defmodule Heex$size do")
                appendLine("  use ExUnit.Case")
                for (i in 0 until size) appendLine("  test \"unrelated $i\" do\n    :ok\n  end")
                appendLine("  defmodule Inner do")
                for (i in 0 until USES) appendLine("    def render_$i(assigns), do: ~H\"<%= target($i) %>\"")
                appendLine("  end")
                appendLine("  def target(a), do: a")
                appendLine("end")
            }
        )
        val references = WalkTestSupport.injectedFiles(file)
            .flatMap { PsiTreeUtil.findChildrenOfType(it, Call::class.java) }
            .filter { it.functionName() == "target" }
            .mapNotNull { it.reference as? PsiPolyVariantReference }
            .distinctBy { it.element }
        check(references.size == USES) { "expected $USES uses of target in Heex$size's fragments, found ${references.size}" }

        return measure(references)
    }

    private fun router(size: Int): List<Map<Counter, Long>> {
        val file = myFixture.addFileToProject(
            "router_$size.ex",
            buildString {
                appendLine("defmodule Router$size do")
                appendLine("  def target(a), do: a")
                for (i in 0 until size) appendLine("  scope \"/unrelated_$i\" do\n    get \"/\", Controller, :index\n  end")
                for (i in 0 until USES) appendLine("  def use_$i, do: target($i)")
                appendLine("end")
            }
        )

        return measure(file, USE)
    }

    private fun kernel(size: Int): List<Map<Counter, Long>> {
        val kernel = myFixture.addFileToProject(
            "kernel_$size.ex",
            buildString {
                appendLine("defmodule Kernel do")
                for (i in 0 until size) appendLine("  def kernel_unrelated_$i(a), do: a")
                appendLine("  def kernel_target(a), do: a")
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

        return try {
            measure(user, Regex("""kernel_target\([0-9]"""))
        } finally {
            // Only one `Kernel` at a time, or the implicit import would walk every size's.
            WriteAction.runAndWait<Throwable> {
                kernel.virtualFile.delete(this)
                user.virtualFile.delete(this)
            }
        }
    }

    /** Per use, each counter's work for one resolve. */
    private fun measure(file: PsiFile, use: Regex): List<Map<Counter, Long>> {
        val offsets = use.findAll(file.text).map { it.range.first }.toList()
        check(offsets.size == USES) { "expected $USES uses matching $use, found ${offsets.size}" }

        return measure(offsets.map { file.viewProvider.findReferenceAt(it, ElixirLanguage) as PsiPolyVariantReference })
    }

    /** Also checks that each use resolves as walking its module does. */
    private fun measure(references: List<PsiPolyVariantReference>): List<Map<Counter, Long>> =
        count(references).also { assertAnswersAreLive(references) }

    private fun count(references: List<PsiPolyVariantReference>): List<Map<Counter, Long>> =
        references.map { reference ->
            WalkProbe.reset()
            val results = WalkProbe.counting { reference.multiResolve(false) }
            check(results.any { it.isValidResult }) { "${location(reference.element)} did not resolve" }

            WalkProbe.snapshot()
        }

    private fun assertAnswersAreLive(references: List<PsiPolyVariantReference>) {
        val tabled = references.map(::answer)
        val live = withoutTable { references.map(::answer) }
        assertEquals("the table's answers against walking the module", live, tabled)
    }

    private fun answer(reference: PsiPolyVariantReference): String =
        location(reference.element) + " -> " + reference.multiResolve(false)
            .joinToString(" | ") { location(it.element) + if (it.isValidResult) "" else " (invalid)" }

    private fun <T> withoutTable(block: () -> T): T {
        CallableTable.enabled = false
        // A file added is a PSI change, so the walks cached with the table are recomputed.
        myFixture.addFileToProject("without_table_${withoutTable++}.ex", "")

        return try {
            block()
        } finally {
            CallableTable.enabled = true
        }
    }

    private var withoutTable = 0

    private fun assertSizeIndependent(perSize: Map<Int, List<Map<Counter, Long>>>) =
        org.elixir_lang.assertSizeIndependent(perSize, Counter.entries)

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
                quote do
                  unquote(message)
                  unquote(block)
                end
              end
            end
        """.trimIndent()
    }
}
