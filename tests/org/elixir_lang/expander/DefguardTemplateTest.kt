package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_UNQUOTE_NAMES
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_RANGE_LITERAL_GENERATED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile

/**
 * The template `Kernel.Utils.defguard/2` gives each case's `defguard`, as the expander reaches it, against what
 * `Kernel.Utils.defguard/3` returns on the leg in a `defmacro` written as `define_guard/3` writes it, linified as
 * Elixir's dispatch linifies a macro's output, counter included.
 */
class DefguardTemplateTest : SdkProbeTestCase() {
    fun testEachTemplateIsElixirs() {
        val failures = CASES.filter { it.appliesAt(legLevel()) }.mapNotNull { case ->
            val token = harness.token()
            val elixir = inspect(elixirTemplate(case, token))
            val plugin = pluginTemplate(case, token)?.let { inspect(ExpandedAstTest.stripped(it.toOtp())) }

            if (elixir == plugin) null else "${case.name}\n  elixir=$elixir\n  plugin=$plugin"
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * An `in` of a range, literal or an attribute's: from 1.20 Elixir marks every node of its output
     * `generated: true`, and the plugin's expansion keeps the mark on the `is_integer` call only. That meta is a
     * deferred divergence: no answer reads it.
     */
    fun testAnInOfARangeDiffersOnlyInGenerated() {
        val diverges = IN_RANGE_LITERAL_GENERATED.isSufficient(legLevel())

        val failures = IN_RANGE_CASES.mapNotNull { case ->
            val token = harness.token()
            val elixir = elixirTemplate(case, token)
            val plugin = pluginTemplate(case, token)?.let { ExpandedAstTest.stripped(it.toOtp()) }
                ?: return@mapNotNull "${case.name}: no template"
            val withoutElixir = inspect(withoutGenerated(elixir))
            val withoutPlugin = inspect(withoutGenerated(plugin))

            when {
                withoutElixir != withoutPlugin -> "${case.name}\n  elixir=$withoutElixir\n  plugin=$withoutPlugin"
                (elixir != plugin) != diverges -> "${case.name} diverges: ${elixir != plugin}"
                else -> null
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** An `in` of an attribute that isn't set: `@` gives `nil`, so the guard's `in` raises, as Elixir's does. */
    fun testAnInOfAnUnsetAttributeRaises() {
        val body = "defguard is_unset(x) when x in @unset\n$AFTER"
        val expansion = expand(body, "")

        assertTrue("${expansion.ended}", expansion.ended is ExpansionResult.Ended.Raised)
        printed { probes.assertMatchesElixir(mapOf(body to expansion)) }
    }

    /**
     * `Kernel.Utils.defguard/2` called outside a module: its fresh variables take the counter of no module, which is
     * `erlang:unique_integer/0`'s there, unordered, so counters compare by where they first appear.
     */
    fun testATemplateOutsideAModuleIsElixirs() {
        val token = harness.token()
        val arguments = "[x, y], is_integer(x) and x > y"
        // A file's top level can't use a module the same file defines, so the call is compiled once the probe's is,
        // under the file the probe reports for.
        val outside = "require TemplateProbe$token; TemplateProbe$token.dg($arguments)"
        val compiled = harness.compileSource(preamble(token) + "Code.compile_string(\"$outside\", __ENV__.file)\n")
        val elixir = compiled.messages.firstOrNull()?.let { ranked(inspect(ExpandedAstTest.stripped(it))) }
            ?: "${compiled.status} ${compiled.diagnostics.joinToString(" | ") { inspect(it) }}"
        val plugin = pluginTemplate("require Kernel.Utils\nKernel.Utils.defguard($arguments)\n")
            ?.let { ranked(inspect(ExpandedAstTest.stripped(it.toOtp()))) }

        assertEquals(elixir, plugin)
    }

    /** [inspected] with each counter replaced by the order of its first appearance in it. */
    private fun ranked(inspected: String): String {
        val counter = Regex("counter: (-?\\d+)")
        val ranks = counter.findAll(inspected).map { it.groupValues[1] }.distinct().withIndex()
            .associate { (rank, value) -> value to rank }

        return counter.replace(inspected) { "counter: #${ranks.getValue(it.groupValues[1])}" }
    }

    class Case(
        val name: String,
        val head: String,
        val args: String,
        val guard: String,
        val setup: String = "",
        val kind: String = "defguard",
        val since: ElixirLanguageFeature? = null,
        val quotedInUsing: Boolean = false,
    ) {
        val source: String get() = "$kind $head when $guard"

        fun appliesAt(level: ElixirLanguageLevel) = since?.isSufficient(level) ?: true
    }

    /** Elixir's template for [case] in modules named with [token], linified and stripped. */
    private fun elixirTemplate(case: Case, token: String): OtpErlangObject {
        val elixir = harness.compileSource(preamble(token) + oracle(token, case))
        val template = elixir.messages.firstOrNull()
            ?: error("${case.name}: ${elixir.status} ${elixir.diagnostics.joinToString(" | ") { inspect(it) }}")

        return ExpandedAstTest.stripped(template)
    }

    /**
     * The template the expander reaches for [case]'s `defguard` in modules named with [token], if it builds one: the
     * last built `case` on `Macro.Env.in_guard?/1`, since a setup's guards come before it.
     */
    private fun pluginTemplate(case: Case, token: String): ElixirAst? = pluginTemplate(plain(token, case))

    /** The last template the expander builds in [source]. */
    private fun pluginTemplate(source: String): ElixirAst? {
        val level = legLevel()
        val forms = ReadAction.computeBlocking<ElixirAst, Throwable> {
            val file = PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, source)

            Lowering.lower(file as ElixirFile, level)
        }
        var template: ElixirAst? = null
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun builtIn(node: ElixirAst, env: Env) {
                if (isTemplate(node)) template = node
            }
        }
        val run = Run(level, observer, legExports, legStructs, clauses = sdk)
        Expander.expandFile(forms, Env.empty(level, legKernel), run)

        return template
    }

    /** `case Macro.Env.in_guard?(__CALLER__)`. */
    private fun isTemplate(node: ElixirAst): Boolean {
        val subject = (node as? ElixirAst.Call)?.takeIf { isCall(it, "case", 2) }?.arguments?.first() as? ElixirAst.Call
        val dot = subject?.callee as? ElixirAst.Call

        return dot != null &&
            isCall(dot, ".", 2) &&
            (dot.arguments!![1] as? ElixirAst.Literal.Atom)?.name == "in_guard?"
    }

    private fun plain(token: String, case: Case) =
        if (case.quotedInUsing) {
            using(token, case.source)
        } else {
            """
            |defmodule GuardCase$token do
            |  import Bitwise
            |${case.setup}
            |  ${case.source}
            |end
            |""".trimMargin()
        }

    /** [definition] inside a `quote` in a `__using__`, so its variables carry the quote's counter. */
    private fun using(token: String, definition: String) = """
        |defmodule GuardUsing$token do
        |  defmacro __using__(_) do
        |    quote do
        |      $definition
        |    end
        |  end
        |end
        |
        |defmodule GuardCase$token do
        |  use GuardUsing$token
        |end
        |""".trimMargin()

    /** `define_guard/3`'s output, with `Kernel.Utils.defguard/2` replaced by the probe's macro calling `defguard/3`. */
    private fun oracle(token: String, case: Case): String {
        val macro = if (case.kind == "defguardp") "defmacrop" else "defmacro"
        val body = "require TemplateProbe$token\nTemplateProbe$token.dg([${case.args}], ${case.guard})"

        if (case.quotedInUsing) return using(token, "@doc guard: true\n$macro ${case.head} do\n$body\nend")

        return """
            |defmodule GuardCase$token do
            |  import Bitwise
            |${case.setup}
            |  @doc guard: true
            |  $macro ${case.head} do
            |    $body
            |  end
            |end
            |""".trimMargin()
    }

    /**
     * A macro that sends `defguard/3`'s template linified as `elixir_dispatch:expand_quoted/7` linifies
     * `Kernel.Utils.defguard/2`'s: with `Kernel.Utils` and the counter the module gives next, after the template's own.
     * The line is a bare integer before 1.14.
     */
    private fun preamble(token: String) = """
        |defmodule TemplateProbe$token do
        |  defmacro dg(args, expr) do
        |    template = Kernel.Utils.defguard(args, expr, __CALLER__)
        |    counter = :elixir_module.next_counter(__CALLER__.module)
        |
        |    context =
        |      if Version.match?(System.version(), "< 1.14.0-rc.0"), do: __CALLER__.line, else: [line: __CALLER__.line]
        |
        |    linified = :elixir_quote.linify_with_context_counter(context, {Kernel.Utils, counter}, template)
        |    IntellijElixir.Quoter.Probe.send(__CALLER__, linified)
        |    template
        |  end
        |end
        |
        |""".trimMargin()

    companion object {
        private fun withoutGenerated(term: OtpErlangObject) = ExpandedAstTest.stripped(term, ExpandedAstTest.GENERATED)

        /** Each case's `defguard` as a case module body, with a statement after it. */
        fun bodies(level: ElixirLanguageLevel): List<String> =
            (CASES + IN_RANGE_CASES).filter { !it.quotedInUsing && it.appliesAt(level) }.map { case ->
                listOfNotNull("import Bitwise", case.setup.trim().ifEmpty { null }, case.source, AFTER)
                    .joinToString("\n")
            }

        private const val AFTER = "def after_guard(y), do: y"

        val IN_RANGE_CASES = listOf(
            Case("iso_is_hour", "is_hour(hour)", "hour", "hour in 0..23", kind = "defguardp"),
            Case(
                "iso_is_microsecond",
                "is_microsecond(microsecond, precision)",
                "microsecond, precision",
                "microsecond in 0..999_999 and precision in 0..6",
                kind = "defguardp",
            ),
            Case("in_attribute_range", "is_small(x)", "x", "x in @r", setup = "  @r 1..3", kind = "defguardp"),
        )

        val CASES = listOf(
            Case("integer_is_odd", "is_odd(integer)", "integer", "is_integer(integer) and (integer &&& 1) == 1"),
            Case("integer_is_even_rem", "is_even(integer)", "integer", "is_integer(integer) and rem(integer, 2) == 0"),
            Case("in_list", "is_kind(kind)", "kind", "kind in [:a, :b, :c]", kind = "defguardp"),
            Case("in_charlist", "is_vowel(c)", "c", "c in 'aeiou'", kind = "defguardp"),
            Case("or_equal", "is_space(c)", "c", "c == ?\\s or c == ?\\t", kind = "defguardp"),
            Case(
                "record_is_record_1",
                "is_record(data)",
                "data",
                "is_tuple(data) and tuple_size(data) > 0 and is_atom(elem(data, 0))",
            ),
            Case(
                "record_is_record_2",
                "is_record(data, kind)",
                "data, kind",
                "is_atom(kind) and is_tuple(data) and tuple_size(data) > 0 and elem(data, 0) == kind",
            ),
            Case("is_map_key", "is_cached(map, key)", "map, key", "is_map_key(map, key)", kind = "defguardp"),
            Case("map_field", "is_big(m)", "m", "m.size > 10", kind = "defguardp"),
            Case("not_in", "not_reserved(x)", "x", "x not in [:a, :b]", kind = "defguardp"),
            Case(
                "not_strict",
                "odd_cases(x)",
                "x",
                "not is_atom(x) and x != 1 and x !== 2 and x === 3",
                kind = "defguardp",
            ),
            Case("map_literal", "is_empty_map(m)", "m", "m == %{}", kind = "defguardp"),
            Case(
                "list_functions",
                "is_pair_list(l)",
                "l",
                "length(l) == 2 and hd(l) + 1 > 0 and tl(l) != []",
                kind = "defguardp",
            ),
            Case("bitwise_function", "is_flag(x)", "x", "band(x, 4) == 4", kind = "defguardp"),
            Case(
                "erlang_remote",
                "is_ref(r)",
                "r",
                "is_map(r) and :erlang.map_get(:ref, r) != nil",
                kind = "defguardp",
            ),
            Case("not_boolean", "fields_size(fields)", "fields", "length(fields)", kind = "defguardp"),
            Case("three_args", "is_between(x, lo, hi)", "x, lo, hi", "is_integer(x) and x >= lo and x <= hi"),
            Case("unused_arg", "first_int(x, y)", "x, y", "is_integer(x)", kind = "defguardp"),
            Case("args_out_of_order", "rev(a, b)", "a, b", "b > a", kind = "defguardp"),
            Case("arg_twice", "is_twice(x)", "x", "x + x == 2"),
            Case(
                "default_argument",
                "is_divisible(value, remainder \\\\ 2)",
                "value, remainder \\\\ 2",
                "is_integer(value) and rem(value, remainder) == 0",
            ),
            Case("no_arguments", "is_true", "", "true"),
            Case("unquote_name", "unquote(:is_foo)(x)", "x", "x == :foo", since = DEFGUARD_UNQUOTE_NAMES),
            Case("is_nil", "is_present(x)", "x", "not is_nil(x)"),
            Case("is_struct_1", "is_some_struct(x)", "x", "is_struct(x)"),
            Case("is_struct_2", "is_uri(x)", "x", "is_struct(x, URI)"),
            Case(
                "local_macro",
                "is_doc(x)",
                "x",
                "is_binary(x) or x == doc_line()",
                setup = "  defmacrop doc_line, do: :doc_line",
            ),
            Case(
                "quoted_using",
                "is_even(value)",
                "value",
                "is_integer(value) and rem(value, 2) == 0",
                quotedInUsing = true,
            ),
            Case(
                "source_guard",
                "is_one_or_two(x)",
                "x",
                "is_one(x) or x == 2",
                setup = "  defguard is_one(x) when x == 1",
            ),
            Case("in_attribute_list", "is_kind(kind)", "kind", "kind in @kinds", setup = "  @kinds [:a, :b]"),
        )
    }
}
