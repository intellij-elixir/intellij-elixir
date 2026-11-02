package org.elixir_lang.ui.framework.core

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.appendText
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * One test's guard, from [NoScreenExtension]. It samples every process carrying this run's sink variable, which a child
 * keeps after it is reparented, so it finds what a walk of the IDE's process tree would lose. After the test it reads the
 * sink, those processes, and each test home written during the test.
 */
class NoScreenGuard private constructor(private val record: Path, private val testsDirectory: Path) {
    private val sinkLine = "${NoScreen.SINK_LOG}=${NoScreen.sinkLog}"
    private val homeLine = "HOME=${NoScreen.privateHome}"
    private val seen = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val displays = ConcurrentHashMap.newKeySet<String>()
    private val forbidden = ConcurrentHashMap.newKeySet<String>()
    private val sinkBaseline = NoScreen.sinkEntries().size
    private val homesBefore = testHomes()
    private val self = ProcessHandle.current().pid()

    @Volatile
    private var peakRssKb = 0L

    @Volatile
    private var running = true

    init {
        stopLeftovers()
    }

    private val sampler = thread(isDaemon = true, name = "no-screen-sampler") {
        while (running) {
            runCatching { sample() }.onFailure { record.appendText("sampler: $it\n") }
            Thread.sleep(500)
        }
    }

    /** Starter puts each test home at `<testsDirectory>/<product>-<build>/<test name>`. */
    private fun testHomes(): Map<Path, FileTime> =
        if (!testsDirectory.isDirectory()) {
            emptyMap()
        } else {
            testsDirectory.listDirectoryEntries().filter { it.isDirectory() }
                .flatMap { product -> product.listDirectoryEntries().filter { it.isDirectory() } }
                .associateWith { it.getLastModifiedTime() }
        }

    /** Read from Linux's `/proc`: off Linux nothing is sampled. */
    private fun runProcesses(): List<Path> = if (!NoScreen.onLinux) emptyList() else Files.list(Path("/proc")).use { entries ->
        entries.asSequence()
            .filter { it.name.all(Char::isDigit) }
            .filter { proc -> environment(proc)?.contains(sinkLine) == true }
            .toList()
    }

    private fun environment(proc: Path): List<String>? =
        runCatching { String(Files.readAllBytes(proc.resolve("environ"))).split('\u0000') }.getOrNull()

    private fun sample() {
        val processes = runProcesses()
        processes.forEach { sampleProcess(it) }
        peakRssKb = maxOf(peakRssKb, processes.sumOf { rssKb(it) })
    }

