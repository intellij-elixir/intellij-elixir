package org.elixir_lang.expander

import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.refactoring.rename.impl.RenameOptions
import com.intellij.refactoring.rename.impl.TextOptions
import com.intellij.refactoring.rename.impl.buildQuery
import org.elixir_lang.beam.Decompiler
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderSourceRows.rows
import org.elixir_lang.expander.ExpanderSourceRows.use
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.config.caret
import kotlin.time.Duration.Companion.seconds

/**
 * The value navigation reads: what lies outside the asker's own expansion only where it is held, each ask that finds
 * it not held starting its warm-up, and the computing value, which every other feature reads, sharing what is warmed.
 */
class NavigationHeldTest : LegSdkFixtureTestCase() {
    override val source: CandidateSource = ExpanderSource.Navigation

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
        val beam = addPlaced()
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

    /**
     * A head a `.beam`'s macro injected, asked for while a warm-up for the `.beam` as it was is still queued, and again
     * once the `.beam` is rebuilt, is placed in the rebuilt `.beam`'s mirror once the warm-ups have run: the queued
     * warm-up builds the `.beam`'s mirror as it is, not as the first ask read it.
     */
    fun testAHeadAskedForAfterItsBeamIsRebuiltIsPlacedOnceTheWarmUpsHaveRun() {
        val beam = addPlaced()
        val use = placedAsker()

        // No warm-up runs while the write action holds, so the first ask's is still queued when the second asks.
        val (stale, rebuilt) = inAWriteAction {
            val stale = answered(use)
            beam.setBinaryContent(beam.contentsToByteArray())
            stale to answered(use)
        }

        assertEquals(
            rows(
                "stale" to "absence UNPLACED",
                "rebuilt" to "absence UNPLACED",
                "after" to "A.f/0 USE VALID via [use Placed] line ${usingLine(beam)}\nabsence certain",
            ),
            rows("stale" to stale, "rebuilt" to rebuilt, "after" to warmUp.asynchronous { ask(use) }),
        )
    }

    /**
     * A call into another module, asked for while a warm-up another file's ask queued for that module is still queued,
     * finds the module's definition once the warm-ups have run, though that other file is deleted before its warm-up runs.
     */
    fun testACallAskedForWhileADeletedFilesWarmUpIsQueuedFindsItsDefinitionOnceTheWarmUpsHaveRun() {
        add("b.ex", B)

        assertEquals(
            rows("asked" to "B.f/1 INDEXED VALID via [] line 2 INDEX\nabsence MODULE", "after" to "B.f/1 OWN VALID via [] line 2\nabsence certain"),
            askedWhileADeletedFilesWarmUpIsQueued(),
        )
    }

    /**
     * A call into a module in two copies, asked for while a warm-up another file's ask queued for those copies is still
     * queued, offers each copy's definition once the warm-ups have run, though that other file is deleted before its
     * warm-up runs.
     */
    fun testACallIntoTwoCopiesAskedForWhileADeletedFilesWarmUpIsQueuedOffersBothOnceTheWarmUpsHaveRun() {
        add("b1.ex", B)
        add("b2.ex", B)

        assertEquals(
            rows(
                "asked" to "B.f/1 INDEXED VALID via [] line 2 INDEX\nabsence SEVERAL",
                "after" to "B.f/1 OWN VALID via [] line 2 SEVERAL\nB.f/1 OWN VALID via [] line 2 SEVERAL\nabsence SEVERAL",
            ),
            askedWhileADeletedFilesWarmUpIsQueued(),
        )
    }

    /**
     * A call into a module in two copies, the first deleted while the warm-up its ask queued is still queued, finds the
     * other copy's definition once the warm-ups have run.
     */
    fun testACallIntoTwoCopiesTheFirstDeletedWhileItsWarmUpIsQueuedFindsTheOtherOnceTheWarmUpsHaveRun() =
        assertACopyDeletedWhileItsWarmUpIsQueuedLeavesTheOther("b1.ex")

    /**
     * A call into a module in two copies, the second deleted while the warm-up its ask queued is still queued, finds the
     * other copy's definition once the warm-ups have run.
     */
    fun testACallIntoTwoCopiesTheSecondDeletedWhileItsWarmUpIsQueuedFindsTheOtherOnceTheWarmUpsHaveRun() =
        assertACopyDeletedWhileItsWarmUpIsQueuedLeavesTheOther("b2.ex")

    /** `a.ex`'s ask for `B.f(1)`, `B` in `b1.ex` and `b2.ex`, with [deleted] gone before the warm-up the ask queued runs. */
    private fun assertACopyDeletedWhileItsWarmUpIsQueuedLeavesTheOther(deleted: String) {
        val copies = listOf("b1.ex", "b2.ex").associateWith { add(it, B) }
        val use = asker()

        // No warm-up runs while the write action holds, so the ask's is still queued when the copy goes.
        val asked = inAWriteAction {
            val asked = answered(use)
            copies.getValue(deleted).virtualFile.delete(this)
            asked
        }

        assertEquals(
            rows("asked" to "B.f/1 INDEXED VALID via [] line 2 INDEX\nabsence SEVERAL", "after" to "B.f/1 OWN VALID via [] line 2\nabsence certain"),
            rows("asked" to asked, "after" to warmUp.asynchronous { ask(use) }),
        )
    }

