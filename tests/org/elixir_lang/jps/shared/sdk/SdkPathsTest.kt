package org.elixir_lang.jps.shared.sdk

import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.PlatformTestCase
import java.io.File

/**
 * Tests for SdkPaths utility methods.
 */
class SdkPathsTest : PlatformTestCase() {
    private val asdfElixir117 = "/home/user/.asdf/installs/elixir/1.17.0"
    private val asdfElixir116 = "/home/user/.asdf/installs/elixir/1.16.0"
    private val customMixHome = "/custom/mix/home"

    fun testDetectSource_mise() {
        assertEquals("mise", SdkPaths.detectSource("/Users/josh/.local/share/mise/installs/elixir/1.15.0"))
        assertEquals("mise", SdkPaths.detectSource("/Users/josh/.local/share/mise/installs/erlang/26.0"))
        assertEquals("mise", SdkPaths.detectSource("/home/user/.local/share/mise/installs/elixir/1.14.0-otp-25"))
    }

    fun testDetectSource_asdf() {
        assertEquals("asdf", SdkPaths.detectSource("/Users/josh/.asdf/installs/elixir/1.15.0"))
        assertEquals("asdf", SdkPaths.detectSource("/Users/josh/.asdf/installs/erlang/26.0"))
        assertEquals("asdf", SdkPaths.detectSource("/home/user/.asdf/installs/elixir/1.14.0"))
    }

    fun testDetectSource_elixirInstall() {
        assertEquals(
            "elixir-install",
            SdkPaths.detectSource("/home/user/.elixir-install/installs/elixir/1.18.4")
        )
        assertEquals(
            "elixir-install",
            SdkPaths.detectSource("/home/user/.elixir-install/installs/otp/27.2")
        )
        // The installer only ever writes under installs/, so the segment is matched at the same
        // depth as asdf's rather than on the .elixir-install directory alone.
        assertNull(SdkPaths.detectSource("/home/user/.elixir-install/cache/elixir"))
    }

    fun testDetectSource_homebrew() {
        assertEquals("Homebrew", SdkPaths.detectSource("/usr/local/Cellar/elixir/1.15.0"))
        assertEquals("Homebrew", SdkPaths.detectSource("/opt/homebrew/Cellar/erlang/26.0"))
        assertEquals(
            "Homebrew",
            SdkPaths.detectSource("/home/linuxbrew/.linuxbrew/Cellar/elixir/1.18.4/lib/elixir")
        )
        // the constants keep their real casing; the matcher lowercases them, not the caller
        assertEquals("Homebrew", SdkPaths.detectSource("/opt/homebrew/Cellar/ERLANG/28.0"))
        assertEquals("Homebrew", SdkPaths.detectSource("/opt/homebrew/Cellar/elixir/1.14.0/libexec"))
        // A home inside a WSL distribution is a UNC path that no Linux root is a prefix of, so the
        // segment has to match mid-path. This is why FileUtil.isAncestor is not used here.
        assertEquals(
            "Homebrew",
            SdkPaths.detectSource(
                """\\wsl.localhost\IntellijElixirWSLDistribution\home\linuxbrew\.linuxbrew\Cellar\erlang\29.0.5\lib\erlang"""
            )
        )
    }

    fun testDetectSource_nix() {
        assertEquals("Nix", SdkPaths.detectSource("/nix/store/abc123-elixir-1.15.0"))
        assertEquals("Nix", SdkPaths.detectSource("/nix/store/xyz789-erlang-26.0/lib/erlang"))
    }

    fun testDetectSource_kerl() {
        assertEquals("kerl", SdkPaths.detectSource("/Users/josh/otp/25.0"))
        assertEquals("kerl", SdkPaths.detectSource("/home/user/otp/26.1"))
    }

    /** A kerl install elsewhere is found by its `.kerl_config`, which is only probed where the home can be reached. */
    fun testDetectSource_kerlConfigIsProbedOnlyWhereReachable() {
        val home = FileUtil.createTempDirectory("kerl_install", null, true)
        File(home, ".kerl_config").writeText("")

        assertEquals("kerl", SdkPaths.detectSource(home.path))
        assertNull(SdkPaths.detectSource(home.path, reachable = false))
        assertNull(
            "a WSL home is not probed unless a caller says it is reachable",
            SdkPaths.detectSource("//wsl.localhost/Missing/home/user/erlang"),
        )
    }

    fun testDetectSource_unknown() {
        assertNull(SdkPaths.detectSource("/usr/local/elixir"))
        assertNull(SdkPaths.detectSource("/opt/elixir/1.15.0"))
        assertNull(SdkPaths.detectSource("/custom/path/to/erlang"))
    }

