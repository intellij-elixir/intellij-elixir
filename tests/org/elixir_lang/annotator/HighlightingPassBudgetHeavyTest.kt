package org.elixir_lang.annotator

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.replaceService
import com.intellij.util.SystemProperties
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.expander.PhoenixCorpus
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.dropPsi
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.model.psi.config.disableMixDepsCheck
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
// Over this package's annotator `Map`.
import kotlin.collections.Map
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * The highlighting pass over each app file of the Phoenix app `-PexpanderPhoenix=<dir>` (`org.elixir_lang.expander.phoenix`)
 * names, with the Experimental expander setting off (the walk) and on (highlighting on the expander), on the same files
 * in one JVM. [testBudget] holds the setting-on passes to their budget against the walk's, and a file with a pass over it
 * is measured again, walk and row, failing only if that pass is over again; [testSteps] writes where each
 * pass's time goes and asserts nothing. Both write to `build/reports/highlighting-budget/<elixir version>/`.
 *
 * It runs only when the corpus is set.
 */
class HighlightingPassBudgetHeavyTest : HeavyTestCase() {
    private lateinit var corpus: PhoenixCorpus
    private var savedSource: String? = null
    private var holder = Disposer.newDisposable()
    private var created = 0
    private val report by lazy { File("build/reports/highlighting-budget/${environment("ELIXIR_VERSION")}").apply { mkdirs() } }

    override fun shouldRunTest(): Boolean {
        if (System.getProperty(PhoenixCorpus.PROPERTY) == null) {
            println(
                "${javaClass.simpleName}.$name skipped: ${PhoenixCorpus.PROPERTY} is not set; use -PexpanderPhoenix=<dir> " +
                    "with Gradle or -D${PhoenixCorpus.PROPERTY}=<dir> as an IDE VM option",
            )
            return false
        }

        return super.shouldRunTest()
    }

