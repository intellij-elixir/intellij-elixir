package org.elixir_lang.beam.chunk.beam_documentation

import com.ericsson.otp.erlang.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.beam.binaryToTerm
import org.elixir_lang.beam.chunk.Chunk

/**
 * Standardised way to store code docs for a given module, available in Elixir since 1.7.
 * For more information lookup EEP-48.
 */
class Documentation(private val docsList: OtpErlangList) {
    /** Beam Language such as 'elixir', 'erlang' */
    val beamLanguage: String by lazy { (docsList.elementAt(2) as OtpErlangAtom).atomValue() }

    /** format the docs are stored in i.e. 'text/markdown' */
    val format: String? by lazy {
        when (val element = docsList.elementAt(3)) {
            is OtpErlangBinary -> String(element.binaryValue(), Charsets.UTF_8)
            is OtpErlangList -> {
                try {
                    element.stringValue()
                } catch (_: Exception) { null }
            }
            else -> null
        }
    }

    /**  List of documentation for other entities (such as functions and types) in the module.*/
    val docs: Docs? by lazy { (docsList.elementAt(6) as? OtpErlangList)?.let { Docs.from(it)} }

    /** [docs] of a module Elixir compiled. */
    val elixirDocs: Docs? get() = if (beamLanguage == "elixir") docs else null

    val moduleDocs: ModuleDocs? by lazy { (docsList.elementAt(4) as? OtpErlangMap)?. let { ModuleDocs(it) } }

    companion object {
        private val LOGGER = Logger.getInstance(Documentation::class.java)

        fun from(chunk: Chunk): Documentation? = decoded(chunk.data)

        /**
         * Parses a `docs_v1` EEP-48 term from raw Erlang External Term Format bytes, or null when they do not
         * decode.
         *
         * This handles both embedded BEAM `Docs` chunk data and external `.chunk` files
         * (e.g. `<app>/doc/chunks/<module>.chunk`) which use the same ETF encoding.
         */
        fun from(data: ByteArray): Documentation? =
            try {
                decoded(data)
            } catch (e: Exception) {
                LOGGER.debug("Failed to parse EEP-48 documentation from byte data", e)
                null
            }

        private fun decoded(data: ByteArray): Documentation? {
            val (term, _) = binaryToTerm(data, 0)

            return if (term is OtpErlangTuple) {
                val firstAtom = term.elements().firstOrNull() as? OtpErlangAtom ?: return null
                if (firstAtom.atomValue() != "docs_v1") {
                    return null
                }

                val list = OtpErlangList(term.elements())
                Documentation(list)
            } else {
                null
            }
        }

        /**
         * Resolves EEP-48 documentation from an external `.chunk` file for the given `.beam` file.
         *
         * Erlang OTP stores documentation as external files at `<app>/doc/chunks/<module>.chunk`
         * when the `Docs` chunk is not embedded in the `.beam` file (common in OTP 23–26 and
         * many mise/asdf-built installations). The `.chunk` file contains a `docs_v1` tuple in
         * Erlang External Term Format.
         *
         * @param beamFile the `.beam` [VirtualFile], typically located in `<app>/ebin/`
         * @return parsed [Documentation], or `null` if no external chunk file is found or parsing fails
         */
        fun fromExternalChunk(beamFile: VirtualFile): Documentation? {
            val moduleName = beamFile.nameWithoutExtension
            // Navigate from <app>/ebin/<module>.beam to <app>/doc/chunks/<module>.chunk
            val ebinDir = beamFile.parent ?: return null
            val appDir = ebinDir.parent ?: return null
            val chunkFile = appDir
                .findChild("doc")
                ?.findChild("chunks")
                ?.findChild("$moduleName.chunk")
                ?: return null

            return try {
                from(chunkFile.contentsToByteArray())
            } catch (e: Exception) {
                LOGGER.debug("Failed to read external doc chunk file: ${chunkFile.path}", e)
                null
            }
        }
    }
}
