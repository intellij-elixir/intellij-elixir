package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.psi.Module
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.call.MaybeExported
import org.elixir_lang.beam.chunk.beam_documentation.docs.Documented
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attribute
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.utils.ElixirModulesUtil.erlangModuleNameToElixir

object BeamDocsHelper {
    fun fetchDocs(element: PsiElement): FetchedDocs? =
        BeamReader.read(element.containingFile.originalFile.virtualFile) { reader -> fetchDocs(element, reader) }

    private fun fetchDocs(element: PsiElement, reader: BeamReader): FetchedDocs? =
            reader.atoms?.moduleName()?.let { erlangModuleNameToElixir(it) }?.let { module ->
                val documentation = reader.documentation

                when (element) {
                    is Module -> documentation?.moduleDocs?.englishDocs?.let { moduleDoc ->
                        FetchedDocs.ModuleDocumentation(module, moduleDoc)
                    }
                    is MaybeExported -> {
                        element.exportedName()?.let { name ->
                            val own = (element as? BeamCallDefinition)?.nameArityInterval?.arityInterval
                            val entries = documentation?.docs
                                ?.documentedNamed(kindForElement(element), name)
                                ?.mapNotNull { entry(module, it)?.takeIf { entry -> visible(entry, own) } }
                                .orEmpty()

                            entries.takeIf(List<*>::isNotEmpty)?.let { FetchedDocs.Arities(module, it) }
                        }
                    }
                    // Only modules and exported definitions carry docs; a type or anything else has none
                    else -> null
                }
            }

    /** A function marked `@doc false` is not listed with the others, but is when it is the one asked about. */
    private fun visible(entry: FetchedDocs.Arities.Entry, own: ArityInterval?): Boolean =
        FetchedDocs.Arities.listed(
            inside = false,
            exported = true,
            hidden = entry.documentation.doc is Hidden,
            used = own?.overlaps(entry.arities) == true
        )

    private fun entry(module: String, documented: Documented): FetchedDocs.Arities.Entry? {
        val signatures = documented.signatures
        val deprecated = documented.deprecated()
        val doc = documented.doc
        // Use specs as heads when available -- they contain the full typed signature (e.g. "@spec any(pred, list) ::
        // boolean() when ...") which is more informative than the EEP-48 signatures field (which is often just
        // "any/2" for Erlang modules).
        val heads = specsFromMetadata(documented.metadatumByName).ifEmpty { signatures }

        return if (deprecated != null || doc != null || heads.isNotEmpty()) {
            // Elixir exports each arity a default covers but documents only the full one.
            FetchedDocs.Arities.Entry(
                ArityInterval(documented.arity - documented.defaults(), documented.arity),
                FetchedDocs.FunctionOrMacroDocumentation(
                    module,
                    deprecated,
                    doc,
                    impls = emptyList(),
                    specs = emptyList(),
                    heads = heads
                )
            )
        } else {
            null
        }
    }

    /**
     * Extracts `@spec` strings from the EEP-48 metadata `signature` key.
     *
     * The `signature` metadata contains a list of Erlang abstract form attribute tuples like
     * `{attribute, Line, spec, {{Name, Arity}, [Definition, ...]}}`. These are parsed using
     * the existing [Attribute] -> [Spec] pipeline and rendered via [Spec.toMacroString].
     */
    private fun specsFromMetadata(metadata: Map<String, OtpErlangObject>): List<String> {
        val signatureTerms = metadata["signature"] as? OtpErlangList ?: return emptyList()
        return signatureTerms.elements().mapNotNull { term ->
            (term as? OtpErlangTuple)?.let { tuple ->
                Attribute.from(tuple)?.let { attribute ->
                    Spec.from(attribute)?.toMacroString(Options())
                }
            }
        }
    }

    /**
     * Returns the list of BEAM documentation "kind" strings to try when looking up docs for [element].
     *
     * The preferred kind is tried first, but we fall back to the other kind in case the BEAM docs
     * use a different categorization than expected (e.g. guards stored as macros).
     */
    private fun kindForElement(element: MaybeExported): List<String> =
        when (element) {
            is BeamCallDefinition -> when (element.capabilities.presentation) {
                Presentation.MACRO, Presentation.GUARD -> listOf("macro", "function")
                Presentation.FUNCTION -> listOf("function", "macro")
            }
            else -> listOf("function", "macro")
        }
}
