package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.Version
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.jps.shared.sdk.SdkPaths
import java.io.File
import java.nio.file.Paths

/**
 * Tests for SdkHomeScan consolidated SDK scanning logic.
 *
 * Tests cover:
 * - Config validation and behavior
 * - Path transformation logic
 * - WSL distribution filtering
 * - Integration with mocked file systems
 */
class SdkHomeScanTest : PlatformTestCase() {

    // ========== Config Validation Tests ==========

    fun `test elixir config uses correct tool name`() {
        val config = createElixirConfig()
        assertEquals("elixir", config.toolName)
    }

    fun `test erlang config uses correct tool name`() {
        val config = createErlangConfig()
        assertEquals("erlang", config.toolName)
    }

    fun `test elixir config has null kerl transforms`() {
        val config = createElixirConfig()
        assertNull("Elixir should not support kerl", config.kerlTransform)
        assertNull("Elixir should not support Travis CI kerl", config.travisCIKerlTransform)
    }

    fun `test erlang config has non-null kerl transforms`() {
        val config = createErlangConfig()
        assertNotNull("Erlang should support kerl", config.kerlTransform)
        assertNotNull("Erlang should support Travis CI kerl", config.travisCIKerlTransform)
    }

    fun `test elixir config windows paths`() {
        val config = createElixirConfig()
        assertEquals("C:\\Program Files (x86)\\Elixir", config.windowsDefaultPath)
        assertEquals("C:\\Program Files\\Elixir", config.windows32BitPath)
    }

    fun `test erlang config windows paths`() {
        val config = createErlangConfig()
        assertEquals("C:\\Program Files\\erl9.0", config.windowsDefaultPath)
        assertNull("Erlang uses same path for 32-bit", config.windows32BitPath)
    }

    fun `test elixir linux system home paths`() {
        assertEquals(
            listOf("/usr/local/lib/elixir", "/usr/lib/elixir", "/usr/lib64/elixir"),
            SdkHomePaths.linuxSystemHomePaths("elixir")
        )
    }

    fun `test erlang linux system home paths`() {
        assertEquals(
            listOf("/usr/local/lib/erlang", "/usr/lib/erlang", "/usr/lib64/erlang"),
            SdkHomePaths.linuxSystemHomePaths("erlang")
        )
    }

