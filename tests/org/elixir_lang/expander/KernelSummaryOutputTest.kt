package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
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
            actual(ElixirLanguageLevel.of("1.14.0-rc.0"), OutputFixture.Case("pipe_right", "x |> (f(1) |> g())", Env.Context.NONE, "")),
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
            cases(leg).filter { family(it.label) == family && it.expected != OutputFixture.SKIP }.map { case ->
                val header = "## $leg ${case.label}: ${case.source}\n"

                header + case.expected to header + actual(ElixirLanguageLevel.of(leg), case)
            }
        }

        assertFalse("no case of $family", compared.isEmpty())
        assertEquals(compared.joinToString("\n") { it.first }, compared.joinToString("\n") { it.second })
    }

    /** What the expander gives [case] at [level], printed as the generator prints Elixir's. */
    private fun actual(level: ElixirLanguageLevel, case: OutputFixture.Case): String {
        val env = Env.empty(level, KERNEL_IMPORTS).copy(context = case.context)
        val state = ExState.empty(level).copy(read = mapOf(Variable("x", Variable.NIL) to 0, Variable("y", Variable.NIL) to 1), version = 2)

        val run = Run(level, ExpansionObserver.NONE, NO_EXPORTS, NO_STRUCTS)

        // Parsed as [level]'s Elixir parses it, whichever leg runs the test.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)

        return when (val expanded = macroExpandOnce(lower(case.source, level), state, env, run)) {
            is MacroExpanded.Node -> {
                val term = expanded.node.toOtp()

                OutputFixture.render(term) + "\nmeta: " + OutputFixture.render(OtpErlangList(OutputFixture.meta(term).toTypedArray()))
            }
            MacroExpanded.Dir -> "DIR"
            is MacroExpanded.Stopped ->
                when (val expansion = expanded.expansion) {
                    is Expansion.Error -> {
                        val message = OutputFixture.RAISE.matchEntire(case.expected)?.groupValues?.get(1)?.let(OutputFixture::unescape)

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

        fun family(label: String): String? = FAMILIES.firstOrNull { Regex("^$it(_|\\d|$)").containsMatchIn(label) }

        fun cases(leg: String): List<OutputFixture.Case> = OutputFixture.cases(File(ROOT, "$leg/A.txt"))
    }
}
