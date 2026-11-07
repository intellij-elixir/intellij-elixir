package sdk

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Resolves Erlang and Elixir SDK locations using a strict preference order.
 *
 * Erlang: PATH (matching expected) -> mise install.
 * Elixir: PATH (matching expected) -> mise install -> legacy source download/build.
 */
class ElixirErlangSdkResolver(
    private val logger: Logger,
    private val projectDir: File,
    private val expectedErlangVersion: String?,
    private val expectedElixirVersion: String?,
    private val elixirInstaller: ElixirSourceInstaller
) {
    fun resolve(): ResolvedSdks {
        val erlang = resolveErlang()
        val erlangHome = erlang.homePath.takeIf { it.isNotBlank() }?.let(::File)
        val elixir = resolveElixir(erlangHome)
        return ResolvedSdks(erlang, elixir)
    }

    private fun resolveErlang(): ResolvedSdk {
        val expected = expectedErlangVersion?.trim()?.ifEmpty { null }
        // Prefer PATH if it matches the expected version.
        logger.lifecycle("Resolving Erlang SDK (expected=${expected ?: "not set"}).")
        val pathCandidate = resolveErlangFromPath()
        if (pathCandidate != null) {
            logger.lifecycle(
                "Found Erlang on ${pathCandidate.source}: ${pathCandidate.home.absolutePath} " +
                    "(version=${pathCandidate.version ?: "unknown"})."
            )
            if (expected == null ||
                (pathCandidate.version != null && isCompatibleVersion(expected, pathCandidate.version))
            ) {
                if (expected == null) {
                    logger.warn("Erlang expected version not set; using PATH version ${pathCandidate.version ?: "unknown"}")
                }
                return ResolvedSdk(
                    name = "erlang",
                    homePath = pathCandidate.home.absolutePath,
                    actualVersion = pathCandidate.version,
                    expectedVersion = expectedErlangVersion,
                    source = pathCandidate.source
                )
            }
            logger.warn(
                "Erlang on PATH does not match expected version. " +
                    "Expected ${expectedErlangVersion}, found ${pathCandidate.version ?: "unknown"} " +
                    "at ${pathCandidate.home.absolutePath}"
            )
        }

        // Fall back to mise if PATH is missing or mismatched.
        var miseHome: File? = null
        var miseVersion: String? = null
        if (expected != null) {
            logger.lifecycle("Installing Erlang via mise: erlang@$expected")
            miseHome = installWithMise("erlang", expected)
            if (miseHome != null) {
                miseVersion = detectErlangVersion(miseHome)
                if (miseVersion != null && isCompatibleVersion(expected, miseVersion)) {
                    return ResolvedSdk(
                        name = "erlang",
                        homePath = miseHome.absolutePath,
                        actualVersion = miseVersion,
                        expectedVersion = expectedErlangVersion,
                        source = "mise"
                    )
                }
                logger.warn(
                    "Erlang installed by mise does not match expected version. " +
                        "Expected $expectedErlangVersion, found ${miseVersion ?: "unknown"} " +
                        "at ${miseHome.absolutePath}"
                )
            } else {
                logger.warn("mise failed to install Erlang $expectedErlangVersion.")
            }
        } else {
            logger.warn("Erlang expected version not set; unable to install via mise.")
        }

        if (pathCandidate != null) {
            logger.warn(
                "Using Erlang from PATH despite version mismatch or missing expected version: " +
                    "${pathCandidate.home.absolutePath}"
            )
            return ResolvedSdk(
                name = "erlang",
                homePath = pathCandidate.home.absolutePath,
                actualVersion = pathCandidate.version,
                expectedVersion = expectedErlangVersion,
                source = pathCandidate.source
            )
        }

        if (miseHome != null) {
            logger.warn(
                "Using Erlang installed by mise despite version mismatch: ${miseHome.absolutePath}"
            )
            return ResolvedSdk(
                name = "erlang",
                homePath = miseHome.absolutePath,
                actualVersion = miseVersion,
                expectedVersion = expectedErlangVersion,
                source = "mise"
            )
        }

        throw GradleException(
            "Unable to resolve Erlang SDK. Ensure Erlang is on PATH or install it via mise."
        )
    }

    private fun resolveElixir(erlangHome: File?): ResolvedSdk {
        val expected = expectedElixirVersion?.trim()?.ifEmpty { null }
        // Prefer PATH if it matches the expected version.
        logger.lifecycle("Resolving Elixir SDK (expected=${expected ?: "not set"}).")
        val pathCandidate = resolveElixirFromPath(erlangHome)
        if (pathCandidate != null) {
            logger.lifecycle(
                "Found Elixir on ${pathCandidate.source}: ${pathCandidate.home.absolutePath} " +
                    "(version=${pathCandidate.version ?: "unknown"})."
            )
            if (expected == null ||
                (pathCandidate.version != null && isCompatibleVersion(expected, pathCandidate.version))
            ) {
                if (expected == null) {
                    logger.warn("Elixir expected version not set; using PATH version ${pathCandidate.version ?: "unknown"}")
                }
                return ResolvedSdk(
                    name = "elixir",
                    homePath = pathCandidate.home.absolutePath,
                    actualVersion = pathCandidate.version,
                    expectedVersion = expectedElixirVersion,
                    source = pathCandidate.source
                )
            }
            logger.warn(
                "Elixir on PATH does not match expected version. " +
                    "Expected ${expectedElixirVersion}, found ${pathCandidate.version ?: "unknown"} " +
                    "at ${pathCandidate.home.absolutePath}"
            )
        }

        // Fall back to mise, then source download/build for Elixir if needed.
        var miseHome: File? = null
        var miseVersion: String? = null
        if (expected != null) {
            logger.lifecycle("Installing Elixir via mise: elixir@$expected")
            miseHome = installWithMise("elixir", expected)
            if (miseHome != null) {
                miseVersion = detectElixirVersion(miseHome, erlangHome)
                if (miseVersion != null && isCompatibleVersion(expected, miseVersion)) {
                    return ResolvedSdk(
                        name = "elixir",
                        homePath = miseHome.absolutePath,
                        actualVersion = miseVersion,
                        expectedVersion = expectedElixirVersion,
                        source = "mise"
                    )
                }
                logger.warn(
                    "Elixir installed by mise does not match expected version. " +
                        "Expected $expectedElixirVersion, found ${miseVersion ?: "unknown"} " +
                        "at ${miseHome.absolutePath}"
                )
            } else {
                logger.warn("mise failed to install Elixir $expectedElixirVersion.")
            }

            logger.lifecycle("Falling back to source download/build for Elixir $expected.")
            val fallbackHome = try {
                elixirInstaller.ensureInstalled()
            } catch (e: GradleException) {
                logger.warn("Failed to download/build Elixir $expectedElixirVersion.", e)
                null
            }

            if (fallbackHome != null) {
                val fallbackVersion = detectElixirVersion(fallbackHome, erlangHome)
                if (fallbackVersion == null) {
                    logger.warn(
                        "Unable to verify Elixir version for source download at ${fallbackHome.absolutePath}."
                    )
                } else if (!isCompatibleVersion(expected, fallbackVersion)) {
                    logger.warn(
                        "Elixir source download does not match expected version. " +
                            "Expected $expectedElixirVersion, found $fallbackVersion " +
                            "at ${fallbackHome.absolutePath}"
                    )
                }
                return ResolvedSdk(
                    name = "elixir",
                    homePath = fallbackHome.absolutePath,
                    actualVersion = fallbackVersion ?: expectedElixirVersion,
                    expectedVersion = expectedElixirVersion,
                    source = "source-download"
                )
            }
        } else {
            logger.warn("Elixir expected version not set; unable to install via mise or download.")
        }

        if (pathCandidate != null) {
            logger.warn(
                "Using Elixir from PATH despite version mismatch or missing expected version: " +
                    "${pathCandidate.home.absolutePath}"
            )
            return ResolvedSdk(
                name = "elixir",
                homePath = pathCandidate.home.absolutePath,
                actualVersion = pathCandidate.version,
                expectedVersion = expectedElixirVersion,
                source = pathCandidate.source
            )
        }

        if (miseHome != null) {
            logger.warn(
                "Using Elixir installed by mise despite version mismatch: ${miseHome.absolutePath}"
            )
            return ResolvedSdk(
                name = "elixir",
                homePath = miseHome.absolutePath,
                actualVersion = miseVersion,
                expectedVersion = expectedElixirVersion,
                source = "mise"
            )
        }

        throw GradleException(
            "Unable to resolve Elixir SDK. Ensure Elixir is on PATH or install it via mise."
        )
    }

    private fun resolveErlangFromPath(): SdkCandidate? {
        // Allow explicit SDK path overrides before consulting PATH.
        resolveSdkHomeFromEnv("ERLANG_SDK_HOME")?.let { home ->
            if (isValidErlangHome(home)) {
                val version = detectErlangVersion(home)
                return SdkCandidate(home, version, "env:ERLANG_SDK_HOME")
            }
            logger.warn("ERLANG_SDK_HOME points to an invalid Erlang SDK: ${home.absolutePath}")
        }

        val homePath = executeErlangCode("io:format(\"~s\", [code:root_dir()]), halt().", null) ?: return null
        val home = File(homePath)
        if (!isValidErlangHome(home)) {
            return null
        }
        val version = detectErlangVersion(home)
        return SdkCandidate(home, version, "path")
    }

    private fun resolveElixirFromPath(erlangHome: File?): SdkCandidate? {
        // Allow explicit SDK path overrides before consulting PATH.
        resolveSdkHomeFromEnv("ELIXIR_SDK_HOME")?.let { home ->
            if (isValidElixirHome(home)) {
                val version = detectElixirVersion(home, erlangHome)
                return SdkCandidate(home, version, "env:ELIXIR_SDK_HOME")
            }
            logger.warn("ELIXIR_SDK_HOME points to an invalid Elixir SDK: ${home.absolutePath}")
        }

        val privDirResult = execAndCapture(
            listOf("elixir", "-e", "IO.puts(:code.priv_dir(:elixir))"),
            projectDir,
            elixirExecutionEnvironment(null, erlangHome)
        ) ?: return null
        if (privDirResult.exitCode != 0 || privDirResult.output.isBlank()) {
            return null
        }
        val privDirPath = lastNonEmptyLine(privDirResult.output) ?: return null
        // `elixir` reports its priv dir through its `bin/..`.
        val privDir = File(privDirPath).normalize()
        val home = privDir.parentFile?.parentFile?.parentFile ?: return null
        if (!isValidElixirHome(home)) {
            return null
        }
        val version = detectElixirVersion(home, erlangHome)
        return SdkCandidate(home, version, "path")
    }

    private fun resolveSdkHomeFromEnv(envName: String): File? {
        val value = System.getenv(envName)
        return if (value.isNullOrBlank()) null else File(value)
    }

    private fun installWithMise(tool: String, version: String): File? {
        // Use mise to install and then locate the tool installation directory.
        val installResult = execAndCapture(listOf("mise", "install", "$tool@$version"), projectDir)
        if (installResult == null) {
            return null
        }
        if (installResult.exitCode != 0) {
            logger.warn("mise install $tool@$version failed (exit ${installResult.exitCode})")
        }

        // `mise where <tool>` (no version) reports whatever the cwd's mise config resolves, NOT what
        // was just installed - so asking for a version that differs from the local pin silently
        // returns the pinned version's path. Always ask for the version explicitly.
        val whereResult =
            execAndCapture(listOf("mise", "where", "$tool@$version"), projectDir) ?: return null
        if (whereResult.exitCode != 0 || whereResult.output.isBlank()) {
            return null
        }
        val path = lastNonEmptyLine(whereResult.output) ?: return null
        return File(path)
    }

    private fun detectErlangVersion(home: File): String? {
        val binDir = File(home, "bin")
        val code =
            "{ok, Version} = file:read_file(filename:join([code:root_dir(), \"releases\", erlang:system_info(otp_release), \"OTP_VERSION\"])), io:fwrite(Version), halt()."
        return executeErlangCode(code, binDir)
    }

    private fun detectElixirVersion(home: File, erlangHome: File?): String? {
        val binDir = File(home, "bin")
        val elixirExec = File(binDir, elixirExecutableName())
        val command = listOf(elixirExec.absolutePath, "-e", "IO.puts System.version")
        val environment = elixirExecutionEnvironment(home, erlangHome)
        val result = execAndCapture(
            command,
            projectDir,
            environment
        ) ?: return null
        if (result.exitCode != 0) {
            logger.lifecycle("${command.joinToString(" ")} exited with code ${result.exitCode}\n${result.output}")
            return null
        }
        return lastNonEmptyLine(result.output)
    }

    private fun elixirExecutionEnvironment(elixirHome: File?, erlangHome: File?): Map<String, String> {
        val home = erlangHome?.takeIf { isValidErlangHome(it) } ?: return emptyMap()
        // Shared with the quoter build tasks (see MixEnvironment.kt).
        return sdkRuntimeEnvironment(elixirHome, home)
    }

    private data class SdkCandidate(
        val home: File,
        val version: String?,
        val source: String
    )
}
