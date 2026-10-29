package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.language_level.ElixirLanguageFeature.ANNOTATE_CASE
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_SORTED
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_RANGE_LITERAL_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ENV_VERSIONED_VARS
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile

/**
 * The expanded AST a run that asks for it builds for each case's expression, against what `:elixir_expand.expand`
 * returns for it on the leg, compared without positions and `version`.
 */
class ExpandedAstTest : SdkProbeTestCase() {
    fun testGuardsInADefguard() = assertEqual("defguard", GUARD_CASES)

    fun testGuardsInADef() = assertEqual("guard", GUARD_CASES.filter { it.args.isNotEmpty() })

    fun testGuardsInACase() = assertEqual("case", GUARD_CASES.filter { it.args.isNotEmpty() })

    /**
     * `in` of a literal range: from 1.20 Elixir marks every node of its output `generated: true`, and the plugin's
     * expansion keeps the mark on the `is_integer` call only. That meta is a deferred divergence: no answer reads it.
     */
    fun testAnInOfARangeDiffersOnlyInGenerated() {
        val diverges = IN_RANGE_LITERAL_GENERATED.isSufficient(legLevel())

        assertNoFailures(GUARD_CONTEXTS.mapNotNull { differsOnlyInGenerated(it, IN_RANGE, diverges) })
    }

    fun testBodies() = assertEqual("body", BODY_CASES)

    /**
     * `if` of a condition that returns a boolean: before 1.18 Elixir marks the `false` and `true` clauses it rewrites
     * the `case` to `generated: true`, and the plugin's clauses don't carry it. That meta is a deferred divergence.
     */
    fun testAnIfDiffersOnlyInItsClausesGenerated() =
        assertNoFailures(listOfNotNull(differsOnlyInGenerated("body", IF, !ANNOTATE_CASE.isSufficient(legLevel()))))

    /** `__ENV__.versioned_vars`: Elixir's node is the field's map itself. */
    fun testAnEnvFieldHoldingAMap() {
        if (MACRO_ENV_VERSIONED_VARS.isSufficient(legLevel())) assertEqual("body", listOf(ENV_VERSIONED_VARS))
    }

    /** `__ENV__.current_vars` before 1.13: Elixir's node is the field's tuple, each map in it escaped. */
    fun testAnEnvFieldHoldingMapsInATuple() {
        if (!MACRO_ENV_VERSIONED_VARS.isSufficient(legLevel())) assertEqual("body", listOf(ENV_CURRENT_VARS))
    }

    /** A map of more than 32 keys, whose escaped pairs are sorted as a smaller map's are, from when the escape sorts. */
    fun testAnEnvFieldHoldingAMapOfMoreThan32Keys() {
        if (ESCAPED_MAP_ENTRIES_SORTED.isSufficient(legLevel())) assertEqual("body", listOf(ENV_VERSIONED_VARS_OVER_32))
    }

    /** Sites that keep the node as written, where Elixir builds another. */
    fun testSitesThatHoldTheSourceNode() =
        assertNoFailures(
            SOURCE_NODE_CASES.mapNotNull { case ->
                val probed = probe("body", case)

                unequal(case.name, probed.node?.let { inspect(stripped(it.toOtp())) } ?: "no node", probed.plugin())
            },
        )

    /** Sites that build Elixir's node but for one part: Elixir's node without that part is the plugin's. */
    fun testSitesThatHoldAPartialNode() =
        assertNoFailures(
            PARTIAL_CASES.mapNotNull { (case, without) ->
                val probed = probe("body", case)

                unequal(case.name, inspect(walked(probed.elixir, without)), probed.plugin())
            },
        )

    /**
     * Sites around a bitstring, which holds its source node: EQUAL with each bitstring's segments dropped, and without
     * the `into: []` 1.20 adds to a `for`.
     */
    fun testSitesAroundABitstring() =
        assertNoFailures(
            AROUND_BITSTRING_CASES.mapNotNull { case ->
                val probed = probe("body", case)
                val plugin = probed.built?.let { inspect(walked(stripped(it.toOtp()), ::withoutSegments)) }

                val elixir = walked(probed.elixir) { withoutSegments(it).let { t -> if (t is OtpErlangTuple) withoutInto(t) else t } }

                unequal(case.name, inspect(elixir), plugin ?: probed.plugin())
            },
        )

