package org.elixir_lang.cli

import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslPath
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.junit.UnitTestCase

/**
 * Pins [getExecutableFilepathWslSafe], the one place SDK home validation is WSL-aware.
 *
 * `Type.isValidSdkHome` for both Erlang and Elixir is a plain `File(...).canExecute()` on the path this
 * function builds, so building `bin/erl.exe` for a Linux-side home is what made "The selected directory
 * is not a valid home for Erlang SDK for Elixir SDK" the reported symptom of #1911 and #2499.
 *
 * Two things make a naive version of this test pass for the wrong reason on a non-Windows host, and
 * both are guarded below rather than assumed:
 *
 * 1. `WslPath.isWslUncPath` short-circuits to `false` unless `WSLUtil.isSystemCompatible()`, which is
 *    `SystemInfo.isWin10OrNewer`. Off Windows the WSL branch is never taken at all, so the assertions
 *    would describe the fallback. `WSLUtil.setSystemCompatible` exists for exactly this and is set per
 *    test, then restored.
 * 2. Off Windows `OS.CURRENT` is not `OS.Windows`, so both branches return the same unsuffixed name and
 *    an equality assertion cannot tell them apart. [testWindowsConventionDiffersForTheSameHome] pins
 *    that the two conventions do differ for one input, which is what makes the assertion discriminating
 *    where the host makes it so.
 *
 * Note the production call reaches `WslPath` directly rather than through `WslCompatService`, so the
 * suite's `MockWslCompatService` does not reach it.
 */
class CliToolWslSafeTest : UnitTestCase() {
    private var systemCompatible = false

    override fun setUp() {
        super.setUp()
        systemCompatible = WSLUtil.isSystemCompatible()
        WSLUtil.setSystemCompatible(true)
    }

    override fun tearDown() {
        try {
            WSLUtil.setSystemCompatible(systemCompatible)
        } finally {
            super.tearDown()
        }
    }

    fun testWslUncHomeGetsTheLinuxExecutableName() {
        for (home in WSL_HOMES) {
            assertTrue("fixture must be recognised as a WSL UNC path, or this asserts the fallback: $home",
                       WslPath.isWslUncPath(home))

            val filepath = CliTool.ERL.getExecutableFilepathWslSafe(home)

            assertEquals("WSL home must use the Linux executable convention: $home",
                         CliTool.ERL.getExecutableFilepath(home, OS.Linux), filepath)
            assertFalse("WSL home must not get the Windows extension: $filepath", filepath.endsWith(EXT))
        }
    }

    fun testWindowsConventionDiffersForTheSameHome() {
        val home = WSL_HOMES.first()

        assertTrue("the Windows convention must add the extension, or the assertion above is vacuous",
                   CliTool.ERL.getExecutableFilepath(home, OS.Windows).endsWith(EXT))
        assertFalse(CliTool.ERL.getExecutableFilepathWslSafe(home) ==
                    CliTool.ERL.getExecutableFilepath(home, OS.Windows))
    }

    fun testNonWslHomeGetsTheCurrentOsConvention() {
        val home = if (OS.CURRENT == OS.Windows) "C:\\Program Files\\erl-27.0" else "/usr/lib/erlang"

        assertFalse("fixture must not be a WSL UNC path: $home", WslPath.isWslUncPath(home))
        assertEquals(CliTool.ERL.getExecutableFilepath(home, OS.CURRENT),
                     CliTool.ERL.getExecutableFilepathWslSafe(home))
    }

    companion object {
        private const val EXT = ".exe"

        /**
         * `\\wsl$\` is the historical prefix and `\\wsl.localhost\` the current one; both are accepted
         * regardless of which the host prefers. The first is the asdf home from #2499, under the tests' distribution name.
         */
        private val WSL_HOMES = listOf(
            "\\\\wsl$\\IntellijElixirWSLDistribution\\home\\joey\\.asdf\\installs\\erlang\\25.3.2.7",
            "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\usr\\lib\\erlang"
        )
    }
}
