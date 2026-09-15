package org.elixir_lang.parser_definition

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirParserDefinition
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.parser.ElixirParser
import org.elixir_lang.psi.call.Call
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Nesting is depth-first, so a parser that re-reads a nested group's contents once per enclosing group costs
 * exponentially in the nesting depth.
 *
 * Every valid shape has a shallow case, asserted against its parse tree and the reference quoter, and a deep case
 * asserted only to finish. [testDeepNestedParenthesesBlock] through [testDeepNestedWithPastRecursionLimit] each took
 * minutes before the lookaheads and the memo in `ElixirParserUtil`, and [testManyNoParenthesesCallsWithoutCommas]
 * takes minutes if each statement's lookahead scans on to the end of the file. The rest are controls that were
 * already linear, because every alternative tried on them fails on its first token rather than after re-reading the
 * whole group.
 *
 * The `testStabSignature` cases each put a stab operation's `->` after a token the lookahead could miscount - `do`,
 * `end`, `fn` or a block keyword such as `else` in each of their lexical roles, or the `(` of `.(` - so a scan that
 * miscounts it hides the `->` and the valid source parses with an error.
 */
class Issue4114TestCase : ParsingTestCase() {
    fun testNestedParenthesesBlock() = assertParsedAndQuotedCorrectly()
    fun testNestedAssignedParenthesesBlock() = assertParsedAndQuotedCorrectly()
    fun testNestedCallArgumentParentheses() = assertParsedAndQuotedCorrectly()
    fun testNestedMatchParentheses() = assertParsedAndQuotedCorrectly()
    fun testParenthesisedAnonymousFunctions() = assertParsedAndQuotedCorrectly()
    fun testNestedSpacedCallArguments() = assertParsedAndQuotedCorrectly()
    fun testNestedSpacedCallArgumentsWithCommas() = assertParsedAndQuotedCorrectly()
    fun testNestedParenthesisedNoParenthesesCalls() = assertParsedAndQuotedCorrectly()
    fun testNestedNoParenthesesCalls() = assertParsedAndQuotedCorrectly()
    fun testParenthesisedDoBlockCalls() = assertParsedAndQuotedCorrectly()
    fun testNestedQuoteUnquote() = assertParsedAndQuotedCorrectly()
    fun testNestedStabsInParentheses() = assertParsedAndQuotedCorrectly()
    fun testNestedTryRescue() = assertParsedAndQuotedCorrectly()
    fun testParenthesisedDoBlocks() = assertParsedAndQuotedCorrectly()
    fun testNestedLists() = assertParsedAndQuotedCorrectly()
    fun testNestedTuples() = assertParsedAndQuotedCorrectly()
    fun testNestedMaps() = assertParsedAndQuotedCorrectly()
    fun testNestedAnonymousFunctions() = assertParsedAndQuotedCorrectly()
    fun testNestedDoBlocks() = assertParsedAndQuotedCorrectly()

    fun testStabSignatureDoBlock() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureAnonymousFunction() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureFieldDo() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureFieldEnd() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureFieldElse() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureKeywordKeys() = assertParsedAndQuotedCorrectly()
    fun testNoParenthesesArgumentsKeywordKeys() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureFieldEndAfterNewline() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureFieldEndAfterComment() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureDotCall() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureWordAfterNumberDo() = assertParsedAndQuotedCorrectly()
    fun testStabSignatureWordAfterNumberEnd() = assertParsedAndQuotedCorrectly()

    /**
     * Half-typed source whose groups do not balance, where error recovery reads a `->` or `,` inside an unclosed group
     * as the stab operation's or the arguments'. The trees are the ones the parser produced before the lookaheads.
     * Where the error lists the operators that could come next, `//` is one only from Elixir 1.12, so those trees are
     * checked in both language levels.
     */
    fun testHalfTypedStabGuardBelow1_12() = assertParsedWithErrorsIn(ElixirLanguageLevel.V1_11, "HalfTypedStabGuard")
    fun testHalfTypedStabGuardFrom1_12() = assertParsedWithErrorsIn(ElixirLanguageLevel.V1_12, "HalfTypedStabGuard")
    fun testHalfTypedStabGuardAtEnd() = assertParsedWithErrors()
    fun testHalfTypedCallArguments() = assertParsedWithErrors()
    fun testHalfTypedCaseClauseAtEndBelow1_12() =
        assertParsedWithErrorsIn(ElixirLanguageLevel.V1_11, "HalfTypedCaseClauseAtEnd")
    fun testHalfTypedCaseClauseAtEndFrom1_12() =
        assertParsedWithErrorsIn(ElixirLanguageLevel.V1_12, "HalfTypedCaseClauseAtEnd")
    fun testHalfTypedCallArgumentsAtEnd() = assertParsedWithErrors()

