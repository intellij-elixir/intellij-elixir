package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.lowering.inspect
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile
import org.junit.Assert.assertEquals
import org.junit.ComparisonFailure

/** Runs [comparison], printing both sides in full when they differ. */
internal fun printed(comparison: () -> Unit) {
    try {
        comparison()
    } catch (failure: ComparisonFailure) {
        println("expander:\n${failure.expected}\nelixir:\n${failure.actual}")
        throw failure
    }
}

/**
 * A test that compiles case bodies in a project, whose expander reads the leg's modules' clauses from their `.beam`s,
 * as a project's SDK gives them. [accounting] and [definitions] are its [ExpansionProbes]' options of those names.
 */
abstract class SdkProbeTestCase(accounting: Boolean = false, definitions: Boolean = false) : PlatformTestCase() {
    // A project, so the leg's `.beam`s are read through their decompiled files.
    private val parse: (String) -> ElixirFile = { text ->
        ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, text) as ElixirFile
        }
    }
    internal val harness = ProbeHarness(parse)
    internal val probes = ExpansionProbes(harness, accounting, definitions, parse)
    internal lateinit var sdk: ModuleClauses.Lookup

    override fun setUp() {
        super.setUp()

        sdk = sdkClauses(project, testRootDisposable)
    }

    /** [body] as a case module after [preamble], with the leg's modules' clauses, and [hook] added to it. */
    internal fun expand(body: String, preamble: String, hook: ProbeHarness.Hook? = null): ExpansionProbes.CaseExpansion =
        probes.expandAll(listOf(body), preamble, hook = hook, clauses = sdk).cases.single()
}

/**
 * The invariant each compile keeps: none of [attempt]'s modules, the nested ones the hook reported among them, is
 * loaded or open, and the same names compile.
 */
internal fun ProbeHarness.assertLeftNothingBehind(attempt: ProbeHarness.Attempt) {
    val layout = attempt.layout
    val modules = (
        listOf(layout.probeModule, layout.hookModule) + layout.cases.indices.map(layout::caseModule) +
            attempt.batch.hooks.keys
        ).distinct()

    assertEquals("left behind", emptyList<String>(), loadedOrOpen(modules))

    val again = attempt(ProbeHarness.Layout(layout.token, layout.cases.map { ProbeHarness.Case(":ok") }, hook = layout.hook))

    assertEquals("the same names again", OtpErlangAtom("ok"), again.compiled.status)
    assertEquals("left behind again", emptyList<String>(), loadedOrOpen(modules))
}

/** Of [modules], those loaded or open, with whether each is. */
private fun ProbeHarness.loadedOrOpen(modules: List<String>): List<String> {
    val compiled = compileSource(
        modules.joinToString("\n") {
            val module = it.removePrefix("Elixir.")

            "IntellijElixir.Quoter.Probe.send(__ENV__, {$module, :code.is_loaded($module), Module.open?($module)})"
        }
    )

    assertEquals("invariant compile status", OtpErlangAtom("ok"), compiled.status)

    return compiled.messages.map { it as OtpErlangTuple }
        .filterNot { it.elementAt(1) == FALSE && it.elementAt(2) == FALSE }
        .map(::inspect)
}

private val FALSE = OtpErlangAtom("false")

/** A test that compiles case bodies through a [ProbeHarness] parsing them with this fixture. */
abstract class ProbeTestCase : ParsingTestCase() {
    protected val harness = ProbeHarness { createPsiFile(getTestName(false), it) as ElixirFile }

    /** `Code.compiler_options/0` as a compile sees it. */
    protected fun compilerOptions(): OtpErlangObject {
        val compiled = harness.compileSource("IntellijElixir.Quoter.Probe.send(__ENV__, Code.compiler_options())")

        assertEquals("compiler options compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.single()
    }
}
