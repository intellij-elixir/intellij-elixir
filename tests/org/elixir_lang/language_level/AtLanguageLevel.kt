package org.elixir_lang.language_level

import com.intellij.openapi.project.Project

/** [block], with [project]'s language level [version]. */
internal fun <T> atLanguageLevel(project: Project, version: String, block: () -> T): T {
    ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of(version))

    try {
        return block()
    } finally {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
    }
}