    /** [source]`.ex` parsed in [languageLevel] against the golden named for the test. */
    private fun assertParsedWithErrorsIn(languageLevel: ElixirLanguageLevel, source: String) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        val expectedName = getTestName(false)
        parseFile(expectedName, loadFile("$source.ex"))
        checkResult(expectedName, myFile)
        assertWithLocalError()
        Quoter.assertError(myFile)
    }

    fun testDeepNestedParenthesesBlock() = assertParsesWithinBudget(block("(", DEPTH))
    fun testDeepNestedAssignedParenthesesBlock() = assertParsesWithinBudget(block("q = (", DEPTH))
    fun testDeepNestedCallArgumentParentheses() = assertParsesWithinBudget("f(${surround("(", ")", DEPTH)})\n")
    fun testDeepNestedMatchParentheses() = assertParsesWithinBudget("x = ${surround("(", ")", DEPTH)}\n")
    fun testDeepNestedParenthesisedAnonymousFunctions() =
        assertParsesWithinBudget("${surround("(fn -> ", " end)", DEPTH)}\n")

    fun testDeepNestedSpacedCallArguments() = assertParsesWithinBudget("${surround("f (", ")", DEPTH)}\n")
    fun testDeepNestedSpacedCallArgumentsWithCommas() =
        assertParsesWithinBudget("${surround("f (", "), 2", 22)}\n")

    fun testDeepNestedParenthesisedNoParenthesesCalls() = assertParsesWithinBudget("${surround("(f ", ")", DEPTH)}\n")
    fun testDeepNestedNoParenthesesCalls() = assertParsesWithinBudget("${"f ".repeat(16)}1\n")
    fun testDeepParenthesisedDoBlockCalls() = assertParsesWithinBudget("${surround("(foo do ", " end)", DEPTH)}\n")
    fun testDeepNestedQuoteUnquote() = assertParsesWithinBudget("${surround("quote do unquote(", ") end", DEPTH)}\n")
    fun testDeepNestedStabsInParentheses() =
        assertParsesWithinBudget("${surround("((a -> ", ") -> b)", 16, "c")}\n")

    fun testDeepNestedTryRescue() =
        assertParsesWithinBudget("${surround("try do (", ") rescue e -> e end", DEPTH)}\n")

    fun testDeepTruncatedSpacedCallArguments() =
        assertParsesWithinBudget(truncated(surround("f (", ")", 16)), valid = false)

    fun testDeepTruncatedWith() =
        assertParsesWithinBudget(truncated(surround("with {:ok, a} <- (", "), do: a", 20)), valid = false)

    fun testDeepTruncatedFor() =
        assertParsesWithinBudget(truncated(surround("for x <- (", "), do: x", 16)), valid = false)

    fun testDeepTruncatedHeredocInterpolation() =
        assertParsesWithinBudget(truncated(surround("\"\"\"\n#{", "}\n\"\"\"", 32)), valid = false)

    fun testDeepTruncatedSigilHeredocInterpolation() =
        assertParsesWithinBudget(truncated(surround("~s\"\"\"\n#{", "}\n\"\"\"", 32)), valid = false)

    fun testDeepClosersDeletedWith() =
        assertParsesWithinBudget(
            surround("with {:ok, a} <- (", "), do: a", 28).replaceFirst("), do: a", ", do: a"),
            valid = false
        )

    fun testDeepClosersDeletedFor() =
        assertParsesWithinBudget(
            surround("for x <- (", "), do: x", 28).replaceFirst("), do: x", ", do: x"),
            valid = false
        )

    /** Nesting past GrammarKit's recursion limit fails to parse; the failure must not be retried per level. */
    fun testDeepNestedStabsInParenthesesPastRecursionLimit() =
        assertParsesWithinBudget("${surround("((a -> ", ") -> b)", 40, "c")}\n", valid = false)

    /**
     * Error recovery past the recursion limit resumes parsing, and on these shapes each resumption descends to the
     * limit again. The work counts miss what that costs, as it is spent formatting the limit's error each time.
     */
    fun testPastRecursionLimitWithinBudget() {
        val heredocs = surround("\"\"\"\n#{", "}\n\"\"\"", 1024)

        for (source in listOf(
            surround("for x <- (", "), do: x", 512).replaceFirst("), do: x", ", do: x"),
            surround("with {:ok, a} <- (", "), do: a", 512).replaceFirst("), do: a", ", do: a"),
            heredocs.substring(0, heredocs.length / 2) + "\n",
            truncated(surround("try do 1 rescue e -> ", " end", 1024)),
            surround("(fn -> [%{a: {", "}}] end)", 512),
        )) {
            assertParsesWithinBudget(source, valid = false, budget = PAST_RECURSION_LIMIT_BUDGET_MILLISECONDS)
        }
    }

    /**
     * `0b1end` lexes as invalid digits that the remapper turns into `end` only when the parser reaches them, so the
     * lookahead has to work out that `end` itself: answering that anything may follow let every level try both readings.
     * `1end` is remapped only by Elixir before 1.12 and stays invalid digits otherwise.
     */
    fun testDeepNestingAroundAWordAfterANumber() {
        assertParsesWithinBudget(
            surround("(foo do ", " end)", 16, "0b1").replaceFirst(" end)", "end)") + "\n",
            budget = PAST_RECURSION_LIMIT_BUDGET_MILLISECONDS
        )
        assertParsesWithinBudget(
            surround("(foo do ", " end)", 16).replaceFirst(" end)", "end)"),
            valid = false,
            budget = PAST_RECURSION_LIMIT_BUDGET_MILLISECONDS
        )
    }

    /**
     * At these depths a reading that is tried and abandoned reaches GrammarKit's recursion limit while the accepted one
     * stays under it, so the source parses and nothing may give up on it.
     */
    fun testDeepestNestingUnderRecursionLimitParses() {
        for (source in listOf(
            surround("with {:ok, a} <- (", "), do: a", 58),
            surround("for x <- (", "), do: x", 58),
            surround("f a: (", ")", 58),
            surround("quote do unquote(", ") end", 47),
            surround("&(", ")", 82, "&1"),
        )) {
            assertParsesWithinBudget("$source\n")
        }
    }

    /** Each statement reaches the recursion limit at a token of its own, and none of them is nested past it. */
    fun testManyStatementsNestedJustUnderRecursionLimitParse() =
        assertParsesWithinBudget("${surround("&(", ")", 82, "&1")}\n".repeat(8))

    fun testDeepNestedWithPastRecursionLimit() =
        assertParsesWithinBudget("${surround("with {:ok, a} <- (", "), do: a", 72)}\n", valid = false)

    /** Not nested at all: each statement's scan for a `,` must not run on to the end of the file. */
    fun testManyNoParenthesesCallsWithoutCommas() = assertParsesWithinBudget("foo 1\n".repeat(64_000))

    fun testDeepNestedParenthesisedDoBlocks() =
        assertParsesWithinBudget("${surround("(case 1 do 1 -> ", " end)", DEPTH)}\n")

    fun testDeepNestedLists() = assertParsesWithinBudget("${surround("[", "]", DEPTH)}\n")
    fun testDeepNestedTuples() = assertParsesWithinBudget("${surround("{", "}", DEPTH)}\n")
    fun testDeepNestedMaps() = assertParsesWithinBudget("${surround("%{a: ", "}", DEPTH)}\n")
    fun testDeepNestedAnonymousFunctions() = assertParsesWithinBudget("${surround("fn -> ", " end", DEPTH)}\n")
    fun testDeepNestedDoBlocks() = assertParsesWithinBudget(block("foo do", DEPTH, "end"))

    /**
     * Doubling the depth of any nesting shape - complete, cut off inside its opening, or with one level's closers
     * broken - must not grow the parser's work per character, past GrammarKit's recursion limit too, nor may that work
     * be large.
     * Work is counted as calls on the [PsiBuilder], so the check does not depend on the machine's speed.
     */
    fun testWorkPerCharacterDoesNotGrowWithDepth() {
        val growing = mutableListOf<String>()

        for ((name, shape) in WORK_SHAPES) {
            for ((variant, source) in workVariants(shape)) {
                val depths = listOf(
                    BELOW_RECURSION_LIMIT / 2,
                    BELOW_RECURSION_LIMIT,
                    NEAR_RECURSION_LIMIT,
                    PAST_RECURSION_LIMIT,
                    PAST_RECURSION_LIMIT * 2
                )
                val works = depths.map { work(source(it)) }

                if (works[1] / works[0] > MAX_WORK_GROWTH ||
                    works[4] / works[3] > MAX_WORK_GROWTH ||
                    works[2] > MAX_WORK_PER_CHARACTER ||
                    works[4] > MAX_WORK_PER_CHARACTER_PAST_LIMIT
                ) {
                    growing += "$name [$variant]: ${works.joinToString { "%.0f".format(it) }} calls per character"
                }
            }
        }

        assertTrue("work per character is high or grows with depth:\n" + growing.joinToString("\n"), growing.isEmpty())
    }

    /** A statement nested past the recursion limit leaves the statements before it as they parse on their own. */
    fun testStatementsBeforeOnePastTheRecursionLimitParse() {
        val before = "def a, do: 1\n"
        myFile = createPsiFile("a", before + surround("for x <- (", "), do: x", 512).replaceFirst("), do: x", ", do: x"))
        ensureParsed(myFile)

        val definition = myFile.firstChild
        assertTrue(definition is Call)
        assertEquals(before.trimEnd(), definition.text)
        assertEquals(before.length, PsiTreeUtil.findChildOfType(myFile, PsiErrorElement::class.java)?.textOffset)
    }

    /** Builder calls per character for parsing [source]. */
    private fun work(source: String): Double {
        val definition = ElixirParserDefinition()
        val builder = PsiBuilderFactory.getInstance().createBuilder(definition, definition.createLexer(project), source)
        var calls = 0L
        val counting = Proxy.newProxyInstance(
            PsiBuilder::class.java.classLoader,
            arrayOf(PsiBuilder::class.java)
        ) { _, method, arguments ->
            calls++

            try {
                method.invoke(builder, *(arguments ?: emptyArray()))
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
        } as PsiBuilder
        ElixirParser().parseLight(definition.fileNodeType, counting)

        return calls.toDouble() / source.length
    }

    /**
     * [shape] as written; cut off after its openings and each prefix of one more, as while it is still being typed;
     * and with its middle level's closers broken: a closer deleted, two adjacent different closers swapped, or a closer
     * doubled.
     */
    private fun workVariants(shape: WorkShape): List<Pair<String, (Int) -> String>> {
        val complete: Pair<String, (Int) -> String> = "valid" to { depth -> shape.nest(depth) }
        val cut = (0 until shape.opening.length).map { length ->
            val prefix = shape.opening.take(length)
            val variant: Pair<String, (Int) -> String> =
                "cut after ${prefix.replace("\n", "\\n")}|" to { depth -> shape.prefix + shape.opening.repeat(depth) + prefix + "\n" }
            variant
        }
        val closing = shape.closing
        val swap = (0 until closing.length - 1)
            .firstOrNull { closing[it] in CLOSERS && closing[it + 1] in CLOSERS && closing[it] != closing[it + 1] }
        val extra = closing.indexOfFirst { it in CLOSERS }
        val end = closing.indexOf("end")
        val deleted = when {
            extra >= 0 -> closing.removeRange(extra, extra + 1)
            end >= 0 -> closing.removeRange(end, end + "end".length)
            else -> error("closing `$closing` has no closer to delete")
        }
        val broken = listOfNotNull(
            "closer deleted" to deleted,
            swap?.let { "closers swapped" to closing.substring(0, it) + closing[it + 1] + closing[it] + closing.substring(it + 2) },
            extra.takeIf { it >= 0 }?.let { "extra closer" to closing.substring(0, it + 1) + closing.substring(it) },
        ).map { (variant, middle) ->
            val source: Pair<String, (Int) -> String> = variant to { depth ->
                shape.prefix + shape.opening.repeat(depth) + shape.core +
                    closing.repeat(depth / 2) + middle + closing.repeat(depth - depth / 2 - 1) + shape.suffix
            }
            source
        }

        return listOf(complete) + cut + broken
    }

    /** `opening` on its own line per level, wrapping `1`, closed by `closing` on its own line. */
    private fun block(opening: String, depth: Int, closing: String = ")"): String {
        val builder = StringBuilder()

        for (level in 0 until depth) {
            builder.append("  ".repeat(level)).append(opening).append('\n')
        }

        builder.append("  ".repeat(depth)).append("1\n")

        for (level in depth - 1 downTo 0) {
            builder.append("  ".repeat(level)).append(closing).append('\n')
        }

        return builder.toString()
    }

    private fun surround(opening: String, closing: String, depth: Int, core: String = "1"): String =
        opening.repeat(depth) + core + closing.repeat(depth)

    /** The first half of [source], as while it is still being typed. */
    private fun truncated(source: String): String = source.substring(0, source.length / 2) + "\n"

    /**
     * Parses under a progress indicator a watchdog cancels, so an exponential parse fails in seconds rather than
     * hanging the suite. Both the lookahead and [com.intellij.lang.impl.PsiBuilderImpl] poll cancellation, so either
     * half of a runaway parse stops.
     */
    private fun assertParsesWithinBudget(source: String, valid: Boolean = true, budget: Long = BUDGET_MILLISECONDS) {
        val indicator = EmptyProgressIndicator()
        val watchdog = Thread({
            try {
                Thread.sleep(budget)
            } catch (_: InterruptedException) {
                return@Thread
            }

            indicator.cancel()
        }, "Issue4114TestCase budget")
        watchdog.isDaemon = true
        watchdog.start()

        try {
            ProgressManager.getInstance().runProcess(
                {
                    myFile = createPsiFile("a", source)
                    ensureParsed(myFile)
                },
                indicator
            )
        } catch (_: ProcessCanceledException) {
            fail("Parsing took longer than $budget ms")
        } finally {
            watchdog.interrupt()
        }

        if (valid) {
            assertWithoutLocalError()
        }
    }

    override fun getTestDataPath(): String = "${super.getTestDataPath()}/issue_4114"

    private class WorkShape(
        val opening: String,
        val closing: String,
        val core: String = "1",
        val prefix: String = "",
        val suffix: String = "\n"
    ) {
        fun nest(depth: Int): String = prefix + opening.repeat(depth) + core + closing.repeat(depth) + suffix
    }

    companion object {
        /** Deep enough that a parse doubling per level runs for minutes, shallow enough to stay readable. */
        private const val DEPTH = 12

        /** Orders of magnitude above the milliseconds a linear parse of [DEPTH] levels takes. */
        private const val BUDGET_MILLISECONDS = 30_000L

        /** Below GrammarKit's recursion limit for every shape. */
        private const val BELOW_RECURSION_LIMIT = 8

        /** Near GrammarKit's recursion limit for the shapes that open several groups per level. */
        private const val NEAR_RECURSION_LIMIT = 16

        /** Several times the nesting at which every shape reaches GrammarKit's recursion limit. */
        private const val PAST_RECURSION_LIMIT = 256

        /** Linear work gives 1 when the depth doubles, and work that multiplies per level far more. */
        private const val MAX_WORK_GROWTH = 1.5

        /** Linear work with a large constant is as slow in practice. The worst shape here takes about 2,000. */
        private const val MAX_WORK_PER_CHARACTER = 4_000.0

        /** Past the limit, GrammarKit's error recovery costs up to about 8,000 on the worst shape here. */
        private const val MAX_WORK_PER_CHARACTER_PAST_LIMIT = 16_000.0

        /** A hundred times what these take; reaching the limit again after each error recovery took seconds. */
        private const val PAST_RECURSION_LIMIT_BUDGET_MILLISECONDS = 1_000L

        private const val CLOSERS = ")]}>"

        private val WORK_SHAPES: List<Pair<String, WorkShape>> = listOf(
            "match parens" to WorkShape("(", ")", prefix = "x = "),
            "semicolon paren blocks" to WorkShape("(a; ", ")"),
            "call double parens" to WorkShape("(", ")", prefix = "f(", suffix = ")\n"),
            "nested calls" to WorkShape("f(", ")"),
            "nested calls two args" to WorkShape("f(1, ", ")"),
            "spaced call args" to WorkShape("f (", ")"),
            "paren no-parens calls" to WorkShape("(f ", ")"),
            "anonymous call" to WorkShape("f.(", ")"),
            "paren fn" to WorkShape("(fn -> ", " end)"),
            "paren fn arg" to WorkShape("(fn x -> ", " end)", "x"),
            "fn" to WorkShape("fn -> ", " end"),
            "fn called" to WorkShape("(fn -> ", " end).()"),
            "fn in call" to WorkShape("f(fn x -> ", " end)", "x"),
            "fn multi clause" to WorkShape("fn 1 -> 1; x -> ", " end", "x"),
            "do blocks" to WorkShape("foo do\n", "\nend"),
            "paren do blocks" to WorkShape("(foo do ", " end)"),
            "paren case" to WorkShape("(case 1 do 1 -> ", " end)"),
            "case" to WorkShape("case 1 do 1 -> ", " end"),
            "if else" to WorkShape("if a do ", " else 1 end"),
            "if keyword" to WorkShape("if a, do: (", ")"),
            "cond" to WorkShape("cond do true -> ", " end"),
            "with" to WorkShape("with {:ok, a} <- (", "), do: a"),
            "try rescue" to WorkShape("try do 1 rescue e -> ", " end"),
            "receive after" to WorkShape("receive do x -> x after 1 -> ", " end"),
            "for" to WorkShape("for x <- (", "), do: x"),
            "quote unquote" to WorkShape("quote do unquote(", ") end"),
            "lists" to WorkShape("[", "]"),
            "tuples" to WorkShape("{", "}"),
            "two tuples" to WorkShape("{1, ", "}"),
            "maps" to WorkShape("%{a: ", "}"),
            "structs" to WorkShape("%S{a: ", "}"),
            "map update" to WorkShape("%{m | a: ", "}"),
            "keyword lists" to WorkShape("[a: ", "]"),
            "keyword args" to WorkShape("f(a: ", ")"),
            "no-parens keyword args" to WorkShape("f a: (", ")"),
            "bitstrings" to WorkShape("<<(", ")::binary>>", "<<1>>"),
            "interpolation" to WorkShape("\"#{", "}\""),
            "sigil interpolation" to WorkShape("~s(#{", "})"),
            "heredoc interpolation" to WorkShape("\"\"\"\n#{", "}\n\"\"\""),
            "sigil heredoc interpolation" to WorkShape("~s\"\"\"\n#{", "}\n\"\"\""),
            "captures" to WorkShape("&(", ")", "&1"),
            "access" to WorkShape("[b", "]", prefix = "a"),
            "unary minus" to WorkShape("-(", ")"),
            "not" to WorkShape("not (", ")", "true"),
            "addition" to WorkShape("1 + (", ")"),
            "pipe" to WorkShape("a |> (", ")"),
            "range" to WorkShape("1..(", ")"),
            "type operator" to WorkShape("x :: (", ")"),
            "when" to WorkShape("x when (", ")", "y"),
            "guard and" to WorkShape("(x > 0 and ", ")", "true", prefix = "fn x when ", suffix = " -> x end\n"),
            "stab in parens" to WorkShape("((a -> ", ") -> b)", "c"),
            "spec arrows" to WorkShape("(a -> ", ")", "b", prefix = "@spec f(", suffix = ") :: c\n"),
            "match chain" to WorkShape("x = (y = ", ")"),
            "mixed containers" to WorkShape("(fn -> [%{a: {", "}}] end)"),
            "field end in guard" to WorkShape("(fn i when i.end > 0 -> ", " end)", "i"),
            "brackets" to WorkShape("{[(", ")]}"),
            "spaced brackets" to WorkShape("{ [ ( ", " ) ] }"),
            "map list call tuple" to WorkShape("%{a: [f({", "})]}"),
            "fn in brackets" to WorkShape("[(fn -> {", "} end)]"),
        )
    }
}