    fun `test mergeLinuxSystemHomePaths adds only directories that exist`() {
        withTempRoot { root ->
            val present = File(root, "usr/lib/elixir")
            assertTrue("could not create $present", present.mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeLinuxSystemHomePaths(homePathByVersion, "elixir") { File(root, it).path }

            // /usr/local/lib/elixir was a candidate too, but was never created.
            assertEquals(
                setOf(FileUtil.toSystemIndependentName(present.absolutePath)),
                homePathByVersion.values.map { FileUtil.toSystemIndependentName(it) }.toSet()
            )
        }
    }

    fun `test mergeLinuxSystemHomePaths covers every candidate`() {
        withTempRoot { root ->
            val candidates = SdkHomePaths.linuxSystemHomePaths("elixir")
            candidates.forEach { assertTrue(File(root, it).mkdirs()) }

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeLinuxSystemHomePaths(homePathByVersion, "elixir") { File(root, it).path }

            assertEquals(
                "every path linuxSystemHomePaths names must be scanned",
                candidates.map { FileUtil.toSystemIndependentName(File(root, it).absolutePath) }.toSet(),
                homePathByVersion.values.map { FileUtil.toSystemIndependentName(it) }.toSet()
            )
        }
    }

    fun `test mergeLinuxSystemHomePaths skips an unreachable path`() {
        withTempRoot { root ->
            assertTrue(File(root, "usr/lib/elixir").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            // A WSL distribution that cannot map the path returns null, as convertLinuxPathToWindowsUnc does.
            SdkHomePaths.mergeLinuxSystemHomePaths(homePathByVersion, "elixir") { null }

            assertEmpty(homePathByVersion.values)
        }
    }

    private fun withTempRoot(body: (File) -> Unit) {
        val root = FileUtil.createTempDirectory("sdkHome", null)

        try {
            body(root)
        } finally {
            FileUtil.delete(root)
        }
    }

    // ========== Transform Behavior Tests ==========

    fun `test both tools resolve a prefix with the same rule`() {
        withTempRoot { root ->
            val elixirPrefix = File(root, "elixir/1.20.3")
            val erlangPrefix = File(root, "erlang/29.0.5")
            assertTrue(File(elixirPrefix, "lib/elixir/bin").mkdirs())
            assertTrue(File(erlangPrefix, "lib/erlang/bin").mkdirs())

            assertEquals(
                File(elixirPrefix, "lib/elixir"),
                createElixirConfig().toolHome(elixirPrefix)
            )
            assertEquals(
                File(erlangPrefix, "lib/erlang"),
                createErlangConfig().toolHome(erlangPrefix)
            )
        }
    }

    fun `test erlang kerlTransform is identity`() {
        val config = createErlangConfig()
        assertNotNull(config.kerlTransform)

        val testFile = File("/home/user/.kerl/builds/27.0")
        val result = config.kerlTransform!!(testFile)

        assertEquals(testFile, result)
    }

    fun `test erlang travisCIKerlTransform is identity`() {
        val config = createErlangConfig()
        assertNotNull(config.travisCIKerlTransform)

        val testFile = File("/home/travis/otp/27.0")
        val result = config.travisCIKerlTransform!!(testFile)

        assertEquals(testFile, result)
    }

    // ========== Integration Tests with Mocked File System ==========

    fun `test homePathByVersion returns empty map for unconfigured platform`() {
        val config = createElixirConfig()
        // This test runs on whatever platform the CI is on
        // Just verify it returns a map (may be empty if no SDKs installed)
        val result = SdkHomeScan.homePathByVersion(null, config)
        assertNotNull(result)
    }

    fun `test homePathByVersion with path parameter passes through`() {
        val config = createElixirConfig()
        val testPath = Paths.get("/test/project")

        // Just verify it doesn't crash with a path parameter
        val result = SdkHomeScan.homePathByVersion(testPath, config)
        assertNotNull(result)
    }

    fun `test homePathByVersion sorts versions in descending order`() {
        val config = createElixirConfig()
        val result = SdkHomeScan.homePathByVersion(null, config)

        // Verify the map maintains descending order
        val versions = result.keys.toList()
        if (versions.size > 1) {
            for (i in 0 until versions.size - 1) {
                assertTrue(
                    "Versions should be in descending order",
                    versions[i].version >= versions[i + 1].version
                )
            }
        }
    }

    // ========== Edge Cases ==========

    fun `test config with all null transforms`() {
        val config = SdkHomeScan.Config(
            toolName = "test",
            nixPattern = SdkHomePaths.nixPattern("test"),
            windowsDefaultPath = null,
            windows32BitPath = null,
            kerlTransform = null,
            travisCIKerlTransform = null,
            elixirInstallScriptDirName = "test"
        )

        assertEquals("test", config.toolName)
        assertNull(config.kerlTransform)
        assertNull(config.travisCIKerlTransform)
    }

    fun `test config with all non-null transforms`() {
        val testTransform: (File) -> File = { it }

        val config = SdkHomeScan.Config(
            toolName = "test",
            nixPattern = SdkHomePaths.nixPattern("test"),
            windowsDefaultPath = "C:\\test",
            windows32BitPath = "C:\\test32",
            kerlTransform = testTransform,
            travisCIKerlTransform = testTransform,
            elixirInstallScriptDirName = "test"
        )

        assertNotNull(config.kerlTransform)
        assertNotNull(config.travisCIKerlTransform)
    }

    // ========== /usr/share, version-scoped (Fedora, RHEL) ==========

    fun `test mergeSystemShare discovers a versioned home`() {
        withTempRoot { root ->
            val home = File(root, "elixir/1.20.3")
            assertTrue("could not create $home", home.mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "elixir", root.absolutePath)

            val key = homePathByVersion.keys.single()
            assertEquals(Version(1, 20, 3), key.version)
            assertEquals("1.20.3", key.qualifier)
            assertNull("a system install is not attributed to an installer", key.source)
            assertEquals(
                FileUtil.toSystemIndependentName(home.absolutePath),
                FileUtil.toSystemIndependentName(homePathByVersion.getValue(key))
            )
        }
    }

    fun `test mergeSystemShare keeps an install whose home directory is not a version`() {
        withTempRoot { root ->
            // Debian and Homebrew both publish `current` beside the versions, pointing at the
            // payload directory. Here the payload IS `current`, so the home's own final segment is
            // the unparseable name - and preferring it would key the install UNKNOWN_VERSION, which
            // this merge drops outright rather than merely sorting last.
            val payload = File(root, "elixir/current")
            assertTrue(payload.mkdirs())
            assertTrue(File(root, "elixir/1.14.0").mkdirs())

            val home = FileUtil.toSystemIndependentName(payload.absolutePath)
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        return if (independent.endsWith("/elixir/1.14.0")) home else independent
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "elixir", root.absolutePath)

            assertEquals(
                "the alias and its target are one install and it must survive the UNKNOWN_VERSION " +
                        "filter, got ${homePathByVersion.keys.map { it.qualifier }}",
                listOf("1.14.0"),
                homePathByVersion.keys.map { it.qualifier },
            )
            assertEquals(home, FileUtil.toSystemIndependentName(homePathByVersion.values.single()))
        }
    }

    fun `test mergeSystemShare finds every version side by side`() {
        withTempRoot { root ->
            assertTrue(File(root, "elixir/1.19.5").mkdirs())
            assertTrue(File(root, "elixir/1.20.3").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "elixir", root.absolutePath)

            assertEquals(
                setOf(Version(1, 19, 5), Version(1, 20, 3)),
                homePathByVersion.keys.map { it.version }.toSet()
            )
        }
    }

    fun `test mergeSystemShare ignores a non-version directory`() {
        withTempRoot { root ->
            // Fedora puts Erlang's ERL_LIBS at /usr/share/erlang/lib, which is not an SDK home.
            assertTrue(File(root, "erlang/lib").mkdirs())
            assertTrue(File(root, "erlang/28.1").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "erlang", root.absolutePath)

            assertEquals(
                setOf(FileUtil.toSystemIndependentName(File(root, "erlang/28.1").absolutePath)),
                homePathByVersion.values.map { FileUtil.toSystemIndependentName(it) }.toSet()
            )
        }
    }

    fun `test mergeSystemShare ignores another tool`() {
        withTempRoot { root ->
            assertTrue(File(root, "erlang/28.1").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "elixir", root.absolutePath)

            assertEmpty(homePathByVersion.values)
        }
    }

    fun `test mergeSystemShare tolerates a missing share root`() {
        withTempRoot { root ->
            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeSystemShare(homePathByVersion, "elixir", File(root, "absent").absolutePath)

            assertEmpty(homePathByVersion.values)
        }
    }

    // ========== Homebrew version prefix ==========

    fun `test toolHomePath descends into the nested home`() {
        withTempRoot { root ->
            // make install PREFIX=<prefix>, the layout Homebrew has produced since 2024-09-22
            val prefix = File(root, "elixir/1.18.0")
            assertTrue(File(prefix, "lib/elixir/bin").mkdirs())
            assertTrue(File(prefix, "lib/elixir/lib/elixir/ebin").mkdirs())
            assertTrue(File(prefix, "bin").mkdirs())

            assertEquals(
                FileUtil.toSystemIndependentName(File(prefix, "lib/elixir").absolutePath),
                FileUtil.toSystemIndependentName(SdkHomePaths.toolHomePath(prefix, "elixir").absolutePath)
            )
        }
    }

    fun `test toolHomePath descends into a multilib nested home`() {
        withTempRoot { root ->
            // PREFIX=/usr with LIBDIR=/usr/lib64, as Fedora builds Erlang and Gentoo builds both tools
            val prefix = File(root, "usr")
            assertTrue(File(prefix, "lib64/erlang/bin").mkdirs())

            assertEquals(
                FileUtil.toSystemIndependentName(File(prefix, "lib64/erlang").absolutePath),
                FileUtil.toSystemIndependentName(SdkHomePaths.toolHomePath(prefix, "erlang").absolutePath)
            )
        }
    }

    fun `test toolHomePath keeps the prefix for the pre-2024 layout`() {
        withTempRoot { root ->
            // bin.install plus per-app lib/<app>/ebin: lib/elixir exists but has no bin of its own
            val prefix = File(root, "elixir/1.17.3")
            assertTrue(File(prefix, "bin").mkdirs())
            assertTrue(File(prefix, "lib/elixir/ebin").mkdirs())
            assertTrue(File(prefix, "lib/eex/ebin").mkdirs())

            assertEquals(
                "the prefix is already the home; descending would break installs predating 2024-09-22",
                FileUtil.toSystemIndependentName(prefix.absolutePath),
                FileUtil.toSystemIndependentName(SdkHomePaths.toolHomePath(prefix, "elixir").absolutePath)
            )
        }
    }

    fun `test toolHomePath keeps the prefix when there is no lib at all`() {
        withTempRoot { root ->
            val prefix = File(root, "elixir/1.18.0")
            assertTrue(File(prefix, "bin").mkdirs())

            assertEquals(
                FileUtil.toSystemIndependentName(prefix.absolutePath),
                FileUtil.toSystemIndependentName(SdkHomePaths.toolHomePath(prefix, "elixir").absolutePath)
            )
        }
    }

    fun `test homebrewCellarPaths names every prefix Homebrew uses`() {
        val cellars = SdkHomePaths.homebrewCellarPaths("/home/me") { it }

        // Nothing else asserts which roots the scan reaches, so dropping one here is invisible.
        assertEquals(
            listOf(
                "/usr/local/Cellar",
                "/opt/homebrew/Cellar",
                "/home/linuxbrew/.linuxbrew/Cellar",
                "/home/me/.linuxbrew/Cellar"
            ).map { FileUtil.toSystemIndependentName(it) },
            cellars.map { FileUtil.toSystemIndependentName(it) }
        )
    }

    fun `test homebrewCellarPaths maps every root through the reader`() {
        // A distribution root, not a UNC path: `File` keeps a leading `//` only on Windows, so a
        // UNC-shaped root would assert the home-derived Cellar differently on each OS.
        val cellars = SdkHomePaths.homebrewCellarPaths("/mnt/distro/home/me") { "/mnt/distro$it" }

        assertEquals(
            "a WSL scan must reach the same roots through its own mapping",
            listOf(
                "/mnt/distro/usr/local/Cellar",
                "/mnt/distro/opt/homebrew/Cellar",
                "/mnt/distro/home/linuxbrew/.linuxbrew/Cellar",
                "/mnt/distro/home/me/.linuxbrew/Cellar"
            ),
            cellars.map { FileUtil.toSystemIndependentName(it) }
        )
    }

    fun `test homebrewCellarPaths drops roots the reader cannot reach`() {
        val cellars = SdkHomePaths.homebrewCellarPaths(null) { null }

        assertEmpty(cellars)
    }

    fun `test mergeHomebrew scans every explicit cellar`() {
        withTempRoot { root ->
            val intel = File(root, "usr/local/Cellar")
            val linuxbrew = File(root, "home/linuxbrew/.linuxbrew/Cellar")
            assertTrue(File(intel, "elixir/1.17.3/bin").mkdirs())
            assertTrue(File(linuxbrew, "elixir/1.18.4/bin").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeHomebrew(
                homePathByVersion, "elixir", { SdkHomePaths.toolHomePath(it, "elixir") },
                listOf(intel.path, linuxbrew.path)
            )

            assertEquals(
                "Homebrew on Linux and WSL uses a .linuxbrew prefix, not a macOS one",
                setOf(Version(1, 17, 3), Version(1, 18, 4)),
                homePathByVersion.keys.map { it.version }.toSet()
            )
        }
    }

    fun `test mergeHomebrew tolerates a cellar that does not exist`() {
        withTempRoot { root ->
            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeHomebrew(
                homePathByVersion, "elixir", { it }, listOf(File(root, "absent").path)
            )

            assertEmpty(homePathByVersion.values)
        }
    }

    fun `test mergeHomebrew finds version-pinned formulae`() {
        withTempRoot { root ->
            val cellar = File(root, "Cellar")
            // erlang@25..28 are real homebrew-core formulae and live beside the unpinned one
            assertTrue(File(cellar, "erlang/28.1.2/lib/erlang/bin").mkdirs())
            assertTrue(File(cellar, "erlang@26/26.2.5/lib/erlang/bin").mkdirs())
            assertTrue(File(cellar, "erlang@27/27.3.4/lib/erlang/bin").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeHomebrew(
                homePathByVersion, "erlang", { File(it, "lib/erlang") }, listOf(cellar.path)
            )

            assertEquals(
                "a pinned Erlang is how an OTP release is held for a given Elixir",
                setOf(Version(28, 1, 2), Version(26, 2, 5), Version(27, 3, 4)),
                homePathByVersion.keys.map { it.version }.toSet()
            )
        }
    }

    fun `test mergeHomebrew keeps every version of one formula`() {
        withTempRoot { root ->
            val cellar = File(root, "Cellar")
            // Homebrew keeps superseded versions until brew cleanup runs
            assertTrue(File(cellar, "elixir/1.18.4/lib/elixir/bin").mkdirs())
            assertTrue(File(cellar, "elixir/1.19.5/lib/elixir/bin").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeHomebrew(
                homePathByVersion, "elixir", { SdkHomePaths.toolHomePath(it, "elixir") },
                listOf(cellar.path)
            )

            assertEquals(
                setOf(Version(1, 18, 4), Version(1, 19, 5)),
                homePathByVersion.keys.map { it.version }.toSet()
            )
        }
    }

    fun `test mergeHomebrew does not confuse another tool with a pinned prefix`() {
        withTempRoot { root ->
            val cellar = File(root, "Cellar")
            assertTrue(File(cellar, "erlang@27/27.3.4/lib/erlang/bin").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeHomebrew(
                homePathByVersion, "elixir", { it }, listOf(cellar.path)
            )

            assertEmpty(homePathByVersion.values)
        }
    }

    // ========== Chooser selection (adjustSelectedSdkHome) ==========

    /** Layout of `make install PREFIX=<prefix>`: home in lib/<tool>, symlinks in <prefix>/bin. */
    private fun installedUnderPrefix(prefix: File, toolName: String): File {
        val home = File(prefix, "lib/$toolName")
        assertTrue(File(home, "bin").mkdirs())
        assertTrue(File(home, "lib/$toolName/ebin").mkdirs())
        assertTrue(File(prefix, "bin").mkdirs())

        return home
    }

    fun `test choosing a Homebrew version prefix resolves to the home`() {
        withTempRoot { root ->
            val prefix = File(root, "Cellar/elixir/1.20.3")
            val home = installedUnderPrefix(prefix, "elixir")

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(prefix.path, "elixir"))
        }
    }

    fun `test choosing bare usr resolves to the distro home`() {
        withTempRoot { root ->
            val usr = File(root, "usr")
            val home = installedUnderPrefix(usr, "elixir")

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(usr.path, "elixir"))
        }
    }

    fun `test choosing bare usr on a multilib distro resolves to the home`() {
        withTempRoot { root ->
            val usr = File(root, "usr")
            val home = File(usr, "lib64/erlang")
            assertTrue(File(home, "bin").mkdirs())
            val stdlibEbin = File(home, "lib/stdlib-7.0/ebin")
            assertTrue(stdlibEbin.mkdirs())
            assertTrue(File(usr, "bin").mkdirs())

            val adjusted = SdkHomePaths.adjustSelectedSdkHome(usr.path, "erlang")
            assertEquals(home.path, adjusted)

            val ebinPaths = mutableListOf<String>()
            SdkEbinPaths.eachEbinPath(adjusted) { ebinPaths.add(FileUtil.toSystemIndependentName(it.toString())) }
            assertEquals(
                "OTP's ebins are the roots",
                listOf(FileUtil.toSystemIndependentName(stdlibEbin.path)),
                ebinPaths,
            )
        }
    }

    fun `test choosing a prefix resolves for erlang too`() {
        withTempRoot { root ->
            val prefix = File(root, "Cellar/erlang/29.0.5")
            val home = installedUnderPrefix(prefix, "erlang")

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(prefix.path, "erlang"))
        }
    }

    fun `test choosing bin lib or src steps up to the home`() {
        withTempRoot { root ->
            // a home selected by one of its own children, not a prefix
            val home = File(root, "elixir")
            assertTrue(File(home, "bin").mkdirs())
            assertTrue(File(home, "lib/elixir/ebin").mkdirs())
            assertTrue(File(home, "src").mkdirs())

            for (child in listOf("bin", "lib", "src")) {
                assertEquals(
                    "selecting $child should resolve to its parent",
                    home.path,
                    SdkHomePaths.adjustSelectedSdkHome(File(home, child).path, "elixir")
                )
            }
        }
    }

    fun `test choosing a home leaves it alone`() {
        withTempRoot { root ->
            val home = File(root, "elixir")
            assertTrue(File(home, "bin").mkdirs())
            assertTrue(File(home, "lib/elixir/ebin").mkdirs())

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(home.path, "elixir"))
        }
    }

    fun `test choosing a kiex version directory resolves to the home`() {
        withTempRoot { root ->
            // kiex keeps a false bin beside lib/elixir; the general rule covers it
            val versionDir = File(root, "elixirs/elixir-1.9.1")
            val home = installedUnderPrefix(versionDir, "elixir")

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(versionDir.path, "elixir"))
            assertEquals(
                home.path,
                SdkHomePaths.adjustSelectedSdkHome(File(versionDir, "bin").path, "elixir")
            )
        }
    }