    private fun rssKb(proc: Path): Long = runCatching {
        proc.resolve("status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
    }.getOrDefault(0L)

    private fun sampleProcess(proc: Path) {
        val variables = environment(proc) ?: return
        val argv = runCatching { Files.readAllBytes(proc.resolve("cmdline")) }.getOrNull()?.let(::String)?.split('\u0000') ?: return
        val comm = runCatching { proc.resolve("comm").readText().trim() }.getOrDefault("?")
        val command = argv.firstOrNull().orEmpty().substringAfterLast('/')
        val display = variables.firstOrNull { it.startsWith("DISPLAY=") }?.removePrefix("DISPLAY=")

        if (seen.add(comm to command)) {
            record.appendText("process ${Instant.now()} pid=${proc.name} comm=$comm display=$display argv=${argv.joinToString(" ").take(300)}\n")
        }
        if (display != null && displays.add(display)) record.appendText("display $display from pid=${proc.name} comm=$comm\n")
        // On screen the test JVM keeps `DISPLAY`, and so does the `jstack` Starter runs from it, with the real home.
        val harness = NoScreen.onScreen && (proc.name.toLong() == self || NoScreen.isStartersJstack(comm, argv.firstOrNull().orEmpty()))
        if (display != null && homeLine !in variables && !harness) forbidden += "$comm in another home"
        if (NoScreen.FORBIDDEN.any { comm.contains(it) || command.contains(it) }) forbidden += "$comm ($command)"
        if (!NoScreen.onScreen && (display == ":0" || variables.any { it.startsWith("WAYLAND_DISPLAY=") })) {
            record.appendText("STOP: pid=${proc.name} comm=$comm reached the real display\n")
            forbidden += "$comm on the real display"
            ProcessHandle.of(proc.name.toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    /**
     * The IDE autolaunches a session bus and accessibility services, which outlive it and can start after the last test's
     * guard stopped its leftovers. Left running, they hold up the next IDE's start.
     */
    private fun stopLeftovers() {
        val leftovers = runProcesses().filter { it.name.toLong() != self }.mapNotNull { proc ->
            val comm = runCatching { proc.resolve("comm").readText().trim() }.getOrDefault("?")
            record.appendText("leftover pid=${proc.name} comm=$comm\n")
            ProcessHandle.of(proc.name.toLong()).orElse(null)
        }
        stop(leftovers)
    }

    /** Homes Starter created or emptied during the test, which is what a context does to its home. */
    private fun homesWritten(): List<Path> = testHomes().filter { (home, modified) -> homesBefore[home] != modified }.keys.toList()

    fun verify() {
        running = false
        sampler.join(TimeUnit.SECONDS.toMillis(5))
        active = null
        stopLeftovers()

        val homes = homesWritten()
        // `system/` holds the indexes, and nothing license-related.
        val homeFiles = homes.flatMap { NoScreen.files(it, skipping = setOf("system")) }
        val keys = (homes.flatMap { NoScreen.keyFiles(it, skipping = setOf("system")) } + NoScreen.keyFiles(NoScreen.privateHome))
        val licenseLines = homeFiles.filter { it.name == "idea.log" }.flatMap { it.readLines() }
            .filter { line -> LICENSE_LINES.any { it in line } }
        val xvfbLogs = homeFiles.filter { it.name == "xvfb-log.txt" }
        val sink = NoScreen.sinkEntries().drop(sinkBaseline)
        record.appendText(
            "test homes written: $homes\nide displays: $displays\nxvfb logs: $xvfbLogs\nsink: $sink\nforbidden: $forbidden\n" +
                "key files: $keys\nlicense lines: $licenseLines\n" +
                "peak rss of the run's processes, test JVM included: $peakRssKb kB\n"
        )
        val problems = problems(Left(sink, forbidden, keys, licenseLines, homes, displays, xvfbLogs), NoScreen.onScreen)
        check(problems.isEmpty()) { "NO-SCREEN STOP: ${problems.joinToString("; ")}. Record: $record" }
    }

    /** What a test left: the sink's new entries, the processes sampled, and what its test homes hold. */
    internal class Left(
        val sink: List<String>,
        val forbidden: Set<String>,
        val keys: List<Path>,
        val licenseLines: List<String>,
        val homes: List<Path>,
        val displays: Set<String>,
        val xvfbLogs: List<Path>,
    )

    companion object {
        /** Asks each process to end, then kills any still running after a few seconds. */
        internal fun stop(processes: List<ProcessHandle>) {
            processes.forEach { it.destroy() }
            processes.forEach { process ->
                runCatching { process.onExit().get(5, TimeUnit.SECONDS) }.onFailure {
                    process.destroyForcibly()
                    process.onExit().get(5, TimeUnit.SECONDS)
                }
            }
        }

        /** Each way [left] shows a test reached a screen, a browser or a license. */
        internal fun problems(left: Left, onScreen: Boolean, linux: Boolean = NoScreen.onLinux): List<String> = buildList {
            if (left.sink.isNotEmpty()) add("something tried to open: ${left.sink}")
            if (left.forbidden.isNotEmpty()) add("forbidden processes: ${left.forbidden}")
            if (left.keys.isNotEmpty()) add("a key file: ${left.keys}")
            if (left.licenseLines.isNotEmpty()) add("a license reached the IDE: ${left.licenseLines}")
            if (left.homes.isNotEmpty()) {
                if (linux && left.displays.isEmpty()) add("no process carried a DISPLAY, so the IDE was not seen")
                if (!onScreen) {
                    if (":0" in left.displays) add("a process ran on :0")
                    left.homes.filter { home -> left.xvfbLogs.none { it.startsWith(home) } }.forEach { add("no xvfb-log.txt under $it") }
                }
            }
        }

        /** An account's license, and the automatic trial: none may reach a run, which is in free mode. */
        private val LICENSE_LINES =
            listOf("Found active license", "Activating a product license", "License activation performed", "Start trial performed")

        /** The guard of the test running now, which [IdeTestContext.setupTestContext] refuses to go without. */
        @Volatile
        var active: NoScreenGuard? = null
            private set

        /**
         * Runs in one checkout share its sink log and private home, and a guard stops every process carrying that sink, so
         * one test JVM at a time holds this lock, until it exits.
         */
        private val runLock: Pair<Path, FileLock?> by lazy {
            val path = NoScreen.sinkLog.resolveSibling("run.lock").createParentDirectories()
            path to FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock()
        }

        /** Starts sampling before the IDE exists, so its first processes are seen. */
        fun start(name: String, testsDirectory: Path): NoScreenGuard {
            NoScreen.check()
            val (lockPath, lock) = runLock
            checkNotNull(lock) { "another testUI run in this checkout holds $lockPath: start this one when it has finished" }
            NoScreen.emptyPrivateHome()
            val record = NoScreen.sinkLog.resolveSibling("runs/$name-${Instant.now().toString().replace(':', '-')}.txt")
            record.createParentDirectories()
            record.writeText("run $name at ${Instant.now()}\n")
            return NoScreenGuard(record, testsDirectory).also { active = it }
        }
    }
}