    /**
     * Each `=` a match inside a pattern is rebuilt with is the one that held its side, so it is as many lines from the
     * outer `=` as Elixir's is. The probe moves the expression, so only the distances can be compared.
     */
    fun testEachMatchInAPatternKeepsItsPosition() {
        val probed = probe("body", PARALLEL_MATCH)
        val elixir = matchLines(probed.sent)
        val built = probed.built ?: return fail("no node: ${probed.plugin()}")
        val plugin = matchLines(built.toOtp())

        assertEquals("$elixir", 3, elixir.size)
        assertEquals("$elixir against $plugin", elixir.map { it - elixir[0] }, plugin.map { it - plugin[0] })
    }

    class Case(val name: String, val args: String, val expr: String, val setup: String = "")

    /** Elixir's node for a case, stripped, and the node the plugin's run left there with its expansion. */
    private class Probed(
        val sent: OtpErlangObject,
        val node: ElixirAst?,
        val expansion: Expansion?,
    ) {
        /** Elixir's node, stripped. */
        val elixir: OtpErlangObject = stripped(sent)

        /** The plugin's built node, if it built one. */
        val built: ElixirAst? get() = ((expansion as? Expansion.Expanded)?.ast as? ExpandedAst.Built)?.node

        /** The plugin's built node, stripped and inspected, or what it gave instead. */
        fun plugin(): String =
            when (expansion) {
                null -> "no expansion at the node"
                is Expansion.Expanded -> when (val ast = expansion.ast) {
                    ExpandedAst.NotAsked -> "NotAsked"
                    is ExpandedAst.Built -> inspect(stripped(ast.node.toOtp()))
                }
                else -> expansion.toString()
            }
    }

    /** Each of [cases] in [context], built EQUAL to Elixir's node. */
    private fun assertEqual(context: String, cases: List<Case>) {
        val failures = cases.mapNotNull { case ->
            val probed = probe(context, case)
            val elixir = inspect(probed.elixir)
            val plugin = probed.plugin()

            unequal(case.name, elixir, plugin)
        }

        assertTrue("$context:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertNoFailures(failures: List<String>) = assertTrue(failures.joinToString("\n"), failures.isEmpty())

    private fun unequal(name: String, elixir: String, plugin: String): String? =
        if (elixir == plugin) null else "$name\n  elixir=$elixir\n  plugin=$plugin"

    /**
     * How [case] in [context] fails to be EQUAL to Elixir's node with each `generated` key dropped, and unequal if
     * [diverges], or `null`.
     */
    private fun differsOnlyInGenerated(context: String, case: Case, diverges: Boolean): String? {
        val probed = probe(context, case)
        val built = probed.built ?: return "$context: ${probed.plugin()}"
        val plugin = stripped(built.toOtp())

        return unequal(context, inspect(stripped(probed.elixir, GENERATED)), inspect(stripped(plugin, GENERATED)))
            ?: "$context diverges: ${probed.elixir != plugin}".takeIf { (probed.elixir != plugin) != diverges }
    }

    private fun probe(context: String, case: Case): Probed {
        val token = harness.token()
        val elixir = harness.compileSource(preamble(token) + module(token, context, case, probed = true).first)
        val elixirTerm = elixir.messages.singleOrNull()
            ?: error("${case.name}: ${elixir.status} ${elixir.diagnostics.joinToString(" | ") { inspect(it) }}")
        val (plain, range) = module(token, context, case, probed = false)
        val (node, expansion) = pluginExpansion(preamble(token) + plain, range.shiftRight(preamble(token).length))

        return Probed(elixirTerm, node, expansion)
    }

    /** The node the run left at [range] in [source], or just inside it, and its expansion. */
    private fun pluginExpansion(source: String, range: TextRange): Pair<ElixirAst?, Expansion?> {
        val level = legLevel()
        val forms = ReadAction.computeBlocking<ElixirAst, Throwable> {
            val file = PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, source)

            Lowering.lower(file as ElixirFile, level)
        }
        val left = mutableMapOf<TextRange, Pair<ElixirAst, Expansion>>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun left(node: ElixirAst, expansion: Expansion) {
                left[node.meta.origin] = node to expansion
            }
        }
        val run = Run(level, observer, legExports, legStructs, legAppEnv(), clauses = sdk)

        run.askingAst { Expander.expandFile(forms, Env.empty(level, legKernel), run) }

        val found = left[range] ?: left[TextRange(range.startOffset + 1, range.endOffset - 1)]

        return found?.first to found?.second
    }