    fun `test choosing an erlang home child steps up`() {
        withTempRoot { root ->
            // an Erlang home keeps releases and usr beside bin and lib
            val home = File(root, "erlang")
            assertTrue(File(home, "bin").mkdirs())
            assertTrue(File(home, "lib/stdlib-7.0/ebin").mkdirs())
            assertTrue(File(home, "releases/29").mkdirs())
            assertTrue(File(home, "usr/include").mkdirs())

            for (child in listOf("bin", "lib", "releases")) {
                assertEquals(
                    "selecting $child should resolve to its parent",
                    home.path,
                    SdkHomePaths.adjustSelectedSdkHome(File(home, child).path, "erlang")
                )
            }
        }
    }

    fun `test usr is not treated as a home child`() {
        withTempRoot { root ->
            // /usr is an install prefix in its own right; stepping up from it would reach the
            // filesystem root and lose the distribution install underneath it
            val usr = File(root, "usr")
            val home = installedUnderPrefix(usr, "elixir")

            assertEquals(home.path, SdkHomePaths.adjustSelectedSdkHome(usr.path, "elixir"))
        }
    }

    fun `test choosing an unrelated bin or src still steps up`() {
        withTempRoot { root ->
            // documents the cost of matching on name alone: an unrelated child resolves to its
            // parent, which the chooser then rejects as an invalid home
            val unrelated = File(root, "some/project/src")
            assertTrue(unrelated.mkdirs())

            assertEquals(
                File(root, "some/project").path,
                SdkHomePaths.adjustSelectedSdkHome(unrelated.path, "elixir")
            )
        }
    }

