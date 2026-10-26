package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.util.TextRange

/**
 * The harness sees, after each statement of a case module body, the change Elixir makes to its env, and nothing else.
 * Each step is compared with the case's own step 0, so this checks the harness against Elixir alone.
 */
class ProbeHarnessSelfTest : ProbeTestCase() {
    fun testEachStatementChangesOnlyWhatElixirChanges() {
        val bodies = listOf("alias Foo.Bar", "x = 1; x", "import List, only: [first: 1]")
        val batch = harness.compile(bodies)
        val byCase = batch.observations.groupBy { it.tag.case }

        assertEquals(
            listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1),
            batch.observations.map { it.tag.case to it.tag.statement }
        )

        val steps = byCase.mapValues { (_, observations) ->
            observations.map { ProbedEnvNormaliser.observed(it.env, batch.probeModule) }
        }

        val alias = steps.getValue(0)
        assertSteps(alias, alias[0], alias[0] + ("aliases" to list(tuple(atom("Elixir.Bar"), atom("Elixir.Foo.Bar")))))

        val variable = steps.getValue(1)
        assertSteps(variable, variable[0], variable[0], variable[0])
        assertEquals(
            listOf("", "x/nil=0", "x/nil=0"),
            VariableClasses.canonical(byCase.getValue(1).map { VariableClasses.observed(it.env) })
        )