    /** The case module, with the expression wrapped in the probe when [probed], and where the expression is. */
    private fun module(token: String, context: String, case: Case, probed: Boolean): Pair<String, TextRange> {
        val probe = { kind: String, args: String -> "ExpandProbe$token.x(:$kind, [$args], " }
        val head = """
            |defmodule AstCase$token do
            |  import Bitwise
            |  require ExpandProbe$token
            |  @values [:a, :b]
            |  @limit 10
            |${case.setup}
            |""".trimMargin()
        val (before, after) = when (context) {
            "defguard" ->
                if (probed) "  defmacro subject(${case.args}) do\n    ${probe("defguard", case.args)}" to ")\n  end\n"
                else "  defguard subject(${case.args}) when " to "\n"
            "guard" ->
                "  def subject(${case.args}) when " + (if (probed) probe("guard", "") else "") to
                    ((if (probed) ")" else "") + ", do: :ok\n")
            "case" ->
                "  def subject(${case.args}) do\n    case :erlang.unique_integer() do\n      _ when " +
                    (if (probed) probe("guard", "") else "") to
                    (if (probed) ")" else "") + " -> 1\n      _ -> 2\n    end\n  end\n"
            "body" ->
                "  def subject(${case.args}) do\n    " + (if (probed) probe("body", "") + "(\n" else "") to
                    (if (probed) "\n))" else "") + "\n  end\n"
            else -> error(context)
        }
        val expr = case.expr.replace(TOKEN, token)
        val start = head.length + before.length
        val text = head + before + expr + after + "end\n"

        return text to TextRange(start, start + expr.length)
    }

    /**
     * A macro that sends the expression it is given expanded as `defguard`, a guard or a body expands it, and a struct
     * whose enforced keys every leg's run knows, since the run compiles it.
     */
    private fun preamble(token: String) = """
        |defmodule ProbeStruct$token do
        |  defstruct [:host, port: 80]
        |end
        |
        |defmodule ExpandProbe$token do
        |  defmacro x(kind, args, expr) do
        |    env = __CALLER__
        |
        |    env =
        |      if kind == :defguard do
        |        {^args, vars} = refs(args)
        |        :elixir_env.with_vars(%{env | context: :guard}, vars)
        |      else
        |        env
        |      end
        |
        |    expanded =
        |      if function_exported?(:elixir_expand, :expand, 3) do
        |        {e, _, _} = :elixir_expand.expand(expr, :elixir_env.env_to_ex(env), env)
        |        e
        |      else
        |        {e, _} = :elixir_expand.expand(expr, env)
        |        e
        |      end
        |
        |    IntellijElixir.Quoter.Probe.send(__CALLER__, expanded)
        |
        |    if kind == :guard, do: true, else: nil
        |  end
        |
        |  defp refs(args) do
        |    Macro.postwalk(args, [], fn
        |      {ref, meta, context} = var, acc when is_atom(ref) and is_atom(context) ->
        |        {var, [{ref, var_context(meta, context)} | acc]}
        |
        |      node, acc ->
        |        {node, acc}
        |    end)
        |  end
        |
        |  defp var_context(meta, kind) do
        |    case :lists.keyfind(:counter, 1, meta) do
        |      {:counter, counter} -> counter
        |      false -> kind
        |    end
        |  end
        |end
        |
        |""".trimMargin()