    /**
     * `a.ex`'s and `c.ex`'s asks for `B.f(1)`, with `a.ex` deleted before the warm-up its ask queued runs: `c.ex`'s
     * answer then, and once the warm-ups have run.
     */
    private fun askedWhileADeletedFilesWarmUpIsQueued(): String {
        val deleted = asker()
        val use = heldUse("defmodule C do\n  def g, do: B.f(1)\nend\n", "B.f(1)", "B.f(1)", "c.ex")

        // No warm-up runs while the write action holds, so `a.ex`'s is still queued when `c.ex` asks and `a.ex` goes.
        val asked = inAWriteAction {
            answered(deleted)
            val asked = answered(use)
            deleted.entrance.containingFile.virtualFile.delete(this)
            asked
        }

        return rows("asked" to asked, "after" to warmUp.asynchronous { ask(use) })
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

    /**
     * The call's reference, which caches what it resolves to, resolves a head a compiled macro injected to nothing
     * while its mirror isn't built, and once the warm-up has built it, to the placed clause, not what it cached.
     */
    fun testAReferenceResolvedBeforeTheWarmUpResolvesToThePlacedHeadAfterIt() {
        val beam = addPlaced()
        val reference = reference(placedAsker())

        val (cold, runs) = inTheBackground { resolved(reference) }

        assertEquals(
            rows("cold" to "", "runs" to "", "warm" to "Elixir.Placed.beam.decompiled.ex:${usingLine(beam)} defmacro __using__(_) do"),
            rows("cold" to cold, "runs" to runs, "warm" to resolved(reference)),
        )
    }

    /** Go to Declaration at a call of a head a compiled macro injected lands, once warmed, on the clause that places it. */
    fun testGoToAtAHeadACompiledMacroInjectedLandsOnItsPlacedClause() {
        val beam = addPlaced()
        add("a.ex", "defmodule A do\n  use Placed\n  def g, do: f()\nend\n")
        myFixture.caret("lib/a.ex", "do: <caret>f()")

        assertEquals(listOf("Elixir.Placed.beam.decompiled.ex:${usingLine(beam)}"), goTo())
    }

    /** Go to Declaration at a `defdelegate` to a head a compiled macro injected lands, once warmed, on its placed clause. */
    fun testGoToAtADelegationToAHeadACompiledMacroInjectedLandsOnItsPlacedClause() {
        val beam = addPlaced()
        add("a.ex", "defmodule A do\n  use Placed\nend\n")
        add("d.ex", "defmodule D do\n  defdelegate f(), to: A\nend\n")
        myFixture.caret("lib/d.ex", "defdelegate <caret>f()")

        assertEquals(listOf("Elixir.Placed.beam.decompiled.ex:${usingLine(beam)}"), goTo())
    }

    /** The call's reference into a module in two copies resolves to the one the index finds, then, once warmed, to both. */
    fun testAReferenceResolvedBeforeTheWarmUpResolvesToEachCopyAfterIt() {
        add("b1.ex", B)
        add("b2.ex", B)
        val reference = reference(asker())

        val (cold, runs) = inTheBackground { resolved(reference) }

        assertEquals(
            rows("cold" to "b1.ex:2 def f(x), do: x", "runs" to "", "warm" to "b1.ex:2 def f(x), do: x\nb2.ex:2 def f(x), do: x"),
            rows("cold" to cold, "runs" to runs, "warm" to resolved(reference)),
        )
    }

    /** Highlight Usages in File, on the EDT at a call of a head a compiled macro injected, builds no mirror. */
    fun testHighlightUsagesInFileOnTheEdtBuildsNoMirror() =
        assertTheEdtBuildsNoMirror(USES_PLACED, " <caret>f()", DELEGATES_TO_A) { assertTrue(myFixture.testAction(HighlightUsagesAction()).isEnabled) }

    /**
     * Highlight Usages in File, on the EDT at a type of `Placed`, which has no source and no `@type`, builds no mirror.
     */
    fun testHighlightUsagesInFileOnTheEdtAtATypeOfACompiledModuleBuildsNoMirror() =
        assertTheEdtBuildsNoMirror(TYPE_OF_PLACED, "Placed.<caret>t()") { myFixture.testAction(HighlightUsagesAction()) }

    /**
     * The rename dialog's validator, on the EDT, at a function an EEx call declares in a module whose `use` also injects
     * a head of its name, builds no mirror, though a delegation in another module renames with it.
     */
    fun testTheRenameDialogsValidatorOnTheEdtBuildsNoMirror() =
        assertTheEdtBuildsNoMirror(USES_PLACED, ":<caret>f,", DELEGATES_TO_A) {
            renameTargets().forEach { (it as ElixirRenameTarget).validator() }
        }

    /** In-place rename's search of the file, on the EDT, from the same function, builds no mirror. */
    fun testInPlaceRenamesSearchOnTheEdtBuildsNoMirror() =
        assertTheEdtBuildsNoMirror(USES_PLACED, ":<caret>f,", DELEGATES_TO_A) {
            val options = RenameOptions(TextOptions(commentStringOccurrences = false, textOccurrences = false), LocalSearchScope(myFixture.file))
            renameTargets().forEach { target -> buildQuery(project, target, options).findAll() }
        }

    /**
     * [gesture] at the caret in [marked] in `a.ex`, [text], beside [others] (each a `lib/` path and its text), on the EDT
     * with each warm-up left to the background, as in the IDE: it reads no mirror and runs no expansion.
     */
    private fun assertTheEdtBuildsNoMirror(text: String, marked: String, vararg others: Pair<String, String>, gesture: () -> Unit) {
        addPlaced()
        others.forEach { (path, other) -> add(path, other) }
        add("a.ex", text)
        myFixture.caret("lib/a.ex", marked)
        held(myFixture.file)

        assertEquals("", inTheBackground(gesture).second)
    }

    /** The rename targets at the caret, at least one. */
    private fun renameTargets() = myFixture.renameTargetsAtCaret().also { assertNotEmpty(it) }

    /** `b.ex`, [B], and the use of `B.f/1` in the asker, `a.ex`, whose expansion is held. */
    private fun remoteCallIntoB(): Use {
        add("b.ex", B)

        return asker()
    }

    /** The use of `B.f/1` in `a.ex`, whose own expansion is held. */
    private fun asker(): Use.OfCall = heldUse("defmodule A do\n  def g, do: B.f(1)\nend\n", "B.f(1)", "B.f(1)")

    /** The use of `f/0`, the head `use Placed` injects, in `a.ex`, whose own expansion is held. */
    private fun placedAsker(): Use.OfCall = heldUse(CALLS_PLACED, "do: f()", "f()")

    /** The use written as [call] at [at] in [path], [text], whose own expansion is held. */
    private fun heldUse(text: String, at: String, call: String, path: String = "a.ex"): Use.OfCall {
        val file = add(path, text)
        held(file)

        return use(file, at, call)
    }

    /** [source]'s answer to [use] with each warm-up left to the background, as [inTheBackground] runs it. */
    private fun cold(use: Use): Pair<String, String> = inTheBackground { ask(use) }

    /**
     * [block]'s value with each warm-up left to the background, as in the IDE, then the runs it made on this thread,
     * `, `-separated; the warm-up it started, if any, has run, and the re-read it queued.
     */
    private fun <T> inTheBackground(block: () -> T): Pair<T, String> {
        val (answer, runs) = withWarmUpsInTheBackground { holder.runs(block) }

        return answer to runs.joinToString()
    }

    /** [block]'s value, computed in a write action [inTheBackground]. */
    private fun <T> inAWriteAction(block: () -> T): T = inTheBackground { WriteAction.compute<T, Throwable> { block() } }.first

    /** The reference of [use]'s call. */
    private fun reference(use: Use.OfCall): PsiPolyVariantReference = read { use.entrance.reference } as PsiPolyVariantReference

    /** What [reference] resolves to, one a line: its file, line and first line of text. */
    private fun resolved(reference: PsiPolyVariantReference): String =
        read {
            reference.multiResolve(false).joinToString("\n") { result ->
                result.element!!.let { "${it.containingFile.name}:${lineOf(it)} ${it.text.lineSequence().first()}" }
            }
        }

    /** Go to Declaration's targets at the caret, each as `file:line`. */
    private fun goTo(): List<String?>? =
        myFixture.gotoDeclarationTargetsAtCaret()?.map { target -> target.destination?.let { "${it.containingFile.name}:${lineOf(it)}" } }

    /** The line [beam]'s decompiled text writes `defmacro __using__` on, which moves with the Elixir that compiled it. */
    private fun usingLine(beam: VirtualFile): Int =
        Decompiler().decompile(beam).lineSequence().indexOfFirst { it.trimStart().startsWith("defmacro __using__(") } + 1

    /** [text] as the project's `lib/[path]`, indexed. */
    private fun add(path: String, text: String): PsiFile = caller(text, "lib/$path")

    private companion object {
        /** `A` uses `Placed`, declares `f/1` with EEx, and calls both `f`s. */
        const val USES_PLACED = "defmodule A do\n  use Placed\n  require EEx\n  EEx.function_from_string(:def, :f, \"<%= a %>\", [:a])\n" +
            "  def g, do: f()\n  def h, do: f(1)\nend\n"

        /** `c.ex`, delegating `f/1` to `A`. */
        val DELEGATES_TO_A = "c.ex" to "defmodule C do\n  defdelegate f(a), to: A\nend\n"

        const val TYPE_OF_PLACED = "defmodule A do\n  @type p :: Placed.t()\nend\n"

        const val B = "defmodule B do\n  def f(x), do: x\nend\n"
    }
}
