package org.elixir_lang.injection.markdown

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentation
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentationHost
import org.elixir_lang.injection.partsOutsideInterpolations
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.keywordAtom
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * Registered after the platform's injection-contributor adapter: the platform keeps only the first injector that fills
 * an element, so a value a `# language=` comment or a template sigil fills gets no Markdown.
 */
class Injector : MultiHostInjector {
    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (prose(context) != context) return

        when (context) {
            is HeredocLiteral -> {
                injectMarkdownInQuote(registrar, context)
                injectElixirInCodeBlocksInQuote(registrar, context)
            }

            is ElixirLine -> injectMarkdownInQuote(registrar, context)
        }
    }

    private fun prose(value: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(value, AtUnqualifiedNoParenthesesCall::class.java)
            ?.takeIf(::isDocumentationHost)
            ?.argument
            ?.let(::proseInQuote)

    private fun proseInQuote(documentation: PsiElement): PsiElement? =
        when (documentation) {
            is HeredocLiteral, is ElixirLine -> documentation.takeIf(::isDocumentation)
            // `deprecated:` is the one metadata key whose value is prose; any other key is data
            is QuotableKeywordList ->
                documentation
                    .quotableKeywordPairList()
                    .firstOrNull()
                    ?.takeIf { it.keywordAtom() == "deprecated" }
                    ?.let { proseInQuote(it.keywordValue) }

            // Any other value shape is not prose to inject into
            else -> null
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
        val parts = documentation.lineBody?.let { partsOutsideInterpolations(documentation, it.textRangeInParent) }.orEmpty()

        // doneInjecting() throws when nothing was added, as it is for a value that is all interpolation
        if (parts.isEmpty()) return

        registrar.startInjecting(MarkdownLanguage.INSTANCE)
        parts.forEach { registrar.addPlace(null, null, documentation, it) }
        registrar.doneInjecting()
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
                    val parts = classifiedLine.elixirRangeRelativeToQuote
                        ?.let { partsOutsideInterpolations(documentation, it) }
                        .orEmpty()

                    // A line that is all interpolation adds no place, so a block opens at its first line that has one
                    if (parts.isNotEmpty()) {
                        if (!inCodeBlock) {
                            registrar.startInjecting(ElixirLanguage)
                                .frankensteinInjection(true)

                            inCodeBlock = true
                        }

                        parts.forEach { registrar.addPlace(null, null, documentation, it) }
                    }
                }
            }
        }

        if (inCodeBlock) {
            registrar.doneInjecting()
        }
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(HeredocLiteral::class.java, ElixirLine::class.java)
}
