package org.elixir_lang.intellij_elixir

import com.ericsson.otp.erlang.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import org.apache.commons.lang3.CharUtils
import org.elixir_lang.GenericServer.call
import org.elixir_lang.IntellijElixir
import org.elixir_lang.Keyword.isKeyword
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.ParentImpl.elixirString
import org.jetbrains.annotations.Contract
import org.junit.Assert
import org.junit.ComparisonFailure
import java.io.IOException

/**
 * Created by kadie.enheduanna.inanna on 12/31/14.
 */
object Quoter {
    /* remote name is Elixir.IntellijElixir.Quoter because all aliases in Elixir look like atoms prefixed with
       with Elixir. from erlang's perspective. */
    private const val REMOTE_NAME = "Elixir.IntellijElixir.Quoter"
    private const val TIMEOUT_IN_MILLISECONDS = 1000

    /** Escape hatch back to dumping both quoted forms in full; see [assertQuotedCorrectly]. */
    private const val FULL_DUMP_PROPERTY = "elixir.quoter.fullDump"

    /** Line budget per side of a reported divergence. */
    private const val MAX_PREVIEW_LINES = 40

    private const val ABSENT = "(no term at this path)"

    /**
     * Set by the build: "false" when no daemon could be built for the Elixir/OTP pair under test.
     * Anything else, absent included, means carry on and let the call decide.
     */
    private const val AVAILABLE_VARIABLE = "QUOTER_AVAILABLE"

    private const val UNAVAILABLE_REASON_VARIABLE = "QUOTER_UNAVAILABLE_REASON"

    /**
     * As [assertError], for the releases that reject by raising rather than by answering `{:error, _}`.
     * The quoter replies `{:raise, module, message}` instead of dying, so an [OtpErlangExit] here is
     * still a dead daemon and still fatal.
     */
    @JvmStatic
    fun assertRaise(file: PsiFile, expectedException: String) {
        try {
            val quotedMessage = quote(file.text)
            assertMessageReceived(quotedMessage)

            Assert.assertEquals(
                "quoter did not raise",
                "raise",
                (quotedMessage!!.elementAt(0) as OtpErlangAtom).atomValue()
            )
            Assert.assertEquals(
                "quoter raised the wrong exception",
                expectedException,
                (quotedMessage.elementAt(1) as OtpErlangAtom).atomValue()
            )
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangDecodeException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangExit) {
            daemonDied(e)
        }
    }

    @JvmStatic
    fun assertError(file: PsiFile) {
        val text = file.text
        try {
            val quotedMessage = quote(text)
            assertMessageReceived(quotedMessage)
            val status = quotedMessage!!.elementAt(0) as OtpErlangAtom
            val statusString = status.atomValue()
            Assert.assertEquals("quoter did not answer an error", "error", statusString)
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangDecodeException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangExit) {
            daemonDied(e)
        }
    }

    /**
     * Fails a test needing the reference quoter when the build could not produce one for this
     * Elixir/OTP pair. Call it from any test whose precondition is a running daemon, not only those
     * that quote - otherwise the test reports its own symptom and leaves the cause to be guessed.
     *
     * A failure and not an `Assume`: skipping would report a clean run for assertions that were never
     * made, and would let a quoter that stopped building on a supported pair pass unnoticed instead of
     * turning a required leg red.
     *
     * Also the reason it runs before [IntellijElixir.getLocalNode] - otherwise each of these tests
     * pays an OTP node setup and a timeout only to report that no message arrived.
     */
    @JvmStatic
    fun assertAvailable() {
        if (System.getenv(AVAILABLE_VARIABLE) != "false") return

        val reason = System.getenv(UNAVAILABLE_REASON_VARIABLE) ?: "no reason recorded"

        throw AssertionError("${quoterPreamble("unavailable")}: $reason\nThis test needs the reference quoter; the rest of the suite does not.")
    }