    override fun setUp() {
        savedSource = SystemProperties.setProperty(SOURCE_PROPERTY, null)
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        disableExpander(testRootDisposable)
        val sdk = LegSdk.setUp(module, testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
        val dir = File(System.getProperty(PhoenixCorpus.PROPERTY)!!)
        // A run stopped mid-pass leaves its created file in the app.
        deleteStaleProbes(File(dir, "lib"))
        corpus = PhoenixCorpus(project, testRootDisposable, dir)
        corpus.setUp(module)
    }

    override fun tearDown() {
        try {
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            Disposer.dispose(holder)
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            try {
                super.tearDown()
            } finally {
                SystemProperties.setProperty(SOURCE_PROPERTY, savedSource)
            }
        }
    }

    fun testBudget() {
        val lines = appPaths().associateWith { File(corpus.dir, it).readLines().size }
        val budgets = { paths: List<String> ->
            passes(REPETITIONS, sampler = null, paths).map { (key, times) ->
                Budget(key.first, key.second, lines.getValue(key.first), times)
            }
        }
        val rows = budgets(lines.keys.toList())
        val again = rows.filter { it.over }.map { it.path }.distinct()
            .flatMap { budgets(listOf(it)) }
            .associateBy { it.path to it.kind }
        val confirmation = { row: Budget -> again[row.path to row.kind]?.takeIf { row.over } }
        File(report, "budget.tsv").writeText(
            "file\tlines\tpass\twalk\trow\tlimit\tover\tagain walk\tagain row\tagain limit\tagain over\n" +
                rows.joinToString("") { it.line(confirmation(it)) },
        )

        assertEmpty(rows.mapNotNull { row -> confirmation(row)?.takeIf { it.over }?.let { row.line(it).trim() } })
    }

    fun testSteps() {
        val sampler = Sampler()
        passes(STEP_REPETITIONS, sampler)
        File(report, "steps.tsv").writeText(
            "file\tpass\tconfiguration\tsamples\tstep\tshare\n" +
                sampler.shares.entries.joinToString("") { (key, shares) ->
                    val total = shares.samples
                    shares.steps.entries.sortedByDescending { it.value }.take(STEPS_SHOWN)
                        .joinToString("") { (step, n) -> "$key\t$total\t$step\t${"%.0f".format(100.0 * n / total)}%\n" }
                },
        )
    }

    /** The app's own files survive the clean-up of a stopped run's probe, even one named or written like a probe. */
    fun testTheCleanUpDeletesOnlyAProbe() {
        val lib = createTempDir("lib")
        val own = File(lib, "${PROBE_PREFIX}controller.ex")
        val lookalike = File(lib, "${PROBE_PREFIX}1.ex")
        val stale = File(lib, "${PROBE_PREFIX}2.ex")
        own.writeText("defmodule HighlightingBudgetController do\nend\n")
        lookalike.writeText("defmodule Other do\nend\n")
        stale.writeText(probe(2))

        deleteStaleProbes(lib)

        assertEquals(listOf(true, true, false), listOf(own, lookalike, stale).map(File::exists))
    }

    /** A probe never takes the name of a file already in the app, so deleting it after its pass deletes only the probe. */
    fun testAProbeLeavesTheAppsOwnFiles() {
        val lib = createTempDir("lib")
        val text = "defmodule Other do\nend\n"
        val own = File(lib, "${PROBE_PREFIX}1.ex").apply { writeText(text) }

        VfsTestUtil.deleteFile(createElsewhere(vf(lib)))

        assertEquals(text, own.takeIf(File::exists)?.readText())
    }

    private enum class Kind { FIRST, AGAIN, EDIT, CREATED }

    private enum class Configuration { WALK, ROW }

    private class Times(val walk: MutableList<Double> = mutableListOf(), val row: MutableList<Double> = mutableListOf()) {
        fun of(configuration: Configuration): MutableList<Double> = if (configuration == Configuration.WALK) walk else row
    }

    private class Budget(val path: String, val kind: Kind, val lines: Int, times: Times) {
        private val large = lines >= LARGE_LINES
        private val statistic: (List<Double>) -> Double = if (kind == Kind.FIRST || kind == Kind.AGAIN) ::median else ::p95
        private val walk = statistic(times.walk)
        private val row = statistic(times.row)
        private val limit = when (kind) {
            Kind.FIRST -> walk + if (large) 1_800 else 1_000
            Kind.AGAIN -> walk * 1.5
            Kind.EDIT, Kind.CREATED -> if (large) walk * 1.7 else walk + 150
        }

        val over = row > limit

        /** This row's figures, then [confirmation]'s beside them when the row was measured again. */
        fun line(confirmation: Budget?): String =
            (listOf(path, lines, kind.name.lowercase()) + figures() + confirmation?.figures().orEmpty()).joinToString("\t") + "\n"

        private fun figures(): List<Any> = listOf("%.1f".format(walk), "%.1f".format(row), "%.1f".format(limit), over)
    }

    private fun appPaths(): List<String> = corpus.appFiles.map { it.relativeTo(corpus.dir).path }

    /** Each of [paths]' passes of each kind, [repetitions] times, the walk's and the setting-on passes taken in turn. */
    private fun passes(repetitions: Int, sampler: Sampler?, paths: List<String> = appPaths()): Map<Pair<String, Kind>, Times> {
        val times = mutableMapOf<Pair<String, Kind>, Times>()
        val settings = ElixirExperimentalSettings.instance.state

        repeat(repetitions) {
            for (path in paths) {
                for (configuration in Configuration.entries) {
                    settings.enableExpander = configuration == Configuration.ROW
                    filePasses(path) { kind, pass ->
                        val nanos = measureNanoTime {
                            if (sampler == null) {
                                pass()
                            } else {
                                sampler.sampling("$path\t${kind.name.lowercase()}\t${configuration.name.lowercase()}", pass)
                            }
                        }
                        times.getOrPut(path to kind) { Times() }.of(configuration) += nanos / 1e6
                    }
                }
            }
        }

        return times
    }

    /** [path] opened on a new expansion holder and new PSI, then each [Kind] of pass, each given to [measure]. */
    private fun filePasses(path: String, measure: (Kind, () -> Unit) -> Unit) {
        freshHolder()
        val (psi, editor) = open(path)
        val pass = { pass(psi, editor) }

        measure(Kind.FIRST, pass)
        DaemonCodeAnalyzer.getInstance(project).restart("the highlighting budget")
        measure(Kind.AGAIN, pass)
        val undo = editBody(editor.document)
        try {
            measure(Kind.EDIT, pass)
        } finally {
            undo()
        }
        val probe = createElsewhere()
        try {
            measure(Kind.CREATED, pass)
        } finally {
            VfsTestUtil.deleteFile(probe)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun pass(psi: PsiFile, editor: Editor) {
        corpus.quiet { CodeInsightTestFixtureImpl.instantiateAndRun(psi, editor, intArrayOf(), true) }
    }

    /** A new expansion holder behind its service, and new PSI, so a first pass expands what it reads again. */
    private fun freshHolder() {
        FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        Disposer.dispose(holder)
        holder = Disposer.newDisposable()
        dropPsi(project)
        project.replaceService(ProjectModules::class.java, ProjectModules(project), holder)
    }

    private fun open(path: String): Pair<PsiFile, Editor> {
        val file = vf(File(corpus.dir, path))
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!
        val psi = ReadAction.computeBlocking<PsiFile, Throwable> { PsiManager.getInstance(project).findFile(file)!! }

        return psi to editor
    }

    /**
     * A call inserted at the start of the first `def`'s body, else of the module, so the pass has something the
     * annotator asks about to read again; returns what takes it out.
     */
    private fun editBody(document: Document): () -> Unit {
        val text = document.charsSequence
        val at = Regex("\\n\\s*defp? [^\\n]* do\\n").find(text) ?: Regex("defmodule [^\\n]* do\\n").find(text)
        val offset = at?.let { it.range.last + 1 } ?: 0
        write { document.insertString(offset, EDIT) }

        return { write { document.deleteString(offset, offset + EDIT.length) } }
    }

    private fun write(body: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) {
            body()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    /** A file of the app's own is never written over: the pass's `finally` deletes the probe. */
    private fun createElsewhere(lib: VirtualFile = vf(File(corpus.dir, "lib"))): VirtualFile {
        val n = generateSequence(created + 1, Int::inc).first { lib.findChild("$PROBE_PREFIX$it.ex") == null }
        created = n

        return VfsTestUtil.createFile(lib, "$PROBE_PREFIX$n.ex", probe(n))
            .also { IndexingTestUtil.waitUntilIndexesAreReady(project) }
    }

    /** Only a file named and written as [createElsewhere]'s: the app is the developer's own directory, not a copy. */
    private fun deleteStaleProbes(lib: File) {
        lib.listFiles()?.forEach { file ->
            val n = PROBE_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && file.readText() == probe(n)) file.delete()
        }
    }

    private fun vf(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!

    /**
     * Samples every thread's stack while a pass runs, and counts each `org.elixir_lang` method once a sample it is on,
     * so a step's share is the share of samples it was on a stack in.
     */
    private class Sampler {
        class Shares {
            var samples = 0
            val steps = mutableMapOf<String, Int>()
        }

        val shares = sortedMapOf<String, Shares>()

        fun sampling(key: String, body: () -> Unit) {
            val counts = shares.getOrPut(key) { Shares() }
            val executor = Executors.newSingleThreadScheduledExecutor()
            executor.scheduleAtFixedRate({ sample(counts) }, 0, SAMPLE_MILLIS, TimeUnit.MILLISECONDS)

            try {
                body()
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        private fun sample(counts: Shares) {
            val steps = Thread.getAllStackTraces().values.flatMapTo(mutableSetOf()) { stack ->
                stack.filter { it.className.startsWith(PACKAGE) && !it.className.startsWith(OWN) }
                    .map { "${it.className.removePrefix(PACKAGE)}.${it.methodName}" }
            }
            synchronized(counts) {
                counts.samples++
                steps.forEach { counts.steps.merge(it, 1, Int::plus) }
            }
        }
    }

    companion object {
        private const val REPETITIONS = 20
        private const val STEP_REPETITIONS = 3
        private const val LARGE_LINES = 1_000
        private const val EDIT = "IO.inspect(:highlighting_budget)\n"
        private const val PROBE_PREFIX = "highlighting_budget_"
        private val PROBE_NAME = Regex("$PROBE_PREFIX(\\d+)\\.ex")
        private const val SAMPLE_MILLIS = 5L
        private const val STEPS_SHOWN = 12
        private const val PACKAGE = "org.elixir_lang."
        private val OWN = HighlightingPassBudgetHeavyTest::class.java.name

        /** The text of the [n]th file [createElsewhere] writes. */
        private fun probe(n: Int): String = "defmodule HighlightingBudget$n do\nend\n"

        private fun median(values: List<Double>): Double = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }

        private fun p95(values: List<Double>): Double = values.sorted()[ceil(values.size * 0.95).toInt() - 1]
    }
}
