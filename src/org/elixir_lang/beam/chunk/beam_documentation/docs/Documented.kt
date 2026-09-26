package org.elixir_lang.beam.chunk.beam_documentation.docs

import com.ericsson.otp.erlang.*
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Doc
import org.elixir_lang.beam.term.inspect
import com.intellij.openapi.diagnostic.Logger

data class Documented(val kind: String,
                      val name: String,
                      val arity: Int,
                      val signatures: List<String>,
                      val doc: Doc?,
                      val metadatumByName: Map<String, OtpErlangObject>) {
    fun deprecated(): OtpErlangObject? = metadatumByName["deprecated"]

    /** Whether the docs mark this as a guard, as `defguard` does. */
    fun guard(): Boolean = (metadatumByName["guard"] as? OtpErlangAtom)?.booleanValue() == true

    companion object {
        private val logger = Logger.getInstance(Documented::class.java)

        fun from(element: OtpErlangObject): Documented? =
            if (element is OtpErlangTuple) {
                from(element)
            } else {
                logger.error("Cannot parse documented element (${inspect(element)}) in Docs")

                null
            }

        private fun from(element: OtpErlangTuple): Documented {
            val firstTuple = element.elementAt(0) as OtpErlangTuple
            val kind = firstTuple.elementAt(0).let { it as OtpErlangAtom }.atomValue()
            val name = firstTuple.elementAt(1).let { it as OtpErlangAtom }.atomValue()
            val arity = firstTuple.elementAt(2).let { it as OtpErlangLong }.uIntValue()

            val signatures = signaturesFrom(element.elementAt(2))
            val doc = Doc.from(element.elementAt(3))
            val metadataByName = metadatumByNameFrom(element.elementAt(4))

            return Documented(kind, name, arity, signatures, doc, metadataByName)
        }

        private fun signaturesFrom(element: OtpErlangObject): List<String> =
                element
                        .let { it as OtpErlangList }
                        .mapNotNull { erlangTermToString(it) }

        private fun erlangTermToString(term: OtpErlangObject): String? =
                when (term) {
                    is OtpErlangBinary -> String(term.binaryValue(), Charsets.UTF_8)
                    is OtpErlangList -> {
                        try {
                            term.stringValue()
                        } catch (_: Exception) {
                            logger.debug("Failed to decode Erlang charlist as string: ${inspect(term)}")
                            null
                        }
                    }
                    else -> {
                        logger.error("Don't know how to decode string from ${inspect(term)}")
                        null
                    }
                }

        private fun metadatumByNameFrom(element: OtpErlangObject): Map<String, OtpErlangObject> =
                when (element) {
                    is OtpErlangMap -> metadatumByNameFrom(element)
                    else -> {
                        logger.error("Don't know how to decode documented metadata from ${inspect(element)}")

                        emptyMap()
                    }
                }

        private fun metadatumByNameFrom(element: OtpErlangMap): Map<String, OtpErlangObject> =
                element
                        .entrySet()
                        .mapNotNull {
                            metadataNameFrom(it.key)?.let { name ->
                                name to it.value
                            }
                        }
                        .toMap()

        private fun metadataNameFrom(element: OtpErlangObject): String? =
                when (element) {
                    is OtpErlangAtom -> element.atomValue()
                    else -> {
                        logger.error("Don't know how to decode metadata name from ${inspect(element)}")

                        null
                    }
                }
    }
}
