package org.elixir_lang.jps.shared.sdk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment
import java.io.File
import java.util.*

object SdkPaths {
    private val logger = Logger.getInstance(SdkPaths::class.java)

    const val SOURCE_NAME_ASDF = "asdf"
    const val SOURCE_NAME_MISE = "mise"
    const val SOURCE_NAME_ELIXIR_INSTALL = "elixir-install"
    const val SOURCE_NAME_HOMEBREW = "Homebrew"
    const val SOURCE_NAME_NIX = "Nix"
    const val SOURCE_NAME_KERL = "kerl"

    val VERSION_MANAGERS: List<String> =
        listOf(SOURCE_NAME_ASDF, SOURCE_NAME_MISE, SOURCE_NAME_ELIXIR_INSTALL)

    private val IS_WINDOWS =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")

    // Installer roots, named once for both halves of the plugin: SdkHomePaths scans them and
    // detectSource recognises what a scan or a user produced. Adding one here is the whole change.
    const val MISE_POSIX_PATH_FROM_HOME = ".local/share/mise/installs"
    const val MISE_WINDOWS_PATH_FROM_HOME = "appdata/local/mise/installs"
    const val ASDF_INSTALLS_PATH_FROM_HOME = ".asdf/installs"
    const val ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME = ".elixir-install/installs"
    const val HOMEBREW_INTEL_CELLAR_PATH = "/usr/local/Cellar"
    const val HOMEBREW_APPLE_SILICON_CELLAR_PATH = "/opt/homebrew/Cellar"
    const val LINUXBREW_CELLAR_PATH = "/home/linuxbrew/.linuxbrew/Cellar"
    const val LINUXBREW_CELLAR_PATH_FROM_HOME = ".linuxbrew/Cellar"
    const val NIX_STORE_PATH = "/nix/store"

    /** kerl installs wherever it is told, so only Travis CI's `~/otp/<version>` is a known layout. */
    const val TRAVIS_CI_KERL_DIR_NAME = "otp"

    private const val WSL_UNC_PREFIX = "//wsl.localhost/"
    private const val LEGACY_WSL_UNC_PREFIX = "//wsl$/"

    private fun isWslPath(homePath: String): Boolean =
        FileUtil.toSystemIndependentName(homePath).lowercase(Locale.ROOT)
            .let { it.startsWith(WSL_UNC_PREFIX) || it.startsWith(LEGACY_WSL_UNC_PREFIX) }

    /**
     * Whether any of [segments] appears anywhere in this already-lowercased path.
     *
     * Deliberately a substring test rather than [FileUtil.isAncestor] or [FileUtil.startsWith]:
     * those anchor at the start of the path, and a home reached inside a WSL distribution is a UNC
     * path (`//wsl.localhost/<distro>/home/linuxbrew/...`) that no Linux root is a prefix of. The
     * roots above are absolute only on the machine that owns them, and the home-relative ones
     * belong to the distribution's user rather than this JVM's, so both are matched as segments.
     *
     * Matching is case-insensitive on every platform for the same reason - the case sensitivity of
     * the filesystem actually holding the path is not the host's. Lowercasing the needle here is
     * what lets the constants above keep their real casing; comparing against `"/usr/local/Cellar/"`
     * by hand silently never matches.
     */
    private fun String.containsAnyPath(vararg segments: String): Boolean =
        segments.any { contains(it.lowercase(Locale.ROOT)) }

    /**
     * @param reachable whether [homePath] can be probed for a kerl install. Probing a home in a WSL distribution that
     * is not installed blocks, so without a caller who can tell, WSL homes are not probed.
     */
    fun detectSource(homePath: String, reachable: Boolean = !isWslPath(homePath)): String? {
        val posixPath = FileUtil.toSystemIndependentName(homePath)
        val matchPath = posixPath.lowercase(Locale.ROOT)

        if (matchPath.containsAnyPath(MISE_POSIX_PATH_FROM_HOME, MISE_WINDOWS_PATH_FROM_HOME)) {
            return SOURCE_NAME_MISE
        }
        if (matchPath.containsAnyPath("/$ASDF_INSTALLS_PATH_FROM_HOME/")) {
            return SOURCE_NAME_ASDF
        }
        if (matchPath.containsAnyPath("/$ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME/")) {
            return SOURCE_NAME_ELIXIR_INSTALL
        }
        if (matchPath.containsAnyPath(
                "$HOMEBREW_INTEL_CELLAR_PATH/",
                "$HOMEBREW_APPLE_SILICON_CELLAR_PATH/",
                "/$LINUXBREW_CELLAR_PATH_FROM_HOME/"
            )
        ) {
            return SOURCE_NAME_HOMEBREW
        }
        if (matchPath.containsAnyPath("$NIX_STORE_PATH/")) {
            return SOURCE_NAME_NIX
        }
        // Unlike the rest, the second test touches the filesystem - see TRAVIS_CI_KERL_DIR_NAME for
        // why there is no installer root to match instead.
        if (matchPath.containsAnyPath("/$TRAVIS_CI_KERL_DIR_NAME/") ||
            (reachable && File(homePath, ".kerl_config").exists())
        ) {
            return SOURCE_NAME_KERL
        }

        return null
    }

