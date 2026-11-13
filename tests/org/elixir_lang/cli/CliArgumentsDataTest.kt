package org.elixir_lang.cli

import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Generate new data for this with the [testData/org/elixir_lang/cli/gen_elixir_cli_base_args.sh](../../../testData/org/elixir_lang/cli/gen_elixir_cli_base_args.sh) script.
 * The tests should work on any platform, but the generation should be done on a posix system.
 */
@RunWith(Parameterized::class)
class CliArgumentsDataTest(private val case: Case) : PlatformTestCase() {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

    companion object {
        private val dataFilePath = Path.of("testData", "org", "elixir_lang", "cli", "elixir_cli_base_args.txt")
        private const val ELIXIR_HOME_PLACEHOLDER = "{elixirHomePath}"
        private val legacyLibRegex = Regex("/lib/([^/\\s]+)/ebin")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = loadCases().map { arrayOf(it) }

        private fun loadCases(): List<Case> {
            val cases = mutableListOf<Case>()
            Files.readAllLines(dataFilePath).forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    return@forEachIndexed
                }
                if (line.startsWith("#")) {
                    return@forEachIndexed
                }
                val parts = line.split("|", limit = 3)
                require(parts.size == 3) { "Invalid data line ${index + 1}: $line" }
                val toolParts = parseTool(parts[0].trim(), index + 1)
                val tool = toolParts.first
                val toolArgs = toolParts.second
                val version = parts[1].trim()
                val argsLine = parts[2].trim()
                cases.add(Case(tool, toolArgs, version, argsLine, index + 1))
            }
            return cases
        }

        private fun parseTool(value: String, lineNumber: Int): Pair<CliTool, List<String>> {
            val parsedToolString = ParametersListUtil.parse(value)
            val tool =  when (parsedToolString[0].lowercase()) {
                "mix" -> CliTool.MIX
                "iex" -> CliTool.IEX
                "elixir" -> CliTool.ELIXIR
                "elixirc" -> CliTool.ELIXIRC
                else -> throw IllegalArgumentException("Unknown tool '$value' on line $lineNumber")
            }
            return Pair(tool, parsedToolString.drop(1).filterNotNull())
        }
    }

    private val createdPaths: MutableList<Path> = mutableListOf()
    private lateinit var erlangHome: Path
    private lateinit var erlangExec: String
    private val targetOS = OS.Linux

    override fun setUp() {
        super.setUp()

        erlangHome = createErlangHome()
        erlangExec = expectedErlExecPath(erlangHome, targetOS)
    }

    override fun tearDown() {
        try {
            cleanup(*createdPaths.toTypedArray())
            createdPaths.clear()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun testArgsMatchData() {
        val elixirHome = createElixirHomeFromArgs(case.argsLine)
        val args = CliArguments.argsOrThrow(
            snapshot = snapshot(case.version, elixirHome),
            tool = case.tool,
            extraElixirArguments = case.toolArgs,
            os = targetOS
        )
        assertEquals(erlangExec, args.exePath)

        val expectedTokens = expectedParameters(case.argsLine, elixirHome)
        val actualTokens = normalize(args.arguments)
        assertEquals("Line ${case.lineNumber}: ${case.tool} ${case.toolArgs} ${case.version}", expectedTokens, actualTokens)
    }

    private fun expectedErlExecPath(erlangHome: Path, os: OS): String {
        val exeName = if (os == OS.Windows) "erl.exe" else "erl"
        return erlangHome.resolve("bin").resolve(exeName).absolutePathString()
    }

    private fun createElixirHomeFromArgs(argsLine: String): Path {
        val libs = legacyLibRegex.findAll(argsLine)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        return createElixirHome(*libs.toTypedArray())
    }

    private fun createElixirHome(vararg libs: String): Path {
        val home = trackPath(Files.createTempDirectory("elixir-home"))
        Files.createDirectories(home.resolve("bin"))
        val libDir = home.resolve("lib")
        libs.forEach { lib -> Files.createDirectories(libDir.resolve(lib).resolve("ebin")) }
        return home
    }

    private fun createErlangHome(): Path {
        val home = trackPath(Files.createTempDirectory("erlang-home"))
        val bin = Files.createDirectories(home.resolve("bin"))
        Files.createFile(bin.resolve("erl"))
        val lib = Files.createDirectories(home.resolve("lib"))
        listOf("asn1-5.0.18.1",
            "common_test-1.22.1",
            "compiler-8.1.1.1",
            "crypto-5.0.6.3",
            "debugger-5.2.1",
            "dialyzer-4.4.4.1",
            "diameter-2.2.5",
            "edoc-1.1",
            "eldap-1.2.10",
            "erl_docgen-1.2.1",
            "erl_interface-5.2.2",
            "erts-12.3.2.6",
            "et-1.6.5",
            "eunit-2.7",
            "ftp-1.1.1",
            "inets-7.5.3.1",
            "kernel-8.3.2.2",
            "megaco-4.3",
            "mnesia-4.20.4.1",
            "observer-2.11.1",
            "odbc-2.13.5",
            "os_mon-2.7.1",
            "parsetools-2.3.2",
            "public_key-1.12.0.1",
            "reltool-0.9",
            "runtime_tools-1.18",
            "sasl-4.1.2",
            "snmp-5.12",
            "ssh-4.13.2.1",
            "ssl-10.7.3.5",
            "stdlib-3.17.2.1",
            "syntax_tools-2.6",
            "tftp-1.0.3",
            "tools-3.5.2",
            "wx-2.1.4",
            "xmerl-1.3.28").forEach {
            Files.createDirectories(lib.resolve(it))
        }
        return home
    }

    private fun cleanup(vararg paths: Path) {
        paths.forEach { path -> FileUtil.delete(path.toFile()) }
    }

    private fun expectedParameters(argsLine: String, elixirHome: Path): List<String> {
        val normalizedHome = normalize(elixirHome.toString())
        val rewritten = argsLine.replace(ELIXIR_HOME_PLACEHOLDER, normalizedHome)
        val rawTokens = rewritten.trim().split(Regex("\\s+"))
        val withoutErl = if (rawTokens.firstOrNull() == "erl") rawTokens.drop(1) else rawTokens
        return normalize(withoutErl)
    }

    private fun normalize(values: List<String>): List<String> = values.map { normalize(it) }
    private fun normalize(value: String): String = FileUtil.toSystemIndependentName(value)

    private fun snapshot(version: String, elixirHome: Path) =
        ElixirSdkSnapshot(version, elixirHome.toString(), version, erlangHome.toString())

    private fun trackPath(path: Path): Path {
        createdPaths.add(path)
        return path
    }

    data class Case(
        val tool: CliTool,
        val toolArgs: List<String>,
        val version: String,
        val argsLine: String,
        val lineNumber: Int,
    ) {
        override fun toString(): String = "${tool.name.lowercase()}${toolArgs.joinToString(" ", " ")} $version (line $lineNumber)"
    }
}
