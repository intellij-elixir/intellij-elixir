package quoter.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import quoter.QuoterAvailability
import sdk.mixEnvironment
import sdk.mixExecutable
import sdk.readPropertiesFile
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Creates a Mix release for the Quoter project using the Elixir/Erlang SDK resolved by
 * `resolveElixirErlangSdks` (mise/PATH/env aware) - no from-source Elixir build required. The
 * release bundles ERTS from the resolved Erlang, so the daemon is self-contained at runtime.
 *
 * A `mix release` that fails is recorded in [availabilityFile] rather than failing the build. Failing
 * here fails the task graph before JUnit starts, which loses the whole suite to say something about
 * only the tests that quote. `-PquoterRequired=true` asks for that hard failure anyway.
 */
abstract class ReleaseQuoterTask : DefaultTask() {

    /** Properties file written by `resolveElixirErlangSdks` (elixir.sdk.path / erlang.sdk.path). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkProperties: RegularFileProperty

    /**
     * Working directory for `mix`. @Internal rather than @InputDirectory because this task's own
     * `_build` output is nested inside it, which would make it perpetually out of date. The real
     * inputs (mix.exs, mix.lock, lib, config) are declared at the registration site, and [depsDir] here.
     */
    @get:Internal
    abstract val quoterDir: DirectoryProperty

    /** Populated by `getQuoterDeps` (which declares both as outputs); consumed here, never written. */
    @get:Internal
    abstract val mixHome: DirectoryProperty

    /** MIX_ARCHIVES for this build's Elixir/OTP pair; populated by `getQuoterDeps`, read here. */
    @get:Internal
    abstract val mixArchives: DirectoryProperty

    /** The dependencies `getQuoterDeps` fetched, given to mix as `MIX_DEPS_PATH`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val depsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val buildDir: DirectoryProperty

    /**
     * Whether the daemon could be built, and why not when it could not. An output so that it travels
     * with the `_build` it describes.
     */
    @get:OutputFile
    abstract val availabilityFile: RegularFileProperty

    /** `-PquoterRequired=true`: fail the build rather than record an unbuildable quoter. */
    @get:Input
    abstract val required: Property<Boolean>

    /**
     * MIX_ENV for the release, from `sdk.resolveMixEnv`. An @Input, not a constant read here, because it
     * selects which `_build/<env>` subtree the launcher lands in: a change has to re-run this task, or
     * [availabilityFile] would keep reporting a daemon built under the previous environment while
     * `startQuoter` looks for one under the new one.
     */
    @get:Input
    abstract val mixEnv: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    init {
        // Retry a recorded failure on the next build. Because the task succeeds when `mix release`
        // does not, Gradle would otherwise record a normal execution and skip it forever, making a
        // transient cause - a killed compile, a half-fetched dependency - permanent. A missing marker
        // is likewise not up to date: there is no record to trust.
        outputs.upToDateWhen {
            QuoterAvailability.readFrom(availabilityFile.get().asFile)?.available == true
        }

        outputs.cacheIf { true }
        // An out-of-date task can still be satisfied by a cache hit, which would defeat the check
        // above wherever the entry was pulled. Reads the previous run's marker, caching state being
        // resolved before the action runs.
        outputs.cacheIf { QuoterAvailability.readFrom(availabilityFile.get().asFile)?.available != false }
    }

    @TaskAction
    fun release() {
        val props = readPropertiesFile(sdkProperties.get().asFile)
        val elixirHome = File(props["elixir.sdk.path"] ?: throw GradleException("Missing elixir.sdk.path"))
        val erlangHome = File(props["erlang.sdk.path"] ?: throw GradleException("Missing erlang.sdk.path"))
        val mixExe = mixExecutable(elixirHome)
        val archives = mixArchives.get().asFile
        val environment =
            mixEnvironment(elixirHome, erlangHome, mixHome.get().asFile, archives, mixEnv.get(), depsDir.get().asFile)

        // Captured rather than streamed, because the reason has to outlive this task - the tests that
        // need the daemon fail later, in another JVM. One buffer for both streams keeps the failure in
        // position among the warnings; the whole output is logged below either way, so a failure keeps
        // every line while a routine build keeps its warnings out of the log.
        val captured = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(mixExe, "release", "--overwrite")
            workingDir(quoterDir.get().asFile)
            environment(environment)
            standardOutput = captured
            errorOutput = captured
            isIgnoreExitValue = true
        }

        // UTF-8 rather than the platform default: mix emits UTF-8, and the summary strips Elixir's
        // box-drawing diagnostic frame by character.
        val output = captured.toString(Charsets.UTF_8)
        if (result.exitValue == 0) logger.info(output) else logger.lifecycle(output)

        val availability = if (result.exitValue == 0) {
            QuoterAvailability.AVAILABLE
        } else {
            QuoterAvailability.unavailable(
                "mix release exited ${result.exitValue}: ${QuoterAvailability.summarize(output)}"
            )
        }
        availability.writeTo(availabilityFile.get().asFile)

        if (!availability.available) {
            val message = "Quoter daemon unavailable for " +
                "Elixir ${props["elixir.version"].orEmpty()} / OTP ${props["erlang.version"].orEmpty()}: " +
                availability.reason

            if (required.get()) {
                throw GradleException(message)
            }

            logger.lifecycle(
                "$message\n" +
                    "Tests that quote through the daemon will fail; the rest of the suite still runs. " +
                    "Pass -PquoterRequired=true to fail the build here instead."
            )
        }
    }
}
