package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.psi.Module
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.call.MaybeExported
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
                            val arity = element.exportedArity(ResolveState.initial())
                            val kind = kindForElement(element)

                            documentation?.docs?.let { docs ->
                                // Try exact arity first, then fall back to other arities for the same name.
                                // In Elixir, default-argument stubs (e.g. info/1) delegate to the full-arity
                                // definition (e.g. info/2) which is where the @doc lives.
                                val documented = kind.firstNotNullOfOrNull { k -> docs.documented(k, name, arity) }
                                    ?: kind.firstNotNullOfOrNull { k -> docs.covering(k, name, arity) }
                                    ?: kind.firstNotNullOfOrNull { k -> docs.documentedByNameFallback(k, name, arity) }

                                documented?.let {
                                    val signatures = it.signatures
                                    val deprecated = it.deprecated()
                                    val doc = it.doc
                                    val specs = specsFromMetadata(it.metadatumByName)

                                    // Use specs as heads when available -- they contain the full
                                    // typed signature (e.g. "@spec any(pred, list) :: boolean() when ...")
                                    // which is more informative than the EEP-48 signatures field
                                    // (which is often just "any/2" for Erlang modules).
                                    val heads = specs.ifEmpty { signatures }

                                    if (deprecated != null || doc != null || heads.isNotEmpty()) {
                                        FetchedDocs.FunctionOrMacroDocumentation(
                                            module,
                                            deprecated,
                                            doc,
                                            impls = emptyList(),
                                            specs = emptyList(),
                                            heads = heads
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }
                        }
                    }
                    // Only modules and exported definitions carry docs; a type or anything else has none
                    else -> null
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
