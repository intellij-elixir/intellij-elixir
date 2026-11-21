package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.beam.Decompiler
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderSourceRows.render
import org.elixir_lang.expander.ExpanderSourceRows.rows
import org.elixir_lang.expander.ExpanderSourceRows.use
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.scope.WalkTestSupport
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The value navigation reads: what lies outside the asker's own expansion only where it is held, each ask that finds
 * it not held starting its warm-up, and the computing value, which every other feature reads, sharing what is warmed.
 */
class NavigationHeldTest : LegSdkFixtureTestCase() {
    private val source: CandidateSource = ExpanderSource.Navigation

    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    private val warmUp: ExpanderWarmUp get() = ExpanderWarmUp.getInstance(project)

    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        Quoter.compile(":ok", 60.seconds)
        super.setUp()
        enableExpander(myFixture.testRootDisposable)
    }

    /**
     * A remote call into a module whose expansion isn't held runs no expansion of it: the index finds the definition,
     * uncertain, and once the warm-up the ask started has run, the expansion's.
     */
    fun testAColdCallIntoAnotherModuleRunsNoExpansionOfItAndTheAskAfterTheWarmUpFindsItsDefinition() {
        val use = remoteCallIntoB()

        val (cold, runs) = cold(use)

        assertEquals(
            rows(
                "cold" to "B.f/1 INDEXED VALID via [] line 2 INDEX\nabsence MODULE",
                "runs" to "",
                "warm" to "B.f/1 OWN VALID via [] line 2\nabsence certain",
            ),
            rows("cold" to cold, "runs" to runs, "warm" to ask(use)),
        )
    }

    /** A module in two source copies runs neither's expansion, and once warmed, each copy's definition is offered. */
    fun testACallIntoAModuleInTwoCopiesRunsNeithersExpansionAndTheAskAfterTheWarmUpOffersBoth() {
        add("b1.ex", B)
        add("b2.ex", B)
        val use = asker()

        val (cold, runs) = cold(use)

        assertEquals(
            rows(
                "cold" to "B.f/1 INDEXED VALID via [] line 2 INDEX\nabsence SEVERAL",
                "runs" to "",
                "warm" to "B.f/1 OWN VALID via [] line 2 SEVERAL\nB.f/1 OWN VALID via [] line 2 SEVERAL\nabsence SEVERAL",
            ),
            rows("cold" to cold, "runs" to runs, "warm" to ask(use)),
        )
    }

    /**
     * A head a `.beam`'s macro injected, whose mirror isn't built, is no find but an UNPLACED gap, not the call of the
     * macro, and the ask builds no mirror; once the warm-up has built it, the head is placed in it, as the computing
     * value places it.
     */
    fun testAHeadACompiledMacroInjectedIsUnplacedUntilItsMirrorIsBuiltThenPlacedAsTheComputingValuePlacesIt() {
        withPlaced { beam ->
            val use = placedAsker()

            val (cold, runs) = cold(use)
            val warm = ask(use)

            assertEquals(
                rows(
                    "cold" to "absence UNPLACED",
                    "runs" to "",
                    "warm" to "A.f/0 USE VALID via [use Placed] line ${usingLine(beam)}\nabsence certain",
                    "computing" to warm,
                ),
                rows("cold" to cold, "runs" to runs, "warm" to warm, "computing" to ask(use, ExpanderSource)),
            )
        }
    }

    /** What the navigation value's ask warmed, the computing value's next answer reads, running nothing again. */
    fun testAWarmUpTheNavigationValueStartsServesTheComputingValuesNextAnswerWithNoSecondRun() {
        val use = remoteCallIntoB()
        val (_, coldRuns) = cold(use)

        val (answer, runs) = holder.runs { ask(use, ExpanderSource) }

        assertEquals(
            rows("cold runs" to "", "computing" to "B.f/1 OWN VALID via [] line 2\nabsence certain", "runs" to ""),
            rows("cold runs" to coldRuns, "computing" to answer, "runs" to runs.joinToString()),
        )
    }

    /** Outside [ExpanderWarmUp.asynchronous], a test's first ask warms first, so it finds what the IDE finds once warmed. */
    fun testOutsideAsynchronousTheFirstAskFindsTheDefinition() {
        val use = remoteCallIntoB()

        assertEquals("B.f/1 OWN VALID via [] line 2\nabsence certain", ask(use))
    }

    /** `b.ex`, [B], and the use of `B.f/1` in the asker, `a.ex`, whose expansion is held. */
    private fun remoteCallIntoB(): Use {
        add("b.ex", B)

        return asker()
    }

    /** The use of `B.f/1` in `a.ex`, whose own expansion is held. */
    private fun asker(): Use = heldUse("defmodule A do\n  def g, do: B.f(1)\nend\n", "B.f(1)", "B.f(1)")

    /** The use of `f/0`, the head `use Placed` injects, in `a.ex`, whose own expansion is held. */
    private fun placedAsker(): Use.OfCall = heldUse("defmodule A do\n  use Placed\n  def g, do: f()\nend\n", "do: f()", "f()")

    /** The use written as [call] at [at] in `a.ex`, [text], whose own expansion is held. */
    private fun heldUse(text: String, at: String, call: String): Use.OfCall {
        val a = add("a.ex", text)
        held(a)

        return use(a, at, call)
    }

    /**
     * [source]'s answer to [use] with each warm-up left to the background, as in the IDE, then the runs that ask made on
     * this thread, `, `-separated; the warm-up it started, if any, has run.
     */
    private fun cold(use: Use): Pair<String, String> {
        val before = warmUp.pending
        val (answer, runs) = warmUp.asynchronous { holder.runs { ask(use) } }
        warmUp.pending?.takeIf { it !== before }?.let(PlatformTestUtil::waitForPromise)

        return answer to runs.joinToString()
    }

    /** [source]'s answer to [use]: its finds, one a line, then why it may be missing what [use] reaches. */
    private fun ask(use: Use, source: CandidateSource = this.source): String {
        val answer = read { source.answer(use, false) }
        val found = render(answer.found)

        return (if (found.isEmpty()) "" else "$found\n") + "absence ${answer.absence?.name ?: "certain"}"
    }

    /** [file]'s expansion, held, so an ask in it reads it and runs nothing of its own. */
    private fun held(file: PsiFile) {
        val elixir = file as ElixirFile
        read { holder.expansion(elixir, (SdkInputs.getInstance(project).inputsFor(elixir) as SdkInputs.Answer.Ready).inputs) }
    }

    /**
     * [block] with a library holding `Elixir.Placed.beam`, given to it, compiled for this test so that nothing has built
     * its mirror, whose `__using__` quotes `def f, do: 1`.
     */
    private fun withPlaced(block: (VirtualFile) -> Unit) {
        val placed = "defmodule Placed do\n  defmacro __using__(_) do\n    quote do\n      def f, do: 1\n    end\n  end\nend\n"
        val compiled = Quoter.compile(
            """
            [{module, binary}] = Code.compile_string(${elixirString(placed)})
            :code.delete(module)
            :code.purge(module)
            IntellijElixir.Quoter.Probe.send(__ENV__, binary)
            """.trimIndent(),
            60.seconds,
        )
        assertEquals("compile ${compiled.diagnostics.map(::inspect)}", OtpErlangAtom("ok"), compiled.status)
        val ebin = FileUtil.createTempDirectory("navigation_held_ebin", null, true)
        val beam = File(ebin, "Elixir.Placed.beam").apply { writeBytes((compiled.messages.single() as OtpErlangBinary).binaryValue()) }

        WalkTestSupport.withLibrary(project, module, myFixture.testRootDisposable, "navigation_held", ebin) {
            block(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam)!!)
        }
    }

    /** The line [beam]'s decompiled text writes `defmacro __using__` on, which moves with the Elixir that compiled it. */
    private fun usingLine(beam: VirtualFile): Int =
        Decompiler().decompile(beam).lineSequence().indexOfFirst { it.trimStart().startsWith("defmacro __using__(") } + 1

    /** [text] as the project's `lib/[path]`, indexed. */
    private fun add(path: String, text: String): PsiFile = caller(text, "lib/$path")

    private companion object {
        const val B = "defmodule B do\n  def f(x), do: x\nend\n"
    }
}
