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
        "org.elixir_lang.code_insight.completion.code_fragment.CodeFragmentOracleTest",
        "org.elixir_lang.expander.CompiledModuleTest",
        "org.elixir_lang.expander.InterpreterTest",
        "org.elixir_lang.expander.VariableClassProbeTest",
        "org.elixir_lang.parser_definition.Issue4114TestCase",
        "org.elixir_lang.snapshot.CompilerCorpusJoinShard0Test",
        "org.elixir_lang.snapshot.CompilerCorpusJoinShard1Test",
        "org.elixir_lang.snapshot.CompilerCorpusJoinShard2Test",
        "org.elixir_lang.snapshot.CompilerCorpusJoinShard3Test",
    )
}

/** Narrows the run to [part] (`-PtestPart=heavy|rest`); without it every class runs. */
fun Test.partition(part: Provider<String>) {
    when (part.orNull?.let(TestPart::parse)) {
        TestPart.HEAVY -> TestPartition.HEAVY.forEach(filter::includeTestsMatching)
        TestPart.REST -> TestPartition.HEAVY.forEach(filter::excludeTestsMatching)
        null -> Unit
    }
}
