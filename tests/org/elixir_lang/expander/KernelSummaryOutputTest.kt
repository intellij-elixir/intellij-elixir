package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `Macro.expand_once/2` of each `Kernel` macro the expander builds the output of, on every leg, against what each
 * leg's Elixir gives (`kernel_summary_output/<leg>/A.txt`, from `generate.exs` beside it). Both sides print the quoted
 * term the same way: `line` and `column` dropped, the counter written `:N`, then every node's other metadata.
 */
class KernelSummaryOutputTest : ExpanderTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testIf() = assertFamily("if")

    fun testUnless() = assertFamily("unless")

    fun testAndAnd() = assertFamily("and_and")

    fun testOrOr() = assertFamily("or_or")

    fun testNot() = assertFamily("not")

    fun testOr() = assertFamily("or")

    fun testAnd() = assertFamily("and")

    fun testPipe() = assertFamily("pipe")

    /** On 1.14.0-rc.0, which no leg runs, `|>` pipes into its right operand as written, so a pipe there raises. */
    fun testPipeIntoAPipeBeforeItIsUnpiped() =
        assertEquals(
            "ERROR pipe_operator_arity",
            actual(ElixirLanguageLevel.of("1.14.0-rc.0"), Case("pipe_right", "x |> (f(1) |> g())", Env.Context.NONE, "")),
        )

    fun testIn() = assertFamily("in")

    fun testConcat() = assertFamily("concat")

    fun testToString() = assertFamily("to_string")

    fun testRaise() = assertFamily("raise")

    /** On 1.13 before Erlang/OTP 24, which no leg runs, `raise` gives what 1.12.3 gives. */
    fun testRaiseBeforeOtp24() {
        val level = ElixirLanguageLevel.of("1.13.4", "23")
        val compared = cases("1.12.3").filter { family(it.label) == "raise" }.map { case ->
            val header = "## 1.13.4 on OTP 23 ${case.label}: ${case.source}\n"

            header + case.expected to header + actual(level, case)
        }

        assertEquals(compared.joinToString("\n") { it.first }, compared.joinToString("\n") { it.second })
    }

    fun testBinding() = assertFamily("binding")

    fun testDestructure() = assertFamily("destructure")

    fun testRange() = assertFamily("range")

    fun testStepRange() = assertFamily("step")

    fun testFullRange() = assertFamily("full_range")

    fun testEveryCaseIsInAFamily() =
        assertEquals(
            emptyList<String>(),
            LEGS.flatMap { leg -> cases(leg).map { it.label } }.distinct().filter { family(it) == null },
        )

    /** Every case of [family] on every leg, compared as one text so a run shows each case that differs. */
    private fun assertFamily(family: String) {
        val compared = LEGS.flatMap { leg ->
            cases(leg).filter { family(it.label) == family && it.expected != SKIP }.map { case ->
                val header = "## $leg ${case.label}: ${case.source}\n"

                header + case.expected to header + actual(ElixirLanguageLevel.of(leg), case)
            }
        }

        assertFalse("no case of $family", compared.isEmpty())
        assertEquals(compared.joinToString("\n") { it.first }, compared.joinToString("\n") { it.second })
    }

    /** What the expander gives [case] at [level], printed as the generator prints Elixir's. */
    private fun actual(level: ElixirLanguageLevel, case: Case): String {
        val env = Env.empty(level, KERNEL_IMPORTS).copy(context = case.context)
        val state = ExState.empty(level).copy(read = mapOf(Variable("x", Variable.NIL) to 0, Variable("y", Variable.NIL) to 1), version = 2)

        val run = Run(level, ExpansionObserver.NONE, NO_EXPORTS, NO_STRUCTS)

        // Parsed as [level]'s Elixir parses it, whichever leg runs the test.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)

        return when (val expanded = macroExpandOnce(lower(case.source, level), state, env, run)) {
            is MacroExpanded.Node -> {
                val term = expanded.node.toOtp()

                render(term) + "\nmeta: " + render(OtpErlangList(meta(term).toTypedArray()))
            }
            MacroExpanded.Dir -> "DIR"
            is MacroExpanded.Stopped ->
                when (val expansion = expanded.expansion) {
                    is Expansion.Error -> {
                        val message = RAISE.matchEntire(case.expected)?.groupValues?.get(1)?.let(::unescape)

                        if (message != null && ErrorKinds.pattern(expansion.kind).containsMatchIn(message)) {
                            case.expected
                        } else {
                            "ERROR ${expansion.kind}"
                        }
                    }
                    is Expansion.Unported -> "UNPORTED `${expansion.at.meta.origin.substring(case.source)}`"
                    is Expansion.Opaque -> "OPAQUE ${ExpanderTestCase.render(expansion.dispatch)}"
                    is Expansion.Expanded -> "EXPANDED"
                }
        }
    }

    private class Case(val label: String, val source: String, val context: Env.Context, val expected: String)

    private companion object {
        val ROOT = File("testData/org/elixir_lang/expander/kernel_summary_output")

        val LEGS: List<String> =
            ROOT.listFiles { file -> file.isDirectory }!!
                .map { it.name }
                .sortedBy { ElixirLanguageLevel.of(it).elixir }

        /** The macros the cases call, at every leg; a case calling a name a leg lacks is `SKIP` there. */
        val KERNEL_IMPORTS = KernelImports(
            functions = listOf(NameArity("+", 1), NameArity("-", 1)),
            macros = listOf(
                NameArity("!", 1), NameArity("&&", 2), NameArity("..", 0), NameArity("..", 2), NameArity("..//", 3),
                NameArity("<>", 2), NameArity("and", 2), NameArity("binding", 0), NameArity("binding", 1),
                NameArity("destructure", 2), NameArity("if", 2), NameArity("in", 2), NameArity("or", 2),
                NameArity("raise", 1), NameArity("raise", 2), NameArity("to_string", 1), NameArity("unless", 2),
                NameArity("|>", 2), NameArity("||", 2),
            ).sortedWith(compareBy({ it.name }, { it.arity })),
        )

        /** The families, longest first where one name starts another. */
        val FAMILIES = listOf(
            "and_and", "or_or", "if", "unless", "not", "or", "and", "pipe", "in", "concat", "to_string", "raise",
            "binding", "destructure", "range", "step", "full_range",
        )

        const val SKIP = "SKIP"

        val HEADER = Regex("""^## (\w+)(?: \((match|guard)\))?: (.*)$""")

        val RAISE = Regex("""^RAISE \S+ "(.*)"$""", RegexOption.DOT_MATCHES_ALL)

        val IDENTIFIER = Regex("""\A[A-Za-z_][A-Za-z0-9_@]*[?!]?\z""")

        fun family(label: String): String? = FAMILIES.firstOrNull { Regex("^$it(_|\\d|$)").containsMatchIn(label) }

        fun cases(leg: String): List<Case> {
            val lines = File(ROOT, "$leg/A.txt").readLines()
            val cases = mutableListOf<Case>()
            var index = 0

            while (index < lines.size) {
                val header = HEADER.matchEntire(lines[index++]) ?: continue
                val body = mutableListOf<String>()

                while (index < lines.size && !lines[index].startsWith("## ")) body.add(lines[index++])

                val (label, context, source) = header.destructured
                val envContext = when (context) {
                    "match" -> Env.Context.MATCH
                    "guard" -> Env.Context.GUARD
                    else -> Env.Context.NONE
                }

                cases.add(Case(label, source, envContext, body.joinToString("\n")))
            }

            return cases
        }

        /** `render/1` of the generator. */
        fun render(term: OtpErlangObject): String =
            when (term) {
                is OtpErlangAtom -> term.atomValue().let { if (IDENTIFIER.matches(it)) ":$it" else ":" + binary(it.toByteArray()) }
                is OtpErlangLong -> term.bigIntegerValue().toString()
                is OtpErlangDouble -> term.doubleValue().toString().replace('E', 'e')
                is OtpErlangBinary -> binary(term.binaryValue())
                is OtpErlangString -> term.stringValue().codePoints().toArray().joinToString(", ", "[", "]")
                is OtpErlangList ->
                    term.elements().joinToString(", ", "[", "") { render(it) } +
                        (term.lastTail?.let { " | " + render(it) } ?: "") + "]"
                is OtpErlangTuple -> {
                    val meta = term.elements().getOrNull(1)

                    if (term.arity() == 3 && meta is OtpErlangList && isKeywords(meta)) {
                        val kept = meta.elements().map { it as OtpErlangTuple }.filter { key(it) !in POSITION }.map {
                            if (key(it) == "counter") OtpErlangTuple(arrayOf(it.elementAt(0), N)) else it
                        }

                        "{" + render(term.elementAt(0)) + ", " + render(OtpErlangList(kept.toTypedArray())) + ", " +
                            render(term.elementAt(2)) + "}"
                    } else {
                        term.elements().joinToString(", ", "{", "}") { render(it) }
                    }
                }
                else -> throw AssertionError("no rendering for $term")
            }

        private fun binary(bytes: ByteArray): String =
            bytes.joinToString("", "\"", "\"") { byte ->
                when (val code = byte.toInt() and 0xFF) {
                    '"'.code -> "\\\""
                    '\\'.code -> "\\\\"
                    in 0x20..0x7E -> code.toChar().toString()
                    else -> "\\x" + code.toString(16).uppercase().padStart(2, '0')
                }
            }

        /** [text], a binary as [render] prints it, back to its text. */
        fun unescape(text: String): String {
            val bytes = ByteArrayOutputStream()
            var index = 0

            while (index < text.length) {
                val char = text[index++]

                if (char != '\\') {
                    bytes.write(char.code)
                } else when (val escaped = text[index++]) {
                    'x' -> bytes.write(text.substring(index, index + 2).toInt(16)).also { index += 2 }
                    else -> bytes.write(escaped.code)
                }
            }

            return bytes.toString(Charsets.UTF_8)
        }

        /** `meta/1` of the generator: every node's metadata but its position, shortened, sorted and deduplicated. */
        fun meta(term: OtpErlangObject): List<OtpErlangTuple> {
            val found = LinkedHashSet<OtpErlangTuple>()

            fun walk(term: OtpErlangObject) {
                when {
                    term is OtpErlangTuple && term.arity() == 3 -> {
                        (term.elementAt(1) as? OtpErlangList)?.elements()?.forEach { entry ->
                            val key = key(entry as OtpErlangTuple)

                            if (key !in POSITION) found.add(OtpErlangTuple(arrayOf(entry.elementAt(0), short(key, entry.elementAt(1)))))
                        }
                        walk(term.elementAt(0))
                        term.elementAt(2).takeIf { it is OtpErlangList || it is OtpErlangString }?.let(::walk)
                    }
                    term is OtpErlangTuple && term.arity() == 2 -> term.elements().forEach(::walk)
                    term is OtpErlangList -> term.elements().forEach(::walk)
                }
            }

            walk(term)

            return found.sortedWith(::compare)
        }

        private fun short(key: String, value: OtpErlangObject): OtpErlangObject =
            when {
                key == "counter" -> N
                value is OtpErlangAtom || value is OtpErlangLong -> value
                value is OtpErlangTuple && value.arity() == 2 && value.elements().all { it is OtpErlangAtom } -> value
                value is OtpErlangList || value is OtpErlangString -> OtpErlangAtom("list")
                else -> OtpErlangAtom("other")
            }

        /** Erlang's term order, over the terms [short] gives: numbers, then atoms, then tuples. */
        private fun compare(left: OtpErlangObject, right: OtpErlangObject): Int {
            val rank = rank(left).compareTo(rank(right))

            return when {
                rank != 0 -> rank
                left is OtpErlangLong -> left.bigIntegerValue().compareTo((right as OtpErlangLong).bigIntegerValue())
                left is OtpErlangAtom -> left.atomValue().compareTo((right as OtpErlangAtom).atomValue())
                else -> {
                    val (l, r) = left as OtpErlangTuple to right as OtpErlangTuple

                    l.arity().compareTo(r.arity()).takeIf { it != 0 }
                        ?: l.elements().zip(r.elements()).map { (a, b) -> compare(a, b) }.firstOrNull { it != 0 }
                        ?: 0
                }
            }
        }

        private fun rank(term: OtpErlangObject): Int =
            when (term) {
                is OtpErlangLong -> 0
                is OtpErlangAtom -> 1
                is OtpErlangTuple -> 2
                else -> throw AssertionError("no order for $term")
            }

        private fun isKeywords(list: OtpErlangList) =
            list.lastTail == null &&
                list.elements().all { it is OtpErlangTuple && it.arity() == 2 && it.elementAt(0) is OtpErlangAtom }

        private fun key(entry: OtpErlangTuple) = (entry.elementAt(0) as OtpErlangAtom).atomValue()

        val POSITION = setOf("line", "column")

        val N = OtpErlangAtom("N")
    }
}
