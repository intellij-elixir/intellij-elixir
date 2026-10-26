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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.common.runAll
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.golden.CommittedGolden
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Compares the plugin's reading of the cursor with Elixir's `Code.Fragment` at every grapheme-cluster boundary of the
 * case table. Elixir answers live through the quoter, so it runs on the baseline leg only.
 *
 * The points are split over [SHARDS] classes, each a contiguous run of [GOLDEN]'s rows, so test JVMs share them evenly.
 * Each row is a test of its own, checked on one fixture the shard's tests share. Shard 0 also checks every case and
 * answer, and that the golden has a row for each point. Where there is no golden, or it is being regenerated, shard 0
 * checks it whole instead, and so writes all of it.
 */
abstract class CodeFragmentOracleTestCase internal constructor(
    private val fixture: SharedFixture<CodeFragmentOracleTestCase>?,
    private val slice: Slice?,
) : PlatformTestCase(), SharedFixtureHost<CodeFragmentOracleTestCase> {
    private var autocompleteOnCodeCompletion = true

    /** The shard's rows that differ from the golden, expected to actual, on its host. */
    private val differing = mutableListOf<Pair<String, String>>()

    init {
        name = slice?.name ?: "shared fixture"
        fixture?.add(this)
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = CodeFragmentProjectDescriptor

    override fun setUp() {
        super.setUp()

        // A single item would otherwise be inserted with no lookup to read it from.
        val settings = CodeInsightSettings.getInstance()
        autocompleteOnCodeCompletion = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false

        CompletionRecorder.register(testRootDisposable)
        // Past the limit the lookup keeps its most relevant half while contributors still add items, so which items
        // remain depends on timing; every item is recorded instead.
        Registry.get("ide.completion.variant.limit").setValue(100_000, testRootDisposable)
    }

    override fun tearDown() {
        runAll(
            { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteOnCodeCompletion },
            { super.tearDown() },
        )
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare {
            serve.run()
            appendDifferingToStepSummary()
        }
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        SharedFixture.runCase(fixture, this) { super.runBare(testRunnable) }
    }

    override fun check(case: CodeFragmentOracleTestCase) {
        when (val slice = case.slice!!) {
            is Slice.Nothing -> {}
            is Slice.Unasked -> throw slice.why
            Slice.Parses -> assertParses()
            Slice.Decodes -> assertDecodes()
            Slice.Rows -> assertRows()
            is Slice.Row -> assertRow(slice.key, slice.expected)
            Slice.Whole -> assertWhole()
        }
    }

    private fun assertParses() {
        val failed = asked.filterValues { (it.parsed as? OtpErlangTuple)?.elementAt(0) != OtpErlangAtom("ok") }

        assertEquals("cases Code.string_to_quoted rejects", emptyMap<String, Any>(), failed.mapValues { it.value.parsed })
    }

    private fun assertDecodes() {
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

    /** The golden has a row for each point and each case's errors, in order, and nothing else. */
    private fun assertRows() {
        val keys = CASES.flatMap { case -> asked.getValue(case.name).points.map { key(case, it.n) } + errorsKey(case) }

        assertEquals(
            "$GOLDEN's rows, by key. Review them, then regenerate with $REGENERATE",
            committed().split('\n').joinToString("\n") { it.substringBefore('\t') },
            keys.joinToString("") { "$it\n" },
        )
    }

    private fun assertRow(key: String, expected: String) {
        val case = CASES.find { it.name == key.substringBefore('@') }
        val actual = when {
            case == null -> "(no case)"
            key == errorsKey(case) -> errorsRow(case)
            else -> answers(case).getOrNull(key.substringAfter('@').toInt())?.let { row(case, it) } ?: "(no point)"
        }

        if (actual != expected) differing += expected to actual
        assertEquals("$GOLDEN differs at this row. Review it, then regenerate with $REGENERATE", expected, actual)
    }

    /** The shard's differing rows as one diff in the CI step summary, as a whole-golden comparison appends its own. */
    private fun appendDifferingToStepSummary() {
        if (differing.isEmpty()) return

        CommittedGolden.appendToStepSummary(
            "`$GOLDEN` differs in ${javaClass.simpleName}",
            GOLDEN,
            differing.joinToString("") { (expected, _) -> "$expected\n" },
            GOLDEN,
            differing.joinToString("") { (_, actual) -> "$actual\n" },
            REGENERATE,
        )?.let { throw it }
    }

    private fun assertWhole() {
        val rows = CASES.flatMap { case -> answers(case).map { row(case, it) } + errorsRow(case) }

        CommittedGolden.assertMatches(GOLDEN, rows.joinToString("") { "$it\n" }, REGENERATE)
    }

    /** [case]'s count of parser errors. */
    private fun errorsRow(case: Case): String {
        configure(case.text)
        val errors = ReadAction.computeBlocking<Int, Throwable> {
            PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java).size
        }

        return "${errorsKey(case)}\t$errors"
    }

    private fun row(case: Case, answer: Answer): String {
        val offset = offsets(case.text, answers(case).map { it.offset })
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
            key(case, answer.n),
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

    /** One test's slice of the shard's checks. */
    sealed class Slice(val name: String) {
        /** Nothing for this shard to check: it is off the baseline leg, or shard 0 checks the golden whole. */
        class Nothing(name: String) : Slice(name)

        /** Whether this is the baseline leg cannot be told, which fails for [why]. */
        class Unasked(val why: AssertionError) : Slice("on the baseline leg")

        object Parses : Slice("every case parses")

        object Decodes : Slice("every answer decodes")

        object Rows : Slice("$GOLDEN has a row for each point")

        class Row(val key: String, val expected: String) : Slice(key)

        object Whole : Slice(GOLDEN)
    }

    private class Case(val name: String, val text: String)

    companion object {
        const val SHARDS = 12
        private val CASES_DIRECTORY = File("testData/org/elixir_lang/code_insight/completion/code_fragment/cases")
        private const val GOLDEN = "testData/org/elixir_lang/code_insight/completion/code_fragment/agreement.tsv"
        private const val REGENERATE =
            "./gradlew test --tests org.elixir_lang.code_insight.completion.code_fragment.CodeFragmentOracleShard00Test -PoverwriteTestData=true"
        private const val CARET = "<caret>"

        private const val FILE = "case.ex"

        private val CASES: List<Case> by lazy {
            CASES_DIRECTORY.listFiles { file -> file.name.endsWith(".ex") }.orEmpty()
                .sortedBy(File::getName)
                .map { Case(it.name, it.readText()) }
        }

        /** Elixir's answers, asked once in each test JVM for all its shards, or why asking failed. */
        private val askedOnce: Result<Map<String, Asked>> by lazy { runCatching { CASES.associate { it.name to ask(it.text) } } }

        private val asked: Map<String, Asked> get() = askedOnce.getOrThrow()

        private val answers = mutableMapOf<String, List<Answer>>()

        private fun answers(case: Case): List<Answer> =
            synchronized(answers) { answers.getOrPut(case.name) { asked.getValue(case.name).points.map { it.decode() } } }

        private val baseline: Boolean by lazy {
            val elixir = JsonParser.parseString(Path.of(".github", "ci-versions.json").readText())
                .asJsonObject["beam"].asJsonObject["baseline"].asJsonObject["elixir"].asString

            environment("ELIXIR_VERSION") == elixir
        }

        private fun key(case: Case, n: Int): String = "${case.name}@${"%04d".format(n)}"

        private fun errorsKey(case: Case): String = "${case.name}@errors"

        private fun committed(): String = StringUtil.convertLineSeparators(File(GOLDEN).readText())

        /** Each committed row's shard, key and row: a contiguous run of the points, and a case's errors with its last. */
        private val SHARDED: List<Triple<Int, String, String>> by lazy {
            val rows = committed().split('\n').filter(String::isNotEmpty)
            val points = rows.count { !it.substringBefore('\t').endsWith("@errors") }
            var point = 0
            var shard = 0

            rows.map { row ->
                val key = row.substringBefore('\t')

                if (!key.endsWith("@errors")) shard = point++ * SHARDS / points
                Triple(shard, key, row)
            }
        }

        /**
         * Turns a 1-based line and column into an offset, counting columns over Elixir's own cluster [boundaries], as
         * `surround_context` counts them.
         */
        private fun offsets(text: String, boundaries: List<Int>): (Position) -> Int = { position ->
            var lineStart = 0
            repeat(position.line - 1) { lineStart = text.indexOf('\n', lineStart) + 1 }

            boundaries.filter { it >= lineStart }.getOrNull(position.column - 1)
                ?: error("no column ${position.column} on line ${position.line}")
        }

        @JvmStatic
        internal fun suite(
            shard: Int,
            test: Class<out CodeFragmentOracleTestCase>,
            new: (SharedFixture<CodeFragmentOracleTestCase>?, Slice?) -> CodeFragmentOracleTestCase,
        ): Test {
            val fixture = SharedFixture { new(null, null) }
            val suite = fixture.suite(test.name)
            val slices = try {
                when {
                    !baseline -> listOf(Slice.Nothing("off the baseline leg"))
                    UsefulTestCase.OVERWRITE_TESTDATA || !File(GOLDEN).isFile ->
                        if (shard == 0) {
                            listOf(Slice.Parses, Slice.Decodes, Slice.Whole)
                        } else {
                            listOf(Slice.Nothing("$GOLDEN checked whole in shard 0"))
                        }
                    else -> buildList {
                        if (shard == 0) addAll(listOf(Slice.Parses, Slice.Decodes, Slice.Rows))
                        SHARDED.filter { it.first == shard }.mapTo(this) { (_, key, row) -> Slice.Row(key, row) }
                    }
                }
            } catch (e: AssertionError) {
                listOf(Slice.Unasked(e))
            }

            for (slice in slices) suite.addTest(new(fixture, slice))

            return suite
        }
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard00Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(0, CodeFragmentOracleShard00Test::class.java, ::CodeFragmentOracleShard00Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard01Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(1, CodeFragmentOracleShard01Test::class.java, ::CodeFragmentOracleShard01Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard02Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(2, CodeFragmentOracleShard02Test::class.java, ::CodeFragmentOracleShard02Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard03Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(3, CodeFragmentOracleShard03Test::class.java, ::CodeFragmentOracleShard03Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard04Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(4, CodeFragmentOracleShard04Test::class.java, ::CodeFragmentOracleShard04Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard05Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(5, CodeFragmentOracleShard05Test::class.java, ::CodeFragmentOracleShard05Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard06Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(6, CodeFragmentOracleShard06Test::class.java, ::CodeFragmentOracleShard06Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard07Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(7, CodeFragmentOracleShard07Test::class.java, ::CodeFragmentOracleShard07Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard08Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(8, CodeFragmentOracleShard08Test::class.java, ::CodeFragmentOracleShard08Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard09Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(9, CodeFragmentOracleShard09Test::class.java, ::CodeFragmentOracleShard09Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard10Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(10, CodeFragmentOracleShard10Test::class.java, ::CodeFragmentOracleShard10Test)
    }
}

@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class CodeFragmentOracleShard11Test private constructor(fixture: SharedFixture<CodeFragmentOracleTestCase>?, slice: Slice?) :
    CodeFragmentOracleTestCase(fixture, slice) {
    companion object {
        @JvmStatic
        fun suite(): Test = suite(11, CodeFragmentOracleShard11Test::class.java, ::CodeFragmentOracleShard11Test)
    }
}
