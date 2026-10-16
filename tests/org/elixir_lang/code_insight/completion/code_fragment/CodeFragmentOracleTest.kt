package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import com.google.gson.JsonParser
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Compares the plugin's reading of the cursor with Elixir's `Code.Fragment` at every grapheme-cluster boundary of the
 * case table. Elixir answers live through the quoter, so it runs on the baseline leg only.
 */
class CodeFragmentOracleTest : PlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    fun testEveryCaseParses() {
        if (!baseline) return

        val failed = asked.filterValues { (it.parsed as? OtpErlangTuple)?.elementAt(0) != OtpErlangAtom("ok") }

        assertEquals("cases Code.string_to_quoted rejects", emptyMap<String, Any>(), failed.mapValues { it.value.parsed })
    }

    fun testEveryAnswerDecodes() {
        if (!baseline) return

        val failures = asked.flatMap { (name, asked) ->
            asked.points.mapNotNull { point ->
                runCatching { point.decode() }.exceptionOrNull()?.let { "$name@${point.n}: ${it.message}" }
            }
        }

        assertEquals("points whose answers do not decode", emptyList<String>(), failures)

        for (case in CASES) {
            val points = asked.getValue(case.name).points

            assertEquals("${case.name}'s points", points.indices.toList(), points.map { it.n })
            assertEquals("${case.name}'s last point", case.text.length, points.last().offset)
        }
    }

    private companion object {
        val CASES_DIRECTORY = File("testData/org/elixir_lang/code_insight/completion/code_fragment/cases")

        class Case(val name: String, val text: String)

        val CASES: List<Case> by lazy {
            CASES_DIRECTORY.listFiles { file -> file.name.endsWith(".ex") }.orEmpty()
                .sortedBy(File::getName)
                .map { Case(it.name, it.readText()) }
        }

        val asked: Map<String, Asked> by lazy { CASES.associate { it.name to ask(it.text) } }

        val baseline: Boolean by lazy {
            val elixir = JsonParser.parseString(Path.of(".github", "ci-versions.json").readText())
                .asJsonObject["beam"].asJsonObject["baseline"].asJsonObject["elixir"].asString

            environment("ELIXIR_VERSION") == elixir
        }
    }
}
