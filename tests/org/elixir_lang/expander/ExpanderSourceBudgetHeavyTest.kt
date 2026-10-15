package org.elixir_lang.expander

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.replaceService
import org.elixir_lang.declaration.Use
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.PhoenixCorpus.Companion.APP
import org.elixir_lang.expander.PhoenixCorpus.Companion.DEP
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import java.io.File
import java.lang.ref.Reference
import org.elixir_lang.sdk.Type as SdkRoots

/**
 * Times [ExpanderSource] and what it reads, and measures the heap the expansions hold, over the Phoenix app
 * `-Dorg.elixir_lang.expander.phoenix` names, and writes each figure beside its budget to
 * `build/reports/expander-budget/<elixir version>/`. It asserts nothing on them: the figures hold for one machine and
 * heap.
 *
 * It runs only when the expander switch and the corpus are both set.
 */
class ExpanderSourceBudgetHeavyTest : HeavyTestCase() {
    private lateinit var corpus: PhoenixCorpus
    private var sdks = 0
    private val report by lazy { File("build/reports/expander-budget/${environment("ELIXIR_VERSION")}").apply { mkdirs() } }

    override fun shouldRunTest(): Boolean {
        PhoenixCorpus.skipReason()?.let {
            println("${javaClass.simpleName}.$name skipped: $it")
            return false
        }

        return super.shouldRunTest()
    }

    override fun setUp() {
        super.setUp()
        moduleSdk(LegSdk.setUp(module, testRootDisposable))
        corpus = PhoenixCorpus(project, testRootDisposable, File(System.getProperty(PhoenixCorpus.PROPERTY)!!))
    }