    /**
     * What a `{reason, stacktrace}` exit carries, or null when the reason has no such shape -
     * [org.elixir_lang.GenericServer.call] reports an unreachable server with an atom reason. Does
     * not distinguish a raised exception from a shutdown reason.
     */
    private fun OtpErlangExit.raisedException(): String? {
        val reason = reason() as? OtpErlangTuple ?: return null
        if (reason.arity() != 2 || reason.elementAt(1) !is OtpErlangList) return null

        val thrown = reason.elementAt(0)
        val struct = (thrown as? OtpErlangMap)?.get(OtpErlangAtom("__struct__"))

        return (struct as? OtpErlangAtom)?.atomValue() ?: thrown.toString()
    }

    /**
     * The daemon died mid-run, which [assertAvailable] cannot catch - it reads a marker written
     * before the run.
     */
    private fun daemonDied(e: OtpErlangExit): Nothing {
        val raised = e.raisedException()
            ?: throw AssertionError("${quoterPreamble("died")}: ${e.reason()}\nThis test needs the reference quoter; the rest of the suite does not.", e)

        throw AssertionError("${quoterPreamble("died")} with $raised; if that is an exception, quoterRef predates the rescue: ${e.reason()}\nThis test needs the reference quoter; the rest of the suite does not.", e)
    }

    private fun quoterPreamble(state: String): String {
        val elixirVersion = System.getenv("ELIXIR_VERSION").orEmpty().ifEmpty { "unknown" }
        val otpVersion = System.getenv("ERLANG_VERSION").orEmpty().ifEmpty { "unknown" }

        return "Quoter daemon $state for Elixir $elixirVersion / OTP $otpVersion"
    }

    @Contract("null -> fail")
    private fun assertMessageReceived(message: OtpErlangObject?) {
        Assert.assertNotNull(
            "did not receive message from $REMOTE_NAME@${IntellijElixir.REMOTE_NODE}.  Make sure it is running",
            message
        )
    }

