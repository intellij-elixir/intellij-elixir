package org.elixir_lang.ui.code_intelligence

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** A gesture, the answer a user should get on both sides, and each side's known failure. */
class Row(
    val name: String,
    val answer: Answer,
    val walk: Known? = null,
    val expander: Known? = null,
    val gesture: FeatureProbes.() -> Reading,
) {
    fun known(source: DeclarationSource): Known? = when (source) {
        DeclarationSource.WALK -> walk
        DeclarationSource.EXPANDER -> expander
    }
}

/**
 * The generated subject, opened once per side, with every row driven by its gesture and judged by [verdict]. Every row
 * runs; the test then fails naming each that failed. Its timings are recorded first, under `build/`, and fail it only by
 * a hang.
 */
class CodeIntelligenceTest {
    @Test
    fun walk() = run(DeclarationSource.WALK)

    @Test
    fun expander() = run(DeclarationSource.EXPANDER)

    private fun run(source: DeclarationSource) {
        val side = source.name.lowercase()
        val verdicts = mutableListOf<Pair<Row, Verdict>>()
        val recording = Recording(side)
        val context = IdeTestContext.setupTestContext("CodeIntelligence-$side")
            .withDeclarationSource(source)
            .applyVMOptionsPatch { addLine("-XX:StartFlightRecording:filename=${recording.flight},settings=profile,dumponexit=true") }
        var probesStopped: Throwable? = null
        context.runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            addSdks()
            // With no subject, no row can run.
            if (recording.timed("open and index") { openSubject() }) {
                probesStopped = runCatching { PerformanceProbes(this, recording).run(TELEMETRY, TELEMETRY_INIT) }.exceptionOrNull()
                val probes = FeatureProbes(this)
                ROWS.forEach { row -> verdicts += row to verdict(row.answer, row.known(source), probes.(row.gesture)()) }
            }
        }
        verdicts.forEach { (row, verdict) -> println("$side: ${row.name}: $verdict") }
        val noSpans = runCatching { recording.spans(context.paths.testHome) }.exceptionOrNull()
        val failed = verdicts.mapNotNull { (row, verdict) -> (verdict as? Verdict.Fail)?.let { "${row.name}: ${it.reason}" } }
        check(failed.isEmpty() && recording.hangs.isEmpty() && probesStopped == null && noSpans == null) {
            listOfNotNull(
                failed.takeIf { it.isNotEmpty() }?.let { "${it.size} of ${verdicts.size} rows failed on the $side:\n${it.joinToString("\n")}" },
                recording.hangs.takeIf { it.isNotEmpty() }?.let { "hung on the $side:\n${it.joinToString("\n")}" },
                probesStopped?.let { "the performance probes stopped on the $side: $it" },
                noSpans?.let { "no spans from the $side: $it" },
            ).joinToString("\n")
        }
    }

    private companion object {
        const val LIVE = "lib/own_web/live/probe_live.ex"
        const val SUMMARY = "lib/own/text/summary.ex"
        const val WORDS = "lib/own/text/words.ex"
        const val TELEMETRY = "lib/own_web/telemetry.ex"
        const val PROBE_DEF = "def item, do: %Item{name: \"a\"}\n"
        const val TELEMETRY_INIT = "def init(_arg) do\n"
        const val WORDS_BODY = "def words(text) do\n"

        // No row depends on an earlier row's state.
        val ROWS = listOf(
            Row(
                "assign, navigation",
                Answer.Is("deps/phoenix_live_view/lib/phoenix_component.ex: def assign(socket_or_assigns, key, value)"),
                expander = Known(
                    "#4610",
                    Reading.Read("_build/dev/lib/phoenix_live_view/ebin/Elixir.Phoenix.Component.beam: def assign(%Phoenix.LiveView.Socket{} = socket, key, value) do"),
                ),
            ) {
                navigation(LIVE, "assign(socket, :live, true)", 1)
            },
            Row(
                "assign, Quick Doc",
                Answer.Includes("module Phoenix.Component", "assign(socket_or_assigns, key, value)"),
                expander = Known("#4610", Reading.Read("assign(%Phoenix.LiveView.Socket{} = socket, key, value)")),
            ) {
                quickDoc(LIVE, "assign(socket, :live, true)", 1)
            },
            Row("assign, completion", Answer.Includes("assign", "assign_new")) {
                completion(LIVE, PROBE_DEF, "  def probe(socket), do: assi")
            },
            Row(
                "connected?, navigation",
                Answer.Is("deps/phoenix_live_view/lib/phoenix_live_view.ex: def connected?(%Socket{transport_pid: transport_pid}), do: transport_pid != nil"),
                expander = Known(
                    "#4610",
                    Reading.Read("_build/dev/lib/phoenix_live_view/ebin/Elixir.Phoenix.LiveView.beam: def connected?(%Phoenix.LiveView.Socket{transport_pid: transport_pid}) do"),
                ),
            ) {
                navigation(LIVE, "connected?(socket)", 1)
            },
            Row(
                "connected?, Quick Doc",
                Answer.Includes("module Phoenix.LiveView", "connected?(socket)", "def connected?(%Socket{transport_pid: transport_pid})"),
                walk = Known("#4609", Reading.Read("connected?(%Socket{transport_pid: transport_pid})")),
                expander = Known("#4609, #4610", Reading.Read("connected?(%Phoenix.LiveView.Socket{transport_pid: transport_pid})")),
            ) {
                quickDoc(LIVE, "connected?(socket)", 1)
            },
            Row("connected?, completion", Answer.Includes("connected?")) {
                completion(LIVE, PROBE_DEF, "  def probe(socket), do: connec")
            },
            Row(
                "String.split, navigation",
                Answer.Is("lib/elixir/lib/string.ex: def split(string, pattern, options \\\\ [])"),
                walk = Known("the walk, replaced and not fixed", Reading.Read("a chooser of 5")),
                expander = Known(
                    "#4602",
                    Reading.Read(
                        "lib/elixir/ebin/Elixir.String.beam: def split(string, %Regex{} = pattern, options) when is_binary(string) and is_list(options) do"
                    ),
                ),
            ) {
                navigation(WORDS, "String.split", 8)
            },
            Row(
                "String.split, Quick Doc",
                Answer.Includes("module String", "split(string, pattern, options \\\\ [])"),
                expander = Known("#4603", Reading.Read("split(x0, x1)")),
            ) {
                quickDoc(WORDS, "String.split", 8)
            },
            Row("finished module, local call", Answer.Is("lib/own_web/telemetry.ex: defp periodic_measurements do")) {
                navigation(TELEMETRY, "periodic_measurements()", 1)
            },
            Row("finished module, completion", Answer.Includes("split")) {
                completion(TELEMETRY, TELEMETRY_INIT, "    String.spl")
            },
            Row("finished module, parameter info", Answer.Is("children, options")) {
                parameterInfo(TELEMETRY, "Supervisor.init(children", 17)
            },
            Row(
                "finished module, highlighting",
                Answer.Is(
                    """
                    defp periodic_measurements: ELIXIR_FUNCTION_DECLARATION
                    periodic_measurements(), period: ELIXIR_FUNCTION_CALL
                    children = [: ELIXIR_VARIABLE
                    @impl true: ELIXIR_MODULE_ATTRIBUTE
                    """.trimIndent()
                ),
            ) {
                highlighting(
                    TELEMETRY,
                    listOf(
                        Token("defp periodic_measurements", 5),
                        Token("periodic_measurements(), period", 0),
                        Token("children = [", 0),
                        Token("@impl true", 0),
                    ),
                )
            },
            Row(
                "finished module, find usages",
                Answer.Is("lib/own_web/telemetry.ex: {:telemetry_poller, measurements: periodic_measurements(), period: 10_000}"),
            ) {
                findUsages(TELEMETRY, "defp periodic_measurements", 6)
            },
            Row(
                "finished module, rename",
                Answer.Is("{:telemetry_poller, measurements: measurements_now(), period: 10_000}\ndefp measurements_now do"),
            ) {
                rename(TELEMETRY, "periodic_measurements", "measurements_now")
            },
            Row(
                "stopped module, local call",
                Answer.Is("lib/own/text/summary.ex: defp local_prefix, do: \"words: \""),
            ) {
                navigation(SUMMARY, "local_prefix() <>", 1)
            },
            Row("stopped module, completion", Answer.Includes("split")) {
                completion(WORDS, WORDS_BODY, "    String.spl")
            },
            Row(
                "stopped module, parameter info",
                Answer.Is("text, minimum \\\\ 0"),
            ) {
                parameterInfo(SUMMARY, "count(text)", 6)
            },
            Row(
                "live view, highlighting",
                Answer.Is("connected?(socket): ELIXIR_FUNCTION_CALL\nsocket = if: ELIXIR_VARIABLE\nsocket) do: ELIXIR_PARAMETER"),
            ) {
                highlighting(LIVE, listOf(Token("connected?(socket)", 0), Token("socket = if", 0), Token("socket) do", 0)))
            },
            Row(
                "stopped module, highlighting",
                Answer.Is(
                    """
                    defp local_prefix: ELIXIR_FUNCTION_DECLARATION
                    local_prefix() <>: ELIXIR_FUNCTION_CALL
                    count(text): ELIXIR_FUNCTION_CALL
                    summary(text): ELIXIR_PARAMETER
                    """.trimIndent()
                ),
            ) {
                highlighting(
                    SUMMARY,
                    listOf(
                        Token("defp local_prefix", 5),
                        Token("local_prefix() <>", 0),
                        Token("count(text)", 0),
                        Token("summary(text)", 8),
                    ),
                )
            },
            Row(
                "stopped module, find usages",
                Answer.Is("lib/own/text/summary.ex: local_prefix() <> Integer.to_string(count(text))"),
            ) {
                findUsages(WORDS, "def count", 5)
            },
            Row(
                "stopped module, rename",
                Answer.Is("local_lead() <> Integer.to_string(count(text))\ndefp local_lead, do: \"words: \""),
            ) {
                rename(SUMMARY, "local_prefix", "local_lead")
            },
            Row("typing", Answer.Is("    # typed")) {
                typing(WORDS, WORDS_BODY, "    # typed\n")
            },
        )
    }
}
