package org.elixir_lang.injection

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.eex.psi.Types as EexTypes
import org.elixir_lang.heex.HeexLanguage
import org.elixir_lang.heex.psi.Types as HeexTypes
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.HeredocLineable
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.intellij.lang.regexp.RegExpLanguage
import org.elixir_lang.eex.Language as EexLanguage

private val LOG = logger<ElixirSigilInjector>()

/** The language the injector fills a sigil named [sigilName] with, or `null` for a sigil it leaves alone. */
internal fun languageForSigil(sigilName: String): Language? {
    LOG.debug("languageForSigil: sigilName='$sigilName'")

    return when (sigilName) {
        "H" -> HeexLanguage.INSTANCE
        "E", "L" -> EexLanguage.INSTANCE
        "r" -> RegExpLanguage.INSTANCE
        else -> null
    }
}

/** The token type each language the injector fills a sigil with gives the parts of its body that are Elixir. */
private val ELIXIR_TOKENS: Map<Language, IElementType> = mapOf(
    HeexLanguage.INSTANCE to HeexTypes.ELIXIR,
    EexLanguage.INSTANCE to EexTypes.ELIXIR
)

/** Whether the injector fills a sigil named [sigilName] with a language that holds Elixir: HEEx or EEx. */
internal fun sigilHoldsElixir(sigilName: String): Boolean = languageForSigil(sigilName) in ELIXIR_TOKENS

/**
 * The parts of [body], the text of a sigil named [sigilName], that are Elixir as the language the injector fills it
 * with lexes them, or `null` when that language holds none.
 */
internal fun sigilElixirParts(sigilName: String, body: CharSequence): Sequence<CharSequence>? {
    val language = languageForSigil(sigilName) ?: return null
    val elixir = ELIXIR_TOKENS[language] ?: return null
    val lexer = LanguageParserDefinitions.INSTANCE.forLanguage(language).createLexer(null)

    return sequence {
        lexer.start(body)

        while (lexer.tokenType != null) {
            if (lexer.tokenType == elixir) yield(lexer.tokenSequence)

            lexer.advance()
        }
    }
}

