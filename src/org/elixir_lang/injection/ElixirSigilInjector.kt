package org.elixir_lang.injection

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LanguageFileViewProviders
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.elixir_lang.eex.psi.Types as EexTypes
import org.elixir_lang.heex.HeexLanguage
import org.elixir_lang.heex.psi.Types as HeexTypes
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.intellij.lang.regexp.RegExpLanguage
import org.elixir_lang.eex.Language as EexLanguage

private val LOG = logger<ElixirSigilInjector>()

/** The language the injector fills a sigil named [sigilName] with, or `null` for a sigil it leaves alone. */
internal fun languageForSigil(sigilName: String): Language? {
    LOG.debug { "languageForSigil: sigilName='$sigilName'" }

    return when (sigilName) {
        "H" -> HeexLanguage.INSTANCE
        "E", "L" -> EexLanguage.INSTANCE
        "r" -> RegExpLanguage.INSTANCE
        else -> null
    }
}

/**
 * Whether the injector fills a sigil named [sigilName] with a template language: one with a file view provider of its own,
 * as HEEx and EEx have.
 *
 * The platform parses such a language from a multi-root injected view provider, which reads the injected window's text,
 * the host's, and not the text the escaper decodes; it then fails to reparse the fragment after an edit, as
 * `InjectionRegistrarImpl.reparse` asserts the two agree. So the escaper decodes nothing for the platform's fragment of a
 * template: no escape, no indentation.
 */
internal fun sigilIsTemplate(sigilName: String): Boolean =
    languageForSigil(sigilName)?.let(LanguageFileViewProviders.INSTANCE::forLanguage) != null

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

/** What the injector fills a sigil with: [language], the [extension] it names the fragment with, and the [ranges] of the sigil it injects. */
internal class SigilInjection(val language: Language, val extension: String?, val ranges: List<TextRange>)

/** What the injector fills [sigil] with, or `null` for a sigil it leaves alone. It does not ask whether [sigil] is a host. */
internal fun sigilInjection(sigil: Sigil): SigilInjection? {
    val sigilName = sigil.sigilName()
    val language = languageForSigil(sigilName) ?: return null
    val hostRanges = when (sigil) {
        is SigilLine -> listOfNotNull(sigil.body?.textRangeInParent)
        is SigilHeredocLiteral -> sigil.heredocLineList.mapNotNull { line ->
            if (line.isValid) {
                line.textRangeInParent
            } else {
                LOG.debug("handleSigilHeredoc: skipping invalid heredocLine")
                null
            }
        }
        else -> emptyList()
    }
    // Interpolations like #{...} are excluded from a regex, or its parser treats `{` as a quantifier start and raises
    // false "Number expected" warnings.
    val ranges = when (language) {
        RegExpLanguage.INSTANCE -> hostRanges.flatMap { partsOutsideInterpolations(sigil, it) }
        else -> hostRanges
    }

    return if (ranges.isEmpty()) null else SigilInjection(language, fileExtensionForSigil(sigilName), ranges)
}

internal class ElixirSigilInjector : MultiHostInjector {
    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        when (context) {
            is SigilLine -> handleSigilLine(registrar, context)
            is SigilHeredocLiteral -> handleSigilHeredoc(registrar, context)
            else -> return
        }
    }

    private fun handleSigilLine(registrar: MultiHostRegistrar, sigilLine: SigilLine) {
        if (!sigilLine.isValidHost) return
        val injection = sigilInjection(sigilLine) ?: return

        registrar.startInjecting(injection.language, injection.extension)
        for (range in injection.ranges) {
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

        val injection = sigilInjection(sigilHeredoc) ?: return
        val heredocLineList = sigilHeredoc.heredocLineList

        if (LOG.isDebugEnabled) {
            LOG.debug("handleSigilHeredoc: injecting ${injection.language.displayName} into ${heredocLineList.size} lines")
        }

        registrar.startInjecting(injection.language, injection.extension)
        for (range in injection.ranges) {
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

    override fun elementsToInjectIn() = listOf(SigilHeredocLiteral::class.java, SigilLine::class.java)
}

// EEx guesses an injected fragment's template-data language from a double extension, the way a
// real `.html.eex` file carries one; with no extension the guess falls through to plain text and
// the markup gets no HTML highlighting. Phoenix.HTML's ~E and LiveView's ~L are HTML by
// definition, so name the fragment to say so. HEEx defaults to HTML without help, and a regex is
// not a template.
private fun fileExtensionForSigil(sigilName: String): String? = when (sigilName) {
    "E", "L" -> "html.eex"
    else -> null
}
