package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.code_insight.completionStringsAtCaret

/** The lookup open at the caret, else the one completion shows there, a lone item shown rather than inserted. */
internal fun CodeInsightTestFixture.shownLookup(): LookupImpl {
    (lookup as? LookupImpl)?.let { return it }

    shown { complete(CompletionType.BASIC) }

    return lookup as? LookupImpl ?: error("no lookup shown")
}

/** What completion shows at the caret, a lone item shown rather than inserted; none where no lookup is shown. */
internal fun CodeInsightTestFixture.shownLookupStrings(): List<String> = shown { completionStringsAtCaret().orEmpty() }

/** [complete] run with a lone item shown rather than inserted. */
private fun <T> shown(complete: () -> T): T =
    CodeInsightSettings.runWithTemporarySettings<T, Throwable> { settings ->
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        complete()
    }
