package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term

/**
 * Each module's attributes against what the hook (a [ProbeHarness.Hook]) reads of them on the leg's Elixir: the values
 * its read functions return once it has compiled, the values the hook names when its body ends, each clause's `@impl`,
 * and each definition's doc and deprecation as the compiled Docs chunk merges them. Only values the expander
 * knows are compared: a case names the reads it expects unknown, and any other read that comes out unknown fails.
 */
class AttributeProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    /**
     * The route the reads and the Docs chunk take: once the module has compiled and loaded, `__after_compile__/2` calls
     * each public zero-arity function whose name starts `r_` and reads the chunk from the bytecode it is given, and the
     * compile leaves no module behind.
     */
    fun testTheAfterCompileHookCallsEachReadFunction() {
        val options = compilerOptions()
        val layout = ProbeHarness.Layout(
            harness.token(),
            listOf(
                ProbeHarness.Case(
                    """
                    @x 1
                    @doc "a"
                    def r_a, do: @x
                    @x 2
                    @deprecated "old"
                    def r_b, do: @x
                    defp r_private, do: @x
                    def r_arity(y), do: y
                    def other, do: r_private()
                    """.trimIndent()
                )
            ),
            hook = ProbeHarness.Hook(),
        )
        val attempt = harness.attempt(layout)

        assertEquals(
            "compile status ${attempt.compiled.diagnostics.map(::inspect)}",
            OtpErlangAtom("ok"),
            attempt.compiled.status,
        )
        assertEquals(
            mapOf("r_a" to OtpErlangLong(1), "r_b" to OtpErlangLong(2)),
            attempt.batch.reads[layout.caseModule(0)],
        )

        val docs = attempt.batch.docs.getValue(layout.caseModule(0))

        assertTrue("docs enabled", docs.enabled)
        assertEquals(
            listOf(
                "function other/0 none nil",
                "function r_a/0 a nil",
                "function r_arity/1 none nil",
                "function r_b/0 none old",
            ),
            docs.entries!!.map { "${it.kind} ${it.name}/${it.arity} ${text(it.doc)} ${text(it.deprecated)}" }.sorted(),
        )
        assertLeftNothingBehind(layout)
        assertEquals("compiler options", options, compilerOptions())
    }

    /**
     * Positional reads, accumulation, `Module`'s writes, what each definition takes, and a nested
     * module, which doesn't see the outer module's attributes.
     */
    fun testAttributesMatchTheHook() {
        assertAttributesMatch(
            AttributeCase(
                """
                @x 1
                def r_a, do: @x
                @x 2
                def r_b, do: @x
                Module.register_attribute(__MODULE__, :acc, accumulate: true)
                @acc 1
                def r_acc1, do: @acc
                @acc 2
                def r_acc2, do: @acc
                Module.put_attribute(__MODULE__, :p, 3)
                def r_p, do: @p
                @d 1
                Module.delete_attribute(__MODULE__, :d)
                def r_d, do: @d
                def r_u, do: @nope
                Module.register_attribute(__MODULE__, :r, [])
                def r_r, do: @r
                @m Foo.Bar
                def r_m, do: @m
                @mm __MODULE__
                def r_mm, do: @mm
                @behaviour {token}.Beh
                @impl true
                def cb, do: :ok
                def r_beh, do: @behaviour
                @doc "hi"
                def r_doc, do: @doc
                def r_doc_after, do: @doc
                @impl {token}.Beh
                def r_impl, do: @impl
                def r_impl_after, do: @impl
                @deprecated "old"
                def r_dep, do: @deprecated
                @y @x
                def r_y, do: @y
                def r_late, do: @late
                @late 9
                @k [1, a: :b, c: {1, 2}]
                def r_k, do: @k
                @s "str"
                def r_s, do: @s
                @t {1, 2, 3}
                def r_t, do: @t
                @md %{a: 1}
                def r_map, do: @md
                def r_default(a \\ @x), do: a
                @x 3
                defmodule Inner do
                  @after_compile {token}.Hook
                  def r_outer, do: @x
                end
                """.trimIndent(),
                attributes = listOf("x", "acc", "p", "behaviour"),
                // A tuple of three, a map, and a module-body read have no Term.
                unknown = setOf("r_t", "r_map", "r_y"),
            ),
            preamble = "\ndefmodule {token}.Beh do\n  @callback cb() :: any\nend\n",
        )
    }

    /** `Module.put_attribute/3` in the module body writes what a later `@` in a definition reads. */
    fun testAPutAttributeReachesARead() {
        assertAttributesMatch(
            AttributeCase("Module.put_attribute(__MODULE__, :a, 1)\ndef r_a, do: @a", attributes = listOf("a"))
        )
    }

    /** `@after_verify` accumulates from 1.14 (A1), and `@nifs` from 1.19 (A3); before, a read gives the last write. */
    fun testAfterVerifyAndNifsWrittenTwice() {
        assertAttributesMatch(
            AttributeCase(
                """
                @after_verify {token}.Noop
                @after_verify {token}.Noop
                def r_after_verify, do: @after_verify
                def f, do: 0
                def g, do: 0
                @nifs [f: 0]
                @nifs [g: 0]
                def r_nifs, do: @nifs
                """.trimIndent(),
                attributes = listOf("after_verify", "nifs"),
            ),
            preamble = NOOP,
        )
    }

    /**
     * A callback attribute written with a module is stored as `{module, callback}`; `final` drops the hook's own entries,
     * and keeps Elixir's `{Module, :compile_definition_attributes}`.
     */
    fun testACallbackAttributeIsPreprocessed() {
        assertAttributesMatch(
            AttributeCase(
                "@after_compile {token}.Noop\ndef f, do: 1",
                attributes = listOf("after_compile", "before_compile", "on_definition"),
            ),
            preamble = NOOP,
        )
    }

    /** Each definition takes `@file` before its body is expanded, so neither read sees it. */
    fun testADefinitionTakesFile() {
        assertAttributesMatch(
            AttributeCase("@file \"x.ex\"\ndef r_a, do: @file\ndef r_b, do: @file", attributes = listOf("file"))
        )
    }

    /** A private definition takes `@doc` and keeps none of it, so the Docs chunk has no entry for it. */
    fun testAPrivateDefinitionKeepsNoDoc() {
        assertAttributesMatch(
            AttributeCase("@doc \"x\"\ndefp p, do: 1\ndef r_doc, do: @doc\ndef f, do: p()", attributes = listOf("doc"))
        )
    }

    /** A write in a `case` clause of the module body, `if`'s included, isn't a statement of it, so a later read is unknown. */
    fun testAWriteInACaseClauseMakesLaterReadsUnknown() {
        assertAttributesMatch(
            AttributeCase(
                "case 1 do\n  1 -> @w 1\nend\ndef r_w, do: @w\n@v 2\ndef r_v, do: @v",
                attributes = listOf("v"),
                unknown = setOf("r_w"),
            ),
            AttributeCase(
                "@x 1\ndef r_a, do: @x\nif true, do: @w(5)\ndef r_w, do: @w",
                attributes = listOf("x"),
                unknown = setOf("r_w"),
            ),
        )
    }

    /** The expander doesn't run `use`'s `__using__` macro, so the module stops at `use`, and nothing after it is compared. */
    fun testAModuleStopsAtUse() {
        val body = "@x 1\ndef r_a, do: @x\nuse Agent\n@w 5\ndef r_w, do: @w"
        val expansion = probes.expandAll(listOf(body), hook = ProbeHarness.Hook()).cases.single()

        assertTrue("$body: ${expansion.outcome}", expansion.outcome is Expansion.Opaque)
        assertEquals("use Agent", expansion.source((expansion.outcome as Expansion.Opaque).at))
        probes.assertMatchesElixirUpToMacro(mapOf(body to expansion))
    }

    /**
     * An interpolation is a binary whose content isn't known, so `@doc` and `@external_resource` take it as the text they
     * need and the module goes on to compile.
     */
    fun testAnInterpolatedDocIsText() = assertCompilesLikeElixir("x = \"a\"\n@doc \"x: #{x}\"\ndef f, do: 1")

    fun testAComputedExternalResourceIsText() =
        assertCompilesLikeElixir("@external_resource \"#{__DIR__}/a.txt\"\ndef f, do: 1")

    fun testAnInterpolatedDeprecationIsText() =
        assertCompilesLikeElixir("x = \"g\"\n@deprecated \"use #{x} instead\"\ndef f, do: 1")

    private fun assertCompilesLikeElixir(body: String) {
        val expansion = probes.expandAll(listOf(body), hook = ProbeHarness.Hook()).cases.single()

        assertEquals("$body: ${expansion.outcome}", ExpansionResult.Ended.Compiled, expansion.ended)
        probes.assertMatchesElixir(mapOf(body to expansion))
    }

    /**
     * A case body, the attributes whose values at the end of the body are compared, and the read functions whose
     * values the expander doesn't know.
     */
    private class AttributeCase(val body: String, val attributes: List<String>, val unknown: Set<String> = emptySet())

    /**
     * Expands and compiles each of [cases] alone with the hook, and compares the expander's attribute log with what the
     * hook saw, for the case module and each module nested in it that names the hook in its own `@after_compile`.
     */
    private fun assertAttributesMatch(vararg cases: AttributeCase, preamble: String = "") {
        val options = compilerOptions()
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        for (case in cases) {
            val expansions = probes.expandAll(listOf(case.body), preamble, hook = ProbeHarness.Hook(case.attributes))
            val expansion = expansions.cases.single()

            assertEquals("${case.body}: ${expansion.outcome}", ExpansionResult.Ended.Compiled, expansion.ended)

            // A probe after a def body's last statement would be what each read function returns, so the compiled
            // layout has the module-body probes only.
            val expanded = expansions.layout
            val layout = ProbeHarness.Layout(
                expanded.token,
                expanded.cases.map { ProbeHarness.Case(it.body) },
                expanded.preamble,
                expanded.hook,
                expanded.top,
            )
            val attempt = harness.attempt(layout)
            val module = layout.caseModule(0)
            val bodyLine = layout.bodyLines[0]

            assertEquals(
                "${case.body}: compile status ${render(attempt.compiled.status)} " +
                    attempt.compiled.diagnostics.map(::inspect),
                OtpErlangAtom("ok"),
                attempt.compiled.status,
            )
            assertTrue("${case.body}: no reads from the case module", module in attempt.batch.reads)

            val hooked = attempt.batch.reads.keys.filter { it == module || it.startsWith("$module.") }.sorted()
            val results = generateSequence(expansions.modules) { it.flatMap(ExpansionResult::nested).ifEmpty { null } }
                .flatten()
                .toList()

            for (name in hooked) {
                val result = results.firstOrNull { it.module == name }

                expected += render(name, layout, elixir(name, attempt.batch, case, layout, bodyLine))
                actual += render(
                    name,
                    layout,
                    result?.let { expander(it, case.attributes.takeIf { name == module }.orEmpty(), bodyLine) }
                        ?: listOf("not expanded"),
                )
            }

            assertLeftNothingBehind(layout)
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
        assertEquals("compiler options", options, compilerOptions())
    }

    private fun render(module: String, layout: ProbeHarness.Layout, rows: List<String>) =
        "== ${module.removePrefix(layout.caseModule(0)).ifEmpty { "case module" }}\n" +
            rows.joinToString("") { "  $it\n" }

    /** What Elixir's hook saw of [module], as rows. */
    private fun elixir(
        module: String,
        batch: ProbeHarness.Batch,
        case: AttributeCase,
        layout: ProbeHarness.Layout,
        bodyLine: Int,
    ): List<String> {
        val reads = batch.reads.getValue(module).toSortedMap().map { (name, value) ->
            "read $name = ${if (name in case.unknown) UNKNOWN else render(value)}"
        }
        val finals = batch.finals[module].orEmpty().toSortedMap().map { (name, value) ->
            "final $name = ${render(withoutHook(name, value, layout))}"
        }
        val impls = batch.definitionAttributes[module].orEmpty()
            .filter { it.values.getValue("impl") != NIL }
            .map { "impl ${it.kind} ${it.name}/${it.arity} line ${it.line - bodyLine + 1} = ${render(it.values.getValue("impl"))}" }
        val docs = batch.docs.getValue(module).let { docs ->
            assertTrue("$module compiled with docs", docs.enabled)

            docs.entries!!.map { entry ->
                "doc ${entry.name}/${entry.arity} = ${docText(entry.doc)} deprecated ${render(entry.deprecated)}"
            }
        }

        return reads + finals + impls.sorted() + docs.sorted()
    }

    /** What the expander logged of [result]'s module, with the final values of [attributes], as rows in [elixir]'s order. */
    private fun expander(result: ExpansionResult, attributes: List<String>, bodyLine: Int): List<String> {
        val log = result.attributes
        val reads = log.reads
            .mapNotNull { read -> (read.owner as? Owner.Definition)?.name?.takeIf { it.startsWith("r_") }?.to(read.value) }
            .toMap()
            .toSortedMap()
            .map { (name, value) -> "read $name = ${render(value)}" }
        val finals = attributes.sorted().map { name ->
            "final $name = ${render(log.final[name] ?: AttributeValue.Known(Term.Atom("nil")))}"
        }
        val impls = log.definitions.flatMap { (nameArity, attributes) ->
            attributes.impls.map { impl ->
                "impl ${impl.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} " +
                    "line ${impl.line - bodyLine + 1} = ${render(impl.value)}"
            }
        }
        val docs = result.table.entries
            .filter { (_, entry) -> !entry.default }
            .mapNotNull { (nameArity, entry) ->
                val attributes = log.definitions[nameArity]
                val doc = docText(attributes?.doc)

                when {
                    entry.kind.public ->
                        "doc ${nameArity.name}/${nameArity.arity} = $doc " +
                            "deprecated ${attributes?.deprecated?.let(::render) ?: ":nil"}"
                    // The chunk has no entry for a private definition, which is its "no doc".
                    doc != ":none" -> "doc private ${nameArity.name}/${nameArity.arity} = $doc"
                    else -> null
                }
            }

        return reads + finals + impls.sorted() + docs.sorted()
    }

    /** `final`'s value of an attribute that names callbacks, less the hook's own. */
    private fun withoutHook(name: String, value: OtpErlangObject, layout: ProbeHarness.Layout): OtpErlangObject =
        if (name in CALLBACK_ATTRIBUTES && value is OtpErlangList) {
            OtpErlangList(
                value.elements().filterNot {
                    (it as? OtpErlangTuple)?.elementAt(0) == OtpErlangAtom(layout.hookModule)
                }.toTypedArray()
            )
        } else {
            value
        }

    /** A Docs chunk entry's doc: its text, `:hidden` or `:none`. */
    private fun docText(doc: OtpErlangObject): String =
        (doc as? OtpErlangAtom)?.let { ":${it.atomValue()}" } ?: render(doc)

    /** The doc the expander says the Docs chunk holds: `nil` is `:none`, and `false` is `:hidden`. */
    private fun docText(doc: AttributeValue?): String =
        when (doc) {
            null, AttributeValue.Known(Term.Atom("nil")) -> ":none"
            AttributeValue.Known(Term.Atom("false")) -> ":hidden"
            else -> render(doc)
        }

    private fun render(value: AttributeValue): String =
        when (value) {
            is AttributeValue.Known -> render(value.term)
            AttributeValue.Unknown -> UNKNOWN
        }

    private fun render(term: Term): String =
        when (term) {
            is Term.Atom -> ":${term.name}"
            is Term.Integer -> term.value.toString()
            is Term.Binary -> term.bytes?.let { "\"${String(it, Charsets.UTF_8)}\"" } ?: UNKNOWN
            is Term.List ->
                term.elements.joinToString(", ", "[", (term.tail?.let { " | ${render(it)}" } ?: "") + "]", transform = ::render)
            is Term.Pair -> "{${render(term.first)}, ${render(term.second)}}"
            is Term.Node, Term.NonTuple, Term.Unexpanded -> UNKNOWN
        }

    private fun render(term: OtpErlangObject): String =
        when (term) {
            is OtpErlangAtom -> ":${term.atomValue()}"
            is OtpErlangLong -> term.bigIntegerValue().toString()
            is OtpErlangBinary -> "\"${String(term.binaryValue(), Charsets.UTF_8)}\""
            // A list of small integers is sent as a string.
            is OtpErlangString -> term.stringValue().codePoints().toArray().joinToString(", ", "[", "]")
            is OtpErlangList ->
                term.elements().joinToString(
                    ", ",
                    "[",
                    (term.lastTail?.let { " | ${render(it)}" } ?: "") + "]",
                    transform = ::render,
                )
            is OtpErlangTuple -> term.elements().joinToString(", ", "{", "}", transform = ::render)
            is OtpErlangMap ->
                term.keys().zip(term.values()).joinToString(", ", "%{", "}") { (key, value) ->
                    "${render(key)} => ${render(value)}"
                }
            else -> inspect(term)
        }

    private fun text(term: OtpErlangObject): String =
        (term as? OtpErlangAtom)?.atomValue() ?: utf8(term)

    private companion object {
        const val UNKNOWN = "unknown"

        /** A module whose callbacks do nothing, for the callback attributes to name. */
        const val NOOP = "\ndefmodule {token}.Noop do\n" +
            "  def __after_compile__(_env, _bytecode), do: :ok\n" +
            "  def __after_verify__(_module), do: :ok\n" +
            "end\n"

        val NIL = OtpErlangAtom("nil")

        /** The attributes whose values name callbacks, so `final`'s hold the hook's own entries too. */
        val CALLBACK_ATTRIBUTES = setOf("before_compile", "after_compile", "on_definition")
    }
}
