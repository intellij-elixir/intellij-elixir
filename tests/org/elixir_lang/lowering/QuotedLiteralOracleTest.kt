package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import org.elixir_lang.expander.elixirString
import org.elixir_lang.expander.failure
import org.elixir_lang.expander.legLevel
import org.elixir_lang.intellij_elixir.Quoter
import kotlin.time.Duration.Companion.seconds

/** [QuotedLiteral] against the leg's `Macro.quoted_literal?/1`, over each of [QUOTED_LITERAL_CASES]' texts. */
class QuotedLiteralOracleTest : LoweringTestCase() {
    fun testEachCaseAnswersAsTheLegsElixir() {
        val texts = QUOTED_LITERAL_CASES.map { it.first }
        val level = legLevel()
        val compiled = Quoter.compile(
            "IntellijElixir.Quoter.Probe.send(__ENV__, Enum.map(" +
                texts.joinToString(", ", "[", "]") { elixirString(it) } +
                ", &Macro.quoted_literal?(Code.string_to_quoted!(&1))))",
            60.seconds,
        )

        assertEquals("compile: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)

        val answers = (compiled.messages.single() as OtpErlangList).elements().map { (it as OtpErlangAtom).booleanValue() }

        assertEachAnswers(texts.zip(answers)) { QuotedLiteral.of(lower(it, level.elixirVersion), level) }
    }
}
