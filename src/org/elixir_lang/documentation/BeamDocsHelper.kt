package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.CompiledIdentity
import org.elixir_lang.beam.psi.Module
import org.elixir_lang.code_insight.definition
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.call.MaybeExported
import org.elixir_lang.beam.chunk.beam_documentation.Docs
import org.elixir_lang.beam.chunk.beam_documentation.docs.Documented
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attribute
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.utils.ElixirModulesUtil.erlangModuleNameToElixir

object BeamDocsHelper {
    /** The documentation of [element], a definition or module in a `.beam`, as its `Docs` chunk holds it. */
    fun fetchDocs(element: PsiElement): FetchedDocs? =
        BeamReader.read(element.containingFile.originalFile.virtualFile) { reader -> fetchDocs(element, reader) }

    /**
     * [decompiled], what [element]'s decompiled source documents, with each function titled by the signatures the
     * `Docs` chunk records for its full arity, where it records any, and, where the decompiled source writes
     * no `@spec`, as it does not from Elixir's debug info, given those the chunk's metadata records, else those the debug
     * info holds for its arities.
     */
    fun headed(element: PsiElement, decompiled: FetchedDocs): FetchedDocs {
        val exported = element as? MaybeExported
        val name = exported?.exportedName()

        if (decompiled !is FetchedDocs.Arities || exported == null || name == null) return decompiled

        return BeamReader.read(element.containingFile.originalFile.virtualFile) { reader ->
            val docs = reader.documentation?.docs
            // The debug info is the largest chunk: it is decoded only for an entry the decompiled source gives no spec.
            val specifications by lazy {
                (reader.debugInfo as? V1)?.typeSpecifications?.specifications
                    ?.let { specifications -> (0 until specifications.size()).map(specifications::get) }
                    ?.filter { it.function == name }
                    .orEmpty()
            }

            decompiled.copy(entries = decompiled.entries.map { entry ->
                val documentation = entry.documentation
                val documented = docs?.let { documented(it, exported, name, entry.arities.definition().first) }.orEmpty()
                val titles = documented.flatMap(::signatures)

                entry.copy(
                    documentation = documentation.copy(
                        titles = titles.ifEmpty { documentation.titles },
                        specs = documentation.specs
                            .ifEmpty { documented.flatMap { specsFromMetadata(it.metadatumByName) } }
                            .ifEmpty {
                                specifications
                                    .filter { it.arity.toInt() in entry.arities }
                                    .flatMap { specValues(it.toString()) }
                            }
                    )
                )
            })
        } ?: decompiled
    }

    /**
     * The signatures [docs] records for the definition named [name] whose full arity is [full], as Quick Documentation
     * and Parameter Info both show them: [element]'s kind tried first, whichever language compiled it.
     */
    internal fun signatures(docs: Docs, element: MaybeExported, name: String, full: Int): List<String> =
        documented(docs, element, name, full).flatMap(::signatures)

    /** What [docs] records for the definition named [name] whose full arity is [full], [element]'s kind tried first. */
    private fun documented(docs: Docs, element: MaybeExported, name: String, full: Int): List<Documented> =
        kindForElement(element).mapNotNull { kind -> docs.documented(kind, name, full) }

    /** [documented]'s signatures, without the `\r` a chunk can carry, which a document cannot hold. */
    private fun signatures(documented: Documented): List<String> = documented.signatures.map { it.replace("\r", "") }

    private fun fetchDocs(element: PsiElement, reader: BeamReader): FetchedDocs? =
            reader.atoms?.moduleName()?.let { erlangModuleNameToElixir(it) }?.let { module ->
                val documentation = reader.documentation

                when (element) {
                    is Module -> documentation?.moduleDocs?.englishDocs?.let { moduleDoc ->
                        FetchedDocs.ModuleDocumentation(module, moduleDoc)
                    }
                    is MaybeExported -> {
                        element.exportedName()?.let { name ->
                            // Read only for an entry `Docs` lists: a `.beam` without `Docs` would decode its whole debug info for nothing.
                            val own by lazy(LazyThreadSafetyMode.NONE) {
                                (element as? BeamCallDefinition)?.nameArityInterval?.let {
                                    CompiledIdentity.of(reader, it.name, it.arityInterval.minimum)
                                }
                            }
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
    private fun visible(entry: FetchedDocs.Arities.Entry, own: NameArity?): Boolean =
        FetchedDocs.Arities.listed(
            inside = false,
            exported = true,
            hidden = entry.documentation.doc is Hidden,
            used = own != null && entry.arities.maximum == own.arity
        )

    private fun entry(module: String, documented: Documented): FetchedDocs.Arities.Entry? {
        val signatures = signatures(documented)
        val deprecated = documented.deprecated()
        val doc = documented.doc
        val specs = specsFromMetadata(documented.metadatumByName)

        return if (deprecated != null || doc != null || signatures.isNotEmpty() || specs.isNotEmpty()) {
            // Elixir exports each arity a default covers but documents only the full one.
            FetchedDocs.Arities.Entry(
                ArityInterval(documented.arity - documented.defaults(), documented.arity),
                FetchedDocs.FunctionOrMacroDocumentation(
                    module,
                    deprecated,
                    doc,
                    impls = emptyList(),
                    specs = specs,
                    // The chunk holds no head as written; the decompiled source does, and it is read first.
                    heads = emptyList(),
                    titles = signatures
                )
            )
        } else {
            null
        }
    }

    /**
     * Extracts `@spec` values from the EEP-48 metadata `signature` key.
     *
     * The `signature` metadata contains a list of Erlang abstract form attribute tuples like
     * `{attribute, Line, spec, {{Name, Arity}, [Definition, ...]}}`. These are parsed using
     * the existing [Attribute] -> [Spec] pipeline and rendered via [Spec.toMacroString].
     */
    private fun specsFromMetadata(metadata: Map<String, OtpErlangObject>): List<String> {
        val signatureTerms = metadata["signature"] as? OtpErlangList ?: return emptyList()
        return signatureTerms.elements().flatMap { term ->
            (term as? OtpErlangTuple)
                ?.let { tuple -> Attribute.from(tuple)?.let { attribute -> Spec.from(attribute)?.toMacroString(Options()) } }
                ?.let(::specValues)
                .orEmpty()
        }
    }

    /** The value of each `@spec` line of [specs], as the heading writes it after its own `@spec`. */
    private fun specValues(specs: String): List<String> = specs.lines().map { it.removePrefix("@spec ") }

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
