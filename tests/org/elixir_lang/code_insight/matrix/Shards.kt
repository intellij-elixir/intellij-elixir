package org.elixir_lang.code_insight.matrix

/**
 * Splits the matrix's scenarios between the shard classes `testFullMatrix` runs in parallel forks: Gradle hands a
 * fork whole test classes, so one class could never be split.
 *
 * Scenarios are dealt longest first to the lightest shard, by their times in [WEIGHTS], so the assignment depends on
 * that file and the oracle alone, never on the machine or on `MATRIX_ONLY`. [WEIGHTS] is regenerated from a full
 * run's archived JUnit XML with:
 *
 * ```sh
 * python3 -c 'import gzip,re,sys,collections,xml.etree.ElementTree as E;t=collections.Counter();[t.update({re.match(r"\w+\[([^,]+,[^,]+,[^,]+),",c.get("name"))[1]:float(c.get("time"))}) for _,c in E.iterparse(gzip.open(sys.argv[1])) if c.tag=="testcase"];print("\n".join(f"{k}\t{round(v*1000)}" for k,v in sorted(t.items())))' RUN.xml.gz > scenario-weights.tsv
 * ```
 *
 * A scenario it does not list yet is weighed by its cell count instead, so a new scenario only unbalances the shards
 * until the file is regenerated.
 */
object Shards {
    /** How many shard classes exist, and so the most `-PmatrixShards` can ask for; `build.gradle.kts` repeats it. */
    const val MAX = 12

    /** The shard count this JVM was forked for: 1, every scenario in shard 0, when run outside `testFullMatrix`. */
    val count: Int get() = System.getProperty("elixir.matrix.shards")?.toIntOrNull() ?: 1

    /** Every scenario that asks behavioural questions, in the oracle's order. */
    val scenarios: List<Scenario> get() = Fixtures.oracle.scenarios.filterNot { Backing.of(it).guardOnly }

    /** Scenario name to milliseconds in the last full run. */
    private val WEIGHTS: Map<String, Long> by lazy {
        Fixtures.file("scenario-weights.tsv").readLines().filter(String::isNotBlank).associate { line ->
            val (name, milliseconds) = line.split('\t')
            name to milliseconds.toLong()
        }
    }

    /** The mean time of a cell in the run [WEIGHTS] came from. */
    private const val MILLISECONDS_PER_CELL = 7L

    /** [scenarios] dealt into [shards] shards, each keeping the oracle's order. */
    fun of(shards: Int, scenarios: List<Scenario> = this.scenarios): List<List<Scenario>> {
        require(shards in 1..MAX) { "the matrix has $MAX shard classes, so it cannot run in $shards shards" }
        val loads = LongArray(shards)
        val dealt = List(shards) { mutableListOf<IndexedValue<Scenario>>() }

        scenarios.withIndex()
            .map { it to weight(it.value) }
            .sortedWith(compareByDescending<Pair<IndexedValue<Scenario>, Long>> { it.second }.thenBy { it.first.index })
            .forEach { (scenario, weight) ->
                val lightest = loads.indices.minBy { loads[it] }
                dealt[lightest] += scenario
                loads[lightest] += weight
            }

        return dealt.map { shard -> shard.sortedBy { it.index }.map { it.value } }
    }

    fun name(scenario: Scenario): String = "${scenario.backing},${scenario.form},${scenario.world}"

    private fun weight(scenario: Scenario): Long =
        WEIGHTS[name(scenario)] ?: (MILLISECONDS_PER_CELL * Crossing.places(scenario).sumOf { place ->
            Feature.entries.count { Crossing.applicability(scenario, it, place) is Applicability.Applicable }
        })
}
