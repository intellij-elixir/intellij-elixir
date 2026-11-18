package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Doc
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.code_insight.definition
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.prevSiblingSequence

/**
 * What hides a function or macro from its module's docs, where Quick Documentation can say: [because] is the reason the
 * line it shows gives, as HTML, `null` where nothing but the doc Elixir recorded says it.
 */
enum class Hiding(val because: String?) {
    DOC_FALSE("<code>@doc false</code>"),
    IMPL("a callback implementation (<code>@impl</code>) with no <code>@doc</code>"),
    UNDERSCORE("its name starts with an underscore and it has no <code>@doc</code>"),
    UNSTATED(null)
}

/** What the documentation of one declaration holds; [module] is `null` where the module is not known, as in a `quote`. */
sealed class FetchedDocs(open val module: String?) {
    data class CallbackDocumentation(override val module: String?, val head: String, val doc: String) :
        FetchedDocs(module)

    data class FunctionOrMacroDocumentation(
        override val module: String?,
        val deprecated: OtpErlangObject?,
        val doc: Doc?,
        val impls: List<String>,
        val specs: List<String>,
        val heads: List<String>,
        val titles: List<String> = emptyList(),
        /** What hides the function, where [doc] is hidden. */
        val hiding: Hiding? = null
    ) : FetchedDocs(module) {
        val heading: Heading get() = Heading(titles, specs, heads)

        /**
         * These docs, written by a definition that replaced [earlier]'s of the same function: Elixir keeps the earlier's
         * doc where this writes none, and its metadata beneath this one's (`Module.compile_doc/6`).
         */
        fun replacing(earlier: FunctionOrMacroDocumentation?): FunctionOrMacroDocumentation =
            earlier?.let { copy(deprecated = deprecated ?: it.deprecated, doc = doc ?: it.doc) } ?: this

        fun merge(other: FunctionOrMacroDocumentation): FunctionOrMacroDocumentation {
            assert(module == other.module)

            val deprecated = mergeDeprecated(this.deprecated, other.deprecated)
            val doc = Doc.merge(this.doc, other.doc)
            val impls = this.impls + other.impls
            val specs = this.specs + other.specs
            val heads = headsOf(listOf(this, other))

            return FunctionOrMacroDocumentation(
                module,
                deprecated,
                doc,
                impls,
                specs,
                heads
            )
        }

        companion object {
            private val logger = Logger.getInstance(FunctionOrMacroDocumentation::class.java)

            /** The heads of declarations of one function, in order, each once: a bodiless head and a clause may be written alike. */
            fun headsOf(documentation: List<FunctionOrMacroDocumentation>): List<String> =
                documentation.flatMap { it.heads }.distinct()

            private fun mergeDeprecated(first: OtpErlangObject?, second: OtpErlangObject?): OtpErlangObject? =
                if (first != null) {
                    if (second != null) {
                        logger.error(
                            "Don't know how to merge deprecated metadata (${inspect(first)}) and ${
                                inspect(
                                    second
                                )
                            })"
                        )

                        null
                    } else {
                        first
                    }
                } else {
                    second
                }
        }
    }

    /** Every function of one name in a module, ascending by arity: an [Entry] each, as default arguments cover several arities of one. */
    data class Arities(override val module: String?, val entries: List<Entry>) : FetchedDocs(module) {
        /** One function's documentation, from the module's [otherCopy]th other copy where the module has several. */
        data class Entry(val arities: ArityInterval, val documentation: FunctionOrMacroDocumentation, val otherCopy: Int = 0) {
            /** Names this entry in a link to it. */
            val id: String get() = "arity-${arities.definition().first}" + if (otherCopy == 0) "" else "-copy-$otherCopy"
        }

        /** Whether there are several entries, so each is linked to its own anchor. */
        val linked: Boolean get() = entries.size > 1

        /** The entry for the function used at [arity], if there is one. */
        fun entryAt(arity: Int): Entry? = entries.firstOrNull { arity in it.arities }

        companion object {
            /**
             * Whether a function is listed: every one from [inside] its module, else those [exported] and not [hidden], and
             * always the one [used]. One [generated], as `defexception`'s functions are, is listed only where it is used.
             */
            fun listed(inside: Boolean, exported: Boolean, hidden: Boolean, used: Boolean, generated: Boolean = false): Boolean =
                used || (!generated && (inside || (exported && !hidden)))
        }
    }

    /**
     * What heads the documentation of a function: Elixir's signature for each arity it covers ([titles]), then each
     * `@spec`, then the head of each declaration as written. Where no signature is known, the heads stand in for it.
     */
    class Heading(val titles: List<String>, val specs: List<String>, val heads: List<String>) {
        /** What names the function: its signatures, else its heads. */
        private val names: List<String> get() = titles.ifEmpty { heads }

        /** Appends the heading to a definition block, each name linked to the anchor [link] when one is given. */
        fun appendTo(html: StringBuilder, link: String?) {
            for (name in names.map(StringUtil::escapeXmlEntities)) {
                if (link != null) html.append("<a href=\"#").append(link).append("\">").append(name).append("</a>") else html.append(name)
                html.append("\n")
            }

            for (spec in specs) html.append("  @spec ").append(StringUtil.escapeXmlEntities(spec)).append("\n")

            if (titles.isNotEmpty()) {
                for (head in heads) html.append("  ").append(StringUtil.escapeXmlEntities(head)).append("\n")
            }
        }

        /** Appends the anchor [id] to a content block, with the names it is linked from. */
        fun appendAnchorTo(html: StringBuilder, id: String) {
            html.append("<a id=\"").append(id).append("\"></a><b>")
                .append(names.joinToString("<br>", transform = StringUtil::escapeXmlEntities))
                .append("</b>")
        }
    }

    data class ModuleDocumentation(override val module: String?, val moduledoc: String) : FetchedDocs(module)

    data class TypeDocumentation(override val module: String?, val head: String, val typedoc: String) :
        FetchedDocs(module)
}

