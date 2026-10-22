package org.elixir_lang.expander

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.junit.HeavyTestCase
import java.io.File
import java.lang.ref.Reference
import kotlin.math.ceil

/**
 * Times a cold `exports(M)` of every module name the Phoenix app `-Dorg.elixir_lang.expander.phoenix` names defines,
 * asked from `lib/demo.ex`, and the cold expansion of each of its app files, as macro bodies are evaluated, over five
 * runs, each on a new holder with no file's PSI held. Measures the heap the clause cache retains after the runs. Writes
 * each figure to `build/reports/interpreter-budget/<elixir version>/`, and fails on a p95 or the cache over its bound.
 *
 * It runs only when the expander switch and the corpus are both set.
 */
class InterpreterBudgetHeavyTest : HeavyTestCase() {
    private lateinit var corpus: PhoenixCorpus
    private val report by lazy { File("build/reports/interpreter-budget/${environment("ELIXIR_VERSION")}").apply { mkdirs() } }

    override fun shouldRunTest(): Boolean {
        PhoenixCorpus.skipReason()?.let {
            println("${javaClass.simpleName}.$name skipped: $it")
            return false
        }

        return super.shouldRunTest()
    }

    override fun setUp() {
        super.setUp()
        val sdk = LegSdk.setUp(module, testRootDisposable)
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
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

    fun testCold() {
        val setup = corpus.setUp(module)
        val modules = (corpus.appFiles + corpus.depFiles).flatMap(::definedNames).distinct()
        val exports = mutableListOf<Long>()
        val expansions = mutableListOf<Long>()
        val byFile = mutableMapOf<String, MutableList<Long>>()
        var expanding: ProjectModules? = null

        repeat(REPETITIONS) {
            val asking = ProjectModules(project)
            val asker = corpus.psi(File(corpus.dir, ASKER))!!
            val inputs = inputs(asker)
            corpus.read {
                for (module in modules) exports += timed { asking.exports(asker, module, inputs) }
            }
            dropPsi()

            val holder = ProjectModules(project).also { expanding = it }
            corpus.read {
                for (file in corpus.appFiles) {
                    val psi = corpus.psi(file) ?: continue
                    val nanos = timed { holder.expansion(psi, inputs) }
                    expansions += nanos
                    byFile.getOrPut(file.relativeTo(corpus.dir).path) { mutableListOf() } += nanos
                }
            }
            dropPsi()
        }

        // The last run's expanding holder still holds its clause cache, which emptying it lets go of.
        val withCache = usedAfterGc()
        expanding!!.boundClauses(null)
        val cache = withCache - usedAfterGc()
        Reference.reachabilityFence(expanding)

        val rows = listOf(
            Row("exports(M), cold, per call", exports, EXPORTS_BOUND),
            Row("expansion, cold, per app file", expansions, EXPANSION_BOUND),
        )
        File(report, "cold.txt").writeText(
            machine(setup, modules.size) +
                "measure\tsamples\tmedian\tp95\tbound (p95)\n" +
                rows.joinToString("") { "${it.measure}\t${it.nanos.size}\t${time(median(it.nanos))}\t${time(p95(it.nanos))}\t${time(it.bound)}\n" } +
                "clause cache, retained after the runs\t${mb(cache)}\tbound ${mb(CACHE_BOUND)}\n"
        )

        File(report, "cold-files.txt").writeText(
            "app file\tmedian\tmax\n" +
                byFile.entries.sortedByDescending { median(it.value) }
                    .joinToString("") { "${it.key}\t${time(median(it.value))}\t${time(it.value.max())}\n" }
        )

        val over = rows.filter { p95(it.nanos) > it.bound }.map { "${it.measure} p95 ${time(p95(it.nanos))} > ${time(it.bound)}" } +
            listOfNotNull("clause cache ${mb(cache)} > ${mb(CACHE_BOUND)}".takeIf { cache > CACHE_BOUND })
        assertTrue(over.joinToString("; "), over.isEmpty())
    }

    /** The module names [file]'s `defmodule`s give, as atoms. */
    private fun definedNames(file: File): List<String> =
        DEFMODULE.findAll(file.readText()).map { "Elixir.${it.groupValues[1]}" }.toList()

    private fun inputs(file: PsiFile): ProjectModules.Inputs =
        (corpus.read { SdkInputs.getInstance(project).inputsFor(file) } as SdkInputs.Answer.Ready).inputs

    /** Drops every file's PSI, so the next run reads each file again and nothing a holder kept on it remains. */
    private fun dropPsi() {
        WriteAction.runAndWait<Throwable> { PsiManagerEx.getInstanceEx(project).fileManager.cleanupForNextTest() }
    }

    private fun machine(setup: String, modules: Int): String =
        buildString {
            appendLine("cpus: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("xmx: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            appendLine("ide build: ${ApplicationInfo.getInstance().build}")
            appendLine("leg: ${environment("ELIXIR_VERSION")}")
            appendLine("setup: $setup")
            appendLine("repetitions: $REPETITIONS")
            appendLine("module names: $modules; app files: ${corpus.appFiles.size}")
            appendLine("lowering errors: ${corpus.loweringErrors.values.sum()}")
        }

    private class Row(val measure: String, val nanos: List<Long>, val bound: Long)

    private companion object {
        const val REPETITIONS = 5
        const val ASKER = "lib/demo.ex"

        /** Each a p95 over the five runs. An app file's expansion now evaluates the macro bodies its `use`s reach. */
        const val EXPORTS_BOUND = 150_000_000L
        const val EXPANSION_BOUND = 200_000_000L

        /** The clause cache's own bound. */
        const val CACHE_BOUND = 64L shl 20

        val DEFMODULE = Regex("""(?m)^\s*defmodule\s+([A-Z][A-Za-z0-9_.]*)""")

        inline fun timed(body: () -> Unit): Long {
            val start = System.nanoTime()
            body()

            return System.nanoTime() - start
        }

        fun median(values: List<Long>): Long = values.sorted()[(values.size - 1) / 2]

        /** By nearest rank. */
        fun p95(values: List<Long>): Long = values.sorted()[ceil(0.95 * values.size).toInt() - 1]

        fun time(nanos: Long): String = "%.2f ms".format(nanos / 1e6)

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
