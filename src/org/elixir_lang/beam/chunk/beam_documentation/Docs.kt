package org.elixir_lang.beam.chunk.beam_documentation

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.diagnostic.Logger
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.chunk.beam_documentation.docs.Documented
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Doc
import java.util.*

class Docs(private val documentedByArityByNameByKind: MutableMap<String, TreeMap<String, TreeMap<Int, Documented>>>) {
    fun deprecated(macroNameArity: MacroNameArity): OtpErlangObject? = documented(macroNameArity)?.deprecated()

    fun doc(macroNameArity: MacroNameArity): Doc? = documented(macroNameArity)?.doc

    private fun documented(macroNameArity: MacroNameArity): Documented? =
            kind(macroNameArity)?.let { kind ->
                documented(kind, macroNameArity.name, macroNameArity.arity)
            }

    fun documented(kind: String, name: String, arity: Int): Documented? =
            documentedByArityByNameByKind[kind]?.get(name)?.get(arity)

    /**
     * The entry for a higher arity whose default arguments cover [arity]. Elixir exports each arity a default covers,
     * but documents only the full one.
     */
    fun covering(kind: String, name: String, arity: Int): Documented? =
            documentedByArityByNameByKind[kind]?.get(name)
                    ?.tailMap(arity, false)
                    ?.values
                    ?.firstOrNull { it.arity - it.defaults() <= arity }

    fun covering(macroNameArity: MacroNameArity): Documented? =
            kind(macroNameArity)?.let { kind -> covering(kind, macroNameArity.name, macroNameArity.arity) }

    /** The entries named [name] of [kinds], ascending by arity. */
    fun documentedNamed(kinds: List<String>, name: String): List<Documented> =
            kinds.flatMap { kind -> documentedByArityByNameByKind[kind]?.get(name)?.values.orEmpty() }.sortedBy { it.arity }

    fun signatures(macroNameArity: MacroNameArity): List<String>? = documented(macroNameArity)?.signatures
    fun typeDocumentedByArityByName(): Map<String, Map<Int, Documented>> =
            documentedByArityByNameByKind["type"] ?: emptyMap()

    companion object {
        val logger = Logger.getInstance(Docs::class.java)

        fun from(list: OtpErlangList): Docs {
            val documentedByArityByNameByKind = mutableMapOf<String, TreeMap<String, TreeMap<Int, Documented>>>()

            for (element in list) {
                Documented.from(element)?.let { documented ->
                    documentedByArityByNameByKind
                            .computeIfAbsent(documented.kind) { TreeMap() }
                            .computeIfAbsent(documented.name) { TreeMap() }
                            .put(documented.arity, documented)
                }
            }

            return Docs(documentedByArityByNameByKind)
        }

        private fun kind(macroNameArity: MacroNameArity): String? =
                when (val macro = macroNameArity.macro) {
                    "def", "defp" -> "function"
                    "defmacro", "defmacrop" -> "macro"
                    else -> {
                        logger.error("Don't know how to convert macro (${macro}) to kind")

                        null
                    }
                }
    }
}
