package org.elixir_lang.parser_definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangTuple
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import junit.framework.TestSuite
import org.elixir_lang.annotator.InvalidConstruct
import org.elixir_lang.annotator.InvalidToken
import org.elixir_lang.annotator.VersionedSyntax
import org.elixir_lang.annotator.recordingAnnotationHolder
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * One test per source that `VersionedSyntax`, `InvalidConstruct` and `InvalidToken` are meant to judge, and per snippet
 * of Elixir's own tests: under the release of the Elixir under test, the annotators must report nothing where that
 * Elixir accepts the source, and where they report, give that Elixir's message. Elixir's hints after the first line are
 * left to hovers.
 */
@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class AnnotatorQuoterAgreementTestCase private constructor(
    private val fixture: SharedFixture<AnnotatorQuoterAgreementTestCase>?,
    private val case: Case?,
) : LightTestCase(), SharedFixtureHost<AnnotatorQuoterAgreementTestCase> {
    init {
        name = case?.hash ?: "shared fixture"
        fixture?.add(this)
    }

    override fun setUp() {
        super.setUp()
        ElixirLanguageLevelResolver.overrideLanguageLevel(
            project,
            ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"), System.getenv("ERLANG_VERSION")),
        )
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare(serve)
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        if (fixture == null) {
            super.runBare(testRunnable)
        } else {
            // Checked on the shared host; logs during the check are this case's.
            UnexpectedLogs.failOnUnexpectedLogs { fixture.check(this) }
        }
    }

    override fun check(case: AnnotatorQuoterAgreementTestCase) {
        val (hash, source, answer, knownFailures) = case.case!!

        if (knownFailures.contains(hash)) {
            knownFailures.expectFailure(hash) { assertAgrees(source, answer) }
        } else {
            assertAgrees(source, answer)
        }
    }

    private fun assertAgrees(source: String, answer: String?) {
        val reported = annotate(source)
        val elixir = "Elixir ${System.getenv("ELIXIR_VERSION")}"

        if (answer == null) {
            assertEquals("$elixir accepts ${escape(source)}", emptyList<String>(), reported)
        } else if (reported.isNotEmpty() && !agrees(answer, reported.first())) {
            fail("$elixir rejects ${escape(source)} with ${escape(answer)}, but the annotators report ${reported.map(::escape)}")
        }
    }

    /** Messages in the order their ranges start. */
    private fun annotate(source: String): List<String> {
        val file = myFixture.configureByText("agreement.ex", source)
        val found = mutableListOf<Pair<Int, String>>()
        val holder = recordingAnnotationHolder { range, message -> found.add((range?.startOffset ?: -1) to message) }
        val annotators: List<Annotator> = listOf(VersionedSyntax(), InvalidConstruct(), InvalidToken())

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                for (annotator in annotators) annotator.annotate(element, holder)
                super.visitElement(element)
            }
        })

        return found.sortedBy { it.first }.map { it.second }
    }

    private data class Case(val hash: String, val source: String, val answer: String?, val knownFailures: KnownFailures)

    companion object {
        private val SOURCES = Path.of("testData", "org", "elixir_lang", "annotator", "quoter_agreement", "sources.jsonl")
        private val KNOWN_DIFFERENCES = Path.of("testData", "org", "elixir_lang", "annotator", "quoter_agreement", "known_differences.tsv")

        @JvmStatic
        fun suite(): Test {
            val fixture = SharedFixture { AnnotatorQuoterAgreementTestCase(null, null) }
            val suite = fixture.suite(AnnotatorQuoterAgreementTestCase::class.java.name)
            val knownFailures = KnownFailures.forElixirUnderTest(KNOWN_DIFFERENCES)
            val sources = LinkedHashMap<String, String>()

            for (line in Files.readAllLines(SOURCES).filter { it.isNotBlank() }) {
                val json = JsonParser.parseString(line).asJsonObject
                sources.putIfAbsent(json.get("hash").asString, json.get("source").asString)
            }
            for (line in Files.readAllLines(ElixirSnippetParsingTestCase.SNIPPETS)) {
                val snippet: JsonObject = JsonParser.parseString(line).asJsonObject
                sources.putIfAbsent(snippet.get("hash").asString, ElixirSnippetParsingTestCase.source(snippet))
            }

            for ((hash, source) in sources) {
                val quoted = try {
                    Quoter.quote(source)
                } catch (e: Throwable) {
                    suite.addTest(TestSuite.warning("The reference quoter could not judge the sources: $e"))
                    return suite
                } ?: run {
                    suite.addTest(TestSuite.warning("The reference quoter did not answer for $hash"))
                    return suite
                }

                suite.addTest(AnnotatorQuoterAgreementTestCase(fixture, Case(hash, source, message(quoted), knownFailures)))
            }

            knownFailures.checkStale(suite, sources.keys)

            return suite
        }

        /** Elixir's message as the annotators word it, or null when Elixir accepts the source. */
        private fun message(quoted: OtpErlangTuple): String? =
            when ((quoted.elementAt(0) as OtpErlangAtom).atomValue()) {
                "ok" -> null
                "error" -> {
                    val error = quoted.elementAt(1) as OtpErlangTuple
                    val token = String((error.elementAt(2) as OtpErlangBinary).binaryValue(), Charsets.UTF_8)
                    val message = error.elementAt(1)

                    if (message is OtpErlangTuple) Quoter.errorMessage(message, token) else Quoter.errorMessage(message, token) + token
                }
                else -> String((quoted.elementAt(2) as OtpErlangBinary).binaryValue(), Charsets.UTF_8)
            }

        /** The whole message, the message with its lines joined, or its first line when the rest is a hint. */
        private fun agrees(elixir: String, reported: String): Boolean =
            reported == elixir || reported == joinLines(elixir) || reported == elixir.substringBefore("\n")

        private fun joinLines(message: String): String = message.trim().split(Regex("\\s+")).joinToString(" ")

        private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    }
}