    fun `test choosing a file is left alone`() {
        withTempRoot { root ->
            val file = File(root, "elixir.txt")
            assertTrue(file.createNewFile())

            assertEquals(file.path, SdkHomePaths.adjustSelectedSdkHome(file.path, "elixir"))
        }
    }

    fun `test choosing a missing path is left alone`() {
        withTempRoot { root ->
            val missing = File(root, "absent")
            assertEquals(missing.path, SdkHomePaths.adjustSelectedSdkHome(missing.path, "elixir"))
        }
    }

    fun `test choosing a filesystem root is left alone`() {
        val root = File(File("/").absolutePath)
        // parentFile is null here; the fallback must return the selection rather than throw
        assertEquals(
            SdkHomePaths.toolHomePath(root, "elixir").path,
            SdkHomePaths.adjustSelectedSdkHome(root.path, "elixir")
        )
    }

    // ========== Version managers ==========

    private fun assertFindsVersionedHome(
        installsFromHome: String,
        merge: (MutableMap<SdkHomeKey, String>, String) -> Unit,
    ) {
        withTempRoot { home ->
            val installed = File(home, "$installsFromHome/elixir/1.20.3")
            assertTrue("could not create $installed", installed.mkdirs())
            // a sibling tool must not be picked up for elixir
            assertTrue(File(home, "$installsFromHome/erlang/29.0.5").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            merge(homePathByVersion, home.path)

            assertEquals(
                setOf(FileUtil.toSystemIndependentName(installed.absolutePath)),
                homePathByVersion.values.map { FileUtil.toSystemIndependentName(it) }.toSet()
            )
            assertEquals(Version(1, 20, 3), homePathByVersion.keys.single().version)
        }
    }

    fun `test mergeASDF finds an installed version`() {
        assertFindsVersionedHome(SdkPaths.ASDF_INSTALLS_PATH_FROM_HOME) { map, home ->
            SdkHomePaths.mergeASDF(map, "elixir", home)
        }
    }

    fun `test mergeMise finds an installed version`() {
        assertFindsVersionedHome(SdkPaths.MISE_POSIX_PATH_FROM_HOME) { map, home ->
            SdkHomePaths.mergeMise(map, "elixir", home)
        }
    }

    fun `test mergeMise finds a Windows-layout install`() {
        assertFindsVersionedHome(SdkPaths.MISE_WINDOWS_PATH_FROM_HOME) { map, home ->
            SdkHomePaths.mergeMise(map, "elixir", home)
        }
    }

    fun `test mergeMise collapses version aliases that resolve to one install`() {
        withTempRoot { home ->
            // mise publishes a version alias per prefix - 26, 26.2 and 26.2.5 are symlinks to
            // 26.2.5.21 - so a scan sees four directories for one Erlang install.
            val installs = File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/erlang")
            val aliases = listOf("26", "26.2", "26.2.5")
            val concrete = File(installs, "26.2.5.21")
            assertTrue(concrete.mkdirs())
            aliases.forEach { assertTrue(File(installs, it).mkdirs()) }

            // Stands in for the symlink resolution canonicalizePath does on a real install; the
            // test cannot create symlinks, which need a privilege the Windows CI runner lacks.
            val canonical = FileUtil.toSystemIndependentName(concrete.absolutePath)
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        if (!aliases.any { independent.endsWith("/erlang/$it") }) return independent

                        // Production cannot currently produce a separator split - both of
                        // canonicalizePath's branches are system-dependent - so this fabricates one
                        // to pin the defensive normalisation in mergeNameSubdirectories. A literal
                        // backslash, not toSystemDependentName, which is the identity off Windows.
                        return if (independent.endsWith("/erlang/${aliases.first()}")) {
                            canonical.replace('/', '\\')
                        } else {
                            canonical
                        }
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeMise(homePathByVersion, "erlang", home.path)

            assertEquals(
                "the four alias directories name one install and must be offered once, got " +
                        "${homePathByVersion.keys.map { it.qualifier }}",
                1,
                homePathByVersion.size,
            )
            assertEquals(canonical, homePathByVersion.values.single())
            assertEquals(
                "the surviving entry must name the concrete install",
                "26.2.5.21",
                homePathByVersion.keys.single().qualifier,
            )
        }
    }

    fun `test mergeMise lets the directory naming the home win over a longer sibling`() {
        withTempRoot { home ->
            // The length tie-break would take the longer name, and both of these parse, so only the
            // "name equals the home's final segment" rule picks the install the home actually is.
            val installs = File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/erlang")
            val concrete = File(installs, "26.2.5.21")
            assertTrue(concrete.mkdirs())
            assertTrue(File(installs, "26.2.5.21-latest").mkdirs())

            val canonical = FileUtil.toSystemIndependentName(concrete.absolutePath)
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        return if (independent.endsWith("/erlang/26.2.5.21-latest")) canonical else independent
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeMise(homePathByVersion, "erlang", home.path)

            assertEquals(
                "both names are one install and must be offered once, got " +
                        "${homePathByVersion.keys.map { it.qualifier }}",
                1,
                homePathByVersion.size,
            )
            assertEquals(
                "the directory the home resolves to must name it, not the longer sibling",
                "26.2.5.21",
                homePathByVersion.keys.single().qualifier,
            )
        }
    }

    fun `test mergeMise lets the home-naming directory win even when it is seen second`() {
        withTempRoot { home ->
            // Companion to the test above, for the other side of the comparison. The alias sorts
            // first, so it becomes the incumbent, and it is both longer and parseable - so neither
            // the length tie-break nor the parse preference can rescue the concrete install. Only
            // the candidate-side name match does.
            val installs = File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/erlang")
            val concrete = File(installs, "26.2.5.21")
            assertTrue(concrete.mkdirs())
            assertTrue(File(installs, "1.0.0-nightly-build").mkdirs())

            val canonical = FileUtil.toSystemIndependentName(concrete.absolutePath)
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        return if (independent.endsWith("/erlang/1.0.0-nightly-build")) canonical else independent
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeMise(homePathByVersion, "erlang", home.path)

            assertEquals(
                "the directory the home resolves to must name it however the listing is ordered",
                "26.2.5.21",
                homePathByVersion.keys.single().qualifier,
            )
        }
    }

    fun `test mergeMise prefers a parseable version over an unparseable alias`() {
        withTempRoot { home ->
            // mise publishes `latest` beside the versions. Deliberately longer than the version here,
            // so the length tie-break would pick it and only the parse preference can save it.
            // An unparseable winner would carry UNKNOWN_VERSION, which sorts last and is dropped
            // outright by mergeSystemShare's filter, losing the install.
            val installs = File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/elixir")
            val concrete = File(installs, "1.19.1")
            assertTrue(concrete.mkdirs())
            assertTrue(File(installs, "latest-stable").mkdirs())

            // Resolve both to a home OUTSIDE the scanned directory, so the "name equals the home's
            // last segment" shortcut cannot fire and the tie-break is what decides.
            val elsewhere = FileUtil.toSystemIndependentName(
                File(home, "opt/elixir/current").also { assertTrue(it.mkdirs()) }.absolutePath,
            )
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        val underScan = independent.endsWith("/elixir/latest-stable") ||
                                independent.endsWith("/elixir/1.19.1")
                        return if (underScan) elsewhere else independent
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeMise(homePathByVersion, "elixir", home.path)

            assertEquals(
                "the two names are one install and must be offered once, got " +
                        "${homePathByVersion.keys.map { it.qualifier }}",
                1,
                homePathByVersion.size,
            )
            assertEquals(
                "the parseable version must name the home, not the longer unparseable alias",
                "1.19.1",
                homePathByVersion.keys.single().qualifier,
            )
        }
    }

    fun `test mergeMise prefers a parseable candidate over an unparseable incumbent`() {
        // Mirror of the test above, for the other side of the parse comparison. Children are scanned
        // in name order, so the alias is the incumbent and the version is the candidate.
        assertWinner(incumbent = "-latest", candidate = "1.19.1", expected = "1.19.1")
    }

    fun `test mergeMise falls back to the longer name when both parse and neither names the home`() {
        // Neither name matches the home's final segment and both parse, so only the length rule is
        // left to choose. `1.19.1` sorts first and is therefore the incumbent.
        assertWinner(incumbent = "1.19.1", candidate = "1.19.1-rc1", expected = "1.19.1-rc1")
    }

    /**
     * Puts two sibling directories under mise's Elixir root that resolve to a home OUTSIDE the
     * scanned directory - so the "name equals the home's final segment" rule cannot fire - and
     * asserts which of them ends up naming the single surviving entry.
     *
     * [incumbent] must sort before [candidate]: children are scanned in name order, so that is what
     * puts each on the side of the comparison its case is about.
     */
    private fun assertWinner(incumbent: String, candidate: String, expected: String) {
        withTempRoot { home ->
            val installs = File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/elixir")
            listOf(incumbent, candidate).forEach { assertTrue(File(installs, it).mkdirs()) }
            val elsewhere = FileUtil.toSystemIndependentName(
                File(home, "opt/elixir/current").also { assertTrue(it.mkdirs()) }.absolutePath,
            )
            installWslCompatService(
                object : WslCompatService by MockWslCompatService() {
                    override fun canonicalizePath(path: String): String {
                        val independent = FileUtil.toSystemIndependentName(path)
                        val underScan = independent.endsWith("/elixir/$incumbent") ||
                                independent.endsWith("/elixir/$candidate")
                        return if (underScan) elsewhere else independent
                    }
                },
            )

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeMise(homePathByVersion, "elixir", home.path)

            assertEquals(
                "both names are one install and must be offered once, got " +
                        "${homePathByVersion.keys.map { it.qualifier }}",
                1,
                homePathByVersion.size,
            )
            assertEquals(expected, homePathByVersion.keys.single().qualifier)
        }
    }

    fun `test mergeMise resolves an exact tie by name order`() {
        // Same length, both parse, and the home is named by neither - so every rule in
        // preferredHomeDirectory is exhausted and only the scan order decides.
        //
        // This pins the contract (the first name wins), not the sort that delivers it: NTFS and ext4
        // both hand listFiles() these two in name order anyway, so deleting the sortedBy in
        // mergeNameSubdirectories leaves this green. Verified by mutation. The sort is there for
        // filesystems that do not - ext4 htree on a large directory, APFS - which a test on a
        // two-entry temp dir cannot reproduce.
        assertWinner(incumbent = "1.19.1", candidate = "1.19.2", expected = "1.19.1")
    }

    fun `test mergeElixirInstallScript finds an installed version`() {
        assertFindsVersionedHome(SdkPaths.ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME) { map, home ->
            SdkHomePaths.mergeElixirInstallScript(map, "elixir", home)
        }
    }

    fun `test a version manager attributes the source it came from`() {
        withTempRoot { home ->
            assertTrue(File(home, "${SdkPaths.ASDF_INSTALLS_PATH_FROM_HOME}/elixir/1.20.3").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeASDF(homePathByVersion, "elixir", home.path)

            assertEquals(SdkPaths.SOURCE_NAME_ASDF, homePathByVersion.keys.single().source)
        }
    }

    fun `test a version manager tolerates a missing install root`() {
        withTempRoot { home ->
            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeASDF(homePathByVersion, "elixir", File(home, "absent").path)

            assertEmpty(homePathByVersion.values)
        }
    }

    // ========== Nix store ==========

    fun `test mergeNixStore finds a derivation and reads its version`() {
        withTempRoot { store ->
            assertTrue(File(store, "abc123-elixir-1.20.3").mkdirs())
            assertTrue(File(store, "def456-erlang-29.0.5").mkdirs())
            assertTrue(File(store, "ghi789-elixir-nodots").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeNixStore(
                homePathByVersion, SdkHomePaths.nixPattern("elixir"), { it }, store.path
            )

            val key = homePathByVersion.keys.single()
            assertEquals(Version(1, 20, 3), key.version)
            assertEquals(SdkPaths.SOURCE_NAME_NIX, key.source)
        }
    }

    fun `test mergeNixStore applies the prefix rule to a derivation`() {
        withTempRoot { store ->
            val derivation = File(store, "abc123-erlang-29.0.5")
            assertTrue(File(derivation, "lib/erlang/bin").mkdirs())

            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeNixStore(
                homePathByVersion, SdkHomePaths.nixPattern("erlang"),
                { SdkHomePaths.toolHomePath(it, "erlang") }, store.path
            )

            assertEquals(
                setOf(FileUtil.toSystemIndependentName(File(derivation, "lib/erlang").absolutePath)),
                homePathByVersion.values.map { FileUtil.toSystemIndependentName(it) }.toSet()
            )
        }
    }

    fun `test mergeNixStore tolerates a missing store`() {
        withTempRoot { root ->
            val homePathByVersion = mutableMapOf<SdkHomeKey, String>()
            SdkHomePaths.mergeNixStore(
                homePathByVersion, SdkHomePaths.nixPattern("elixir"), { it },
                File(root, "absent").path
            )

            assertEmpty(homePathByVersion.values)
        }
    }

    // ========== Dispatch ==========

    fun `test homePathByVersion wires the version managers into the scan`() {
        withTempRoot { home ->
            assertTrue(File(home, "${SdkPaths.ASDF_INSTALLS_PATH_FROM_HOME}/elixir/1.20.3").mkdirs())
            assertTrue(File(home, "${SdkPaths.MISE_POSIX_PATH_FROM_HOME}/elixir/1.19.5").mkdirs())
            assertTrue(
                File(home, "${SdkPaths.ELIXIR_INSTALL_INSTALLS_PATH_FROM_HOME}/elixir/1.18.4").mkdirs()
            )

            val found = withUserHome(home.path) {
                SdkHomeScan.homePathByVersion(null, createElixirConfig())
            }

            // One scan, one source list: a source that stops being wired in fails here rather than
            // going missing on whichever platform nobody checked.
            assertTrue(
                "expected asdf, mise and elixir-install homes, got ${found.values}",
                found.keys.map { it.version }
                    .containsAll(listOf(Version(1, 20, 3), Version(1, 19, 5), Version(1, 18, 4)))
            )
        }
    }

    private fun installWslCompatService(service: WslCompatService) {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            service,
            testRootDisposable,
        )
    }

    private fun <T> withUserHome(userHome: String, body: () -> T): T {
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", userHome)

        try {
            return body()
        } finally {
            if (previous != null) System.setProperty("user.home", previous)
        }
    }

    // ========== Helper Methods ==========

    private fun createElixirConfig() = SdkHomeScan.Config(
        toolName = "elixir",
        nixPattern = SdkHomePaths.nixPattern("elixir"),
        windowsDefaultPath = "C:\\Program Files (x86)\\Elixir",
        windows32BitPath = "C:\\Program Files\\Elixir",
        kerlTransform = null,
        travisCIKerlTransform = null,
        elixirInstallScriptDirName = "elixir"
    )

    private fun createErlangConfig() = SdkHomeScan.Config(
        toolName = "erlang",
        nixPattern = SdkHomePaths.nixPattern("erlang"),
        windowsDefaultPath = "C:\\Program Files\\erl9.0",
        windows32BitPath = null,
        kerlTransform = { it },
        travisCIKerlTransform = { it },
        elixirInstallScriptDirName = "otp"
    )
}
