package org.elixir_lang.ui.sdk_choice

import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_choice.RowsTable.NEW
import org.elixir_lang.ui.sdk_choice.RowsTable.OLD
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.launchPyCharm
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * On PyCharm the chooser has a row per module with the Elixir facet and no Project SDK row. Each row opens on its facet's
 * SDK and lists every Elixir SDK, and OK writes only the changed rows' facets. File > Open's chooser stays until it is
 * answered.
 */
class RowsChooserSmallIdeTest {
    private val rows = Rows()

    @Test
    fun `File Open's chooser stays open, and each row writes its own facet`() {
        val modules = hubModules(
            StagedModule(HUB, "", facetIml(NEW)),
            StagedModule("alpha", "apps/alpha", facetIml(OLD)),
            StagedModule("beta", "apps/beta", facetIml(null)),
        )
        launchPyCharm("RowsChooserSmallIdeTest", configure = { RowsTable.seed(it) }) { _ ->
            row(rows, OPEN) { fileOpenStaysOpen(rows, OPEN, "open_cancel", close = "Cancel") }

            val project = stageModules(HUB, modules)
            val frame = openStaged(project)
            val before = savedImls(project, modules)
            row(rows, FACETS) {
                rows.check(FACETS, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                chooser().apply {
                    waitUntilReady()
                    rows.check(FACETS, "no Project SDK row", !hasRow(PROJECT_ROW))
                    for (module in modules.map(StagedModule::name)) {
                        val items = rowItems(module)
                        rows.check(FACETS, "$module's row lists both Elixir SDKs and no Project SDK: $items",
                            items.any(isSdk(NEW)) && items.any(isSdk(OLD)) && items.none(::isProjectSdk))
                    }
                    rows.check(FACETS, "hub's row holds its facet's '$NEW': '${rowText(HUB)}'", isSdk(NEW)(rowText(HUB)))
                    rows.check(FACETS, "alpha's row holds its facet's '$OLD': '${rowText("alpha")}'", isSdk(OLD)(rowText("alpha")))
                    pickInRow("beta", isSdk(OLD))
                    clickButtonText("OK")
                }
                rows.check(FACETS, "OK closes the chooser", chooserCloses())
                rows.check(FACETS, "beta's facet names '$OLD': '${facetSdkName("beta")}'", appears("beta's facet", 10.seconds) { facetSdkName("beta") == OLD })
                val beta = modules.first { it.name == "beta" }
                checkOnlyChanged(rows, FACETS, project, modules, beta, before, unchanged = modules.filter { it != beta })
            }
        }
        rows.assertAllHeld()
    }

    /** A Python module whose Elixir facet names [sdk], or none. */
    private fun facetIml(sdk: String?): String = moduleIml("PYTHON_MODULE", "<configuration${sdk?.let { " sdkName=\"$it\"" }.orEmpty()} />")

    private companion object {
        const val OPEN = "File > Open's chooser"
        const val FACETS = "each row writes its own facet"
    }
}