    override fun tearDown() {
        try {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testTime() {
        val setup = corpus.setUp(module)
        val times = Times()
        val held = holdAll(corpus.appFiles + corpus.depFiles)
        val asked = corpus.asked()

        for (rep in 1..REPETITIONS) {
            times.rep = rep
            fresh(held) {
                times.time("inputsFor, cold") { corpus.read { SdkInputs.getInstance(project).inputsFor(asked.first().psi) } }
                for (measure in listOf("inputsFor, a file's first ask", "inputsFor, warm, per call")) {
                    for (file in asked) {
                        times.time(measure) { corpus.read { SdkInputs.getInstance(project).inputsFor(file.psi) } }
                    }
                }
            }

            fresh(held) {
                val holder = ProjectModules.getInstance(project)
                val inputs = asked.associateWith { inputs(it.psi) }
                for (phase in PHASES) {
                    pass(times, "env at a use", phase, asked, sampled = false) { file, use ->
                        holder.indexed(file.psi, inputs.getValue(file)).envs.at(use.entrance.textRange)
                    }
                }
            }

            fresh(held) {
                for (phase in PHASES) {
                    pass(times, "candidates", phase, asked, sampled = false) { _, use -> ExpanderSource.candidates(use, false) }
                }
            }

            fresh(held) {
                for (phase in PHASES) {
                    pass(times, "visible, no prefix", phase, asked, sampled = true) { _, use -> ExpanderSource.visible(use.entrance) }
                }
            }
        }
        Reference.reachabilityFence(held)

        File(report, "time.txt").writeText(machine(setup) + times.against(TIME_BUDGET))
    }

    /**
     * The heap every app and dep file's expansion and env index hold, with each file's PSI held strongly, and with it
     * let go before the heap is read: the heap with the holder less the heap once it is released, each after three
     * collections. `before` is the heap before indexing, so `after - before` also counts what indexing leaves in the
     * platform's caches.
     */
    fun testMemory() {
        val setup = corpus.setUp(module)
        val files = corpus.appFiles + corpus.depFiles
        val rows = StringBuilder("case\tround\tbefore\tafter\tcleared\tretained\tafter - before\tindexed\n")
        val retained = mutableMapOf<String, MutableList<Long>>()

        for (heldStrongly in listOf(true, false)) {
            val case = if (heldStrongly) "PSI held" else "PSI let go"
            for (round in 1..REPETITIONS) {
                var held: List<Any>? = holdAll(files)
                val before = usedAfterGc()
                var holder: ProjectModules? = ProjectModules(project)
                val indexed = corpus.quiet {
                    held!!.filterIsInstance<ElixirFile>().count { file ->
                        runCatching { ReadAction.computeBlocking<Any, Throwable> { holder!!.indexed(file, inputs(file)) } }.isSuccess
                    }
                }
                if (!heldStrongly) held = null
                val after = usedAfterGc()
                release(holder!!, held.orEmpty().filterIsInstance<PsiFile>())
                holder = null
                val cleared = usedAfterGc()
                Reference.reachabilityFence(held)
                retained.getOrPut(case) { mutableListOf() } += after - cleared
                rows.append("$case\t$round\t$before\t$after\t$cleared\t${after - cleared}\t${after - before}\t$indexed\n")
                held = null
                dropPsi()
            }
        }

        File(report, "memory.txt").writeText(
            machine(setup) + "files: ${files.size}\n" + rows +
                retained.entries.joinToString("\n", postfix = "\n") { (case, bytes) ->
                    "$case: retained median ${mb(median(bytes))}, p95 ${mb(p95(bytes))} " +
                        "(${p95(bytes) / files.size / 1024} kB a file); budget ${MEMORY_BUDGET.getValue(case)}"
                }
        )
    }

    /**
     * Runs [body] on a new holder behind the service and a new SDK, which holds no inputs yet, and lets go of what the
     * holder kept on [held]'s PSI afterwards.
     */
    private fun fresh(held: List<Any>, body: () -> Unit) {
        moduleSdk(anotherLegSdk("leg ${++sdks}"))
        val holder = ProjectModules(project)
        val disposable = Disposer.newDisposable()
        project.replaceService(ProjectModules::class.java, holder, disposable)
        try {
            body()
        } finally {
            release(holder, held.filterIsInstance<PsiFile>())
            Disposer.dispose(disposable)
        }
    }

    /**
     * Drops every file's PSI, so the next holder starts on new PSI: a holder keeps a file's expansion on its PSI under a
     * key of its own, so PSI read again would still hold each earlier holder's.
     */
    private fun dropPsi() {
        WriteAction.runAndWait<Throwable> { PsiManagerEx.getInstanceEx(project).cleanupForNextTest() }
    }

    /** Lets go of what [holder] keeps on each of [files]' PSI, under the keys it holds privately. */
    private fun release(holder: ProjectModules, files: List<PsiFile>) {
        val keys = HOLDER_KEYS.map { name ->
            @Suppress("UNCHECKED_CAST")
            ProjectModules::class.java.getDeclaredField(name).apply { isAccessible = true }.get(holder) as Key<Any>
        }
        files.forEach { file -> keys.forEach { file.putUserData(it, null) } }
    }

    /** Times [ask] at each use of [files], per use and for the pass, and a cold pass's first use in each file apart. */
    private fun pass(
        times: Times,
        measure: String,
        phase: String,
        files: List<PhoenixCorpus.Asked>,
        sampled: Boolean,
        ask: (PhoenixCorpus.Asked, Use) -> Any?,
    ) {
        var total = 0L
        corpus.quiet {
            for (file in files) {
                ReadAction.computeBlocking<Unit, Throwable> {
                    for ((i, use) in (if (sampled) file.sampled else file.uses).withIndex()) {
                        val start = System.nanoTime()
                        ask(file, use)
                        val nanos = System.nanoTime() - start
                        total += nanos
                        times.add("$measure, $phase, per use, ${file.set}", nanos)
                        if (phase == COLD && i == 0) times.add("$measure, cold, first use in a file, ${file.set}", nanos)
                    }
                }
            }
        }
        times.add("$measure, $phase, a pass", total)
    }

    private fun inputs(file: PsiFile): ProjectModules.Inputs =
        (corpus.read { SdkInputs.getInstance(project).inputsFor(file) } as SdkInputs.Answer.Ready).inputs

    /** The leg's Elixir again, as an SDK of its own paired with the leg's Erlang. */
    private fun anotherLegSdk(name: String): Sdk {
        val erlang = ProjectJdkTable.getInstance().findJdk(LegSdk.ERLANG)!!
        val elixir = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk(name, environment("ELIXIR_LANG_ELIXIR_PATH")),
            testRootDisposable,
        )
        WriteAction.runAndWait<Throwable> {
            elixir.sdkModificator.apply {
                SdkRoots.addCodePaths(this)
                commitChanges()
            }
        }
        SdkFixtures.commit(elixir, SdkAdditionalData(erlang, elixir))

        return elixir
    }

    /** [sdk] as the module's SDK and the project's, since a library file has no module and reads the project's. */
    private fun moduleSdk(sdk: Sdk) {
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Each file's PSI and its tree, in a list that is the only reference the test keeps. */
    private fun holdAll(files: List<File>): List<Any> {
        val held = ArrayList<Any>()
        files.mapNotNull(corpus::psi).let { psi -> corpus.read { psi.forEach { held += it; held += it.node } } }

        return held
    }

    private fun machine(setup: String): String =
        buildString {
            // Only Linux has `/proc`; elsewhere the model and RAM are left out.
            val model = proc("cpuinfo")?.firstOrNull { it.startsWith("model name") }?.substringAfter(": ")
            appendLine("cpu: ${model?.let { "$it x " }.orEmpty()}${Runtime.getRuntime().availableProcessors()}")
            proc("meminfo")?.firstOrNull()?.let { appendLine("ram: ${it.substringAfter(":").trim()}") }
            appendLine("xmx: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            appendLine("ide build: ${ApplicationInfo.getInstance().build}")
            appendLine("leg: ${environment("ELIXIR_VERSION")}")
            appendLine("setup: $setup")
            appendLine("repetitions: $REPETITIONS")
            appendLine("lowering errors: ${corpus.loweringErrors.values.sum()}")
        }

    private fun proc(name: String): List<String>? = File("/proc/$name").takeIf(File::isFile)?.readLines()

    /** Nanosecond samples by measure and repetition, pooled over the repetitions. */
    private class Times {
        var rep = 0
        private val samples = linkedMapOf<String, MutableList<Pair<Int, Long>>>()

        fun add(measure: String, nanos: Long) {
            samples.getOrPut(measure) { mutableListOf() } += rep to nanos
        }

        fun time(measure: String, body: () -> Unit) {
            val start = System.nanoTime()
            body()
            add(measure, System.nanoTime() - start)
        }

        fun against(budget: Map<String, Long>): String =
            buildString {
                appendLine("measure\tsamples\tmedian\tp95\tbudget (p95)\tover\tp95 by repetition")
                for ((measure, repNanos) in samples) {
                    val nanos = repNanos.map { it.second }
                    val limit = budget[measure]
                    val p95 = p95(nanos)
                    val byRep = repNanos.groupBy({ it.first }, { it.second }).values.joinToString(", ") { time(p95(it)) }
                    appendLine(
                        "$measure\t${nanos.size}\t${time(median(nanos))}\t${time(p95)}\t" +
                            "${limit?.let(::time) ?: "-"}\t${if (limit != null && p95 > limit) "OVER" else ""}\t$byRep"
                    )
                }
            }
    }

    private companion object {
        const val REPETITIONS = 5
        const val COLD = "cold"
        val PHASES = listOf(COLD, "warm")
        val HOLDER_KEYS = listOf("held", "chosen")

        /** The proposed budget, in nanoseconds, each a p95 over the five repetitions. */
        val TIME_BUDGET = mapOf(
            "inputsFor, warm, per call" to 25_000L,
            "inputsFor, cold" to 1_100_000L,
            "env at a use, warm, per use, $APP" to 2_000L,
            "env at a use, warm, per use, $DEP" to 2_000L,
            "env at a use, cold, first use in a file, $APP" to 3_500_000L,
            "env at a use, cold, first use in a file, $DEP" to 19_000_000L,
            "candidates, warm, per use, $APP" to 75_000L,
            "candidates, warm, per use, $DEP" to 60_000L,
            "candidates, cold, first use in a file, $APP" to 2_200_000L,
            "candidates, cold, first use in a file, $DEP" to 13_000_000L,
            "candidates, warm, a pass" to 470_000_000L,
            "visible, no prefix, warm, per use, $APP" to 1_100_000L,
            "visible, no prefix, warm, per use, $DEP" to 1_100_000L,
        )
        val MEMORY_BUDGET = mapOf("PSI held" to "<= 340 MB, <= 460 kB a file", "PSI let go" to "<= 2 MB")

        fun median(values: List<Long>): Long = values.sorted()[(values.size - 1) / 2]

        /** By nearest rank. */
        fun p95(values: List<Long>): Long = values.sorted()[Math.ceil(0.95 * values.size).toInt() - 1]

        fun time(nanos: Long): String =
            when {
                nanos >= 1_000_000 -> "%.2f ms".format(nanos / 1e6)
                else -> "%.1f µs".format(nanos / 1e3)
            }

        fun mb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

        fun usedAfterGc(): Long {
            repeat(3) {
                System.gc()
                Thread.sleep(200)
            }
            val runtime = Runtime.getRuntime()

            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
}
