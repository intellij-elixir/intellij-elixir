package testing

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import oshi.SystemInfo
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * How many test JVMs this machine can run at once: two cores stay free for Gradle, the quoter and the forks' own
 * threads, and each fork is budgeted [GIB_PER_FORK] of the memory available when `test` starts, after the Gradle and
 * Kotlin daemons hold theirs, not the machine's total, which on a workstation is mostly spoken for.
 */
object TestForks {
    /**
     * Six forks peaked between 1.5 and 3.2 GiB (a 2 GiB heap plus native memory), and not at the same time, so the
     * budget sits below the largest peak.
     */
    private const val GIB_PER_FORK = 2.5
    private const val GIB = 1024L * 1024 * 1024

    class Choice(val forks: Int, private val cores: Int, private val availableBytes: Long, private val capped: Boolean) {
        /** The measurements [forks] was computed from. */
        val basis: String
            get() = "$cores cores, ${"%.1f".format(Locale.ROOT, availableBytes.toDouble() / GIB)} GiB available" +
                if (capped) ", capped by testMaxForks" else ""
    }

    fun choose(limit: Int?): Choice {
        val cores = Runtime.getRuntime().availableProcessors()
        val available = availableMemoryBytes()
        val byMemory = (available / (GIB_PER_FORK * GIB)).toInt()
        val forks = max(1, min(cores - 2, byMemory))
        val capped = limit?.let { min(forks, it) } ?: forks

        return Choice(capped, cores, available, capped < forks)
    }

    fun parse(property: String, value: String): Int =
        value.toIntOrNull()?.takeIf { it >= 1 }
            ?: throw GradleException("$property must be a positive whole number, not '$value'")

    /** The host's available memory, or what the container's memory limit leaves, whichever is less. */
    private fun availableMemoryBytes(): Long =
        listOfNotNull(hostAvailableBytes(), cgroupRemainingBytes()).minOrNull() ?: 0

    /**
     * OSHI's available memory: `MemAvailable` on Linux, which counts reclaimable cache, the available pages on Windows,
     * and free plus inactive pages on macOS, where the JDK's free figure counts only free pages.
     */
    private fun hostAvailableBytes(): Long? = runCatching { SystemInfo().hardware.memory.available }.getOrNull()

    /** `MemAvailable` describes the host; a container's limit is in its cgroup (v2, then v1). */
    private fun cgroupRemainingBytes(): Long? =
        remaining("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current")
            ?: remaining("/sys/fs/cgroup/memory/memory.limit_in_bytes", "/sys/fs/cgroup/memory/memory.usage_in_bytes")

    private fun remaining(limitPath: String, usagePath: String): Long? {
        val limit = File(limitPath).takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: return null
        val usage = File(usagePath).takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: return null

        return max(0, limit - usage)
    }
}

/**
 * Sizes the forks when the task runs, as available memory differs build to build, at most [forkLimit] (`testMaxForks`);
 * [explicitForks] (`-PtestForks=N`) overrides both. Each fork is its own IDE: `org.elixir_lang.junit.ForkIsolation`
 * gives it its own sandbox directories and quoter client node when there is more than one. The count moves a leg's wall time by a whole fork's share, so it is
 * also appended to [stepSummary], where CI compares the timings.
 */
fun Test.runInForks(explicitForks: Provider<Int>, forkLimit: Provider<Int>, stepSummary: Provider<String>) {
    // Read while configuring, so a bad value also fails a run that is up to date or taken from the cache.
    val explicit = explicitForks.orNull
    val cap = forkLimit.orNull
    doFirst {
        val (forks, basis) = explicit?.let { it to "-PtestForks" } ?: TestForks.choose(cap).let { it.forks to it.basis }
        maxParallelForks = forks
        systemProperty("elixir.test.forks", forks)
        val line = "Running tests in $forks fork(s) ($basis)"
        logger.lifecycle(line)
        stepSummary.orNull?.let { File(it).appendText("$line\n") }
    }
}

/** Records which fork ran each class in [timeline], which every fork appends to, so it is emptied once per run. */
fun Test.recordTimeline(timeline: File) {
    jvmArgumentProviders.add(TimelineArgument(timeline))
    doFirst { timeline.delete() }
}

/** An output rather than a system property, so a cached run restores the timeline and its path is not in the key. */
class TimelineArgument(@get:OutputFile val timeline: File) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Delixir.test.timeline=${timeline.path}")
}
