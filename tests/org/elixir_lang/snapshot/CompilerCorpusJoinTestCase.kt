package org.elixir_lang.snapshot

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.snapshot.CompilerFacts.Fact
import java.io.File

/**
 * Fails when what the plugin answers at the compiler's facts about Elixir's own tests differs from the join committed
 * for the leg. The facts are split over [SHARDS] classes, a `ref`, `alias` or `diag` fact by its file and a `def` fact
 * or a declaration by its module, so parallel test JVMs share the corpus between them.
 */
abstract class CompilerCorpusJoinTestCase(private val shard: Int) : SnapshotTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    override fun setUp() {
        super.setUp()

        // The corpus reaches cycles the plugin's recursion guards stop. Production sets neither flag, so there the
        // stopped resolve answers with what the guard returns, and that answer is what the join measures.
        RecursionManager.disableMissedCacheAssertions(testRootDisposable)
        RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
    }

    fun testJoinMatchesGolden() {
        if (!CompilerCorpus.measured) return

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

        assertEquals(
            "Joined, by kind, other than once for each position or def fact",
            counted(sharded),
            joined.groupingBy { it.kind }.eachCount().filterKeys { it != "declaration" }
        )

        LegManifest.assertMatchesFile(
            CompilerFacts.ROOT,
            elixirVersion,
            "corpus-$shard.join.txt",
            CompilerJoin.grouped(joined).joinToString("\n", postfix = "\n"),
            LegManifest.regenerateCommand(this::class, elixirVersion, environment("ERLANG_VERSION"))
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

    companion object {
        const val SHARDS = 4
        private const val NANOS_PER_MILLI = 1_000_000
        private val SOURCES = VirtualFileFilter { it.isDirectory || it.extension in setOf("ex", "exs") }
    }
}

class CompilerCorpusJoinShard0Test : CompilerCorpusJoinTestCase(0)

class CompilerCorpusJoinShard1Test : CompilerCorpusJoinTestCase(1)

class CompilerCorpusJoinShard2Test : CompilerCorpusJoinTestCase(2)

class CompilerCorpusJoinShard3Test : CompilerCorpusJoinTestCase(3)
