package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import junit.framework.TestCase.assertEquals
import java.io.File

/** Each `.beam` in every app of the leg's Elixir and OTP, by module as atom text. */
internal val appBeams: Map<String, File> by lazy {
    (appEbins("ELIXIR_LANG_ELIXIR_PATH") + appEbins("ERLANG_SDK_HOME"))
        .flatMap { ebin -> ebin.listFiles { file -> file.extension == "beam" }.orEmpty().toList() }
        .associateBy { it.nameWithoutExtension }
}

/**
 * What [answer], an expression of the loaded module `m`, gives for each of [modules] on the leg's node, [BATCH_SIZE]
 * to a compile. A module its release doesn't hold is loaded from its [appBeams] `.beam` for the probe, then unloaded.
 * A module the node can't load is null.
 */
internal fun ProbeHarness.probeLoaded(modules: List<String>, answer: String): Map<String, OtpErlangObject?> =
    modules.chunked(BATCH_SIZE).flatMap { batch ->
        // Names are binaries made atoms at run time: a quoted atom per module makes the compiler warn once for each.
        val list = batch.joinToString(", ") { "{\"$it\", ${elixirString(appBeams.getValue(it).path)}}" }
        val compiled = compileSource(
            """
            for {name, path} <- [$list] do
              m = String.to_atom(name)
              held = Code.ensure_loaded?(m)
              loaded = held or match?({:module, _}, :code.load_binary(m, String.to_charlist(path), File.read!(path)))

              if loaded do
                IntellijElixir.Quoter.Probe.send(__ENV__, {m, {:loaded, ($answer)}})
              else
                IntellijElixir.Quoter.Probe.send(__ENV__, {m, :unloaded})
              end

              if loaded and not held do
                :code.delete(m)
                :code.purge(m)
              end
            end
            """.trimIndent(),
        )

        assertEquals("probe compile status", OtpErlangAtom("ok"), compiled.status)

        compiled.messages.map { message ->
            val (module, value) = (message as OtpErlangTuple).elements()

            (module as OtpErlangAtom).atomValue() to (value as? OtpErlangTuple)?.elementAt(1)
        }
    }.toMap()

/** [text] as an Elixir string literal, so a Windows path's backslashes aren't read as escapes. */
internal fun elixirString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("#", "\\#") + "\""

/** Modules per probe compile: 1,768 took about 30 s on the slowest leg, so a batch takes about a tenth of that. */
private const val BATCH_SIZE = 200
