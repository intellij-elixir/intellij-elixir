package org.elixir_lang.sdk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.Version
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.jps.shared.sdk.SdkPaths
import org.elixir_lang.sdk.wsl.wslCompat
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

object SdkHomePaths {
    private const val HEAD_PREFIX = "HEAD-"

    /** `make install` with the Makefile's default `PREFIX`, i.e. a build from source. */
    private const val LINUX_SOURCE_ROOT_PATH = "/usr/local/lib"

    /**
     * `PREFIX=/usr` with the default `LIBDIR`, i.e. a distribution package. Debian, Ubuntu, Mint,
     * Arch, Alpine and openSUSE all land here; it is not specific to Mint.
     */
    private const val LINUX_DISTRO_ROOT_PATH = "/usr/lib"

    /**
     * `PREFIX=/usr` with a multilib `LIBDIR`. Gentoo builds both tools with it, and Fedora uses it
     * for Erlang.
     */
    private const val LINUX_MULTILIB_ROOT_PATH = "/usr/lib64"

    /** Version-scoped homes for packages installed outside a libdir, as Fedora does for Elixir. */
    const val LINUX_SHARE_ROOT_PATH = "/usr/share"


    /**
     * Roots a source build or a distribution package installs a tool under, in scan order.
     * [linuxSystemHomePaths] suffixes the tool name onto each; a new distro layout is added here and
     * nowhere else.
     */
    private val LINUX_SYSTEM_ROOT_PATHS =
        listOf(LINUX_SOURCE_ROOT_PATH, LINUX_DISTRO_ROOT_PATH, LINUX_MULTILIB_ROOT_PATH)

    @JvmField
    val UNKNOWN_VERSION = Version(0, 0, 0)

    /** Intel macOS, Apple Silicon macOS, and Homebrew on Linux, whose prefix is `.linuxbrew`. */
    private val HOMEBREW_CELLAR_PATHS = listOf(
        SdkPaths.HOMEBREW_INTEL_CELLAR_PATH,
        SdkPaths.HOMEBREW_APPLE_SILICON_CELLAR_PATH,
        SdkPaths.LINUXBREW_CELLAR_PATH
    )
    private val NIX_STORE = File(SdkPaths.NIX_STORE_PATH)
    private val LOGGER = Logger.getInstance(SdkHomePaths::class.java)

    @JvmStatic
    fun nixPattern(name: String): Pattern {
        return Pattern.compile(".+-$name-(\\d+)\\.(\\d+)\\.(\\d+)")
    }

    /**
     * Every hardcoded Linux home path for [toolName], in scan order.
     */
    @JvmStatic
    fun linuxSystemHomePaths(toolName: String): List<String> =
        LINUX_SYSTEM_ROOT_PATHS.map { "$it/$toolName" }

    /**
     * Adds each of [linuxSystemHomePaths] that exists as a directory.
     *
     * These keep [unknownVersionKey], so they sort below every version-manager install. That is
     * intended: a system install declares no version in its path, and a version manager's is
     * preferred whenever both are present.
     *
     * The native Linux scan and the WSL scan differ only by [toLocalPath], which maps a Linux path
     * to how this machine reaches it - identity when running on Linux, a UNC path when reaching into
     * a WSL distribution, and null when it cannot be reached at all. Both therefore go through this
     * one call, so the WSL scan cannot be left without a path the native scan gained.
     */
    @JvmStatic
    fun mergeLinuxSystemHomePaths(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        toolName: String,
        toLocalPath: (String) -> String? = { it },
    ) {
        for (candidate in linuxSystemHomePaths(toolName)) {
            val homePath = toLocalPath(candidate)

            if (homePath == null) {
                LOGGER.trace { "$candidate: Not reachable" }
                continue
            }

            if (File(homePath).isDirectory) {
                homePathByVersion[unknownVersionKey(homePath)] = homePath
            } else {
                LOGGER.trace { "$homePath: Not a directory" }
            }
        }
    }

    @JvmStatic
    fun mergeASDF(homePathByVersion: MutableMap<SdkHomeKey, String>, name: String) {
        mergeASDF(homePathByVersion, name, System.getProperty("user.home"))
    }

    @JvmStatic
    fun mergeASDF(homePathByVersion: MutableMap<SdkHomeKey, String>, name: String, userHome: String) {
        mergeNameSubdirectories(
            homePathByVersion,
            Path.of(userHome, SdkPaths.ASDF_INSTALLS_PATH_FROM_HOME).toFile(),
            name, SdkPaths.SOURCE_NAME_ASDF
        )
    }

