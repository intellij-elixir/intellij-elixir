package org.elixir_lang.model.psi.config

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.inspection.UnreadConfigKey

/** The keys the unread key inspection reports in [path], sorted. */
internal fun CodeInsightTestFixture.unreadConfigKeysIn(path: String): List<String> {
    enableInspections(UnreadConfigKey::class.java)
    configureFromTempProjectFile(path)

    return doHighlighting().filter { it.inspectionToolId == "UnreadConfigKey" }.map { it.text }.sorted()
}
