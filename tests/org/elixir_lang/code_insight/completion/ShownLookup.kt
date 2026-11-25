package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
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

/** What [shownLookup] shows whose pattern is [pattern]. */
internal fun CodeInsightTestFixture.itemsWithPattern(pattern: String): List<LookupElement> {
    val lookup = shownLookup()

    return lookup.items.filter { lookup.itemPattern(it) == pattern }
}

/** The lookup strings of [itemsWithPattern]. */
internal fun CodeInsightTestFixture.lookupStringsWithPattern(pattern: String): List<String> =
    itemsWithPattern(pattern).map(LookupElement::getLookupString)

/** Chooses the first item [lookupString] whose pattern is [pattern] in [shownLookup], with [completionChar]. */
internal fun CodeInsightTestFixture.chooseItem(
    pattern: String,
    lookupString: String,
    completionChar: Char = Lookup.NORMAL_SELECT_CHAR
) {
    val lookup = shownLookup()
    lookup.currentItem = lookup.items.first { it.lookupString == lookupString && lookup.itemPattern(it) == pattern }

    finishLookup(completionChar)
}
