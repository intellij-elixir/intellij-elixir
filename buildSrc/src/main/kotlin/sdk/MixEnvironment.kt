package sdk

import quoter.QuoterAvailability
import java.io.File

/**
 * Shared helpers for running `mix`/`elixir` from a RESOLVED Elixir SDK against a RESOLVED Erlang SDK
 * (as produced by [ElixirErlangSdkResolver] / the `resolveElixirErlangSdks` task). Used by both the
 * resolver (for version detection) and the quoter build tasks, so the quoter no longer needs a
 * from-source Elixir build.
 */

/** Platform-specific `mix` launcher name. */
fun mixExecutableName(): String = if (isWindows()) "mix.bat" else "mix"

/** Absolute path to the `mix` launcher inside a resolved Elixir SDK home. */
fun mixExecutable(elixirHome: File): String =
    File(File(elixirHome, "bin"), mixExecutableName()).absolutePath

/**
 * PATH/ERTS environment so `elixir`/`mix` run from the given SDKs regardless of ambient PATH.
 * Prepends `<elixirHome>/bin` (when given), then `<erlangHome>/bin`, to PATH (and `Path` on Windows)
 * and sets `ERTS_BIN`/`ERLANG_SDK_HOME`. Elixir's `bin` matters because POSIX `mix` starts with
 * `#!/usr/bin/env elixir`, which otherwise runs whatever `elixir` the ambient PATH holds.
 */
fun sdkRuntimeEnvironment(elixirHome: File?, erlangHome: File): Map<String, String> {
    val binDir = File(erlangHome, "bin")
    val existingPath = System.getenv("PATH") ?: System.getenv("Path") ?: ""
    val newPath = listOfNotNull(elixirHome?.let { File(it, "bin").absolutePath }, binDir.absolutePath, existingPath)
        .filter { it.isNotBlank() }
        .joinToString(File.pathSeparator)
    return buildMap {
        put("ERTS_BIN", binDir.absolutePath + File.separator)
        put("PATH", newPath)
        if (isWindows()) put("Path", newPath)
        put("ERLANG_SDK_HOME", binDir.parentFile.absolutePath)
    }
}

/** MIX_ENV the quoter is built under when nothing in the environment asks for another. */
const val DEFAULT_MIX_ENV = "prod"

/**
 * The MIX_ENV to build the quoter under, given the ambient value (`null` when unset).
 *
 * Resolved rather than left to mix, because `mix release` has no preferred environment: with MIX_ENV
 * unset it assembles under `:dev`, which compiles the quoter's dev/test-only dependencies - credo and
 * dialyxir, plus whatever else the pinned `quoterRef` declares, hundreds of files - ahead of the
 * quoter's own three, and emits their warnings. The daemon needs none of them at runtime.
 *
 * The value belongs to the build, not to mix's default, for a second reason: it decides a path.
 * [quoterReleaseExecutablePath] derives the launcher location from the same value that
 * [mixEnvironment] hands to the child process, so the directory the build looks in cannot drift from
 * the one mix writes. Before this was pinned, an ambient MIX_ENV was enough to separate them - Gradle's
 * `environment(Map)` adds to the inherited environment rather than replacing it, so `MIX_ENV=test` in a
 * shell reached `mix release`, which still exited 0, so the availability marker recorded a daemon that
 * `startQuoter` then could not find.
 *
 * An explicit ambient MIX_ENV still wins, so a contributor can build the quoter in another environment
 * without editing the build; the path follows it.
 */
fun resolveMixEnv(ambient: String?): String =
    ambient?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MIX_ENV

/**
 * Path of the quoter daemon's release launcher relative to the quoter project directory, for the
 * [mixEnv] the release was assembled under. Derive it from [resolveMixEnv], never hard-code the
 * environment segment - see that function.
 */
fun quoterReleaseExecutablePath(mixEnv: String): String =
    "_build/$mixEnv/rel/quoter/bin/quoter"

/**
 * Full environment for `mix` commands: the SDK runtime environment, MIX_HOME/MIX_ARCHIVES so
 * hex/rebar and fetched dependencies are cached under the project rather than the user's home, and
 * MIX_ENV so neither the environment the build inherits nor mix's own default decides it.
 *
 * Pass both the per-pair directories from [mixPairDir], never a shared root - see that function - and,
 * for the quoter, [mixEnv] from [resolveMixEnv]. [depsPath] is `MIX_DEPS_PATH`, given to every `mix` command of a
 * project alike, as one inherited from the build would otherwise reach the ones that don't set it.
 */
fun mixEnvironment(
    elixirHome: File,
    erlangHome: File,
    mixHome: File,
    mixArchives: File,
    mixEnv: String,
    depsPath: File,
): Map<String, String> =
    sdkRuntimeEnvironment(elixirHome, erlangHome) + mapOf(
        "MIX_HOME" to mixHome.absolutePath,
        "MIX_ARCHIVES" to mixArchives.absolutePath,
        "MIX_ENV" to mixEnv,
        "MIX_DEPS_PATH" to depsPath.absolutePath,
    )