    @JvmStatic
    fun mergeMise(homePathByVersion: MutableMap<SdkHomeKey, String>, name: String, userHome: String) {
        listOf(SdkPaths.MISE_POSIX_PATH_FROM_HOME, SdkPaths.MISE_WINDOWS_PATH_FROM_HOME).forEach { misePath ->
            mergeNameSubdirectories(
                homePathByVersion,
                Path.of(userHome, misePath).toFile(),
                name, SdkPaths.SOURCE_NAME_MISE
            )
        }
    }

    @JvmStatic
    fun mergeElixirInstallScript(homePathByVersion: MutableMap<SdkHomeKey, String>, name: String) {
        mergeElixirInstallScript(homePathByVersion, name, System.getProperty("user.home"))
    }

    @JvmStatic
    fun mergeElixirInstallScript(homePathByVersion: MutableMap<SdkHomeKey, String>, name: String, userHome: String) {
        mergeNameSubdirectories(
            homePathByVersion,
            Path.of(userHome, SdkPaths.ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME).toFile(),
            name, SdkPaths.SOURCE_NAME_ELIXIR_INSTALL
        )
    }

    /**
     * Scans `/usr/share/<name>/<version>` for packages installing version-scoped homes outside a
     * libdir, as Fedora and RHEL do for Elixir. Same `<root>/<name>/<version>` shape as the version
     * managers, so the version is parsed from the directory name.
     *
     * Only subdirectories naming a parseable version are taken. `/usr/share/<name>` also holds
     * directories that are not homes - Fedora puts Erlang's `ERL_LIBS` at `/usr/share/erlang/lib` -
     * and suggesting those would offer the user a path that cannot validate.
     */
    @JvmStatic
    @JvmOverloads
    fun mergeSystemShare(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        name: String,
        shareRoot: String = LINUX_SHARE_ROOT_PATH,
    ) {
        val versioned = mutableMapOf<SdkHomeKey, String>()
        mergeNameSubdirectories(versioned, File(shareRoot), name, source = null)

        for ((key, homePath) in versioned) {
            if (key.version != UNKNOWN_VERSION) {
                homePathByVersion[key] = homePath
            } else {
                LOGGER.trace { "$homePath: Not a version directory" }
            }
        }
    }

    /**
     * The SDK home for [toolName] at or under [candidate].
     *
     * An install prefix is not itself a home: `make install PREFIX=<prefix>` puts the home in
     * `<prefix>/lib/<toolName>` and leaves only symlinks in `<prefix>/bin`. Homebrew version
     * prefixes, kiex version directories, `/usr` and `/usr/local` are all this shape, while an
     * already-correct home is not, so the two are told apart by whether the nested directory has a
     * `bin` of its own - a home always does, and a bare OTP application directory never does.
     *
     * Homebrew is the reason both shapes are live at once: it switched Elixir to the Makefile's
     * `install` target on 2024-09-22, so anything installed before that keeps the flat layout.
     */
    @JvmStatic
    fun toolHomePath(candidate: File, toolName: String): File {
        val nested = File(candidate, "lib/$toolName")

        return if (File(nested, "bin").isDirectory) nested else candidate
    }

    /**
     * Children of a home a chooser selection can land on instead of the home itself.
     *
     * `releases` is one an Erlang home has and an Elixir home does not, so listing it costs Elixir
     * nothing. `usr` is deliberately absent even though an Erlang home has one: treating it as a
     * child would step `/usr` up to the filesystem root and break selecting a distribution install.
     */
    private val SDK_HOME_CHILD_BASE_NAMES = setOf("bin", "lib", "src", "releases")

    /**
     * The home for [toolName] implied by a path chosen in the SDK file chooser.
     *
     * A selection lands on the home, on a child of it, or on an install prefix holding it, so step
     * up out of a known child and then down into a prefix. Both steps are no-ops for a path that is
     * already a home, and neither invents a path that does not exist.
     *
     * The scan applies [toolHomePath] to what it discovers; this is the same rule for what a user
     * picks, so the two agree on which directory is the home.
     */
    @JvmStatic
    fun adjustSelectedSdkHome(homePath: String, toolName: String): String {
        val homePathFile = File(homePath)
        if (!homePathFile.isDirectory) return homePath

        val candidate = if (homePathFile.name in SDK_HOME_CHILD_BASE_NAMES) {
            homePathFile.parentFile ?: homePathFile
        } else {
            homePathFile
        }

        return toolHomePath(candidate, toolName).path
    }

