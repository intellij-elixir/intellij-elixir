package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.StandardPatterns.instanceOf
import org.elixir_lang.model.psi.config.Set
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirKeywordKey

// It reads the config index, through `ConfigNames`.
@Suppress("CanBeDumbAware")
internal class ConfigKey : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().inFile(instanceOf(ElixirFile::class.java)),
            org.elixir_lang.code_insight.completion.provider.ConfigKey(),
        )
    }

    /**
     * In a keyword key of `config/2`'s options (`ret: 3`), the dummy identifier's trailing space would part it from its
     * colon.
     */
    override fun beforeCompletion(context: CompletionInitializationContext) {
        val key = context.file.findElementAt(context.identifierEndOffset - 1)?.parent as? ElixirKeywordKey ?: return

        if (Set.slotAt(key) != null) context.dummyIdentifier = CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED
    }
}