/**
 * Directory for one Elixir/OTP pair under a shared [root]. Used for both MIX_ARCHIVES and MIX_HOME.
 *
 * Mix installs archives flat into MIX_ARCHIVES (`hex-2.5.1/`), so a single shared archives directory
 * hands a hex compiled by one Elixir/OTP to another, which fails to load:
 *
 *     Error loading module 'Elixir.Hex': corrupt atom table
 *
 * OTP is part of the key because the archive is BEAM code: an Elixir release ships per-OTP builds, and
 * the loader rejects a chunk layout from a different OTP.
 *
 * MIX_HOME is keyed the same way even though mix namespaces it internally by Elixir version
 * (`<MIX_HOME>/elixir/1-15/`), for two reasons. That namespacing is not universal - Elixir 1.13 writes
 * `rebar3` to the root - so the pairs are not reliably isolated from each other. And a shared MIX_HOME
 * is declared as an output of `getQuoterDeps`, so one pair's install changed another pair's output
 * snapshot. Keying it makes each pair's contents provably its own, which is what lets the task skip a
 * reinstall it already has.
 */
fun mixPairDir(root: File, elixirVersion: String?, erlangVersion: String?): File =
    File(root, pairToken(elixirVersion, erlangVersion))

/**
 * `elixir-<elixir>-otp-<otp>` - the one naming rule for anything scoped to a single Elixir/OTP pair.
 *
 * Shared with the quoter build directory rather than being private to [mixPairDir]: the reason given
 * above for keying MIX_ARCHIVES on OTP - the content is BEAM code, and the loader rejects a chunk
 * layout from a different OTP - applies just as much to the quoter's own `_build` and `deps`. Both
 * therefore derive their directory name here, so a pair's build tree and the hex/rebar that produced
 * it cannot end up scoped differently.
 */
fun pairToken(elixirVersion: String?, erlangVersion: String?): String =
    "elixir-${pathToken(elixirVersion)}-otp-${pathToken(erlangVersion)}"

/** Reduce a version string to something safe to use as a single path segment. */
private fun pathToken(version: String?): String =
    version?.trim()?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9._-]"), "-") ?: "unknown"

/**
 * Environment for the JVM test tasks (`test`, jps-builder `test`) that read the Elixir
 * stdlib source/ebin: the resolved SDK runtime env (so `elixir` and `erl` are on PATH) plus the
 * ELIXIR_LANG_ELIXIR_PATH / ELIXIR_EBIN_DIRECTORY / ELIXIR_VERSION vars pointed at the resolved SDK.
 * Read the properties file produced by `resolveElixirErlangSdks`.
 *
 * [quoterAvailabilityFile] is the marker `releaseQuoter` writes, forwarded as
 * QUOTER_AVAILABLE/QUOTER_UNAVAILABLE_REASON so the tests that quote can fail immediately and by name
 * rather than each waiting for a daemon that is not there. Taken from the marker rather than a Gradle
 * property, so a stale run cannot claim a daemon it did not build. Callers whose tests never quote
 * (jps-builder) omit it and set neither variable.
 */
fun elixirTestEnvironment(
    sdkPropertiesFile: File,
    quoterAvailabilityFile: File? = null
): Map<String, String> {
    val props = readPropertiesFile(sdkPropertiesFile)
    val elixirHome = File(props["elixir.sdk.path"] ?: error("Missing elixir.sdk.path in ${sdkPropertiesFile.absolutePath}"))
    val erlangHome = File(props["erlang.sdk.path"] ?: error("Missing erlang.sdk.path in ${sdkPropertiesFile.absolutePath}"))
    val version = props["elixir.version"].orEmpty()
    val ebin = File(elixirHome, "lib${File.separator}elixir${File.separator}ebin")
    val quoterEnvironment = quoterAvailabilityFile?.let(QuoterAvailability::readFrom)?.let { availability ->
        buildMap {
            put(QuoterAvailability.AVAILABLE_ENVIRONMENT_VARIABLE, availability.available.toString())
            if (!availability.available && availability.reason != null) {
                put(QuoterAvailability.REASON_ENVIRONMENT_VARIABLE, availability.reason)
            }
        }
    }.orEmpty()

    return sdkRuntimeEnvironment(elixirHome, erlangHome) + mapOf(
        "ELIXIR_LANG_ELIXIR_PATH" to elixirHome.absolutePath,
        "ELIXIR_EBIN_DIRECTORY" to ebin.absolutePath + File.separator,
        "ELIXIR_VERSION" to version,
        // Lets a failure name the whole pair; ELIXIR_VERSION alone would not.
        "ERLANG_VERSION" to props["erlang.version"].orEmpty(),
    ) + quoterEnvironment
}
