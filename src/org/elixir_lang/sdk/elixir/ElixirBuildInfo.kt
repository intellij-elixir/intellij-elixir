package org.elixir_lang.sdk.elixir

import com.ericsson.otp.erlang.OtpErlangBinary
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.ThreadingAssertions
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import java.io.File
import java.util.concurrent.CancellationException
import org.elixir_lang.beam.chunk.code.operation.Code as OpCode
import org.elixir_lang.beam.term.Atom as BeamAtom
import org.elixir_lang.beam.term.List as BeamList
import org.elixir_lang.beam.term.Literal as BeamLiteral
import org.elixir_lang.sdk.SdkHomePaths
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Reads build metadata directly from compiled BEAM artifacts in an Elixir SDK home.
 *
 * Currently used to extract which OTP release a given Elixir SDK was compiled against,
 * by parsing the otp_release key from the build_info/0 literal map in Elixir.System.beam.
 */
object ElixirBuildInfo {
    private val LOG = Logger.getInstance(ElixirBuildInfo::class.java)

    /**
     * Extracts the compiled-against OTP release from `Elixir.System.beam`'s `build_info/0`
     * function literal map (the `otp_release` key).
     *
     * Returns the OTP major version string (e.g. `"26"`) or `null` if the BEAM file is
     * absent, cannot be parsed, or does not contain the expected map structure.
     *
     * Must be called with a canonical (WSL-resolved) home path, off the EDT: it reads a ~40KB BEAM file, which on a
     * `\\wsl.localhost` home goes through the Plan 9 redirector. Only [org.elixir_lang.sdk.SdkVersionsFiller] calls
     * this; everything else reads what it found from [org.elixir_lang.sdk.SdkVersionsStore].
     */
    fun elixirOtpRelease(canonicalHome: String): String? {
        ThreadingAssertions.assertBackgroundThread()
        val beamFile = File(canonicalHome, "${SdkHomePaths.ELIXIR_EBIN}/Elixir.System.beam")
        if (!wslCompat.exists(beamFile)) return null

        return when (val outcome = readOtpRelease(beamFile)) {
            is OtpRelease.Found -> outcome.major
            OtpRelease.Absent -> null
            is OtpRelease.Unreadable -> {
                LOG.debug("Could not read otp_release from ${beamFile.path}: ${outcome.reason}", outcome.cause)
                null
            }
        }
    }

    private sealed interface OtpRelease {
        data class Found(val major: String) : OtpRelease

        /** The file parsed and carries no `otp_release`: Elixir below 1.6. */
        data object Absent : OtpRelease

        data class Unreadable(val reason: String, val cause: Throwable? = null) : OtpRelease
    }

    /**
     * A missing literal chunk is [OtpRelease.Absent], not [OtpRelease.Unreadable]: a module with no
     * literals has none, so there is no `otp_release` to find and nothing went wrong.
     */
    private fun readOtpRelease(beamFile: File): OtpRelease = try {
        when (val read = BeamReader.readResult(beamFile.readBytes(), beamFile.path, ::parseOtpRelease)) {
            null -> OtpRelease.Unreadable("not a BEAM")
            is ReadResult.Present -> read.value
            ReadResult.Absent -> OtpRelease.Absent
            is ReadResult.Unreadable -> OtpRelease.Unreadable(read.reason, read.cause)
        }
    } catch (exception: Exception) {
        // Reading the file. Errors are not caught, so a test-mode TestLoggerAssertionError still escapes and
        // fails the test.
        //
        // Hand-rolled for the same reason as [org.elixir_lang.beam.reportUnexpectedBeamData].
        if (exception is ControlFlowException || exception is CancellationException) throw exception
        OtpRelease.Unreadable("could not be read", exception)
    }

    /** A chunk the read cannot do without, so absent and unreadable both end it. */
    private inline fun <T : Any> ReadResult<T>.required(absent: String, otherwise: (OtpRelease) -> Nothing): T =
        when (this) {
            is ReadResult.Present -> value
            ReadResult.Absent -> otherwise(OtpRelease.Unreadable(absent))
            is ReadResult.Unreadable -> otherwise(OtpRelease.Unreadable(reason, cause))
        }

    private fun parseOtpRelease(reader: BeamReader): OtpRelease {
        val atoms = reader.atomsResult.required("no atom chunk") { return it }
        val code = reader.codeResult.required("no code chunk") { return it }
        val literals = when (val read = reader.literalsResult) {
            is ReadResult.Present -> read.value
            ReadResult.Absent -> return OtpRelease.Absent
            is ReadResult.Unreadable -> return OtpRelease.Unreadable(read.reason, read.cause)
        }

        val buildInfoIndex = (1..atoms.size()).firstOrNull { atoms.getOrNull(it)?.string == "build_info" }
            ?: return OtpRelease.Absent
        val otpReleaseIndex = (1..atoms.size()).firstOrNull { atoms.getOrNull(it)?.string == "otp_release" }
            ?: return OtpRelease.Absent

        var inBuildInfo = false
        for (i in 0 until code.size()) {
            val op = code[i]
            when (op.code) {
                OpCode.FUNC_INFO -> {
                    val func = op.termList.getOrNull(1) as? BeamAtom
                    val arity = op.termList.getOrNull(2) as? BeamLiteral
                    inBuildInfo = func?.index == buildInfoIndex && arity?.index == 0
                }
                OpCode.PUT_MAP_ASSOC, OpCode.PUT_MAP_EXACT -> {
                    if (!inBuildInfo) continue
                    val elements = (op.termList.getOrNull(4) as? BeamList)?.elements ?: continue
                    var j = 0
                    while (j < elements.size - 1) {
                        if ((elements[j] as? BeamAtom)?.index == otpReleaseIndex) {
                            val litIndex = (elements[j + 1] as? BeamLiteral)?.index ?: break
                            val major = (literals[litIndex] as? OtpErlangBinary)
                                ?.let { String(it.binaryValue(), Charsets.UTF_8).trim() }
                            // A blank literal is not a major, and caching it as Found would return
                            // "" once and null thereafter from the same bytes.
                            return major?.takeIf { it.isNotEmpty() }
                                ?.let { OtpRelease.Found(it) }
                                ?: OtpRelease.Absent
                        }
                        j += 2
                    }
                }
                else -> {}
            }
        }
        return OtpRelease.Absent
    }
}
