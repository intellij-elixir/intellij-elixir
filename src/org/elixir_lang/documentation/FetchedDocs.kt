package org.elixir_lang.documentation

import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Doc
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.prevSiblingSequence
import org.elixir_lang.psi.impl.stripAccessExpression

sealed class FetchedDocs(open val module: String) {
    data class CallbackDocumentation(override val module: String, val head: String, val doc: String) :
        FetchedDocs(module)

    data class FunctionOrMacroDocumentation(
        override val module: String,
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
            val heads = this.heads + other.heads

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

            fun fromCallDefinitionClauseCall(
                module: String,
                call: Call,
                head: PsiElement
            ): FunctionOrMacroDocumentation {
                val callDefinitionAttributeListByName = callDefinitionAttributeListByName(call)
                val deprecated = callDefinitionAttributeListByName[DEPRECATED]?.joinModuleAttributeQuoteText()
                    ?.let { OtpErlangBinary(it.toByteArray()) }
                val doc = callDefinitionAttributeListByName[DOC]?.joinModuleAttributeQuoteText()
                    ?.let { MarkdownByLanguage.english(it) }
                val impls = callDefinitionAttributeListByName[IMPL].moduleAttributeValueTextList()
                val specs = callDefinitionAttributeListByName[SPEC].moduleAttributeValueTextList()
                val heads = listOf(head.text)

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

    data class ModuleDocumentation(override val module: String, val moduledoc: String) : FetchedDocs(module)

    data class TypeDocumentation(override val module: String, val head: String, val typedoc: String) :
        FetchedDocs(module)
}

private val DEPRECATED = "deprecated"
private val DOC = "doc"
private val IMPL = "impl"
private val SPEC = "spec"
private val CALL_DEFINITION_ATTRIBUTE_NAME_SET: Set<String> = setOf(DEPRECATED, DOC, IMPL, SPEC)

private fun callDefinitionAttributeListByName(callDefinitionCall: Call): Map<String, List<AtUnqualifiedNoParenthesesCall<*>>> =
    callDefinitionCall
        .prevSiblingSequence()
        .drop(1)
        // A defdelegate ends the run of attributes belonging to whatever follows it, exactly as a def
        // does. Without it the walk runs past any number of delegations and attaches an @doc written
        // for one of them to a later, undocumented definition.
        .takeWhile { it !is Call || CallableDeclaration.headBindingFormOf(it) == null }
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
fun AtUnqualifiedNoParenthesesCall<*>.moduleAttributeValue(): PsiElement? = this
    .finalArguments()
    ?.singleOrNull()
    ?.stripAccessExpression()
