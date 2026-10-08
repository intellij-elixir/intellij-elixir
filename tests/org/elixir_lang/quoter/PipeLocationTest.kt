package org.elixir_lang.quoter

import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.intellij_elixir.Quoter
import java.io.File

/**
 * Verifies that the quoter daemon creates pipes in cache/quoter_tmp_*
 * instead of inside _build/ (which breaks Gradle caching).
 */
class PipeLocationTest : PlatformTestCase() {

    fun testPipesNotInBuildDirectory() {
        val projectRoot = File(System.getProperty("user.dir"))
        val cacheDir = File(projectRoot, "cache")

        // Find any _build directories in cache
        val buildDirs = cacheDir.listFiles { file ->
            file.isDirectory && file.name.contains("-quoter-")
        }?.flatMap { quoterDir ->
            File(quoterDir, "_build").walkTopDown()
                .filter { it.name == "pipe" && it.isDirectory }
                .toList()
        } ?: emptyList()

        // Check no pipes exist in _build
        for (pipeDir in buildDirs) {
            val pipes = pipeDir.listFiles { f -> f.name.startsWith("erlang.pipe") } ?: emptyArray()
            assertTrue(
                "Found pipes in _build directory: ${pipeDir.absolutePath}. " +
                    "Pipes should be in cache/quoter_tmp_* via RELEASE_TMP.",
                pipes.isEmpty()
            )
        }
    }

    fun testPipesInQuoterTmpDirectory() {
        // Skip on Windows - named pipes via --pipe-to are not supported
        // See: https://github.com/elixir-lang/elixir/blob/2cd8a57b37011319b36b15c6765ab36c5f245bc7/bin/elixir.bat#L63
        if (OS.CURRENT == OS.Windows) {
            return
        }

        // Only the daemon creates these pipes, so with no daemon this asserts nothing. Report that
        // rather than "No quoter_tmp_* directory found", which names the symptom and leaves the cause
        // to be guessed. After the Windows return, because there is no daemon to need there either.
        Quoter.assertAvailable()

        val projectRoot = File(System.getProperty("user.dir"))
        val cacheDir = File(projectRoot, "cache")

        // Find quoter_tmp_* directories
        val quoterTmpDirs = cacheDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("quoter_tmp_")
        } ?: emptyArray()

        assertTrue(
            "No quoter_tmp_* directory found in cache/. " +
                "Expected RELEASE_TMP to create cache/quoter_tmp_<pair>-<quoter ref>/",
            quoterTmpDirs.isNotEmpty()
        )

        // Check at least one has pipes (quoter should be running during tests)
        val hasPipes = quoterTmpDirs.any { tmpDir ->
            val pipeDir = File(tmpDir, "pipe")
            pipeDir.exists() && (pipeDir.listFiles()?.isNotEmpty() == true)
        }

        assertTrue(
            "No pipes found in any quoter_tmp_*/pipe/ directory. " +
                "Is the quoter daemon running?",
            hasPipes
        )
    }
}
