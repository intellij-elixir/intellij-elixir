package testing

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * The part of the suite a CI job runs. A class is never split between forks, so the few classes holding half of a
 * leg's time set its wall time from inside one fork; CI runs them as their own job beside the rest.
 */
enum class TestPart {
    /** Only [TestPartition.HEAVY]. */
    HEAVY,

    /** Everything but [TestPartition.HEAVY], so the two parts together run every class exactly once. */
    REST;

    companion object {
        fun parse(value: String): TestPart =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw GradleException("testPart must be one of ${entries.joinToString { it.name.lowercase() }}, not '$value'")
    }
}

object TestPartition {
    /** The classes with the most time per leg on CI, which [TestPart.HEAVY] runs and [TestPart.REST] leaves out. */
    val HEAVY: List<String> = listOf(
        "org.elixir_lang.annotator.VersionedSyntaxTest",
        "org.elixir_lang.beam.SdkStdlibSweepTest",
        "org.elixir_lang.code_insight.completion.code_fragment.CodeFragmentOracleShard*Test",
        "org.elixir_lang.expander.CompiledModuleTest",
        "org.elixir_lang.expander.InterpreterTest",
        "org.elixir_lang.expander.VariableClassProbeTest",
        "org.elixir_lang.parser_definition.Issue4114TestCase",
        "org.elixir_lang.snapshot.CompilerCorpusJoinShard*Test",
    )

    /**
     * Classes whose result cannot differ by the Elixir/OTP the leg runs: no quoter, no SDK in the project, no
     * environment read, and a language level that is a literal or the SDK-less fallback. CI runs them only on the
     * legs of the baseline pair. A class reading anything the leg provides does not belong here.
     */
    val VERSION_INDEPENDENT: List<String> = listOf(
        "org.elixir_lang.FindUsagesTest",
        "org.elixir_lang.FormattingTest",
        "org.elixir_lang.annotator.HeredocErrorTest",
        "org.elixir_lang.annotator.InvalidConstructTest",
        "org.elixir_lang.annotator.InvalidTokenTest",
        "org.elixir_lang.annotator.VersionedSyntaxTest",
        "org.elixir_lang.injection.markdown.ExpressionDocInjectorTest",
        "org.elixir_lang.mix.sync.MixDepsSyncServiceTest",
        "org.elixir_lang.model.psi.function.DelegationRenameTest",
        "org.elixir_lang.model.psi.rename.RenameMatrixTest",
        "org.elixir_lang.model.psi.variable.VariableOracleCorpusShard*Test",
        "org.elixir_lang.psi.scope.callable_table.ParityTest",
    )
}

/**
 * Narrows the run to [part] (`-PtestPart=heavy|rest`), and leaves out [TestPartition.VERSION_INDEPENDENT] when
 * [versionIndependent] (`-PtestVersionIndependent`) is false. With neither set every class runs.
 */
fun Test.partition(part: Provider<String>, versionIndependent: Provider<String>) {
    when (part.orNull?.let(TestPart::parse)) {
        TestPart.HEAVY -> TestPartition.HEAVY.forEach(filter::includeTestsMatching)
        TestPart.REST -> TestPartition.HEAVY.forEach(filter::excludeTestsMatching)
        null -> Unit
    }

    val runVersionIndependent = versionIndependent.orNull?.let { value ->
        value.toBooleanStrictOrNull() ?: throw GradleException("testVersionIndependent must be true or false, not '$value'")
    } ?: true

    if (!runVersionIndependent) TestPartition.VERSION_INDEPENDENT.forEach(filter::excludeTestsMatching)
}
