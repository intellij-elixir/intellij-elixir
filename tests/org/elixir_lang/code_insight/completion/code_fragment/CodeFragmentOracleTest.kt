package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import com.google.gson.JsonParser
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.golden.CommittedGolden
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Compares the plugin's reading of the cursor with Elixir's `Code.Fragment` at every grapheme-cluster boundary of the
 * case table. Elixir answers live through the quoter, so it runs on the baseline leg only.
 */
class CodeFragmentOracleTest : PlatformTestCase() {
    private var autocompleteOnCodeCompletion = true

    override fun getProjectDescriptor(): LightProjectDescriptor = CodeFragmentProjectDescriptor

    override fun setUp() {
        super.setUp()

        // A single item would otherwise be inserted with no lookup to read it from.
        val settings = CodeInsightSettings.getInstance()
        autocompleteOnCodeCompletion = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        runAll(
            { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteOnCodeCompletion },
            { super.tearDown() },
        )
    }

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

    fun testAgreement() {
        if (!baseline) return

        CompletionRecorder.register(testRootDisposable)
        // Past the limit the lookup keeps its most relevant half while contributors still add items, so which items
        // remain depends on timing; every item is recorded instead.
        Registry.get("ide.completion.variant.limit").setValue(100_000, testRootDisposable)

        val rows = CASES.flatMap(::rows)

        CommittedGolden.assertMatches(GOLDEN, rows.joinToString("") { "$it\n" }, REGENERATE)
    }

    /** A row per point of [case], then its count of parser errors. */
    private fun rows(case: Case): List<String> {
        val asked = asked.getValue(case.name)
        val answers = asked.points.map { it.decode() }
        val offset = offsets(case.text, answers.map { it.offset })
        val rows = answers.map { row(case, it, offset) }

        configure(case.text)
        val errors = ReadAction.computeBlocking<Int, Throwable> {
            PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java).size
        }

        return rows + "${case.name}@errors\t$errors"
    }

    private fun row(case: Case, answer: Answer, offset: (Position) -> Int): String {
        val surround = answer.surround?.surround
        val span = surround?.let { span(it, offset, answer.offset, case.text) }
        val container = (answer.container as? Container.Quoted)?.ast
        val path = container?.let(::path)
        val plugin = observe(case, answer.offset, answer.cursor)
        val judgement = judge(
            Elixir(answer.cursor, span, path, surround?.context, answer.literal, answer.atomStart),
            plugin,
            container?.let(::scope) ?: Scope(),
            case.text.getOrNull(answer.offset - 1)?.isWhitespace() ?: true,
        )

        return listOf(
            "${case.name}@${"%04d".format(answer.n)}",
            answer.cursor.toString(),
            surround?.let {
                span?.let { span -> "${it.context} ${span.begin}-${span.end}" }
                    ?: "${it.context} ${offset(it.begin)}-${offset(it.end)} (no segment at the caret)"
            } ?: answer.surround?.toString() ?: "-",
            if (answer.literal) "literal" else "-",
            if (answer.atomStart) "atomStart" else "-",
            path?.let { compared(answer.cursor, it).joinToString(" ", "[", "]", transform = Step::shown) } ?: Container.Error.toString(),
            items(plugin.items),
            "${plugin.items.map { plugin.caret - it.pattern.length }.distinct().sorted().joinToString(",").ifEmpty { "_" }}-${plugin.identifierEnd}",
            plugin.heads?.joinToString(" ", "[", "]", transform = Step::shown) ?: "(lowering logged)",
            *Check.entries.map { judgement.checks.getValue(it).toString() }.toTypedArray(),
            judgement.failed.joinToString(" ").ifEmpty { "-" },
        ).joinToString("\t")
    }

    /** Completes on [case]'s whole text with the caret at [caret], as the editor completes. */
    private fun observe(case: Case, caret: Int, cursor: CursorContext): Plugin {
        var loweringLogged = false
        val processor = object : GuardedLoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (category.contains("org.elixir_lang.lowering")) {
                    loweringLogged = true
                    Action.NONE
                } else {
                    super.processError(category, message, details, t)
                }
        }

        return LoggedErrorProcessor.executeWith(processor).use {
            configure(case.text.substring(0, caret) + CARET + case.text.substring(caret))
            val recorded = CompletionRecorder.record("${case.name} at $caret") { myFixture.complete(CompletionType.BASIC) }
            val items = offered(cursor)

            Plugin(
                caret,
                items,
                recorded.identifierEnd,
                heads(recorded.copy, caret, recorded.dummyIdentifier, recorded.copyText).takeUnless { loweringLogged },
            )
        }
    }

    /** Configures [text] as [FILE] in a new file, so no earlier text's file or document stays in the project. */
    private fun configure(text: String) {
        myFixture.tempDirFixture.getFile(FILE)?.let { file -> WriteAction.runAndWait<Throwable> { file.delete(this) } }
        myFixture.configureByText(FILE, text)
    }

    private fun offered(cursor: CursorContext): List<Item> {
        val lookup = myFixture.lookup as LookupImpl? ?: return emptyList()
        val items = ReadAction.computeBlocking<List<Item>, Throwable> {
            lookup.items.map { item ->
                val (kind, owner) = kind(item.psiElement, cursor)

                Item(item.lookupString, lookup.itemPattern(item), kind, owner)
            }
        }

        lookup.hideLookup(true)

        return items
    }

    private fun items(items: List<Item>): String =
        if (items.isEmpty()) {
            "none"
        } else {
            val kinds = items.groupingBy { it.kind.name.lowercase() }.eachCount().toSortedMap()
                .entries.joinToString(",") { (kind, count) -> "$kind:$count" }
            val patterns = items.map { charlist(it.pattern) }.distinct().sorted().joinToString(",")

            "${items.size} $kinds $patterns"
        }

    private companion object {
        val CASES_DIRECTORY = File("testData/org/elixir_lang/code_insight/completion/code_fragment/cases")
        const val GOLDEN = "testData/org/elixir_lang/code_insight/completion/code_fragment/agreement.tsv"
        const val REGENERATE =
            "./gradlew test --tests org.elixir_lang.code_insight.completion.code_fragment.CodeFragmentOracleTest -PoverwriteTestData=true"
        const val CARET = "<caret>"

        const val FILE = "case.ex"

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

        /**
         * Turns a 1-based line and column into an offset, counting columns over Elixir's own cluster [boundaries], as
         * `surround_context` counts them.
         */
        fun offsets(text: String, boundaries: List<Int>): (Position) -> Int = { position ->
            var lineStart = 0
            repeat(position.line - 1) { lineStart = text.indexOf('\n', lineStart) + 1 }

            boundaries.filter { it >= lineStart }.getOrNull(position.column - 1)
                ?: error("no column ${position.column} on line ${position.line}")
        }
    }
}