private const val DEPRECATED = "deprecated"
private const val DOC = "doc"
private const val IMPL = "impl"
private const val SPEC = "spec"
private val CALL_DEFINITION_ATTRIBUTE_NAME_SET: Set<String> = setOf(DEPRECATED, DOC, IMPL, SPEC)

/** Whether an `@doc` that is not metadata is written above [call]: `false` hides the function, which says something. */
@RequiresReadLock
internal fun writesDoc(call: Call): Boolean = DeclarationAttributes.of(call).writesDoc

/**
 * The `@doc`, `@deprecated`, `@impl` and `@spec` written above a declaration, read once: [doc] is the `@doc` that
 * stands, and [hidesDoc] whether it is `false`, which hides the function from documentation.
 */
internal class DeclarationAttributes private constructor(
    private val attributeListByName: Map<String, List<AtUnqualifiedNoParenthesesCall<*>>>,
    private val doc: AtUnqualifiedNoParenthesesCall<*>?,
    val hidesDoc: Boolean
) {
    /** Whether an `@doc` that is not metadata is written: `false` hides the function, which says something. */
    val writesDoc: Boolean get() = doc != null

    /** Whether the `@doc` written, if any, can be read: one whose value is another attribute can't, which is not no doc. */
    val docReadable: Boolean
        @RequiresReadLock
        get() = doc == null || hidesDoc || doc.moduleAttributeValue()?.documentationMarkdownText() != null

    /**
     * Whether the `@impl` that stands, the last one written, is `true` or a module, an alias or an atom as Erlang's
     * `:gen_server` is, which marks the function a callback and so hides it where no doc is written; `@impl false`, or a
     * value this can't read, doesn't (`Module.preprocess_attribute/2`).
     */
    val implemented: Boolean
        @RequiresReadLock
        get() = attributeListByName[IMPL]?.firstOrNull()?.moduleAttributeValue()?.let(::marksCallback) == true

    /** The docs these write for the declaration, shown with [head], in [module]. */
    @RequiresReadLock
    fun documentation(module: String?, head: String?): FetchedDocs.FunctionOrMacroDocumentation {
        val deprecated = attributeListByName[DEPRECATED]?.joinModuleAttributeQuoteText()
            ?.let { OtpErlangBinary(it.toByteArray()) }
        val markdown = doc?.moduleAttributeValue()?.documentationMarkdownText()?.let { MarkdownByLanguage.english(it) }
        val impls = attributeListByName[IMPL].moduleAttributeValueTextList()
        val specs = attributeListByName[SPEC].moduleAttributeValueTextList()

        return FetchedDocs.FunctionOrMacroDocumentation(module, deprecated, markdown, impls, specs, listOfNotNull(head))
    }

    companion object {
        /**
         * The attributes written above a declaration [call]. The `@doc` that stands is the nearest one: a keyword list
         * only sets metadata beside the doc and replaces nothing, and `@doc nil` writes no doc, so the function keeps
         * any it replaced (`Module.compile_doc/6`).
         */
        @RequiresReadLock
        fun of(call: Call): DeclarationAttributes {
            val attributeListByName = callDefinitionAttributeListByName(call)
            val doc = attributeListByName[DOC]
                ?.firstOrNull { it.moduleAttributeValue() !is QuotableKeywordList }
                ?.takeUnless { it.moduleAttributeValue()?.let(AtomName::of) == "nil" }

            return DeclarationAttributes(attributeListByName, doc, doc?.moduleAttributeValue()?.let(AtomName::of) == "false")
        }
    }
}

/** Whether [impl], the value of an `@impl`, is `true` or a module: an alias, or an atom other than `false` and `nil`. */
@RequiresReadLock
private fun marksCallback(impl: PsiElement): Boolean =
    impl is QualifiableAlias ||
        ((impl is ElixirAtom || impl is ElixirAtomKeyword) && AtomName.of(impl)?.let { it != "false" && it != "nil" } == true)

private fun callDefinitionAttributeListByName(callDefinitionCall: Call): Map<String, List<AtUnqualifiedNoParenthesesCall<*>>> {
    val source = sourceFor(Feature.DOCUMENTATION)

    return callDefinitionCall
        .prevSiblingSequence()
        .drop(1)
        // Every declaration takes the attributes written above it, so the run ends at the previous one.
        .takeWhile { it !is Call || source.declaredForm(it) == null }
        .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
        .filter { CALL_DEFINITION_ATTRIBUTE_NAME_SET.contains(it.atIdentifier.identifierName()) }
        .groupBy { it.atIdentifier.identifierName() }
}

private fun List<AtUnqualifiedNoParenthesesCall<*>>.joinModuleAttributeQuoteText(): String? =
    this
        .asSequence()
        .mapNotNull { moduleAttribute -> moduleAttribute.moduleAttributeValue()?.documentationMarkdownText() }
        .toList()
        .takeIf(List<*>::isNotEmpty)
        ?.joinToString("")

private fun List<AtUnqualifiedNoParenthesesCall<*>>?.moduleAttributeValueTextList(): List<String> =
    this?.mapNotNull { it.moduleAttributeValueText() }.orEmpty()

private fun AtUnqualifiedNoParenthesesCall<*>.moduleAttributeValueText(): String? = this.moduleAttributeValue()?.text

@RequiresReadLock
fun AtUnqualifiedNoParenthesesCall<*>.moduleAttributeValue(): PsiElement? = argument
