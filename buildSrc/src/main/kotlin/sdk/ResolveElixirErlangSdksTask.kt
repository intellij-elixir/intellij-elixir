package sdk

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Depends on PATH, environment variables, and external tools.")
/**
 * Resolves Erlang/Elixir SDKs and writes their paths to a properties file for tests.
 */
abstract class ResolveElixirErlangSdksTask : DefaultTask() {

    @get:Internal
    abstract val projectDir: DirectoryProperty

    /**
     * Expected versions, already resolved by the build script from `-PelixirVersion`/`-PotpVersion`
     * or, failing those, from mise (see [MiseCurrentVersionValueSource]). Absent means neither source
     * produced a version, which is a hard error - the build has no way to know what to look for.
     */
    @get:Optional
    @get:Input
    abstract val elixirVersion: Property<String>

    @get:Optional
    @get:Input
    abstract val otpVersion: Property<String>

    /** Where each expected version came from, for the log line only. */
    @get:Input
    abstract val versionSource: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val archiveOps: ArchiveOperations

    @get:Inject
    abstract val fileOps: FileSystemOperations

    @TaskAction
    fun resolve() {
        logger.lifecycle("Resolving Erlang and Elixir SDKs for tests.")

        val expectedElixirVersion = elixirVersion.orNull?.ifBlank { null }
        val expectedErlangVersion = otpVersion.orNull?.ifBlank { null }
        if (expectedElixirVersion == null || expectedErlangVersion == null) {
            throw GradleException(
                buildString {
                    append("Cannot determine which Elixir/Erlang to build against ")
                    append("(elixir=${expectedElixirVersion ?: "unknown"}, ")
                    append("erlang=${expectedErlangVersion ?: "unknown"}).\n")
                    append("Either install mise and pin the project's versions:\n")
                    append("    mise install\n")
                    append("or pass both explicitly:\n")
                    append("    -PelixirVersion=<version> -PotpVersion=<version>")
                }
            )
        }
        logger.lifecycle(
            "Expected versions (from ${versionSource.get()}): " +
                "Elixir $expectedElixirVersion, Erlang $expectedErlangVersion"
        )
        val installer = ElixirSourceInstaller(
            logger,
            projectDir.get().asFile,
            expectedElixirVersion,
            execOps,
            archiveOps,
            fileOps
        )
        val resolver = ElixirErlangSdkResolver(
            logger = logger,
            projectDir = projectDir.get().asFile,
            expectedErlangVersion = expectedErlangVersion,
            expectedElixirVersion = expectedElixirVersion,
            elixirInstaller = installer
        )
        val resolved = resolver.resolve()
        requireDetectedVersions(resolved)

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(resolved.toPropertiesString())
        logger.lifecycle(
            "Resolved SDKs: erlang=${resolved.erlang.homePath} (source=${resolved.erlang.source}, " +
                "version=${resolved.erlang.actualVersion ?: "unknown"}), " +
                "elixir=${resolved.elixir.homePath} (source=${resolved.elixir.source}, " +
                "version=${resolved.elixir.actualVersion ?: "unknown"})."
        )
        logger.lifecycle("Wrote SDK properties to ${output.absolutePath}")
    }

    /**
     * Fails rather than recording a version the resolver could not read off the SDK.
     *
     * [ElixirErlangSdkResolver] warns and carries on with a null version, which used to be written
     * out as an empty field. Nothing downstream treats that as an error: `elixirTestEnvironment`
     * maps it to a blank `ELIXIR_VERSION`, and the parser tests read that to decide the language
     * level, so a blank silently changes what they assert. Worse, this task is `@OutputFile`-based
     * and its inputs are the *expected* versions - so one bad resolution stays UP-TO-DATE and is
     * served to every later run until an input happens to change.
     */
    private fun requireDetectedVersions(resolved: ResolvedSdks) {
        val undetected = listOf(resolved.erlang, resolved.elixir).filter { it.actualVersion.isNullOrBlank() }
        if (undetected.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("Resolved an SDK whose version could not be read:")
                undetected.forEach {
                    appendLine("    ${it.name}: expected ${it.expectedVersion ?: "unknown"}, found at ${it.homePath} (source=${it.source})")
                }
                appendLine("The version is not cosmetic - it selects the language level the parser tests")
                appendLine("assert against, so recording a blank would change what they test.")
                appendLine("Check that the SDK above runs, or name the versions explicitly:")
                append("    -PelixirVersion=<version> -PotpVersion=<version>")
            }
        )
    }
}