        val import = steps.getValue(2)
        val functions = (import[0].getValue("functions") as OtpErlangList).elements()
        val requires = (import[0].getValue("requires") as OtpErlangList).elements() + atom("Elixir.List")
        val listFirst = tuple(atom("Elixir.List"), list(tuple(atom("first"), OtpErlangLong(1))))
        assertSteps(
            import,
            import[0],
            import[0] +
                ("functions" to list(listFirst, *functions)) +
                ("requires" to list(*requires.sortedBy { (it as OtpErlangAtom).atomValue() }.toTypedArray()))
        )
    }

    fun testAParenthesisedBlockIsOneStatement() {
        val batch = harness.compile(listOf("(alias Foo.Bar; :ok)"))

        assertEquals(listOf(0 to 0, 0 to 1), batch.observations.map { it.tag.case to it.tag.statement })

        val steps = batch.observations.map { ProbedEnvNormaliser.observed(it.env, batch.probeModule) }
        assertSteps(steps, steps[0], steps[0] + ("aliases" to list(tuple(atom("Elixir.Bar"), atom("Elixir.Foo.Bar")))))
    }

    fun testEmptyParenthesesAreOneStatement() {
        val batch = harness.compile(listOf("()"))

        assertEquals(listOf(0 to 0, 0 to 1), batch.observations.map { it.tag.case to it.tag.statement })
    }

    /** A module a case body defines is hooked as the case module is, though its body never names the hook. */
    fun testTheHookReportsANestedModule() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(harness.token(), listOf(ProbeHarness.Case(NESTED_MODULE)), hook = ProbeHarness.Hook())
        )
        val caseModule = attempt.layout.caseModule(0)

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf(caseModule, "$caseModule.Fixture"), attempt.batch.hooks.keys.sorted())
        assertEquals(
            listOf("f/1"),
            attempt.batch.hooks.getValue("$caseModule.Fixture").map { "${it.name}/${it.arity}" },
        )
        harness.assertLeftNothingBehind(attempt)
    }

    /**
     * Modules at any depth, in a `do` block, in a keyword `do:` value, and with no body, are hooked; a `defmodule` in
     * the quoted code of a `quote` or in a definition's head, but for what they evaluate, is not a module of the case.
     */
    fun testEveryModuleACaseDefinesIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(
                    ProbeHarness.Case(
                        """
                        defmodule A do
                          defmodule B, do: (def b(x), do: x)
                          defmodule C do
                            defmodule D, do: def(d(x), do: x)
                          end
                          defmodule E do end
                          defmodule F, do: ()
                        end
                        def q, do: quote(do: defmodule(Q, do: nil))
                        def defmodule(name, do: block), do: {name, block}
                        """.trimIndent()
                    )
                ),
                hook = ProbeHarness.Hook(),
            )
        )
        val caseModule = attempt.layout.caseModule(0)

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf("", ".A", ".A.B", ".A.C", ".A.C.D", ".A.E", ".A.F"),
            attempt.batch.hooks.keys.map { it.removePrefix(caseModule) }.sorted()
        )
        harness.assertLeftNothingBehind(attempt)
    }

    /** A keyword module that ends the body is wrapped with its hook call, and the probe after it is outside the wrapper. */
    fun testAProbeAfterAKeywordModuleReportsTheCaseModule() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(harness.token(), listOf(ProbeHarness.Case("defmodule M, do: :ok")), hook = ProbeHarness.Hook())
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf(attempt.layout.caseModule(0), attempt.layout.caseModule(0)),
            attempt.batch.observations.map { (it.env.get(OtpErlangAtom("module")) as OtpErlangAtom).atomValue() },
        )
    }

    /** The same holds for the probe of a clause body that ends with such a module. */
    fun testAClauseBodyProbeAfterAKeywordModuleReportsTheCaseModule() {
        val body = "case 1 do\n  _ -> defmodule M, do: :ok\nend"
        val module = body.indexOf("defmodule")
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case(body, bodies = listOf(listOf(TextRange(module, module + "defmodule M, do: :ok".length))))),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            List(3) { attempt.layout.caseModule(0) },
            attempt.batch.observations.map { (it.env.get(OtpErlangAtom("module")) as OtpErlangAtom).atomValue() },
        )
    }

    /** A module defined through `Kernel.defmodule` is a module of the case, and so is hooked. */
    fun testAQualifiedDefmoduleIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case("Kernel.defmodule G do\n  def g(x), do: x\nend")),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf("", ".G"),
            attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted()
        )
    }

    /** A `defmodule` defined through `Kernel.def` is a name being defined, not a module. */
    fun testAQualifiedDefinitionOfDefmoduleIsNotHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case("defmodule A do end\nKernel.def defmodule(name, do: block), do: {name, block}")),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf("", ".A"), attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted())
    }

    /** A module defined by what a definition's head unquotes is a module of the case, though the head is not code. */
    fun testAModuleDefinedInAnUnquotedHeadIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case("def f(unquote((defmodule M do end; 0))), do: :ok")),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf("", ".M"), attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted())
    }

    /**
     * A module defined by what a `quote` evaluates is a module of the case: what its `do` body unquotes, and its other
     * options, but not the quoted code, and its `do` body's unquotes only as Elixir reads `unquote:` and `bind_quoted`.
     */
    fun testAModuleDefinedInAQuoteIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(
                    ProbeHarness.Case(
                        """
                        quote(do: unquote((defmodule M do def f, do: :ok end; :ok)))
                        quote(line: (defmodule N do end; 1), do: defmodule(Q, do: nil))
                        quote(bind_quoted: [a: 1], unquote: true, do: unquote((defmodule U do end; 1)))
                        """.trimIndent()
                    )
                ),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf("", ".M", ".N", ".U"),
            attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted()
        )
        harness.assertLeftNothingBehind(attempt)
    }

    /** The name of a call in a `quote` is evaluated where the `quote` is written, so a module it defines is hooked. */
    fun testAModuleDefinedInAQuotedCallNameIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(
                    ProbeHarness.Case(
                        """
                        quote(do: Kernel.unquote((defmodule M do end; :f))(1))
                        quote(do: unquote((defmodule N do end; :g))(1))
                        """.trimIndent()
                    )
                ),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf("", ".M", ".N"),
            attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted()
        )
        harness.assertLeftNothingBehind(attempt)
    }

    /**
     * A nested `quote` given its options as a separate argument evaluates what they unquote, where one given a single
     * keyword list does not.
     */
    fun testAModuleDefinedInANestedQuotesOptionsIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(
                    ProbeHarness.Case(
                        """
                        quote(do: quote([line: unquote((defmodule M2 do end; 1))], do: :x))
                        quote(do: quote([line: unquote((defmodule M1 do end; 1)), do: :x]))
                        """.trimIndent()
                    )
                ),
                hook = ProbeHarness.Hook(),
            )
        )

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(
            listOf("", ".M2"),
            attempt.batch.hooks.keys.map { it.removePrefix(attempt.layout.caseModule(0)) }.sorted()
        )
        harness.assertLeftNothingBehind(attempt)
    }

    /** A module whose `do` is a tuple in a keyword list, which has no `do` token, holds its definition as a `do:` does. */
    fun testAModuleWhoseDoIsATupleIsHooked() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case("defmodule M, [{:do, def(f, do: :ok)}]")),
                hook = ProbeHarness.Hook(),
            )
        )
        val caseModule = attempt.layout.caseModule(0)

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf("", ".M"), attempt.batch.hooks.keys.map { it.removePrefix(caseModule) }.sorted())
        assertEquals(listOf("f/0"), attempt.batch.hooks.getValue("$caseModule.M").map { "${it.name}/${it.arity}" })
        harness.assertLeftNothingBehind(attempt)
    }

    /** A comment between `do:` and a module's value doesn't move the value out of the module. */
    fun testAKeywordModuleWhoseValueFollowsACommentHoldsItsDefinition() {
        val attempt = harness.attempt(
            ProbeHarness.Layout(
                harness.token(),
                listOf(ProbeHarness.Case("defmodule M, do: # note\n  def m(x), do: x")),
                hook = ProbeHarness.Hook(),
            )
        )
        val caseModule = attempt.layout.caseModule(0)

        assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)
        assertEquals(listOf("m/1"), attempt.batch.hooks.getValue("$caseModule.M").map { "${it.name}/${it.arity}" })
        assertEquals(emptyList<String>(), attempt.batch.hooks.getValue(caseModule).map { it.name })
    }

    /** The hook reaches a nested module by a call to the probe module, which dispatches nothing the expander reports. */
    fun testANestedModulesHookAddsNoDispatchEvent() {
        val token = harness.token()

        fun events(hook: ProbeHarness.Hook?): List<String> {
            val attempt = harness.attempt(ProbeHarness.Layout(token, listOf(ProbeHarness.Case(NESTED_MODULE)), hook = hook))

            assertEquals(OtpErlangAtom("ok"), attempt.compiled.status)

            return DispatchEvents.of(
                attempt.compiled.events,
                attempt.batch.caseModule(0),
                attempt.batch.probeModule,
                attempt.bodyLines[0],
                attempt.layout.hookModule,
                attempt.batch.definitionAttributes,
            )
        }

        val without = events(null)

        assertTrue("the nested module's events: $without", without.any { it.contains("Kernel.def/2") })
        assertEquals(without, events(ProbeHarness.Hook()))
    }

    private fun assertSteps(actual: List<Map<String, OtpErlangObject>>, vararg expected: Map<String, OtpErlangObject>) =
        assertEquals(
            expected.joinToString("\n---\n") { ProbedEnvNormaliser.render(it) },
            actual.joinToString("\n---\n") { ProbedEnvNormaliser.render(it) }
        )

    private companion object {
        const val NESTED_MODULE = "defmodule Fixture do\n  def f(a), do: a\nend"
    }
}
