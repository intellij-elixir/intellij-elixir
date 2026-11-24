package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.StandardPatterns.instanceOf
import org.elixir_lang.psi.ElixirFile

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
}
