package org.elixir_lang.navigation

import com.intellij.navigation.ChooseByNameRegistry
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project

/** What Go To Symbol offers in [project] for [name], non-project items included. */
internal fun gotoSymbolItemsNamed(project: Project, name: String): Array<NavigationItem> =
    ChooseByNameRegistry
        .getInstance()
        .symbolModelContributors
        .filterIsInstance<GotoSymbolContributor>()
        .single()
        .getItemsByName(name, name, project, true)