    /**
     * Every Cellar root reachable through [toLocalPath], plus the one under [userHome].
     *
     * Homebrew's prefix differs by platform, but a prefix that belongs to another platform simply
     * does not exist, so all of them are offered rather than chosen by OS - and a Homebrew on Linux
     * installed to a macOS-looking custom prefix is then found too.
     */
    @JvmStatic
    fun homebrewCellarPaths(userHome: String?, toLocalPath: (String) -> String?): List<String> =
        HOMEBREW_CELLAR_PATHS.mapNotNull(toLocalPath) +
            listOfNotNull(userHome?.let { File(it, SdkPaths.LINUXBREW_CELLAR_PATH_FROM_HOME).path })

    @JvmStatic
    fun mergeHomebrew(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        name: String,
        versionPathToHomePath: (File) -> File,
    ) {
        mergeHomebrew(
            homePathByVersion, name, versionPathToHomePath,
            HOMEBREW_CELLAR_PATHS + File(
                System.getProperty("user.home"), SdkPaths.LINUXBREW_CELLAR_PATH_FROM_HOME
            ).path
        )
    }

    /**
     * Scans explicit Cellar roots. Homebrew does not run natively on Windows, so the only Windows
     * users with one are reaching into a WSL distribution, whose roots are UNC paths.
     */
    @JvmStatic
    fun mergeHomebrew(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        name: String,
        versionPathToHomePath: (File) -> File,
        cellarPaths: List<String>,
    ) {
        for (cellar in cellarPaths.map { File(it) }) {
            for (formula in homebrewFormulaNames(cellar, name)) {
                mergeNameSubdirectories(
                    homePathByVersion,
                    cellar,
                    formula,
                    SdkPaths.SOURCE_NAME_HOMEBREW,
                    versionPathToHomePath
                )
            }
        }
    }

