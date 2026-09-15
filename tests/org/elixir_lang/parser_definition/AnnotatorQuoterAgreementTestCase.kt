package org.elixir_lang.parser_definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangTuple
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import junit.framework.TestSuite
import org.elixir_lang.annotator.InvalidConstruct
import org.elixir_lang.annotator.VersionedSyntax
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/**
 * One test per source that `VersionedSyntax` and `InvalidConstruct` are meant to judge, and per snippet of Elixir's own
 * tests: under the release of the Elixir under test, the annotators must report nothing where that Elixir accepts the
 * source, and where they report, give that Elixir's message. Elixir's hints after the first line are left to hovers.
 */
class AnnotatorQuoterAgreementTestCase private constructor(
    private val hash: String,
    private val source: String,
    private val answer: String?,
    private val knownFailures: KnownFailures,
) : BasePlatformTestCase() {
    init {
        name = hash
    }

    override fun setUp() {
        super.setUp()
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION")))
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

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        if (knownFailures.contains(hash)) {
            super.runBare { knownFailures.expectFailure(hash) { assertAgrees() } }
        } else {
            super.runBare { assertAgrees() }
        }
    }

    private fun assertAgrees() {
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
        val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, arguments ->
            var start = -1
            val message = arguments[1] as String

            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(method.returnType)) { builder, builderMethod, builderArguments ->
                when (builderMethod.name) {
                    "range" -> {
                        start = (builderArguments[0] as com.intellij.openapi.util.TextRange).startOffset
                        builder
                    }
                    "create" -> {
                        found.add(start to message)
                        null
                    }
                    else -> builder
                }
            }
        } as AnnotationHolder
        val annotators: List<Annotator> = listOf(VersionedSyntax(), InvalidConstruct())

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                for (annotator in annotators) annotator.annotate(element, holder)
                super.visitElement(element)
            }
        })

        return found.sortedBy { it.first }.map { it.second }
    }

    companion object {
        private val SOURCES = Path.of("testData", "org", "elixir_lang", "annotator", "quoter_agreement", "sources.jsonl")
        private val KNOWN_DIFFERENCES = Path.of("testData", "org", "elixir_lang", "annotator", "quoter_agreement", "known_differences.tsv")

        @JvmStatic
        fun suite(): Test {
            val suite = TestSuite(AnnotatorQuoterAgreementTestCase::class.java.name)
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

                suite.addTest(AnnotatorQuoterAgreementTestCase(hash, source, message(quoted), knownFailures))
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
