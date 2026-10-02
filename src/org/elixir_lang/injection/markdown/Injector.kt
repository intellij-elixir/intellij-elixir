package org.elixir_lang.injection.markdown

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments
import com.intellij.psi.PsiElement
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentation
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.keywordAtom
import org.elixir_lang.psi.impl.stripAccessExpression
import org.intellij.plugins.markdown.lang.MarkdownLanguage

class Injector : MultiHostInjector {
    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        context
            .let { it as? AtUnqualifiedNoParenthesesCall<*> }
            ?.takeIf(org.elixir_lang.injection.PsiLanguageInjectionHost::isDocumentationHost)
            ?.lastChild
            ?.firstChild
            ?.firstChild
            ?.stripAccessExpression()
            ?.let { getLanguagesToInjectInQuote(registrar, it) }
    }

    private fun getLanguagesToInjectInQuote(registrar: MultiHostRegistrar, documentation: PsiElement) {
        when (documentation) {
            // The walk also reaches a string that only starts the value, e.g. `@doc "text" <> "more"`, which is not
            // the documentation itself
            is HeredocLiteral -> if (isDocumentation(documentation)) {
                injectMarkdownInQuote(registrar, documentation)
                injectElixirInCodeBlocksInQuote(registrar, documentation)
            }

            // Issue #2923 associated bug.  @moduledoc on unquote
            //    #
            //    # * We inline most of the code for performance, so it is specific
            //    #   per helper module anyway.
            //    #
            //    code = quote do
            //      @moduledoc unquote(docs) && """
            //      Module with named helpers generated from #{inspect unquote(env.module)}.
            //      """
            //      unquote(defhelper)
            //      unquote(defcatch_all)
            //      unquote_splicing(impls)
            is ElixirMatchedUnqualifiedParenthesesCall,
            is ElixirAlias,
                // @external_resource "README.md"
                // @moduledoc @external_resource
                //            |> File.read!()
                //            |> String.split("<!-- MDOC !-->")
                //            |> Enum.fetch!(1)
            is ElixirMatchedArrowOperation,
                // @moduledoc @doc_header <> @doc_footer
            is ElixirMatchedAtOperation,
                // With missing quotes like in #2991 `@module implements logic`
            is ElixirIdentifier,
            is ElixirAtomKeyword -> Unit

            is ElixirLine -> if (isDocumentation(documentation)) injectMarkdownInQuote(registrar, documentation)
            // `deprecated:` is the one metadata key whose value is prose; any other key is data
            is QuotableKeywordPair -> if (documentation.keywordAtom() == "deprecated") {
                getLanguagesToInjectInQuote(registrar, documentation.keywordValue)
            }

            // Any other value shape is not prose to inject into
            else -> Unit
        }
    }

    private fun injectMarkdownInQuote(registrar: MultiHostRegistrar, documentation: HeredocLiteral) {
        // One place per run of documentation between code blocks, rather than one per line: each place is
        // a shred, and validating the injected document walks every shred on nearly every access
        val places = markdownInjection(documentation).places

        if (places.isEmpty()) return

        registrar.startInjecting(MarkdownLanguage.INSTANCE)

        // Caught per addPlace, not around the whole loop: doneInjecting() below must still run even when
        // one place fails, or the registrar is left mid-startInjecting and the next startInjecting() -
        // for this heredoc's own Elixir code blocks, or anywhere else in the file - throws
        // IllegalStateException
        for (place in places) {
            try {
                registrar.addPlace(place.prefix, place.suffix, documentation, place.rangeInHost)
            } catch (runtimeExceptionWithAttachments: RuntimeExceptionWithAttachments) {
                Logger.error(
                    javaClass,
                    "Cannot inject markdown in Heredoc",
                    documentation,
                    runtimeExceptionWithAttachments
                )
            }
        }

        registrar.doneInjecting()
    }

    private fun injectMarkdownInQuote(registrar: MultiHostRegistrar, documentation: ElixirLine) {
        documentation.lineBody?.let { lineBody ->
            registrar.startInjecting(MarkdownLanguage.INSTANCE)
            registrar.addPlace(null, null, documentation, lineBody.textRangeInParent)
            registrar.doneInjecting()
        }
    }


    private fun injectElixirInCodeBlocksInQuote(registrar: MultiHostRegistrar, documentation: HeredocLiteral) {
        // Consumes the same classification injectMarkdownInQuote's markdownInjection() builds from, so the
        // two injections cannot disagree about where a code block starts or ends
        var inCodeBlock = false

        for (classifiedLine in classifyLines(documentation)) {
            when (classifiedLine.kind) {
                LineKind.LIST_START, LineKind.PROSE -> {
                    if (inCodeBlock) {
                        registrar.doneInjecting()

                        inCodeBlock = false
                    }
                }

                LineKind.LIST_CONTINUATION, LineKind.BLANK -> Unit

                LineKind.CODE -> {
                    classifiedLine.elixirRangeRelativeToQuote?.let { textRangeInQuote ->
                        if (!inCodeBlock) {
                            registrar.startInjecting(ElixirLanguage)
                                .frankensteinInjection(true)

                            inCodeBlock = true
                        }

                        registrar.addPlace(null, null, documentation, textRangeInQuote)
                    }
                }
            }
        }

        if (inCodeBlock) {
            registrar.doneInjecting()
        }
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(AtUnqualifiedNoParenthesesCall::class.java)
}