    @JvmStatic
    fun assertQuotedCorrectly(file: PsiFile) {
        val text = file.text

        try {
            val quotedMessage = quote(text)
            assertMessageReceived(quotedMessage)
            val status = quotedMessage!!.elementAt(0) as OtpErlangAtom
            val statusString = status.atomValue()
            val expectedQuoted = quotedMessage.elementAt(1)

            if (statusString == "ok") {
                val actualQuoted =
                    ApplicationManager.getApplication().runReadAction(Computable { ElixirPsiImplUtil.quote(file) })
                assertQuotedCorrectly(expectedQuoted, actualQuoted)
            } else if (statusString == "error") {
                throw AssertionError(
                    "quoter returned ${rejection(quotedMessage)}, use assertQuotesAroundError if error is expect in Elixir natively, but not in intellij-elixir plugin"
                )
            } else if (statusString == "raise") {
                throw AssertionError(
                    "quoter ${rejection(quotedMessage)}, use " +
                        "assertParsedAndQuotedAroundErrorOrRaise(languageLevel, exception) if releases below " +
                        "languageLevel reject the construct that way"
                )
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangDecodeException) {
            throw RuntimeException(e)
        } catch (e: OtpErlangExit) {
            daemonDied(e)
        }
    }

    /** How the quoter rejected source: an `error` answer's message, location and token, or a `raise` answer's exception. */
    @JvmStatic
    fun rejection(quoted: OtpErlangTuple): String =
        when (val status = (quoted.elementAt(0) as OtpErlangAtom).atomValue()) {
            "error" -> {
                val error = quoted.elementAt(1) as OtpErlangTuple
                val location = when (val metadata = error.elementAt(0)) {
                    is OtpErlangLong -> "on line $metadata"
                    is OtpErlangList -> {
                        val line = metadata.elementAt(0)
                        val column = metadata.elementAt(1)
                        "on line $line in column $column"
                    }

                    else -> TODO()
                }
                val token = ElixirPsiImplUtil.javaString(error.elementAt(2) as OtpErlangBinary)
                val message = errorMessage(error.elementAt(1), token)

                "\"$message\" $location due to $token"
            }

            "raise" -> {
                val exception = (quoted.elementAt(1) as OtpErlangAtom).atomValue()
                val message = ElixirPsiImplUtil.javaString(quoted.elementAt(2) as OtpErlangBinary)

                "raised $exception \"$message\""
            }

            else -> throw IllegalArgumentException("the quoter did not reject the source: it answered $status")
        }

    /** A `{prefix, suffix}` message surrounds its token, as `elixir_errors:parse_error/5` joins them. */
    internal fun errorMessage(message: OtpErlangObject, token: String): String =
        if (message is OtpErlangTuple) {
            utf8(message.elementAt(0)) + token + utf8(message.elementAt(1))
        } else {
            utf8(message)
        }

    private fun utf8(binary: OtpErlangObject): String =
        String((binary as OtpErlangBinary).binaryValue(), Charsets.UTF_8)

    /**
     * Reports where two quoted forms diverge rather than dumping both in full.
     *
     * A single differing node used to serialize both entire quoted forms, which for a real stdlib
     * source is ~100k lines per side. The platform test framework also logs each failure at SEVERE,
     * so every dump was stored twice in the JUnit XML - enough to push one result file past
     * libxml2's CDATA limit, at which point the report parser skips the file and silently drops
     * every failure in it. The path plus the diverging subterm is what identifies the defect; the
     * identical 99.9% of both trees never is.
     *
     * Set -Delixir.quoter.fullDump=true to restore the complete side-by-side dump.
     */
    private fun assertQuotedCorrectly(
        expectedQuoted: OtpErlangObject,
        actualQuoted: OtpErlangObject
    ) {
        if (expectedQuoted == actualQuoted) return

        if (System.getProperty(FULL_DUMP_PROPERTY).toBoolean()) {
            throw ComparisonFailure(null, toString(expectedQuoted, 0), toString(actualQuoted, 0))
        }

        // A divergence whose terms are both absent means the walk could not localize the
        // difference (equal structure, unequal terms), so fall back to the full dump instead of
        // reporting nothing useful.
        val divergence = divergence(expectedQuoted, actualQuoted, emptyList())

        if (divergence == null || (divergence.expected == null && divergence.actual == null)) {
            throw ComparisonFailure(
                "quoted forms differ but the difference could not be localized" +
                        (divergence?.let { "; ${it.description}" } ?: ""),
                toString(expectedQuoted, 0),
                toString(actualQuoted, 0)
            )
        }

        throw ComparisonFailure(
            "quoted forms diverge at ${divergence.path}: ${divergence.description}",
            preview(divergence.expected),
            preview(divergence.actual)
        )
    }

    /** Where two terms first differ: [path] into the quoted form, and the subterms found there. */
    private class Divergence(
        pathSegments: List<String>,
        val description: String,
        val expected: OtpErlangObject?,
        val actual: OtpErlangObject?
    ) {
        val path: String = if (pathSegments.isEmpty()) "the root term" else pathSegments.joinToString("")
    }

    /**
     * Walks [expected] and [actual] in parallel, depth first, returning the first difference, or
     * `null` when the two are equal.
     */
    private fun divergence(
        expected: OtpErlangObject,
        actual: OtpErlangObject,
        path: List<String>
    ): Divergence? {
        if (expected == actual) return null

        if (expected.javaClass != actual.javaClass) {
            return Divergence(
                path,
                "expected ${expected.javaClass.simpleName}, got ${actual.javaClass.simpleName}",
                expected,
                actual
            )
        }

        return when (expected) {
            is OtpErlangTuple -> elementsDivergence(
                expected.elements(), (actual as OtpErlangTuple).elements(), path, "tuple"
            ) { _, index -> "{$index}" }

            is OtpErlangList -> elementsDivergence(
                expected.elements(), (actual as OtpErlangList).elements(), path, "list"
            ) { element, index -> keywordKey(element)?.let { "[:$it]" } ?: "[$index]" }

            is OtpErlangMap -> mapDivergence(expected, actual as OtpErlangMap, path)
            else -> Divergence(path, "different values", expected, actual)
        }
    }

    /**
     * Compares elements pairwise, then falls back to reporting a length difference against the
     * first unpaired element - never against the whole container, which is what made these
     * failures unreadable.
     */
    private fun elementsDivergence(
        expected: Array<OtpErlangObject>,
        actual: Array<OtpErlangObject>,
        path: List<String>,
        kind: String,
        segment: (OtpErlangObject, Int) -> String
    ): Divergence {
        val paired = minOf(expected.size, actual.size)

        for (index in 0 until paired) {
            // A keyword pair with the same key on both sides is reported as [:key] on the value
            // rather than as an index into the pair, so metadata paths read like {2}[:line].
            val expectedElement = expected[index]
            val actualElement = actual[index]
            val key = keywordKey(expectedElement)
            val actualKey = keywordKey(actualElement)

            val divergence = when {
                key != null && key == actualKey ->
                    divergence(
                        (expectedElement as OtpErlangTuple).elementAt(1),
                        (actualElement as OtpErlangTuple).elementAt(1),
                        path + "[:$key]"
                    )
                // Two keyword pairs with different keys: name the key mismatch rather than
                // descending into the pair, where it would surface as "different values".
                key != null && actualKey != null ->
                    Divergence(
                        path + "[$index]",
                        "keyword key, expected :$key, got :$actualKey",
                        expectedElement,
                        actualElement
                    )

                else -> divergence(expectedElement, actualElement, path + segment(expectedElement, index))
            }

            if (divergence != null) return divergence
        }

        if (expected.size == actual.size) {
            return Divergence(path, "$kind of ${expected.size} differs with no differing element", null, null)
        }

        return Divergence(
            path,
            "$kind size, expected ${expected.size}, got ${actual.size}",
            expected.getOrNull(paired),
            actual.getOrNull(paired)
        )
    }

    private fun mapDivergence(expected: OtpErlangMap, actual: OtpErlangMap, path: List<String>): Divergence {
        for (key in expected.keys()) {
            val expectedValue = expected.get(key)!!
            val actualValue = actual.get(key)
                ?: return Divergence(path, "missing key ${toString(key, 0).trim()}", expectedValue, null)

            divergence(expectedValue, actualValue, path + "%{${toString(key, 0).trim()}}")
                ?.let { return it }
        }

        actual.keys()
            .firstOrNull { expected.get(it) == null }
            ?.let {
                return Divergence(path, "unexpected key ${toString(it, 0).trim()}", null, actual.get(it))
            }

        return Divergence(path, "maps of ${expected.arity()} differ with no differing entry", null, null)
    }

    /** The key of a `{:key, value}` keyword pair, or `null` if [element] is not one. */
    private fun keywordKey(element: OtpErlangObject): String? =
            (element as? OtpErlangTuple)
                ?.takeIf { it.arity() == 2 }
                ?.let { it.elementAt(0) as? OtpErlangAtom }
                ?.atomValue()

    /** Renders a diverging subterm, bounded so a large subtree cannot recreate the original problem. */
    private fun preview(term: OtpErlangObject?): String {
        if (term == null) return ABSENT

        val rendered = toString(term, 0)
        val lines = rendered.lines()

        if (lines.size <= MAX_PREVIEW_LINES) return rendered

        return (lines.take(MAX_PREVIEW_LINES) +
                "... ${lines.size - MAX_PREVIEW_LINES} more lines; " +
                "re-run with -D$FULL_DUMP_PROPERTY=true for the complete terms")
            .joinToString("\n")
    }

    fun quote(code: String): OtpErlangTuple? {
        assertAvailable()

        val otpNode = IntellijElixir.getLocalNode()
        val otpMbox = otpNode.createMbox()
        val request: OtpErlangObject = elixirString(code)
        return call(
            otpMbox,
            otpNode,
            REMOTE_NAME,
            IntellijElixir.REMOTE_NODE,
            request,
            TIMEOUT_IN_MILLISECONDS
        ) as OtpErlangTuple?
    }

    private fun toString(quoted: OtpErlangBitstr, depth: Int): String {
        val indent = indent(depth)
        return quoted.binaryValue().joinToString(prefix = "$indent\"", separator = "", postfix = "\"") {
            when {
                it.toInt() == 0x0A -> {
                    "\\n"
                }

                CharUtils.isAsciiPrintable(it.toInt().toChar()) -> {
                    it.toInt().toChar().toString()
                }

                else -> {
                    String.format("\\x%02X", it)
                }
            }
        }
    }

    private fun toString(quoted: OtpErlangList, depth: Int): String {
        val prefix = "["
        val elements = quoted.elements()
        val postfix = "]"

        return if (isKeyword(quoted)) {
            val keyDepth = depth + 1
            val keyIndent = indent(keyDepth)
            val valueDepth = keyDepth + 1
            toString(prefix, elements, postfix, depth) { element ->
                val pair = element as OtpErlangTuple
                val key = pair.elementAt(0)
                val suffix = when (val value = pair.elementAt(1)) {
                    // One-liners
                    is OtpErlangInt, is OtpErlangFloat, is OtpErlangDouble, is OtpErlangLong -> " ${toString(value, 0)}"
                    else -> {
                        val valueString = toString(value, valueDepth)
                        valueString.lineSequence().singleOrNull()?.let {
                            val valueIndent = indent(valueDepth)

                            " ${it.removePrefix(valueIndent)}"
                        } ?: "\n$valueString"
                    }
                }

                "$keyIndent$key:$suffix"
            }
        } else {
            toString(prefix, elements, postfix, depth)
        }
    }

    private fun toString(quoted: OtpErlangObject, depth: Int): String =
            when (quoted) {
                is OtpErlangBoolean,
                is OtpErlangAtom,
                is OtpErlangByte,
                is OtpErlangChar,
                is OtpErlangFloat,
                is OtpErlangDouble,
                is OtpErlangExternalFun,
                is OtpErlangFun,
                is OtpErlangInt,
                is OtpErlangLong,
                is OtpErlangMap,
                is OtpErlangPid,
                is OtpErlangString -> {
                    val indent = indent(depth)
                    quoted.toString().prependIndent(indent)
                }

                is OtpErlangBitstr -> {
                    toString(quoted, depth)
                }

                is OtpErlangList -> {
                    toString(quoted, depth)
                }

                is OtpErlangTuple -> {
                    toString(quoted, depth)
                }

                else -> {
                    throw IllegalArgumentException("Don't know how to convert ${quoted.javaClass} to string")
                }
            }

    private fun toString(quoted: OtpErlangTuple, depth: Int): String =
            toString("{", quoted.elements(), "}", depth)

    private fun toString(prefix: String, elements: Array<OtpErlangObject>, postfix: String, depth: Int): String =
            toString(prefix, elements, postfix, depth) { toString(it, depth + 1) }

    private fun toString(
        prefix: String,
        elements: Array<OtpErlangObject>,
        postfix: String,
        depth: Int,
        transform: (OtpErlangObject) -> CharSequence
    ): String {
        val indent = indent(depth)
        return elements.joinToString(
            prefix = "$indent$prefix\n",
            separator = ",\n",
            postfix = "\n$indent$postfix",
            transform = transform
        )
    }

    private fun indent(depth: Int): String = "  ".repeat(depth)
}
