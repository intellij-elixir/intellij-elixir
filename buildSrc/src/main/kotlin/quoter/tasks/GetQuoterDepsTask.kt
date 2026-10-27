package quoter.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import sdk.mixEnvironment
import sdk.mixExecutable
import sdk.readPropertiesFile
import java.io.File
import javax.inject.Inject

/**
 * Fetches a Mix project's dependencies - the Quoter's, and a test fixture's - using the Elixir/Erlang SDK
 * resolved by `resolveElixirErlangSdks` (mise/PATH/env aware) - no from-source Elixir build required.
 */
abstract class GetQuoterDepsTask : DefaultTask() {

    /** Properties file written by `resolveElixirErlangSdks` (elixir.sdk.path / erlang.sdk.path). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sdkProperties: RegularFileProperty

    /**
     * The Mix project, and the working directory for `mix`. Deliberately @Internal, not @InputDirectory:
     * the quoter's `deps` output - and `releaseQuoter`'s `_build` output - are nested inside it, so
     * tracking the whole tree as an input made every build invalidate this task and re-run
     * `mix local.hex` (a network round-trip; `--force` skips the prompt, not the download). The inputs
     * that actually decide the dependency set, `mix.exs` and the lock, are declared at each registration
     * site.
     */
    @get:Internal
    abstract val projectDir: DirectoryProperty

    /**
     * MIX_HOME for this build's Elixir/OTP pair, wired from `build.gradle.kts`. Per-pair rather than
     * shared: mix's own `<MIX_HOME>/elixir/1-15/` namespacing is not universal (1.13 writes `rebar3` to
     * the root), and while this is a declared output a shared directory meant another pair's install
     * changed this task's output snapshot.
     *
     * MIX_HOME and MIX_ARCHIVES are OUTPUTS, not internal state: `mix local.rebar` and
     * `mix local.hex` populate them, and `releaseQuoter`'s `mix release` cannot run without them.
     * Declaring them has two effects that the previous `@Internal` did not:
     *
     * - the build cache stores and restores them alongside `deps`, so a cache hit that skips this
     *   action still leaves hex/rebar in place (otherwise `mix release` fails with
     *   "Could not find Hex, which is needed to build dependency :credo");
     * - an up-to-date or from-cache task never runs the action, so `local.hex`/`local.rebar` stop
     *   hitting the network on every build - `--force` suppresses the prompt, not the download.
     */
    @get:OutputDirectory
    abstract val mixHome: DirectoryProperty

    /**
     * MIX_ARCHIVES itself - the directory for the Elixir/OTP pair this build targets, wired from
     * `build.gradle.kts`. Declaring the enclosing root here instead meant a sibling pair's directory
     * changed this task's output snapshot, so the other pair's cache entry could not be restored and a
     * version switch re-downloaded hex.
     */
    @get:OutputDirectory
    abstract val mixArchives: DirectoryProperty

    /** Where `deps.get` writes, given to mix as `MIX_DEPS_PATH`, so it need not be under [projectDir]. */
    @get:OutputDirectory
    abstract val depsDir: DirectoryProperty

    /**
     * MIX_ENV for the fetch. The quoter's is from `sdk.resolveMixEnv` - the same value `releaseQuoter`
     * builds under, so `deps` holds what that release needs and nothing else.
     *
     * It reaches `mix deps.get` twice over, and only the second one does anything. `deps.get` ignores
     * MIX_ENV by design, fetching every dependency in the lock whatever its `only:`, so the environment
     * has to be named again as `--only <env>`.
     */
    @get:Input
    abstract val mixEnv: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    init {
        // Configure task to cache outputs
        outputs.cacheIf { true }
    }

    @TaskAction
    fun getDeps() {
        val props = readPropertiesFile(sdkProperties.get().asFile)
        val elixirHome = File(props["elixir.sdk.path"] ?: throw GradleException("Missing elixir.sdk.path"))
        val erlangHome = File(props["erlang.sdk.path"] ?: throw GradleException("Missing erlang.sdk.path"))
        val mixExe = mixExecutable(elixirHome)
        val home = mixHome.get().asFile.apply { mkdirs() }
        val archives = mixArchives.get().asFile.apply { mkdirs() }
        // `--only <env>` can have nothing to fetch - a quoter whose every dependency is dev/test-only
        // has an empty prod set - and mix then does not create `deps` at all. It is a declared output
        // here and a declared input of `releaseQuoter`, so create it rather than leave that to mix.
        val deps = depsDir.get().asFile.apply { mkdirs() }
        val environment = mixEnvironment(elixirHome, erlangHome, home, archives, mixEnv.get(), deps)
        val dir = projectDir.get().asFile

        fun mix(vararg args: String) {
            execOps.exec {
                commandLine(listOf(mixExe) + args)
                workingDir(dir)
                environment(environment)
            }
        }

        // hex + rebar are required to fetch dependencies.
        //
        // `--force` suppresses the overwrite prompt, not the download, so the only way to avoid the
        // network round-trip is not to call it. The quoter's fetch cannot be made up-to-date or cacheable
        // across a version switch - its `deps` sits inside unzipQuoter's declared output directory, which
        // disables caching, and up-to-date history holds one entry per task - so it re-executes whenever
        // the targeted pair changes, and skipping the reinstall is what keeps that cheap.
        //
        // Presence is a sound signal only because both directories are keyed on the Elixir/OTP pair:
        // anything in them was installed by this task for this pair. Neither is searched at a fixed
        // depth - mix puts `hex-<version>` directly in MIX_ARCHIVES, but `rebar3` either in MIX_HOME's
        // root (Elixir 1.13) or under `elixir/<minor>/` (later), so the layout is not predicted.
        val installedHex = archives.listFiles()?.firstOrNull { it.name.startsWith("hex-") }
        if (installedHex == null) {
            mix("local.hex", "--force")
        } else {
            logger.info("Reusing ${installedHex.name} in ${archives.name}; skipping mix local.hex")
        }

        val installedRebar = home.walkTopDown().firstOrNull { it.isFile && it.name.startsWith("rebar3") }
        if (installedRebar == null) {
            mix("local.rebar", "--force")
        } else {
            logger.info("Reusing ${installedRebar.name} in ${home.name}; skipping mix local.rebar")
        }

        // `--only` because deps.get is the one mix task that disregards MIX_ENV: unrestricted it fetches
        // the whole lock, so the quoter's dev/test-only dependencies were downloaded on a cold cache for
        // a prod release that never compiles them. Never prunes `deps` - mix leaves an already-fetched
        // dependency alone - so switching environments locally leaves the previous set in place.
        mix("deps.get", "--only", mixEnv.get())
    }
}
