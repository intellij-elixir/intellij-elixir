package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.lowering.ElixirAst
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import kotlin.time.Duration.Companion.seconds

/**
 * A definition body that reaches `def` as a quoted value keeps its source nodes: over ExUnit's forms and the bodies of
 * `testData/org/elixir_lang/expander/ex_unit_body_origins`, each node the expansion reaches is *source* (entered at its
 * own origin), *macro-made* (entered at its call's origin) or *built* (reported through
 * [ExpansionObserver.builtIn], never entered).
 */
class ExUnitBodyOriginsHeavyTest : HeavyTestCase() {
    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        exUnitOnQuoter()
        super.setUp()
        val sdk = LegSdk.setUp(module, testRootDisposable)
        // A library file has no module, so it reads the project's SDK.
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
    }

    override fun tearDown() {
        try {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    /**
     * Every variable the compiler keeps at a position whose text is its name is a source node with the compiler's
     * version, and what it reads is bound on a source node. Elixir's other occurrences are a macro's, and `_` has no
     * version in the expander.
     */
    fun testEachCallerWrittenVariableIsSourceAtTheCompilersVersion() {
        val project = project()
        val compiler = compilerVariables(project)
        val failures = mutableListOf<String>()
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for ((file, variables) in compiler) {
            val text = File(project.dir, file).readText()
            val rows = rows(project, file)

            val written = variables.filter { it.name != "_" && it.writtenIn(text) }
            val marked = written.filter { it.generated }.map(::occurrence).toSet()
            val unmarked = written.filterNot { it.generated }.filterNot { notSource(file, it) }.map(::occurrence)

            expected += unmarked.map { "$file $it" }
            // `assert` marks what it reads generated on some legs, so what's marked and written may be source or not.
            actual += rows
                .filter { it.isSource && it.name != "_" && it.function != "-" }
                .map { "${it.function} ${it.name} ${it.line}:${it.column} version=${it.version}" }
                .filterNot { it in marked && it !in unmarked }
                .map { "$file $it" }

            rows.filter { it.name != null && !it.built && it.version != null }
                .groupBy { listOf(it.function, it.name, it.context, it.version) }
                .filterValues { occurrences -> occurrences.any { it.isSource } && !occurrences.first().isSource }
                .forEach { (key, occurrences) -> failures += "$file $key binds at ${occurrences.first().text}" }
        }

        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
        assertEquals("", failures.joinToString("\n"))
    }

    private fun occurrence(variable: CompilerVariable) =
        "${variable.function} ${variable.name} ${variable.line}:${variable.column} version=${variable.version}"

    private fun notSource(file: String, variable: CompilerVariable): Boolean =
        when {
            // The tests after a `doctest` aren't reached before 1.14.
            file == "test/doctest_then_tests_test.exs" -> isBefore(version, "1.14.0")
            // The pattern and its read are equal terms on one line, told apart only by `stop_generated` from 1.20.
            file.endsWith("equal_on_a_line_test.exs") ->
                isBefore(version, "1.20.0") && variable.name == "context" && variable.line == 7 && variable.column == 11
            // The same under a list, which no position aligns, so the read stays built on every leg.
            file.endsWith("built_line_test.exs") ->
                variable.name == "context" && (variable.column == 76 || isBefore(version, "1.20.0"))
            else -> false
        }

    /**
     * `y` in `assert {:ok, y} = ...` is a source node at each occurrence the compiler keeps but the generated one, which
     * the macro makes; `x`, in the module's body, the same, and its value names the `def` after it.
     */
    fun testAnAssertMatchesVariableIsSourceAtEachOccurrenceButTheGeneratedOne() {
        val project = project()
        val file = "test/assert_match_test.exs"
        val compiler = compilerVariables(project).getValue(file).filter { it.name == "y" && it.function == "test match/1" && it.context == "nil" }

        assertEquals(
            compiler.map { if (it.generated) "macro-made version=${it.version}" else "${it.line}:${it.column} version=${it.version}" }.sorted(),
            rows(project, file).filter { it.name == "y" && it.function == "test match/1" && it.context == "nil" }
                .map { if (it.isSource) "${it.line}:${it.column} version=${it.version}" else if (it.built) "built" else "macro-made version=${it.version}" }
                .sorted(),
        )

        val x = rows(project, file).filter { it.name == "x" && it.function == "-" && it.context == "nil" }
        val sources = x.filter { it.isSource }

        assertEquals(listOf("4:16", "4:16", "5:25"), sources.map { "${it.line}:${it.column}" })
        assertEquals(sources[1].version, sources[2].version)
        assertEquals(1, x.count { !it.isSource && !it.built })
        assertTrue(
            ProjectModules.results(observed(project, file).expansion)
                .any { result -> result.table.entries.keys.any { it.name == "bound_1" && it.arity == 0 } },
        )
    }

    /** A `for`'s variable read in a `test`'s body is undefined there, as a definition's body starts a new env. */
    fun testAGeneratorsVariableInATestsBodyIsSourceAndUndefined() {
        val x = rows(project(), "test/for_read_test.exs").filter { it.name == "x" && it.function == "test t 1/1" }

        assertEquals(listOf("6:14 version=null"), x.filter { it.isSource }.map { "${it.line}:${it.column} version=${it.version}" })
        assertEquals(emptyList<Row>(), x.filter { it.built })
    }

    /**
     * Beside an `unquote`, each node of the body is entered as it is in the same body without one, and the fragment's
     * value is built with the `unquote`'s origin.
     */
    fun testAValueBodysSpineAndSiblingsAreEnteredAsALiteralBodysAreAndItsFragmentIsBuilt() {
        val project = project()
        val value = rows(project, "test/origins/value_fragment_test.exs")
        val literal = rows(project, "test/origins/value_literal_test.exs")
        val entered = { rows: List<Row>, replace: Boolean ->
            rows.filter { !it.built && it.kind != "Integer" && isDefinitionMade(it.function) }
                .groupingBy { "${it.function} ${it.kind} ${if (replace) it.text.replace("unquote(n)", "1") else it.text}" }
                .eachCount().toSortedMap().toString().replace(", ", ",\n")
        }

        assertEquals(entered(literal, false), entered(value, true))

        for (n in 1..2) {
            val fragments = value.filter { it.function == "test t $n/1" && it.built && it.kind == "Integer" }
            // Up to 1.16 `test` takes its body as a value, so the fragment has the call's origin.
            val origin = if (isBefore(version, "1.17.0")) "test \"t #{n}\", %{n: m} do" else "unquote(n)"

            assertEquals(listOf("$origin $n"), fragments.map { "${it.text} ${it.value}" })
        }

        assertNoNodeInTwoStores(value)
    }

    /** The arity is what it was written with, so each node is the body's. The fragment, a literal, has no row. */
    fun testASpliceKeepingTheArityLeavesEachNodeSource() {
        val rows = setups(project(), "test/origins/splice_test.exs")

        assertEquals(emptyList<String>(), rows.filter { it.built }.map(Row::toString))
        assertEquals(2, rows.count { it.callee == "foo" && it.text == "foo(unquote_splicing(xs), y)" })
        assertEquals(setOf("y", "context"), rows.filter { it.isSource }.map { it.name }.toSet())
        assertNoNodeInTwoStores(rows)
    }

    /** `foo(y)` isn't what was written, so what holds it is built, while its variables are still found. */
    fun testASpliceChangingTheArityLeavesWhatHoldsItBuiltAndItsVariablesSource() {
        val rows = setups(project(), "test/origins/splice_arity_test.exs").filter { it.function == "__ex_unit_setup_0/1" }

        for (kind in listOf("Block", "ListNode", "Tuple")) assertTrue("$kind in $rows", rows.any { it.built && it.kind == kind })
        assertTrue("foo in $rows", rows.any { it.built && it.callee == "foo" })
        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "foo" })
        assertEquals(listOf("7:11 context", "8:11 context", "8:7 y", "9:37 y"), rows.filter { it.isSource }.map { "${it.line}:${it.column} ${it.name}" }.sorted())
    }

    /** A value past a splice, handwritten onto a line, stays built rather than take the origin of what's at its position. */
    fun testAPositionPastASpliceGivesNoOrigin() {
        val rows = setups(project(), "test/origins/splice_shift_fragment_test.exs")

        assertEquals(listOf("foo(unquote_splicing([]), bar(unquote(q)), unquote_splicing(ys))"), rows.filter { !it.built && it.callee == "foo" }.map { it.text })
        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "bar" })
        assertEquals(2, rows.count { it.built && it.callee == "bar" })
    }

    /** `bar(2)` is found by its equal term; `bar(3)` and what changed arity beside it aren't anything written. */
    fun testASpliceShiftingAValueOntoASiblingLeavesItBuilt() {
        val rows = setups(project(), "test/origins/splice_shift_test.exs")

        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "foo" })
        assertEquals(listOf("bar(2)"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
        assertEquals(1, rows.count { it.built && it.callee == "bar" })
    }

    /** A term built with a call's name, arity and line can take that call's origin: a macro built this `bar(3)`. */
    fun testATermBuiltOnASourceCallsLineTakesItsOrigin() {
        val rows = setups(project(), "test/origins/built_line_test.exs")

        assertEquals(listOf("bar(unquote(q))", "bar(unquote(q))"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
    }

    /** The same past a splice that changes the arity, where no position is aligned. */
    fun testATermSplicedOntoASourceCallsLineTakesItsOrigin() {
        val rows = setups(project(), "test/origins/splice_arity_line_test.exs")

        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "foo" })
        assertEquals(listOf("bar(unquote(q))", "bar(unquote(q))"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
    }

    /** The same for a spliced term equal to a source term on its line. */
    fun testATermSplicedEqualToASourceTermTakesItsOrigin() {
        val rows = setups(project(), "test/origins/splice_equal_line_test.exs")

        assertEquals(listOf("bar(2)", "bar(2)"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
    }

    /** A value's arguments reversed by a macro align by position, so each `bar` has the other's origin. */
    fun testAValueAMacroReordersAlignsByPosition() {
        val rows = rows(project(), "test/origins/reversed_value_test.exs").filter { it.function == "r/0" }

        assertEquals(listOf("bar(unquote(x))", "bar(unquote(y))"), rows.filter { it.callee == "bar" }.map { it.text })
    }

    /** A macro's result holds the very terms it was given, so each `bar` keeps its own origin however it reorders them. */
    fun testAResultAMacroReordersKeepsEachArgumentsOrigin() {
        val rows = setups(project(), "test/origins/reversed_result_test.exs")

        assertEquals(listOf("bar(unquote(y))", "bar(unquote(x))"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
    }

    /** From the first splice on, each element and all below it can only be an equal term. */
    fun testNothingBelowAnElementPastASpliceTakesAnOriginByPosition() {
        val rows = setups(project(), "test/origins/two_splices_test.exs")

        assertEquals(1, rows.count { !it.built && it.callee == "foo" })
        assertEquals(emptyList<Row>(), rows.filter { !it.built && (it.callee == "bar" || it.callee == "baz") })
        assertEquals(listOf("bar", "bar", "baz"), rows.filter { it.built && (it.callee == "bar" || it.callee == "baz") }.map { it.callee }.sortedBy { it })
    }

    /** `y` is found by its equal term wherever it is, while the `foo` it is in has changed arity. */
    fun testAVariablePastASpliceIsFoundByItsEqualTerm() {
        val rows = setups(project(), "test/origins/splice_after_variable_test.exs")

        assertEquals(listOf("8:7 y", "9:37 y"), rows.filter { it.isSource && it.name == "y" }.map { "${it.line}:${it.column} ${it.name}" })
        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "foo" })
    }

    /** Two equal terms past a splice could each be either, so neither has an origin. */
    fun testEqualTermsPastASpliceStayBuilt() {
        val rows = setups(project(), "test/origins/equal_after_splice_test.exs")

        assertEquals(1, rows.count { !it.built && it.callee == "foo" })
        assertEquals(emptyList<Row>(), rows.filter { !it.built && it.callee == "bar" })
        assertEquals(2, rows.count { it.built && it.callee == "bar" })
    }

    /** Before the first splice a position still aligns, so `bar` is the source's, its fragment's value built. */
    fun testAPrefixBeforeASpliceIsSourceAndItsFragmentBuilt() {
        val rows = setups(project(), "test/origins/prefix_before_splice_test.exs")

        assertEquals(listOf("bar(unquote(q))"), rows.filter { !it.built && it.callee == "bar" }.map { it.text })
        assertEquals(1, rows.count { it.built && it.callee == "baz" })
    }

    /** Roots of one name and line are told apart by what fits; what fits both has no origin. */
    fun testARootThatFitsTwoSourcesStaysBuilt() {
        val rows = rows(project(), "test/origins/two_roots_test.exs").filter { it.function == "both/2" }

        assertEquals(listOf("foo(unquote(n))"), rows.filter { !it.built && it.callee == "foo" }.map { it.text })
        assertEquals(1, rows.count { it.built && it.callee == "foo" })
    }

    /** `test` wraps a value body in a block of its own up to 1.16, which stays built around the block written. */
    fun testAWrapperStaysBuiltAroundItsBody() {
        val rows = rows(project(), "test/origins/wrapper_test.exs")

        assertEquals(
            listOf("test ok last/1 20:5", "test three/1 14:5", "test two/1 9:5"),
            rows.filter { it.kind == "Block" && !it.built && it.function != "-" && it.line != 0 }.map { "${it.function} ${it.line}:${it.column}" }.sorted(),
        )
        assertEquals(
            if (isBefore(version, "1.17.0")) setOf("test one/1", "test two/1", "test three/1", "test ok last/1") else emptySet(),
            rows.filter { it.kind == "Block" && it.built && it.function != "-" }.map { it.function }.toSet(),
        )
        assertEquals(listOf("4:15", "5:5"), rows.filter { it.function == "test one/1" && it.isSource }.map { "${it.line}:${it.column}" })
    }

    /** The pattern and read of one line are one equal term before 1.20, which marks the pattern `stop_generated`. */
    fun testEqualTermsOnALineAlignUnderTheirBlock() {
        val rows = setups(project(), "test/origins/equal_on_a_line_test.exs")

        assertEquals(1, rows.count { it.isSource && it.name == "x" && it.line == 7 && it.column == 43 })
        assertEquals(
            if (isBefore(version, "1.20.0")) "built" else "source",
            if (rows.any { it.name == "context" && it.built }) "built" else "source",
        )
    }

    /** From 1.14 the tests after a `doctest` are reached, and what they write is source. */
    fun testTheVariablesAfterADoctestAreSource() {
        val rows = rows(project(), "test/doctest_then_tests_test.exs")
        val written = rows.filter { it.isSource }.map { "${it.line}:${it.column} ${it.name}" }.sorted()

        assertEquals(if (isBefore(version, "1.14.0")) emptyList() else listOf("15:32 user", "16:14 user", "6:17 context", "7:12 context"), written)
    }

    /** Nothing a caller wrote is in these bodies, and the expander says so of the module as it does elsewhere. */
    fun testADoctestsOtherBodiesHoldNoCallerWrittenVariable() {
        val project = project()

        for (file in listOf("test/doctest_duplicate_test.exs", "test/doctest_tagged_test.exs", "test/doctest_registered_test.exs")) {
            assertEquals(file, emptyList<Row>(), rows(project, file).filter { it.isSource && it.function != "-" })
        }
    }

    /** What a macro's `Macro.expand` made is the macro's wherever it goes on to: it is no node of the source. */
    fun testAnExpandedArgumentIsBuiltInAValue() {
        val rows = rows(project(), "test/origins/expanded_argument_test.exs")

        for (function in listOf("direct/0", "passed/0", "spined/0")) {
            val baz = rows.filter { it.function == function && it.callee == "baz" }

            assertEquals(function, listOf(true), baz.map { it.built })
        }
    }

    /** A body placed twice reads its variable at each place's version: a later module names definitions by the answers. */
    fun testABodyPlacedTwiceReadsItsVariableAtEachPlacesVersion() {
        val project = project()
        val file = "test/origins/shared_variable_test.exs"
        val expected = listOf("first_left", "second_right", "walked_first_left", "walked_second_right", "given_first_left", "given_second_right", "before_rebinding", "after_rebinding")
        val answers = expected + expected.filter { it.endsWith("_left") || it.endsWith("_right") }.map {
            if (it.endsWith("_left")) it.replace("_left", "_right") else it.replace("_right", "_left")
        }
        val names = answers.map { "$it/1" }
        val compiler = compilerVariables(project).getValue(file).map { it.function }.filter { it in names }.toSet()
        val expander = ProjectModules.results(observed(project, file).expansion)
            .flatMap { result -> result.table.entries.keys.map { "${it.name}/${it.arity}" } }
            .filter { it in names }
            .toList()

        assertEquals(expected.map { "$it/1" }.sorted(), compiler.sorted())
        assertEquals(compiler.sorted(), expander.sorted())
    }

    /** The sweep reads only what compiled, so a fixture Elixir raises on would leave it unseen. */
    fun testEveryOriginFixtureCompiles() {
        val compiled = compilerVariables(project()).keys.filter { it.startsWith("test/origins/") }

        assertEquals(ORIGINS.list()!!.map { "test/origins/$it" }.sorted(), compiled.sorted())
    }

    /** A value written with a `quote` in it is entered as written, and what the `quote` unquotes is the caller's. */
    fun testAnUnquoteInANestedQuoteIsBuiltAndItsVariableSource() {
        val rows = setups(project(), "test/origins/nested_quote_test.exs")

        assertEquals(listOf("foo", "quote"), rows.filter { !it.built && it.callee != null && it.callee != "=" }.map { it.callee })
        assertEquals(listOf("8:7", "9:33"), rows.filter { it.isSource && it.name == "a" }.map { "${it.line}:${it.column}" })
    }

    /** `context` in the list is equal to the pattern on its line, and a list gives no position, so it has no origin. */
    fun testAReadEqualToItsPatternUnderAListStaysBuilt() {
        val context = setups(project(), "test/origins/built_line_test.exs").filter { it.name == "context" && it.function != "-" }

        assertEquals(emptyList<String>(), context.filter { it.isSource && it.column == 76 }.map(Row::toString))
        assertEquals(if (isBefore(version, "1.20.0")) 2 else 1, context.count { it.built })
    }

    private val version: String get() = LegManifest.environment("ELIXIR_VERSION")

    private fun isDefinitionMade(function: String) = function.startsWith("test ") || function.startsWith("__ex_unit_setup")

    private fun assertNoNodeInTwoStores(rows: List<Row>) {
        val entered = rows.filter { !it.built }

        assertEquals(
            "a node in two stores",
            emptyMap<Int, Set<String>>(),
            entered.groupBy { it.identity }.mapValues { (_, each) -> each.map { it.function }.toSet() }.filterValues { it.size > 1 },
        )
        assertEquals(
            "a node entered twice",
            emptyMap<Int, List<Row>>(),
            entered.filter { it.name == null }.groupBy { it.identity }.filterValues { it.size > 1 },
        )
    }

    private fun setups(project: Corpus, file: String): List<Row> =
        rows(project, file).filter { it.function.startsWith("__ex_unit_setup") }

    private class Corpus(val dir: File, val phoenix: PhoenixCorpus) {
        val observed = HashMap<String, Observed>()
    }

    private class Observed(val rows: List<Row>, val expansion: FileExpansion)

    private data class Row(
        val function: String,
        val kind: String,
        val name: String?,
        val callee: String?,
        val line: Int,
        val column: Int,
        val text: String,
        val built: Boolean,
        val version: Int?,
        val context: String?,
        val value: String?,
        val identity: Int,
    ) {
        /** Entered at the variable's own text: a macro's has its call's origin. */
        val isSource: Boolean get() = name != null && !built && text == name
    }

    private class CompilerVariable(
        val function: String,
        val name: String,
        val line: Int,
        val column: Int,
        val version: Int?,
        val context: String,
        val generated: Boolean,
    ) {
        fun writtenIn(text: String): Boolean {
            if (line == 0 || column == 0) return false
            val at = text.lines().getOrNull(line - 1) ?: return false

            return at.startsWith(name, column - 1) && (at.getOrNull(column - 1 + name.length)?.let { !it.isLetterOrDigit() && it != '_' } ?: true)
        }
    }

    private class Observer(private val text: String) : ExpansionObserver {
        private val identities = IdentityHashMap<ElixirAst, Int>()

        val rows = mutableListOf<Row>()

        private fun function(env: Env) = env.function?.let { "${it.name}/${it.arity}" } ?: "-"

        private fun text(range: TextRange): String =
            text.substring(range.startOffset.coerceAtMost(text.length), range.endOffset.coerceAtMost(text.length)).lines().firstOrNull().orEmpty()

        private fun row(node: ElixirAst, function: String, built: Boolean, version: Int?): Row {
            val variable = (node as? ElixirAst.Call)?.takeIf { it.arguments == null && it.callee is ElixirAst.Literal.Atom }
            val start = node.meta.start

            return Row(
                function,
                node::class.simpleName!!,
                (variable?.callee as? ElixirAst.Literal.Atom)?.name,
                ((node as? ElixirAst.Call)?.takeIf { variable == null }?.callee as? ElixirAst.Literal.Atom)?.name,
                start.line,
                start.column,
                text(node.meta.origin),
                built,
                version,
                when (val context = variable?.context) {
                    null -> null
                    ElixirAst.VariableContext.Nil -> "nil"
                    is ElixirAst.VariableContext.Atom -> context.name
                },
                (node as? ElixirAst.Literal.Integer)?.value?.toString(),
                identities.getOrPut(node) { identities.size },
            )
        }

        private fun isVariableShape(node: ElixirAst) =
            (node as? ElixirAst.Call)?.let { it.arguments == null && it.callee is ElixirAst.Literal.Atom } == true

        override fun entering(node: ElixirAst, state: ExState, env: Env) {
            if (!isVariableShape(node)) rows += row(node, function(env), false, null)
        }

        override fun builtIn(node: ElixirAst, env: Env) {
            rows += row(node, function(env), true, null)
        }

        override fun left(node: ElixirAst, expansion: Expansion) {
            if (!isVariableShape(node)) return

            val expanded = expansion as? Expansion.Expanded

            rows += row(
                node,
                expanded?.env?.let(::function) ?: "?",
                false,
                expanded?.state?.read?.get(variable(node as ElixirAst.Call)),
            )
        }
    }

    private fun project(): Corpus {
        val dir = createTempDir("ex_unit_body_origins")
        FileUtil.copyDir(ExUnitFormsHeavyTest.CORPUS, dir)
        // Under its own module names, the same length: `ExUnitFormsHeavyTest` may be compiling the corpus on the same node.
        dir.walkTopDown().filter(File::isFile).forEach { it.writeText(it.readText().replace("ExUnitForms", "ExUnitBodys")) }
        if (isBefore(version, "1.20.0")) FileUtil.delete(File(dir, "test/v120"))
        FileUtil.copyDir(ORIGINS, File(dir, "test/origins"))

        return Corpus(dir, PhoenixCorpus(project, testRootDisposable, dir).also { it.setUp(module) })
    }

    private fun observed(corpus: Corpus, file: String): Observed =
        corpus.observed.getOrPut(file) {
            val source = File(corpus.dir, file)
            val psi = corpus.phoenix.psi(source)!!
            val observer = Observer(psi.text)
            val holder = ProjectModules.getInstance(project)
            val expansion = corpus.phoenix.quiet {
                ReadAction.nonBlocking(Callable { holder.observe(psi, legInputs, observer) }).executeSynchronously()
            }

            Observed(observer.rows, expansion)
        }

    private fun rows(corpus: Corpus, file: String): List<Row> = observed(corpus, file).rows

    /**
     * Each variable occurrence in the definitions of each module Elixir compiles, by the file it's in, from each module's
     * debug info, columns kept, as a test's `Macro.prewalk` of it reads them. A file Elixir raises on has no entry.
     */
    private fun compilerVariables(corpus: Corpus): Map<String, List<CompilerVariable>> {
        val prefix = corpus.dir.invariantSeparatorsPath + "/"
        val files = File(corpus.dir, "test").walkTopDown().filter(File::isFile).map { it.relativeTo(corpus.dir).invariantSeparatorsPath }
            .sortedWith(compareBy({ !it.startsWith("test/support/") }, { it }))
            .toList()
        val compiled = Quoter.compile(
            """
            dir = ${elixirString(corpus.dir.invariantSeparatorsPath)}
            ExUnit.start(autorun: false)
            ${ExUnitFormsHeavyTest.COMPILE_APPS}

            for file <- [${files.joinToString(", ") { elixirString(it) }}] do
              path = Path.join(dir, file)

              try do
                quoted = Code.string_to_quoted!(File.read!(path), file: path, columns: true)

                variables =
                  for {module, binary} <- Code.compile_quoted(quoted, path),
                      {:ok, {^module, [debug_info: {:debug_info_v1, backend, data}]}} = :beam_lib.chunks(binary, [:debug_info]),
                      {:ok, %{definitions: definitions}} = backend.debug_info(:elixir_v1, module, data, []),
                      {{function, arity}, _, _, clauses} <- definitions,
                      {_, args, guards, body} <- clauses,
                      variable <-
                        elem(
                          Macro.prewalk([args, guards, body], [], fn
                            {name, meta, context} = node, acc when is_atom(name) and is_list(meta) and is_atom(context) ->
                              {node, [{name, meta, context} | acc]}

                            node, acc ->
                              {node, acc}
                          end),
                          1
                        ) do
                    {name, meta, context} = variable

                    {"#{function}/#{arity}", name, meta[:line] || 0, meta[:column] || 0, meta[:version], context, meta[:generated] == true}
                  end

                IntellijElixir.Quoter.Probe.send(__ENV__, {:variables, path, variables})
              rescue
                exception -> IntellijElixir.Quoter.Probe.send(__ENV__, {:raised, path, exception.__struct__})
              end
            end
            """.trimIndent(),
            COMPILE_TIMEOUT,
        )

        assertEquals("forms compile: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.mapNotNull { it as OtpErlangTuple }
            .filter { it.elementAt(0) == OtpErlangAtom("variables") }
            .associate { message ->
                val path = String((message.elementAt(1) as OtpErlangBinary).binaryValue()).removePrefix(prefix)

                path to (message.elementAt(2) as OtpErlangList).map { variable ->
                    val (function, name, line, column) = (variable as OtpErlangTuple).elements()
                    val elements = variable.elements()

                    CompilerVariable(
                        String((function as OtpErlangBinary).binaryValue()),
                        (name as OtpErlangAtom).atomValue(),
                        (line as OtpErlangLong).intValue(),
                        (column as OtpErlangLong).intValue(),
                        (elements[4] as? OtpErlangLong)?.intValue(),
                        (elements[5] as OtpErlangAtom).atomValue(),
                        elements[6] == OtpErlangAtom("true"),
                    )
                }
            }
    }

    private companion object {
        val ORIGINS = File("testData/org/elixir_lang/expander/ex_unit_body_origins")
        val COMPILE_TIMEOUT = 120.seconds
    }
}
