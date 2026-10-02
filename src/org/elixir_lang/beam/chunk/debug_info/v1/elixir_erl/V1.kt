package org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl

import com.ericsson.otp.erlang.*
import org.elixir_lang.Macro.adjustNewLines
import org.elixir_lang.beam.chunk.DebugInfo
import org.elixir_lang.beam.chunk.Keyword
import org.elixir_lang.beam.chunk.debug_info.logger
import org.elixir_lang.beam.chunk.debug_info.v1.ElixirErl
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.Definitions
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.TypeSpecifications
import org.elixir_lang.beam.chunk.from
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.debugger.stack_frame.value.Presentation.Companion.toUtf8String

fun v1(metadata: OtpErlangObject, elixirErl: ElixirErl): DebugInfo =
    when (metadata) {
        is OtpErlangTuple -> v1(metadata, elixirErl)
        else -> {
            logger.error("""
                         Dbgi :debug_info_v1 version :elixir_erl backend :elixir_v1 version metadata is not a tuple

                         ## Metdata

                         ```elixir
                         ${inspect(metadata)}
                         ```
                         """.trimIndent())

            elixirErl
        }
    }

private fun v1(metadata: OtpErlangTuple, elixirErl: ElixirErl): DebugInfo {
    val arity = metadata.arity()

    return if (arity == 3) {
        V1(elixirErl, metadata)
    } else {
        logger.error("""
                     Dbgi :debug_info_v1 version :elixir_erl backend :elixir_v1 version metadata arity (${arity}) does not match expected (3)

                     ## Metdata

                     ```elixir
                     ${inspect(metadata)}
                     ```
                     """.trimIndent())

        elixirErl
    }
}


class V1(val elixirErl: ElixirErl, val metadata: OtpErlangTuple): DebugInfo {
    val map by lazy { metadata.elementAt(1) as? OtpErlangMap }
    val attributes by lazy { keyword("attributes") }
    val compileOpts by lazy { keyword("compile_opts") }
    val definitions by lazy { Definitions.from(get("definitions"), this) }
    val file by lazy { (get("file") as? OtpErlangBinary)?.let(::toUtf8String) }
    val line by lazy { (get("line") as? OtpErlangLong)?.intValue() }
    val module by lazy { get("module") as? OtpErlangAtom }
    val struct by lazy { get("struct") }
    val inspectedModule by lazy { module?.let { inspect(it) }  }
    val unreachable by lazy { get("unreachable") as OtpErlangList? }
    val typeSpecifications by lazy { TypeSpecifications.from(metadata.elementAt(2)) }

    fun moduleContext(inner: () -> String): String =
            "defmodule ${inspectedModule!!} do\n" +
                    "  # ...\n" +
                    "  ${adjustNewLines(inner(), "\n  ")}\n" +
                    "  # ...\n" +
                    "end"

    private fun get(key: String): OtpErlangObject? = map?.get(OtpErlangAtom(key))

    private fun keyword(key: String): Keyword? =
        get(key)?.let { attributes ->
            (attributes as? OtpErlangList)?.let(::from)
        }
}
