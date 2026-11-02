package org.elixir_lang.ui.framework.core

import com.intellij.ide.starter.utils.Git
import com.intellij.util.system.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * Keeps a `testUI` run off the machine's real screen and out of any license. The `testUI` task sets the environment
 * this checks; [NoScreenExtension] checks it before each test and reads what the test left after it.
 */
object NoScreen {
    private val DISPLAY_VARIABLES = listOf("DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR")
    private val REMOVED = DISPLAY_VARIABLES + listOf("DBUS_SESSION_BUS_ADDRESS", "PULSE_SERVER")
    internal val FORBIDDEN = listOf("wslview", "msedge", "cmd.exe", "powershell.exe", "explorer.exe")
    internal const val SINK_LOG = "NO_SCREEN_SINK_LOG"

    /** `-PtestUiHeadless=false`: the display variables stay, so the IDE shows on the real screen. */
    val onScreen: Boolean
        get() = System.getProperty("testUi.headless") == "false"

    val sink: Path by lazy { Git.getRepoRoot().resolve("testUI/no-screen/xdg-open") }

    val sinkLog: Path
        get() = Path(System.getenv(SINK_LOG) ?: error("$SINK_LOG is not set: run through the testUI task"))

    /** The IDE's `user.home`, `HOME` and XDG folders, emptied before each test, so no account or key reaches it. */
    val privateHome: Path
        get() = sinkLog.resolveSibling("home")

    /** The thread dump tool in the IDE's own JBR, under Starter's builds, and no other process of that name. */
    internal fun isStartersJstack(comm: String, executable: String): Boolean =
        comm == "jstack" && "/ide-tests/cache/builds/" in executable && executable.endsWith("/jbr/bin/jstack")

    /** Headless runs, the process sampling and the browser route's proof all need Linux. */
    internal val onLinux: Boolean
        get() = OS.CURRENT == OS.Linux

    fun check() = check(System.getenv(), onScreen)

    /** Fails before any IDE starts, naming each way `env` would let a run reach the real screen. */
    fun check(env: Map<String, String>, onScreen: Boolean, linux: Boolean = onLinux) {
        val problems = buildList {
            if (!onScreen && !linux) add("a headless run needs Linux's xvfb-run; run on screen with -PtestUiHeadless=false")
            REMOVED.filter { it in env && !(onScreen && it in DISPLAY_VARIABLES) }.forEach { add("$it is set") }
            if (onScreen && linux && "DISPLAY" !in env) add("on screen was asked for, but DISPLAY is not set")
            if (SINK_LOG !in env) add("$SINK_LOG is not set")

            val browser = env["BROWSER"]?.let(::Path)
            if (browser == null || !browser.exists() || !Files.isSameFile(browser, sink)) add("BROWSER is not the sink $sink: $browser")

            val xdgOpen = env["PATH"].orEmpty().split(java.io.File.pathSeparatorChar)
                .filter { it.isNotEmpty() }
                .map { Path(it, "xdg-open") }
                .firstOrNull { Files.isExecutable(it) }
            if (xdgOpen == null || !Files.isSameFile(xdgOpen, sink)) add("the first xdg-open on PATH is not the sink $sink: $xdgOpen")
        }
        check(problems.isEmpty()) { "testUI would reach the real screen: ${problems.joinToString("; ")}" }
    }

    /**
     * Free mode, so the IDE neither starts a trial nor looks for a license. `.ce_migration_attempted` is what the
     * auto-trial's eligibility check skips on; `ide.restarted.to.free.mode.manually` is what the paid-functionality check
     * reads. Both are platform internals.
     */
    fun prepareFreeMode(configDir: Path) {
        configDir.resolve(".ce_migration_attempted").createParentDirectories().writeText("")
        configDir.resolve("options/other.xml").createParentDirectories().writeText(
            """
            <application>
              <component name="PropertyService"><![CDATA[{"keyToString":{"ide.restarted.to.free.mode.manually":"true"}}]]></component>
            </application>
            """.trimIndent()
        )
    }

    /** Key files are only ever seen by name: their contents are never read. */
    internal fun keyFiles(root: Path, skipping: Set<String> = emptySet()): List<Path> =
        files(root, skipping).filter { it.name.endsWith(".key") }

    /** Every regular file under `root`, without the top-level folders named in `skipping`. */
    internal fun files(root: Path, skipping: Set<String> = emptySet()): List<Path> {
        if (!root.exists()) return emptyList()
        val skipped = skipping.map(root::resolve)
        return Files.walk(root).use { paths ->
            paths.asSequence().filter { path -> skipped.none(path::startsWith) && path.isRegularFile() }.toList()
        }
    }

    fun sinkEntries(): List<String> = if (sinkLog.exists()) sinkLog.readLines() else emptyList()

    internal fun emptyPrivateHome() {
        privateHome.toFile().deleteRecursively()
        privateHome.createDirectories()
    }
}
