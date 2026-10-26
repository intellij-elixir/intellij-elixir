package org.elixir_lang.snapshot

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import org.elixir_lang.coverage.FactsReport
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.golden.CommittedGolden
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.elixir_lang.snapshot.CompilerFacts.Fact
import java.io.File

/**
 * Fails when what the plugin answers at the compiler's facts about Elixir's own tests differs from the join committed
 * for the leg. The facts are split over [SHARDS] classes, a `ref`, `alias` or `diag` fact by its file and a `def` fact
 * or a declaration by its module, so parallel test JVMs share the corpus between them.
 *
 * Each line of the leg's goldens is a test of its own, checked against one join the shard's tests share; the lines a
 * golden lacks and its layout are tests too, so every test passes exactly when each golden matches.
 */
abstract class CompilerCorpusJoinTestCase internal constructor(
    private val shard: Int,
    private val fixture: SharedFixture<CompilerCorpusJoinTestCase>?,
    private val case: Case?,
) : SnapshotTestCase(), SharedFixtureHost<CompilerCorpusJoinTestCase> {
    private var joined: Result<Join>? = null

    init {
        name = case?.name ?: "shared fixture"
        fixture?.add(this)
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    override fun setUp() {
        super.setUp()

        // The corpus reaches cycles the plugin's recursion guards stop. Production sets neither flag, so there the
        // stopped resolve answers with what the guard returns, and that answer is what the join measures.
        RecursionManager.disableMissedCacheAssertions(testRootDisposable)
        RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare(serve)
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        SharedFixture.runCase(fixture, this) { super.runBare(testRunnable) }
    }

    override fun check(case: CompilerCorpusJoinTestCase) {
        val slice = case.case!!

        try {
            slice.check(::joinOnce)
        } catch (failure: AssertionError) {
            // Said once, on the first of a golden's tests to fail.
            val unreported = slice.golden?.let { joined?.getOrNull()?.unreportedSummaryFailures?.remove(it) }
            unreported?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** The join, made by the first test that needs it; a later test fails for the reason it failed. */
    private fun joinOnce(): Join =
        joined?.getOrElse { throw AssertionError("Not joined: the join failed in an earlier test", it) }
            ?: runCatching { join() }.also { joined = it }.getOrThrow()

    private fun join(): Join {
        val elixirVersion = environment("ELIXIR_VERSION")
        val started = System.nanoTime()
        val root = copySources()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val parsed = CompilerCorpus.facts.values.flatMap { lines ->
            lines.drop(1).map { CompilerFacts.parse(it)!! to it }
        }
        val compiled = parsed.mapNotNull { (fact, _) -> path(fact) }.toSet()
        val sharded = parsed.filter { (fact, _) -> inShard(fact) }
        val facts = sharded.map { it.second }
        val copied = System.nanoTime()
        val joined = ReadAction.computeBlocking<List<CompilerJoin.Joined>, Throwable> {
            CompilerJoin(project, root, elixirVersion) { module, path -> path in compiled && inShard(module) }
                .join(facts)
        }
        val done = System.nanoTime()
        println(
            "compiler corpus shard $shard of $SHARDS on $elixirVersion: ${facts.size} facts, " +
                "generate and copy ${(copied - started) / NANOS_PER_MILLI} ms, " +
                "join ${(done - copied) / NANOS_PER_MILLI} ms"
        )

        val (grouped, report) = goldens(shard)

        return Join(
            counted(sharded),
            joined.groupingBy { it.kind }.eachCount().filterKeys { it != "declaration" },
            mapOf(
                grouped to CompilerJoin.grouped(joined).joinToString("\n", postfix = "\n"),
                report to FactsReport.report(elixirVersion, joined, shard),
            ),
            elixirVersion,
            LegManifest.regenerateCommand(this::class, elixirVersion, environment("ERLANG_VERSION")),
        )
    }

    /** Copies each application's test directory into the project, under the application's name. */
    private fun copySources(): VirtualFile =
        CompilerCorpus.APPLICATIONS.map { (application, testDirectory) ->
            myFixture.tempDirFixture.copyAll(File(CompilerCorpus.tree, testDirectory).path, application, SOURCES)
        }.first().parent

    /** Each `ref` and `alias` position once, and each `def` fact once, by kind. */
    private fun counted(facts: List<Pair<Fact, String>>): Map<String, Int> =
        facts.mapNotNull { (fact, line) ->
            when (fact) {
                is Fact.Ref -> "ref" to fact.position
                is Fact.Alias -> "alias" to fact.position
                is Fact.Def -> "def" to line
                is Fact.Diag -> null
            }
        }.distinct().groupingBy { it.first }.eachCount()

    private fun inShard(fact: Fact): Boolean = inShard(if (fact is Fact.Def) fact.module else path(fact)!!)

    /** The file [fact] is in: a `def` fact names none. */
    private fun path(fact: Fact): String? =
        when (fact) {
            is Fact.Ref -> fact.position.path
            is Fact.Alias -> fact.position.path
            is Fact.Diag -> fact.path
            is Fact.Def -> null
        }

    private fun inShard(key: String): Boolean = Math.floorMod(key.hashCode(), SHARDS) == shard

    /** What the shard's tests check, measured once. */
    internal class Join(
        val expectedCounts: Map<String, Int>,
        val counts: Map<String, Int>,
        val texts: Map<Golden, String>,
        val elixirVersion: String,
        val regenerate: String,
    ) {
        val rows: Map<Golden, Map<String, String>> = texts.mapValues { (golden, text) -> golden.rows(text) }

        /** Why a split golden's diff could not be appended to the step summary. */
        val unreportedSummaryFailures: MutableMap<Golden, Throwable> =
            texts.filterKeys { it.split(elixirVersion) }
                .mapNotNull { (golden, text) ->
                    golden.appendDiffToStepSummary(elixirVersion, text, regenerate)?.let { golden to it }
                }
                .toMap(mutableMapOf())
    }

    /** A committed golden whose rows are the lines its renderer's [key] names; the lines it names none of are its header. */
    internal class Golden(
        private val root: String,
        val name: String,
        private val key: (String) -> String?,
    ) {
        /** Whether a test per row checks this golden, rather than one test for the whole, which also writes it. */
        fun split(elixirVersion: String): Boolean = !OVERWRITE_TESTDATA && File(path(elixirVersion)).isFile

        /** The committed rows, each by its key. */
        fun committedRows(elixirVersion: String): Map<String, String> = rows(committed(elixirVersion))

        fun rows(text: String): Map<String, String> {
            val rows = lines(text).mapNotNull { line -> key(line)?.let { it to line } }
            val shared = rows.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }

            check(shared.isEmpty()) { "$name has rows that share a key: ${shared.values.flatten()}" }

            return rows.toMap()
        }

        fun assertMatches(elixirVersion: String, actual: String, regenerate: String) {
            LegManifest.assertMatchesFile(root, elixirVersion, name, actual, regenerate)
        }

        fun assertSameLayout(elixirVersion: String, actual: String, actualRows: Map<String, String>) {
            val keys = committedRows(elixirVersion).keys.intersect(actualRows.keys)

            assertEquals(
                "${path(elixirVersion)}'s header, the order of the rows both have, and its final newline",
                layout(committed(elixirVersion), keys),
                layout(actual, keys),
            )
        }

        /** Each header line, and the key of each row [keys] holds, in order, then whether a newline ends [text]. */
        private fun layout(text: String, keys: Set<String>): String =
            (
                lines(text).mapNotNull { line ->
                    when (val key = key(line)) {
                        null -> line
                        in keys -> key
                        else -> null
                    }
                } + listOfNotNull(NO_FINAL_NEWLINE.takeUnless { text.endsWith("\n") })
            ).joinToString("\n")

        /** Appends the whole diff to the CI step summary when [actual] differs, as a whole-golden comparison does. */
        fun appendDiffToStepSummary(elixirVersion: String, actual: String, regenerate: String): Throwable? {
            val path = path(elixirVersion)
            val expected = committed(elixirVersion)

            return if (expected == actual) {
                null
            } else {
                CommittedGolden.appendToStepSummary("`$path` differs", path, expected, path, actual, regenerate)
            }
        }

        fun path(elixirVersion: String): String = LegManifest.path(root, elixirVersion, name)

        private fun committed(elixirVersion: String): String =
            StringUtil.convertLineSeparators(File(path(elixirVersion)).readText())

        private fun lines(text: String): List<String> = text.removeSuffix("\n").split('\n')
    }

    /** One test's slice of the shard's checks. */
    internal sealed class Case(val name: String, val golden: Golden?) {
        abstract fun check(join: () -> Join)

        class Counts : Case("joined once for each position or def fact", null) {
            override fun check(join: () -> Join) {
                val measured = join()

                assertEquals(
                    "Joined, by kind, other than once for each position or def fact",
                    measured.expectedCounts,
                    measured.counts,
                )
            }
        }

        /** The corpus is not measured on this OS, so there is nothing to check. */
        class NotMeasured : Case("not measured on this OS", null) {
            override fun check(join: () -> Join) {}
        }

        /** The join cannot be made here, which fails for [why]. */
        class Unjoined(private val why: AssertionError) : Case("joined", null) {
            override fun check(join: () -> Join): Unit = throw why
        }

        class Row(golden: Golden, private val key: String, private val expected: String) :
            Case("${golden.name}: ${key.replace("\t", " | ")}", golden) {
            override fun check(join: () -> Join) {
                val measured = join()

                assertEquals(
                    "${golden!!.path(measured.elixirVersion)} differs at this line. Review it, then regenerate with " +
                        measured.regenerate,
                    expected,
                    measured.rows.getValue(golden)[key] ?: "(no line)",
                )
            }
        }

        class Lacking(golden: Golden) : Case("${golden.name}: lines it lacks", golden) {
            override fun check(join: () -> Join) {
                val measured = join()
                val committed = golden!!.committedRows(measured.elixirVersion)

                assertEquals(
                    "Lines the join has and ${golden.path(measured.elixirVersion)} lacks. Review them, then " +
                        "regenerate with ${measured.regenerate}",
                    "",
                    measured.rows.getValue(golden).filterKeys { it !in committed }.values.joinToString("\n"),
                )
            }
        }

        class Layout(golden: Golden) : Case("${golden.name}: header and order", golden) {
            override fun check(join: () -> Join) {
                val measured = join()

                golden!!.assertSameLayout(
                    measured.elixirVersion,
                    measured.texts.getValue(golden),
                    measured.rows.getValue(golden),
                )
            }
        }

        /** The golden as a whole, where there is none to split or it is being regenerated. */
        class Whole(golden: Golden) : Case(golden.name, golden) {
            override fun check(join: () -> Join) {
                val measured = join()

                golden!!.assertMatches(measured.elixirVersion, measured.texts.getValue(golden), measured.regenerate)
            }
        }
    }

    companion object {
        const val SHARDS = 12
        private const val NANOS_PER_MILLI = 1_000_000
        private const val NO_FINAL_NEWLINE = "(no newline at the end)"
        private val SOURCES = VirtualFileFilter { it.isDirectory || it.extension in setOf("ex", "exs") }

        /** Each shard's grouped join and report: the same instances for its tests and its join, which key on them. */
        private val GOLDENS: List<List<Golden>> =
            (0 until SHARDS).map { shard ->
                listOf(
                    Golden(CompilerFacts.ROOT, "corpus-$shard.join.txt", CompilerJoin::groupedKey),
                    Golden(FactsReport.ROOT, "corpus-$shard.report", FactsReport::key),
                )
            }

        private fun goldens(shard: Int): List<Golden> = GOLDENS[shard]

        @JvmStatic
        internal fun suite(
            shard: Int,
            test: Class<out CompilerCorpusJoinTestCase>,
            new: (SharedFixture<CompilerCorpusJoinTestCase>?, Case?) -> CompilerCorpusJoinTestCase,
        ): Test {
            val fixture = SharedFixture { new(null, null) }
            val suite = fixture.suite(test.name)

            if (!CompilerCorpus.measured) {
                suite.addTest(new(fixture, Case.NotMeasured()))
                return suite
            }

            val elixirVersion = try {
                environment("ELIXIR_VERSION")
            } catch (e: AssertionError) {
                suite.addTest(new(fixture, Case.Unjoined(e)))
                return suite
            }

            val cases = buildList {
                add(Case.Counts())
                for (golden in goldens(shard)) {
                    if (golden.split(elixirVersion)) {
                        for ((key, row) in golden.committedRows(elixirVersion)) add(Case.Row(golden, key, row))
                        add(Case.Lacking(golden))
                        add(Case.Layout(golden))
                    } else {
                        add(Case.Whole(golden))
                    }
                }
            }
            for (case in cases) suite.addTest(new(fixture, case))

            return suite
        }
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard00Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(0, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(0, CompilerCorpusJoinShard00Test::class.java, ::CompilerCorpusJoinShard00Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard01Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(1, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(1, CompilerCorpusJoinShard01Test::class.java, ::CompilerCorpusJoinShard01Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard02Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(2, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(2, CompilerCorpusJoinShard02Test::class.java, ::CompilerCorpusJoinShard02Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard03Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(3, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(3, CompilerCorpusJoinShard03Test::class.java, ::CompilerCorpusJoinShard03Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard04Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(4, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(4, CompilerCorpusJoinShard04Test::class.java, ::CompilerCorpusJoinShard04Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard05Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(5, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(5, CompilerCorpusJoinShard05Test::class.java, ::CompilerCorpusJoinShard05Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard06Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(6, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(6, CompilerCorpusJoinShard06Test::class.java, ::CompilerCorpusJoinShard06Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard07Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(7, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(7, CompilerCorpusJoinShard07Test::class.java, ::CompilerCorpusJoinShard07Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard08Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(8, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(8, CompilerCorpusJoinShard08Test::class.java, ::CompilerCorpusJoinShard08Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard09Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(9, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(9, CompilerCorpusJoinShard09Test::class.java, ::CompilerCorpusJoinShard09Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard10Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(10, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(10, CompilerCorpusJoinShard10Test::class.java, ::CompilerCorpusJoinShard10Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CompilerCorpusJoinShard11Test private constructor(fixture: SharedFixture<CompilerCorpusJoinTestCase>?, case: Case?) :
    CompilerCorpusJoinTestCase(11, fixture, case) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(11, CompilerCorpusJoinShard11Test::class.java, ::CompilerCorpusJoinShard11Test)
    }
}