internal class ElixirSigilInjector : MultiHostInjector {
    // Per-line view of a sigil body used to compute injection ranges consistently for line and heredoc sigils.
    private data class InjectionLine(
        val hostRange: TextRange,
        val bodyElement: PsiElement?,
        val bodyStartInHost: Int
    )

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        when (context) {
            is SigilLine -> handleSigilLine(registrar, context)
            is SigilHeredocLiteral -> handleSigilHeredoc(registrar, context)
            else -> return
        }
    }

    private fun handleSigilLine(registrar: MultiHostRegistrar, sigilLine: SigilLine) {
        if (!sigilLine.isValidHost) return
        val sigilName = sigilLine.sigilName()
        val lang = languageForSigil(sigilName) ?: return
        val ranges = sigilLineInjectionRanges(sigilLine, lang)
        if (ranges.isEmpty()) return

        registrar.startInjecting(lang, fileExtensionForSigil(sigilName))
        for (range in ranges) {
            registrar.addPlace(null, null, sigilLine, range)
        }
        registrar.doneInjecting()
    }

    private fun handleSigilHeredoc(registrar: MultiHostRegistrar, sigilHeredoc: SigilHeredocLiteral) {
        if (!sigilHeredoc.isValidHost || sigilHeredoc.heredocLineList.isEmpty() || !sigilHeredoc.isValid) {
            if (LOG.isDebugEnabled) {
                LOG.debug(
                    "handleSigilHeredoc: returning early: " +
                            "isValidHost=${sigilHeredoc.isValidHost}, " +
                            "heredocLineList.isEmpty=${sigilHeredoc.heredocLineList.isEmpty()}, " +
                            "isValid=${sigilHeredoc.isValid}"
                )
            }
            return
        }

        val sigilName = sigilHeredoc.sigilName()
        val lang = languageForSigil(sigilName) ?: return
        val heredocLineList = sigilHeredoc.heredocLineList

        if (LOG.isDebugEnabled) {
            LOG.debug("handleSigilHeredoc: injecting ${lang.displayName} into ${heredocLineList.size} lines")
        }

        val ranges = sigilHeredocInjectionRanges(heredocLineList, lang)
        if (ranges.isEmpty()) {
            return
        }

        registrar.startInjecting(lang, fileExtensionForSigil(sigilName))
        for (range in ranges) {
            if (LOG.isDebugEnabled) {
                LOG.debug("handleSigilHeredoc: injecting range $range")
            }
            registrar.addPlace(null, null, sigilHeredoc, range)
        }
        if (LOG.isDebugEnabled) {
            LOG.debug("handleSigilHeredoc: done injecting into ${heredocLineList.size} lines")
        }
        registrar.doneInjecting()
    }

    private fun sigilHeredocInjectionRanges(
        heredocLineableList: List<HeredocLineable>,
        lang: Language
    ): List<TextRange> {
        val lines = heredocInjectionLines(heredocLineableList)
        return injectionRangesForLines(lines, lang)
    }

    override fun elementsToInjectIn() = listOf(SigilHeredocLiteral::class.java, SigilLine::class.java)

    // EEx guesses an injected fragment's template-data language from a double extension, the way a
    // real `.html.eex` file carries one; with no extension the guess falls through to plain text and
    // the markup gets no HTML highlighting. Phoenix.HTML's ~E and LiveView's ~L are HTML by
    // definition, so name the fragment to say so. HEEx defaults to HTML without help, and a regex is
    // not a template.
    private fun fileExtensionForSigil(sigilName: String): String? = when (sigilName) {
        "E", "L" -> "html.eex"
        else -> null
    }

    private fun interpolationRanges(body: PsiElement, bodyStartOffsetInHost: Int): List<TextRange> {
        val interpolations = PsiTreeUtil.findChildrenOfType(body, ElixirInterpolation::class.java)
        if (interpolations.isEmpty()) {
            return emptyList()
        }

        return interpolations
            .map { it.textRangeInParent.shiftRight(bodyStartOffsetInHost) }
            .sortedBy { it.startOffset }
    }

    private fun regexInjectionRangesExcludingInterpolations(
        baseRange: TextRange,
        body: PsiElement?,
        bodyStartOffsetInHost: Int
    ): List<TextRange> {
        if (body == null) return listOf(baseRange)

        // Interpolations like #{...} must be excluded from injected RegExpLanguage, or the parser will
        // treat `{` as a quantifier start and raise false "Number expected" warnings.
        val interpolations = interpolationRanges(body, bodyStartOffsetInHost)
        return buildInjectionRanges(baseRange, interpolations)
    }

    private fun sigilLineInjectionRanges(sigilLine: SigilLine, lang: Language): List<TextRange> {
        val lineBody = sigilLine.body ?: return emptyList()
        val hostRange = lineBody.textRangeInParent

        val lines = listOf(InjectionLine(hostRange, lineBody, hostRange.startOffset))
        return injectionRangesForLines(lines, lang)
    }

    private fun heredocInjectionLines(heredocLineableList: List<HeredocLineable>): List<InjectionLine> {
        val lines = mutableListOf<InjectionLine>()
        for (item in heredocLineableList) {
            if (!item.isValid) {
                if (LOG.isDebugEnabled) {
                    LOG.debug("handleSigilHeredoc: skipping invalid heredocLine")
                }
                continue
            }

            val hostRange = item.textRangeInParent
            val lineBody = item.body
            // The body start is needed to shift interpolation ranges into the sigil host coordinate space.
            val bodyStartInHost = hostRange.startOffset + (lineBody?.textRangeInParent?.startOffset ?: 0)
            lines.add(InjectionLine(hostRange, lineBody, bodyStartInHost))
        }

        return lines
    }

    private fun injectionRangesForLines(lines: List<InjectionLine>, lang: Language): List<TextRange> {
        if (lines.isEmpty()) return emptyList()
        // Non-regex sigils inject the full body range; regex sigils skip interpolation ranges.
        if (lang != RegExpLanguage.INSTANCE) {
            return lines.map { it.hostRange }
        }

        val ranges = mutableListOf<TextRange>()
        for (line in lines) {
            ranges.addAll(
                regexInjectionRangesExcludingInterpolations(
                    line.hostRange,
                    line.bodyElement,
                    line.bodyStartInHost
                )
            )
        }
        return ranges
    }

    private fun buildInjectionRanges(baseRange: TextRange, excludedRanges: List<TextRange>): List<TextRange> {
        if (baseRange.length <= 0) return emptyList()
        if (excludedRanges.isEmpty()) return listOf(baseRange)

        val ranges = ArrayList<TextRange>()
        var currentStart = baseRange.startOffset

        for (excluded in excludedRanges) {
            val start = maxOf(baseRange.startOffset, excluded.startOffset)
            val end = minOf(baseRange.endOffset, excluded.endOffset)
            if (start > currentStart) {
                ranges.add(TextRange(currentStart, start))
            }
            if (end > currentStart) {
                currentStart = end
            }
        }

        if (currentStart < baseRange.endOffset) {
            ranges.add(TextRange(currentStart, baseRange.endOffset))
        }

        return ranges
    }

}
