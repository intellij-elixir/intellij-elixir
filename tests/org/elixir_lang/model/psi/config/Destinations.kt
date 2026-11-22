package org.elixir_lang.model.psi.config

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.expander.ExpanderSourceRows.lineOf

/** Where Ctrl-Click goes, as `path:line`, sorted; empty where it goes nowhere or shows usages. */
internal fun CodeInsightTestFixture.destinations(navigation: GtduNavigation = gtduNavigationAtCaret()): List<String> =
    when (navigation) {
        is GtduNavigation.GotoDeclaration -> navigation.targets.map { target ->
            val destination = target.destination!!

            "${destination.containingFile.virtualFile.path.substringAfter("/src/")}:${lineOf(destination)}"
        }.sorted()
        GtduNavigation.None, is GtduNavigation.ShowUsages -> emptyList()
    }
