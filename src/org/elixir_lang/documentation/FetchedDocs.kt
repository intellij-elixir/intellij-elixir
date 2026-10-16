package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Doc
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.prevSiblingSequence
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

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
        val heads: List<String>
    ) : FetchedDocs(module) {
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

            @RequiresReadLock
            fun fromCallDefinitionClauseCall(
                module: String?,
                call: Call,
                head: String
            ): FunctionOrMacroDocumentation {
                val callDefinitionAttributeListByName = callDefinitionAttributeListByName(call)
                val deprecated = callDefinitionAttributeListByName[DEPRECATED]?.joinModuleAttributeQuoteText()
                    ?.let { OtpErlangBinary(it.toByteArray()) }
                val doc = docAbove(call)?.moduleAttributeValue()?.documentationMarkdownText()
                    ?.let { MarkdownByLanguage.english(it) }
                val impls = callDefinitionAttributeListByName[IMPL].moduleAttributeValueTextList()
                val specs = callDefinitionAttributeListByName[SPEC].moduleAttributeValueTextList()
                val heads = listOf(head)

                return FunctionOrMacroDocumentation(module, deprecated, doc, impls, specs, heads)
            }

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
        data class Entry(val arities: ArityInterval, val documentation: FunctionOrMacroDocumentation) {
            /** Names this entry in a link to it. */
            val id: String get() = "arity-${arities.maximum ?: arities.minimum}"
        }

        /** The entry for the function used at [arity], if there is one. */
        fun entryAt(arity: Int): Entry? = entries.firstOrNull { arity in it.arities }

        companion object {
            /**
             * Whether a function is listed: every one from [inside] its module, else those [exported] and not [hidden], and
             * always the one [used].
             */
            fun listed(inside: Boolean, exported: Boolean, hidden: Boolean, used: Boolean): Boolean =
                inside || (exported && !hidden) || used
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

/** Whether the `@doc` written above [call] is `false` or `nil`, which hides the function from documentation. */
@RequiresReadLock
internal fun hidesDoc(call: Call): Boolean =
    docAbove(call)
        ?.moduleAttributeValue()
        ?.let { AtomName.of(it) in SourceFileDocsHelper.HIDDEN_DOCUMENTATION } == true

/** The `@doc` above [call] that stands, the nearest one: a keyword list only sets metadata beside the doc and replaces nothing. */
private fun docAbove(call: Call): AtUnqualifiedNoParenthesesCall<*>? =
    callDefinitionAttributeListByName(call)[DOC]?.firstOrNull { it.moduleAttributeValue() !is QuotableKeywordList }

private fun callDefinitionAttributeListByName(callDefinitionCall: Call): Map<String, List<AtUnqualifiedNoParenthesesCall<*>>> =
    callDefinitionCall
        .prevSiblingSequence()
        .drop(1)
        // Every declaration takes the attributes written above it, so the run ends at the previous one.
        .takeWhile { it !is Call || DeclaringForm.declaredBy(it) == null }
        .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
        .filter { CALL_DEFINITION_ATTRIBUTE_NAME_SET.contains(it.atIdentifier.identifierName()) }
        .groupBy { it.atIdentifier.identifierName() }

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