    fun mixHome(homePath: String?): File? {
        if (homePath == null) {
            return null
        }

        val source = detectSource(homePath)
        if (source == null || !VERSION_MANAGERS.contains(source)) {
            return null
        }

        val binDir = File(homePath, "bin")
        val parentDir = binDir.parentFile ?: return null

        return File(parentDir, ".mix")
    }

    private fun expectedMixHomePrefix(source: String?, homePath: String?): String? = when (source) {
        SOURCE_NAME_MISE -> expectedMisePrefix(homePath)
        SOURCE_NAME_ASDF -> "/$ASDF_INSTALLS_PATH_FROM_HOME/"
        SOURCE_NAME_ELIXIR_INSTALL -> "/$ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME/"
        else -> null
    }

    private fun expectedMisePrefix(homePath: String?): String {
        if (homePath == null) {
            return MISE_POSIX_PATH_FROM_HOME
        }

        val posixPath = FileUtil.toSystemIndependentName(homePath)
        return if (containsIgnoreCase(posixPath, MISE_WINDOWS_PATH_FROM_HOME)) {
            MISE_WINDOWS_PATH_FROM_HOME
        } else {
            MISE_POSIX_PATH_FROM_HOME
        }
    }

    /**
     * Only replace MIX_HOME when it is missing or still under the current manager's default prefix.
     * If the user configured a custom MIX_HOME elsewhere, keep it.
     */
    private fun shouldReplaceMixHomeIfExpectedPrefixMatches(existingMixHome: String?, expectedPrefix: String?): Boolean {
        if (existingMixHome == null) {
            return true
        }
        if (expectedPrefix == null) {
            return false
        }

        return pathContains(existingMixHome, expectedPrefix)
    }

    private fun pathContains(path: String, expectedPrefix: String): Boolean {
        val posixPath = FileUtil.toSystemIndependentName(path)
        return if (IS_WINDOWS) {
            containsIgnoreCase(posixPath, expectedPrefix)
        } else {
            posixPath.contains(expectedPrefix)
        }
    }

    private fun containsIgnoreCase(text: String, fragment: String): Boolean =
        text.lowercase(Locale.ROOT).contains(fragment.lowercase(Locale.ROOT))

    /** The `MIX_HOME` and `MIX_ARCHIVES` for a process that runs the SDK at [homePath]. */
    @JvmStatic
    fun mixHomeEnvironment(homePath: String?): SdkEnvironment =
        SdkEnvironment { launchVariables, toolManagerVariables ->
            mixHomeVariables(launchVariables, toolManagerVariables, homePath)
        }

    /**
     * The `MIX_HOME` and `MIX_ARCHIVES` for a process that runs the SDK at [homePath], both taken from whichever sets
     * `MIX_HOME`: the launch, then the tool manager, then the SDK. The launch's `MIX_HOME` gives way to the SDK's
     * while it is under the version manager's install prefix and the tool manager sets none.
     */
    @JvmStatic
    fun mixHomeVariables(
        launchVariables: Map<String, String>,
        toolManagerVariables: Map<String, String>,
        homePath: String?,
    ): Map<String, String> {
        val launchMixHome = launchVariables["MIX_HOME"]?.takeIf { it.isNotEmpty() }
        val launchMixArchives = launchVariables["MIX_ARCHIVES"]?.takeIf { it.isNotEmpty() }
        val toolManagerMixHome = toolManagerVariables["MIX_HOME"]?.takeIf { it.isNotEmpty() }
        val toolManagerMixArchives = toolManagerVariables["MIX_ARCHIVES"]?.takeIf { it.isNotEmpty() }
        val mixHome = mixHome(homePath)

        val variables = when {
            launchMixHome != null && (toolManagerMixHome != null || !sdkReplaces(launchMixHome, homePath)) ->
                mixHomeVariables(launchMixHome, launchMixArchives)
            toolManagerMixHome != null -> mixHomeVariables(toolManagerMixHome, launchMixArchives ?: toolManagerMixArchives)
            mixHome != null -> mixHomeVariables(
                mixHome.absolutePath,
                toolManagerMixArchives?.let { launchMixArchives ?: it } ?: mixHome.resolve("archives").absolutePath,
            )
            else -> emptyMap()
        }
        logger.trace { "SDK mix environment variables: $variables" }
        return variables
    }

    private fun sdkReplaces(launchMixHome: String, homePath: String?): Boolean =
        homePath != null && shouldReplaceMixHomeIfExpectedPrefixMatches(launchMixHome, expectedMixHomePrefix(detectSource(homePath), homePath))

    private fun mixHomeVariables(mixHome: String, mixArchives: String?): Map<String, String> {
        val separator = if ('\\' in mixHome && '/' !in mixHome) '\\' else '/'
        return mapOf("MIX_HOME" to mixHome, "MIX_ARCHIVES" to (mixArchives ?: "${mixHome.trimEnd(separator)}${separator}archives"))
    }
}