    companion object {
        private val POSITION_KEYS = setOf(
            "line", "column", "end", "closing", "end_of_expression", "newlines", "do", "last", "parens",
            "indentation",
        )

        val GENERATED = POSITION_KEYS + "generated"

        /** [term] with [keys] (positions and `version` by default) dropped from each meta, and charlists as lists. */
        fun stripped(term: OtpErlangObject, keys: Set<String> = POSITION_KEYS): OtpErlangObject =
            when (term) {
                is OtpErlangString -> list(term.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) })
                is OtpErlangList -> {
                    val elements = term.elements().map { stripped(it, keys) }.toTypedArray()
                    val tail = term.lastTail?.let { stripped(it, keys) }

                    if (tail == null) OtpErlangList(elements) else OtpErlangList(elements, tail)
                }
                is OtpErlangTuple -> {
                    val elements = term.elements().toMutableList()

                    if (elements.size == 3 && isMeta(elements[1])) {
                        elements[1] = list(
                            (elements[1] as OtpErlangList).elements().filterNot {
                                atom((it as OtpErlangTuple).elementAt(0)) in keys
                            }
                        )
                    }

                    OtpErlangTuple(elements.map { stripped(it, keys) }.toTypedArray())
                }
                else -> term
            }

        private fun isMeta(term: OtpErlangObject): Boolean =
            term is OtpErlangList && term.elements().all {
                it is OtpErlangTuple && it.arity() == 2 && it.elementAt(0) is OtpErlangAtom
            }

        val GUARD_CASES = listOf(
            Case("and", "x", "is_integer(x) and x > 0"),
            Case("or", "x", "is_atom(x) or is_binary(x)"),
            Case("is_type", "x", "is_list(x)"),
            Case("in_attribute", "x", "x in @values"),
            Case("in_list", "x", "x in [:a, :b, :c]"),
            Case("in_charlist", "x", "x in 'abc'"),
            Case("equal", "x", "x == 1"),
            Case("elem", "x", "elem(x, 0) == :ok"),
            Case("elem_variable_index", "x, i", "elem(x, i) == :ok"),
            Case("comparisons", "x", "x >= 0 and x < 256"),
            Case("tuple_size", "x", "tuple_size(x) == 2"),
            Case("is_struct_2", "x", "is_struct(x, URI)"),
            Case("bitwise_function", "x", "band(x, 1) == 1"),
            Case("bitwise_operator", "x", "(x &&& 1) == 1"),
            Case("bitwise_remote", "x", "Bitwise.band(x, 1) == 1"),
            Case("is_map_key", "x", "is_map_key(x, :a)"),
            Case("map_field", "x", "x.size > 0"),
            Case("not_equal", "x", "x != nil"),
            Case("not", "x", "not is_atom(x)"),
            Case("not_in", "x", "x not in [1, 2]"),
            Case("is_nil", "x", "is_nil(x)"),
            Case("is_struct_1", "x", "is_struct(x)"),
            Case("map_literal", "x", "x == %{}"),
            Case("length_hd_tl", "x", "length(x) > 0 and hd(x) == 1 and tl(x) == []"),
            Case("strict", "x", "x === 1.0 or x !== 2"),
            Case("arithmetic", "x", "x + 1 > 2"),
            Case("attribute", "x", "x < @limit"),
            Case("remote_erlang", "x", ":erlang.map_get(:a, x) == 1"),
            Case("not_boolean", "x, y", "tuple_size(x) + map_size(y)"),
            Case("argument_twice", "x", "is_integer(x) and x >= 0 and x <= 9"),
            Case("local_macro", "x", "x == one()", setup = "  defmacro one, do: 1\n"),
            Case("negative_literal", "x", "x > -1"),
            Case("no_arguments", "", "true"),
            Case("source_guard", "x", "is_small(x) and x > 0", setup = "  defguard is_small(x) when x < 10\n"),
        )

        val BODY_CASES = listOf(
            Case("match", "x", "y = x + 1"),
            Case("case", "x", "case x + 1 do\n  1 -> :one\n  y when y > 2 -> y - 1\n  _ -> :other\nend"),
            Case("cond", "x", "cond do\n  x > 1 -> x + 1\n  true -> 0\nend"),
            Case("fn", "x", "fn y -> x + y end"),
            Case("fn_guarded", "x", "fn y when is_integer(y) -> y + 1; _ -> x end"),
            Case("with", "x", "with {:ok, y} <- x, do: y + 1"),
            Case("try", "x", "try do\n  x + 1\nrescue\n  _ -> nil\nafter\n  x - 1\nend"),
            Case("try_catch", "x", "try do\n  x + 1\ncatch\n  :throw, y -> y + 1\nend"),
            Case("receive", "x", "receive do\n  ^x -> x + 1\nafter\n  0 -> :timeout\nend"),
            Case("remote_call_plain", "x", "Enum.map(x, x + 1)"),
            Case("list_append", "x", "[x + 1 | x] ++ [2]"),
            Case("tuple", "x", "{x + 1, x, :a}"),
            Case("map_update", "x", "%{x | a: x + 1}"),
            Case("anonymous_call", "x", "x.(x + 1)"),
            Case("module", "x", "{__MODULE__, x + 1}"),
            Case("alias", "x", "{URI, x + 1}"),
            Case("pin_in_match", "x", "{^x, y} = {x + 1, 2}"),
            Case("block", "x", "y = x + 1\n    y + 1"),
            Case("local_call", "x", "subject(x + 1)"),
            Case("sign", "x", "-x + -1"),
            Case("elem_body", "x", "elem(x, 0)"),
            Case("map_put", "x", "Map.put(x, :a, 1)"),
            Case("attribute_body", "x", "@values ++ [x]"),
            Case("env_field", "x", "__ENV__.module"),
            Case("alias_directive", "x", "alias URI, as: U"),
            Case("require_directive", "x", "require Integer"),
            Case("with_variable", "x", "with y <- x + 1, do: y"),
            Case("super", "x", "super(x + 1)", setup = "  def subject(x), do: x\n  defoverridable subject: 1\n"),
            Case("catch_value", "x", "try do\n  x + 1\ncatch\n  y -> y\nend"),
            Case("catch_value_guarded", "x", "try do\n  x + 1\ncatch\n  y when is_integer(y) -> y\nend"),
            Case("to_string_of_inspect", "x", "to_string(inspect(x))"),
            Case("to_string_of_two", "x", "String.Chars.to_string(x, x)"),
        )

        /** In a case's expression, the compile's token. */
        private const val TOKEN = "{token}"

        private val GUARD_CONTEXTS = listOf("defguard", "guard", "case")

        private val IN_RANGE = Case("in_range", "x", "x in 1..10")

        private val IF = Case("if", "x", "if x > 1, do: x + 1, else: 0")

        private val ENV_VERSIONED_VARS = Case("env_versioned_vars", "x, a", "{__ENV__.versioned_vars, x + a}")

        private val ENV_CURRENT_VARS = Case("env_current_vars", "x, a", "{__ENV__.current_vars, x + a}")

        private val ENV_VERSIONED_VARS_OVER_32 =
            Case("env_versioned_vars_over_32", (1..33).joinToString { "v$it" }, "{__ENV__.versioned_vars, v1}")

        /** Cases whose site keeps the node as written: `__DIR__`, a bitstring and an interpolation. */
        private val SOURCE_NODE_CASES = listOf(
            Case("dir", "x", "__DIR__"),
            Case("bitstring", "x", "<<x::8, 0>>"),
            Case("to_string", "x", "\"#{x}\""),
        )

        /** Cases around a bitstring, which holds its source node. */
        private val AROUND_BITSTRING_CASES = listOf(
            Case("for_bitstring", "x", "for <<y <- x>>, do: y + 1"),
            Case("to_string_of_bitstring", "x", "to_string(<<x::binary>>)"),
        )

        /** Matches inside a pattern, which Elixir expands in parallel. */
        private val PARALLEL_MATCH = Case("parallel_match", "x", "[y =\n  z =\n  w] =\n  [x]")

        /** Cases whose site builds Elixir's node but for one part, each with Elixir's node rewritten without it. */
        private val PARTIAL_CASES = listOf(
            Case("for", "x", "for y <- x, do: y * 2") to ::withoutInto,
            Case("quote", "x", "quote do: unquote(x) + 1") to ::withoutValidateQuote,
            Case("struct", "x", "%ProbeStruct$TOKEN{host: x + 1}") to ::withoutDefaults,
            Case("remote_call", "x", "Enum.map(x, &(&1 + 1))") to ::withoutCapture,
            Case("capture_remote", "x", "&Enum.map(x, &1)") to ::withoutCapture,
        )

        /** [term] with [rewrite] applied to each tuple, after its elements. */
        private fun walked(term: OtpErlangObject, rewrite: (OtpErlangTuple) -> OtpErlangObject): OtpErlangObject =
            when (term) {
                is OtpErlangList -> OtpErlangList(term.elements().map { walked(it, rewrite) }.toTypedArray())
                is OtpErlangTuple -> rewrite(OtpErlangTuple(term.elements().map { walked(it, rewrite) }.toTypedArray()))
                else -> term
            }

        /**
         * A bitstring without its segments, which the plugin holds as written; a bitstring generator's `<<>>`, around
         * its `<-`, is kept.
         */
        private fun withoutSegments(tuple: OtpErlangTuple): OtpErlangObject {
            if (tuple.arity() != 3 || atom(tuple.elementAt(0)) != "<<>>") return tuple

            val args = tuple.elementAt(2) as? OtpErlangList ?: return tuple
            val generator = args.elements().any { it is OtpErlangTuple && it.arity() == 3 && atom(it.elementAt(0)) == "<-" }

            return if (generator) tuple else node(tuple.elementAt(0), list(emptyList()), list(emptyList()))
        }

        /** The `line` of each `=` in [term], outermost first. */
        private fun matchLines(term: OtpErlangObject): List<Long> =
            when (term) {
                is OtpErlangList -> term.elements().flatMap(::matchLines)
                is OtpErlangTuple -> {
                    val line = term.takeIf { it.arity() == 3 && atom(it.elementAt(0)) == "=" }
                        ?.let { (it.elementAt(1) as OtpErlangList).elements() }
                        ?.map { it as OtpErlangTuple }
                        ?.firstOrNull { atom(it.elementAt(0)) == "line" }
                        ?.let { (it.elementAt(1) as OtpErlangLong).longValue() }

                    listOfNotNull(line) + term.elements().flatMap(::matchLines)
                }
                else -> emptyList()
            }

        private fun atom(term: OtpErlangObject?): String? = (term as? OtpErlangAtom)?.atomValue()

        private fun node(name: OtpErlangObject, meta: OtpErlangObject, args: OtpErlangObject) =
            OtpErlangTuple(arrayOf(name, meta, args))

        private fun list(elements: List<OtpErlangObject>) = OtpErlangList(elements.toTypedArray())

        /** A `for` without the `into: []` that 1.20 adds where the value is used. */
        private fun withoutInto(tuple: OtpErlangTuple): OtpErlangObject {
            if (tuple.arity() != 3 || atom(tuple.elementAt(0)) != "for") return tuple

            val args = (tuple.elementAt(2) as OtpErlangList).elements().toList()
            val options = (args.last() as OtpErlangList).elements().filterNot {
                it is OtpErlangTuple && atom(it.elementAt(0)) == "into" && it.elementAt(1) == OtpErlangList()
            }

            return node(tuple.elementAt(0), tuple.elementAt(1), list(args.dropLast(1) + listOf(list(options))))
        }

        /** A `quote` without the `:elixir_quote.validate_quote/1` around it from 1.20. */
        private fun withoutValidateQuote(tuple: OtpErlangTuple): OtpErlangObject {
            val dot = tuple.elementAt(0) as? OtpErlangTuple
            val target = dot?.takeIf { atom(it.elementAt(0)) == "." }?.let { it.elementAt(2) as? OtpErlangList }
                ?.elements()?.map(::atom)

            return if (target == listOf("elixir_quote", "validate_quote")) {
                (tuple.elementAt(2) as OtpErlangList).elementAt(0)
            } else {
                tuple
            }
        }

        /** A struct's map with only its written `host` pair, without the defaults Elixir fills. */
        private fun withoutDefaults(tuple: OtpErlangTuple): OtpErlangObject {
            if (tuple.arity() != 3 || atom(tuple.elementAt(0)) != "%{}") return tuple

            val pairs = (tuple.elementAt(2) as OtpErlangList).elements().filter {
                atom((it as OtpErlangTuple).elementAt(0)) == "host"
            }

            return node(tuple.elementAt(0), tuple.elementAt(1), list(pairs))
        }

        /** A capture's `fn` without 1.20's `capture: true`. */
        private fun withoutCapture(tuple: OtpErlangTuple): OtpErlangObject {
            if (tuple.arity() != 3 || atom(tuple.elementAt(0)) != "fn") return tuple

            val meta = (tuple.elementAt(1) as OtpErlangList).elements().filterNot {
                atom((it as OtpErlangTuple).elementAt(0)) == "capture"
            }

            return node(tuple.elementAt(0), list(meta), tuple.elementAt(2))
        }
    }
}
