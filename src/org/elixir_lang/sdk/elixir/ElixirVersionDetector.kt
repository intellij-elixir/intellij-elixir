package org.elixir_lang.sdk.elixir

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.io.File
import java.util.regex.Pattern
import org.elixir_lang.sdk.SdkHomePaths
import org.elixir_lang.sdk.wsl.wslCompat

object ElixirVersionDetector {
    private val LOG = Logger.getInstance(ElixirVersionDetector::class.java)

    /** Matches `{vsn, "X.Y.Z"}` (or any quoted value) anywhere in an Erlang `.app` file.
     *  Allows optional whitespace around the key, comma, value, and closing brace. */
    private val VSN_PATTERN: Pattern = Pattern.compile("""\{\s*vsn\s*,\s*"([^"]+)"\s*}""")

    /**
     * Reads the Elixir version from `<canonicalHome>/lib/elixir/ebin/elixir.app`.
     *
     * The `.app` file is the standard Erlang application resource file - a plain-text term of the
     * form `{application, elixir, [{vsn, "X.Y.Z"}, ...]}.`. Parsing the `vsn` field via regex is
     * safe because the value is always a simple string literal in OTP `.app` files.
     *
     * Must NOT be called on the EDT - file I/O on WSL UNC paths (`\\wsl.localhost\...`)
     * goes through the Plan 9 filesystem redirector and can block for 50–200 ms. Only
     * [org.elixir_lang.sdk.SdkVersionsFiller] calls this; everything else reads what it found from
     * [org.elixir_lang.sdk.SdkVersionsStore].
     */
    @RequiresBackgroundThread
    internal fun readElixirAppVersion(canonicalHome: String): String? {
        ThreadingAssertions.assertBackgroundThread()
        val appFile = File(canonicalHome, "${SdkHomePaths.ELIXIR_EBIN}/elixir.app")
        if (!wslCompat.exists(appFile)) {
            LOG.debug("elixir.app not found at ${appFile.path}")
            return null
        }
        return try {
            val content = appFile.readText(Charsets.UTF_8)
            val matcher = VSN_PATTERN.matcher(content)
            if (matcher.find()) {
                matcher.group(1).trim().ifEmpty {
                    LOG.debug("Empty vsn field in ${appFile.path}")
                    null
                }
            } else {
                LOG.debug("Could not find vsn field in ${appFile.path}")
                null
            }
        } catch (e: Exception) {
            LOG.debug("Failed to read ${appFile.path}", e)
            null
        }
    }
}