    fun testDetectSource_nullSafe() {
        // Edge cases
        assertNull(SdkPaths.detectSource(""))
        assertNull(SdkPaths.detectSource("/"))
    }

    fun testMixHomeVariables_setsWhenMissing() {
        val environment = mutableMapOf<String, String>()

        environment.putAll(SdkPaths.mixHomeVariables(environment, emptyMap(), asdfElixir117))

        val expectedMixHome = mixHomePath(asdfElixir117)
        assertEquals(expectedMixHome, environment["MIX_HOME"])
        assertEquals(File(expectedMixHome, "archives").absolutePath, environment["MIX_ARCHIVES"])
    }

    fun testMixHomeVariables_replacesWhenUnderManagerPrefix() {
        val environment = mutableMapOf<String, String>()
        environment["MIX_HOME"] = mixHomePath(asdfElixir116)
        environment["MIX_ARCHIVES"] = File(environment["MIX_HOME"]!!, "archives").absolutePath

        environment.putAll(SdkPaths.mixHomeVariables(environment, emptyMap(), asdfElixir117))

        val expectedMixHome = mixHomePath(asdfElixir117)
        assertEquals(expectedMixHome, environment["MIX_HOME"])
        assertEquals(File(expectedMixHome, "archives").absolutePath, environment["MIX_ARCHIVES"])
    }

    fun testMixHomeVariables_preservesCustomMixHome() {
        val environment = mutableMapOf<String, String>()
        environment["MIX_HOME"] = customMixHome
        environment["MIX_ARCHIVES"] = "$customMixHome/archives"

        environment.putAll(SdkPaths.mixHomeVariables(environment, emptyMap(), asdfElixir117))

        assertEquals(customMixHome, environment["MIX_HOME"])
        assertEquals("$customMixHome/archives", environment["MIX_ARCHIVES"])
    }

    fun testMixHomeVariables_takesBothFromTheToolManagerWhenItSetsMixHome() {
        val toolManagerVariables = mapOf("MIX_HOME" to "/home/user/.asdf/installs/elixir/1.16.0/.mix")

        assertEquals(
            mapOf(
                "MIX_HOME" to "/home/user/.asdf/installs/elixir/1.16.0/.mix",
                "MIX_ARCHIVES" to "/home/user/.asdf/installs/elixir/1.16.0/.mix/archives",
            ),
            SdkPaths.mixHomeVariables(emptyMap(), toolManagerVariables, asdfElixir117),
        )
    }

    fun testMixHomeVariables_setsWhenTheToolManagerDoesNotSetMixHome() {
        val variables = SdkPaths.mixHomeVariables(emptyMap(), mapOf("RUSTUP_TOOLCHAIN" to "1.94.1"), asdfElixir117)

        assertEquals(mixHomePath(asdfElixir117), variables["MIX_HOME"])
    }

    fun testMixHomeVariables_joinsArchivesWithTheMixHomesOwnSeparator() {
        val wslMixHome = "\\\\wsl.localhost\\Ubuntu\\home\\user\\custom-mix"

        val variables = SdkPaths.mixHomeVariables(mapOf("MIX_HOME" to wslMixHome), emptyMap(), asdfElixir117)

        assertEquals("$wslMixHome\\archives", variables["MIX_ARCHIVES"])
    }

    fun testMixHomeVariables_treatsAnEmptyMixHomeAsUnset() {
        val variables = SdkPaths.mixHomeVariables(mapOf("MIX_HOME" to ""), emptyMap(), asdfElixir117)

        assertEquals(mixHomePath(asdfElixir117), variables["MIX_HOME"])
    }

    fun testMixHomeVariables_aLaunchMixHomeUnderThePrefixWinsOverTheToolManagers() {
        val launchMixHome = mixHomePath(asdfElixir116)

        val variables = SdkPaths.mixHomeVariables(
            mapOf("MIX_HOME" to launchMixHome),
            mapOf("MIX_HOME" to "/tool-manager/.mix"),
            asdfElixir117,
        )

        assertEquals(mapOf("MIX_HOME" to launchMixHome, "MIX_ARCHIVES" to "$launchMixHome${File.separator}archives"), variables)
    }

    fun testMixHomeVariables_treatsAnEmptyMixArchivesAsUnset() {
        val variables = SdkPaths.mixHomeVariables(
            mapOf("MIX_HOME" to customMixHome, "MIX_ARCHIVES" to ""),
            emptyMap(),
            asdfElixir117,
        )

        assertEquals("$customMixHome/archives", variables["MIX_ARCHIVES"])
    }

    private fun mixHomePath(homePath: String): String = File(homePath, ".mix").absolutePath
}