    /**
     * Cellar directory names holding [toolName], newest-pinned last.
     *
     * Homebrew keeps every installed version of a formula in its own Cellar directory until
     * `brew cleanup` runs, and it also ships version-pinned formulae - `erlang@25` through
     * `erlang@28` - which live under their own name rather than beside the unpinned versions. Both
     * have to be scanned or a pinned Erlang, the usual way to hold an OTP release for a given
     * Elixir, is invisible.
     */
    private fun homebrewFormulaNames(cellar: File, toolName: String): List<String> {
        val pinnedPrefix = "$toolName@"
        val names = cellar
            .listFiles { file -> file.isDirectory && file.name.startsWith(pinnedPrefix) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

        return listOf(toolName) + names
    }

    @JvmStatic
    fun mergeNixStore(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        nixPattern: Pattern,
        versionPathToHomePath: (File) -> File,
    ) {
        mergeNixStore(homePathByVersion, nixPattern, versionPathToHomePath, NIX_STORE.absolutePath)
    }

    @JvmStatic
    fun mergeNixStore(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        nixPattern: Pattern,
        versionPathToHomePath: (File) -> File,
        nixStorePath: String,
    ) {
        val nixStore = File(nixStorePath)
        if (nixStore.isDirectory) {
            nixStore.listFiles { dir, name ->
                val matcher: Matcher = nixPattern.matcher(name)
                var accept = false
                if (matcher.matches()) {
                    val major = matcher.group(1).toInt()
                    val minor = matcher.group(2).toInt()
                    val bugfix = matcher.group(3).toInt()
                    val version = Version(major, minor, bugfix)
                    val homePath = versionPathToHomePath(File(dir, name)).absolutePath
                    val key = SdkHomeKey(version, name, SdkPaths.SOURCE_NAME_NIX, homePath)
                    homePathByVersion[key] = homePath
                    accept = true
                }
                accept
            }
        }
    }

    @JvmStatic
    fun mergeTravisCIKerl(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        versionPathToHomePath: (File) -> File,
    ) {
        val userHome = System.getProperty("user.home")
        if (userHome != null) {
            mergeTravisCIKerl(homePathByVersion, versionPathToHomePath, userHome)
        }
    }

    @JvmStatic
    fun mergeTravisCIKerl(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        versionPathToHomePath: (File) -> File,
        userHome: String,
    ) {
        mergeNameSubdirectories(
            homePathByVersion,
            File(userHome),
            SdkPaths.TRAVIS_CI_KERL_DIR_NAME,
            SdkPaths.SOURCE_NAME_KERL,
            versionPathToHomePath
        )
    }

    @JvmStatic
    fun mergeKerl(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        versionPathToHomePath: (File) -> File,
    ) {
        if (!isCommandAvailable("kerl")) {
            return
        }

        try {
            val process = ProcessBuilder("kerl", "list", "installations")
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(" ", limit = 2)
                    if (parts.size == 2) {
                        val versionString = parts[0].trim()
                        val path = parts[1].trim()
                        val homePath = versionPathToHomePath(File(path))
                        if (homePath.isDirectory) {
                            val version = parseVersion(versionString)
                            val key =
                                SdkHomeKey(version, versionString, SdkPaths.SOURCE_NAME_KERL, homePath.absolutePath)
                            homePathByVersion[key] = homePath.absolutePath
                        }
                    }
                }
            }

            process.waitFor(5, TimeUnit.SECONDS)
        } catch (e: IOException) {
            if (LOGGER.isDebugEnabled) {
                LOGGER.debug("kerl list installations failed: ${e.message}")
            }
        } catch (e: InterruptedException) {
            if (LOGGER.isDebugEnabled) {
                LOGGER.debug("kerl list installations failed: ${e.message}")
            }
        }
    }

    private fun mergeNameSubdirectories(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        parent: File,
        name: String,
        source: String?,
        versionPathToHomePath: (File) -> File = { it },
    ) {
        LOGGER.trace { "$parent: Scanning for SDK Home Paths" }
        if (!parent.isDirectory) {
            LOGGER.trace { "$parent: Not a directory" }
            return
        }

        val nameDirectory = File(parent, name)
        LOGGER.trace { "$nameDirectory: Scanning" }
        if (!nameDirectory.isDirectory) {
            LOGGER.trace { "$nameDirectory: Not a directory" }
            return
        }

        // Sorted: listFiles() order is unspecified, and it decides an exact tie in
        // preferredHomeDirectory - so without this the name shown for an install varies by machine.
        val children = nameDirectory.listFiles()?.sortedBy { it.name } ?: return

        // mise publishes a version alias per prefix - 26, 26.2 and 26.2.5 all symlink to 26.2.5.21 -
        // which canonicalizePath resolves to one home, so a key carrying the directory name offers
        // that install once per alias.
        val childByHomePath = LinkedHashMap<String, Pair<String, File>>()

        for (child in children) {
            LOGGER.trace { "$child: Scanning" }
            if (!child.isDirectory) continue

            val homePath = wslCompat.canonicalizePath(versionPathToHomePath(child).absolutePath)
            val groupingKey = FileUtil.toSystemIndependentName(homePath)
            val incumbent = childByHomePath[groupingKey]

            if (incumbent == null || preferredHomeDirectory(child, incumbent.second, groupingKey) === child) {
                childByHomePath[groupingKey] = homePath to child
            }
        }

        for ((homePath, child) in childByHomePath.values) {
            // The version names the directory scanned, not the home inside it: a transform that
            // descends (Homebrew's `lib/<tool>`) would otherwise read the tool name as a version.
            val versionString = child.name
            val version = parseVersion(versionString)
            val key = SdkHomeKey(version, versionString, source, homePath)
            LOGGER.trace { "$child: Adding $key" }
            homePathByVersion[key] = homePath
        }
    }

    /**
     * Picks which of two directories resolving to the same home, named by [groupingKey], should
     * name it.
     *
     * The parse rule runs first because [mergeSystemShare] drops an [UNKNOWN_VERSION] key outright,
     * so an unparseable winner loses the install rather than merely sorting it last - and the home's
     * own final segment can be the unparseable one. Note [UNKNOWN_VERSION] is `0.0.0`, so `0latest`
     * counts as unparseable.
     *
     * Then the name matching the home's final segment, which for a version-manager alias is the
     * concrete install. Length breaks a remaining tie, and scan order an exact one.
     */
    private fun preferredHomeDirectory(candidate: File, incumbent: File, groupingKey: String): File {
        val candidateParses = parseVersion(candidate.name) != UNKNOWN_VERSION
        val incumbentParses = parseVersion(incumbent.name) != UNKNOWN_VERSION
        if (candidateParses != incumbentParses) return if (candidateParses) candidate else incumbent

        // Split the normalised key, not the raw home: a backslash the raw form carried is not a
        // separator off Windows, which would hide the final segment and stop this rule firing.
        val homeName = groupingKey.substringAfterLast('/')
        if (candidate.name == homeName) return candidate
        if (incumbent.name == homeName) return incumbent

        return if (candidate.name.length > incumbent.name.length) candidate else incumbent
    }

    private fun parseVersion(versionString: String): Version {
        var version = Version.parseVersion(versionString)
        if (version == null) {
            version = if (versionString.startsWith(HEAD_PREFIX)) {
                val sha1 = versionString.substring(HEAD_PREFIX.length)
                Version(0, 0, Integer.parseInt(sha1, 16))
            } else {
                UNKNOWN_VERSION
            }
        }
        return version
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command).start()
            process.waitFor() == 0
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            false
        }
    }

    fun unknownVersionKey(path: String) = SdkHomeKey(UNKNOWN_VERSION, null, null, path)
}
